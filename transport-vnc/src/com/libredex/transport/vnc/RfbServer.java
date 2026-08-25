package com.libredex.transport.vnc;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;

/**
 * Minimal but correct RFB 3.8 (VNC) server that streams the DeX framebuffer
 * fed through {@link #setFrame(byte[], int, int)}.
 *
 * <p>Borrows the same strategy scrcpy uses for arbitrary virtual displays.
 * We always advertise a standard true-colour 32bpp pixel format
 * (R/G/B each 8 bits, shifts 16/8/0, little-endian) and encode rects with
 * ZRLE (encoding 16); we also fall back to ZRLE's raw tiles and solid tiles
 * so no external native library is required.</p>
 *
 * <p>Clients may join through a legacy TCP socket (TightVNC/RealVNC) or
 * through the noVNC WebSocket bridged by {@link VncHttpServer} via
 * {@link #addConnection(RfbConnection)}.</p>
 */
public final class RfbServer {

    public interface InputSink {
        void onPointer(int x, int y, int buttons);

        void onKey(boolean down, int keysym);
    }

    private static final String NAME = "LibreDeX VNC";
    private static final int ENCODING_RAW = 0;
    private static final int ENCODING_ZRLE = 16;

    private final int width;
    private final int height;
    private final InputSink inputSink;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();
    private final Object frameLock = new Object();
    private byte[] latestRgba;
    private volatile int latestW;
    private volatile int latestH;

    private Thread acceptThread;

    public RfbServer(int width, int height, InputSink inputSink) {
        this.width = width;
        this.height = height;
        this.inputSink = inputSink;
    }

    /** Called by the frame source as soon as a new RGBA frame is available. */
    public void setFrame(byte[] rgba, int w, int h) {
        synchronized (frameLock) {
            latestRgba = rgba;
            latestW = w;
            latestH = h;
        }
        for (ClientHandler c : clients) {
            c.wakeUp();
        }
    }

    private byte[] currentFrame() {
        synchronized (frameLock) {
            return latestRgba;
        }
    }

    public boolean startAsync(int port) {
        if (running.get()) {
            return true;
        }
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(true);
        } catch (Throwable t) {
            return false;
        }
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "libredex-vnc-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                addConnection(new SocketRfbConnection(socket));
            } catch (Throwable t) {
                if (running.get()) {
                    // transient accept failure, keep going
                } else {
                    break;
                }
            }
        }
    }

    /** Registers a non-TCP client (e.g. a noVNC WebSocket bridge). */
    public void addConnection(RfbConnection conn) {
        if (!running.get()) {
            conn.close();
            return;
        }
        ClientHandler handler = new ClientHandler(conn);
        clients.add(handler);
        Thread t = new Thread(handler, "libredex-vnc-client");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running.set(false);
        for (ClientHandler c : clients) {
            c.close();
        }
        clients.clear();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Throwable ignored) {
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    // ------------------------------------------------------------------
    // per-client connection
    // ------------------------------------------------------------------
    private final class ClientHandler implements Runnable {
        private final RfbConnection conn;
        private final Object writeLock = new Object();
        private volatile DataOutputStream out;
        private byte[] prevRgba;
        private int bpp = 32;
        private boolean bigEndian = false;
        private boolean supportsZRLE = true;
        private boolean firstRequest = true;
        private final AtomicBoolean dirty = new AtomicBoolean(false);

        ClientHandler(RfbConnection conn) {
            this.conn = conn;
        }

        void wakeUp() {
            dirty.set(true);
        }

        void close() {
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void run() {
            try {
                DataInputStream in = new DataInputStream(conn.input());
                DataOutputStream out = new DataOutputStream(conn.output());
                this.out = out;
                handshake(in, out);
                messageLoop(in, out);
            } catch (Throwable t) {
                // disconnected
            } finally {
                close();
                clients.remove(this);
            }
        }

        private void handshake(DataInputStream in, DataOutputStream out) throws Exception {
            out.write("RFB 003.008\n".getBytes("ISO-8859-1"));
            out.flush();
            byte[] ver = new byte[12];
            in.readFully(ver);
            // security types: 1 method = None
            out.writeByte(1);
            out.writeByte(1);
            out.flush();
            in.readByte(); // chosen security type
            out.writeInt(0); // SecurityResult OK
            out.flush();
            in.readByte(); // shared flag

            // ServerInit
            out.writeShort(width);
            out.writeShort(height);
            writePixelFormat(out, 32, 24, false, true, 255, 255, 255, 16, 8, 0);
            byte[] name = NAME.getBytes("ISO-8859-1");
            out.writeInt(name.length);
            out.write(name);
            out.flush();
            prevRgba = new byte[width * height * 4]; // black frame
        }

        private void messageLoop(DataInputStream in, DataOutputStream out) throws Exception {
            while (running.get()) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0: // SetPixelFormat
                        in.skipBytes(3);
                        readPixelFormat(in);
                        break;
                    case 2: // SetEncodings
                        in.skipBytes(1);
                        int count = in.readUnsignedShort();
                        supportsZRLE = false;
                        for (int i = 0; i < count; i++) {
                            int enc = in.readInt();
                            if (enc == ENCODING_ZRLE || enc == ENCODING_RAW) {
                                supportsZRLE = true;
                            }
                        }
                        break;
                    case 3: // FramebufferUpdateRequest
                        int incremental = in.readUnsignedByte();
                        int x = in.readUnsignedShort();
                        int y = in.readUnsignedShort();
                        int w = in.readUnsignedShort();
                        int h = in.readUnsignedShort();
                        sendFramebufferUpdate(out, incremental == 1);
                        break;
                    case 4: // KeyEvent
                        boolean down = in.readUnsignedByte() != 0;
                        in.skipBytes(2);
                        int keysym = in.readInt();
                        if (inputSink != null) {
                            inputSink.onKey(down, keysym);
                        }
                        break;
                    case 5: // PointerEvent
                        int buttons = in.readUnsignedByte();
                        int px = in.readUnsignedShort();
                        int py = in.readUnsignedShort();
                        if (inputSink != null) {
                            inputSink.onPointer(px, py, buttons);
                        }
                        break;
                    case 6: // ClientCutText
                        in.skipBytes(3);
                        int len = in.readInt();
                        if (len > 0 && len < 1 << 20) {
                            in.skipBytes(len);
                        }
                        break;
                    default:
                        // unknown message; break the loop to avoid desync
                        throw new IllegalStateException("unknown msg: " + type);
                }
            }
        }

        private void readPixelFormat(DataInputStream in) throws Exception {
            bpp = in.readUnsignedByte();
            in.readUnsignedByte(); // depth
            bigEndian = in.readUnsignedByte() != 0;
            in.readUnsignedByte(); // true-colour
            in.readUnsignedShort(); // red-max
            in.readUnsignedShort(); // green-max
            in.readUnsignedShort(); // blue-max
            in.readUnsignedByte();  // red-shift
            in.readUnsignedByte();  // green-shift
            in.readUnsignedByte();  // blue-shift
            in.skipBytes(3);
        }

        private void writePixelFormat(DataOutputStream out, int bpp, int depth, boolean bigEndian,
                                      boolean trueColour, int rMax, int gMax, int bMax,
                                      int rShift, int gShift, int bShift) throws Exception {
            out.writeByte(bpp);
            out.writeByte(depth);
            out.writeByte(bigEndian ? 1 : 0);
            out.writeByte(trueColour ? 1 : 0);
            out.writeShort(rMax);
            out.writeShort(gMax);
            out.writeShort(bMax);
            out.writeByte(rShift);
            out.writeByte(gShift);
            out.writeByte(bShift);
            out.writeByte(0);
            out.writeByte(0);
            out.writeByte(0);
        }

        private void sendFramebufferUpdate(DataOutputStream out, boolean incremental) throws Exception {
            byte[] frame = currentFrame();
            if (frame == null) {
                return;
            }
            int fw = latestW;
            int fh = latestH;
            int[] bbox = computeDirty(frame, prevRgba, fw, fh, incremental, firstRequest);
            boolean first = firstRequest;
            firstRequest = false;
            dirty.set(false);

            if (bbox == null) {
                return;
            }

            ByteArrayOutputStream tiles = new ByteArrayOutputStream();
            DataOutputStream tileOut = new DataOutputStream(tiles);
            int rx = bbox[0];
            int ry = bbox[1];
            int rw = bbox[2];
            int rh = bbox[3];
            encodeTiles(tileOut, frame, fw, rx, ry, rw, rh);
            tileOut.flush();
            byte[] raw = tiles.toByteArray();

            synchronized (writeLock) {
                int count = 1;
                out.writeByte(0); // FramebufferUpdate type
                out.writeByte(0);
                out.writeShort(count);
                out.writeShort(rx);
                out.writeShort(ry);
                out.writeShort(rw);
                out.writeShort(rh);
                if (supportsZRLE) {
                    out.writeInt(ENCODING_ZRLE);
                    writeZRLE(out, raw);
                } else {
                    out.writeInt(ENCODING_RAW);
                    out.write(raw);
                }
                out.flush();
            }
        }

        private int[] computeDirty(byte[] cur, byte[] prev, int fw, int fh,
                                   boolean incremental, boolean first) {
            if (!incremental || first || prev.length != cur.length) {
                // full frame from top-left
                return new int[]{0, 0, fw, fh};
            }
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = -1, maxY = -1;
            for (int py = 0; py < fh; py++) {
                int rowBase = py * fw * 4;
                for (int px = 0; px < fw; px++) {
                    int i = rowBase + px * 4;
                    if (cur[i] != prev[i] || cur[i + 1] != prev[i + 1]
                            || cur[i + 2] != prev[i + 2]) {
                        if (px < minX) minX = px;
                        if (px > maxX) maxX = px;
                        if (py < minY) minY = py;
                        if (py > maxY) maxY = py;
                    }
                }
            }
            if (maxX < 0) {
                return null;
            }
            return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
        }

        private void encodeTiles(DataOutputStream tileOut, byte[] frame, int fw,
                                 int x, int y, int w, int h) throws Exception {
            for (int ty = y; ty < y + h; ty += 64) {
                int th = Math.min(64, y + h - ty);
                for (int tx = x; tx < x + w; tx += 64) {
                    int tw = Math.min(64, x + w - tx);
                    encodeTile(tileOut, frame, fw, tx, ty, tw, th);
                }
            }
        }

        private void encodeTile(DataOutputStream tileOut, byte[] frame, int fw,
                                int x, int y, int tw, int th) throws Exception {
            int r0 = 0, g0 = 0, b0 = 0;
            boolean solid = true;
            outer:
            for (int py = y; py < y + th; py++) {
                int rowBase = py * fw * 4;
                for (int px = x; px < x + tw; px++) {
                    int i = rowBase + px * 4;
                    int r = frame[i] & 0xff;
                    int g = frame[i + 1] & 0xff;
                    int b = frame[i + 2] & 0xff;
                    if (solid) {
                        if (px == x && py == y) {
                            r0 = r;
                            g0 = g;
                            b0 = b;
                        } else if (r != r0 || g != g0 || b != b0) {
                            solid = false;
                            break outer;
                        }
                    }
                }
            }
            if (solid) {
                tileOut.writeByte(1);
                writePixel(tileOut, r0, g0, b0);
            } else {
                tileOut.writeByte(0);
                for (int py = y; py < y + th; py++) {
                    int rowBase = py * fw * 4;
                    for (int px = x; px < x + tw; px++) {
                        int i = rowBase + px * 4;
                        writePixel(tileOut, frame[i] & 0xff, frame[i + 1] & 0xff, frame[i + 2] & 0xff);
                    }
                }
            }
        }

        private void writePixel(DataOutputStream o, int r, int g, int b) throws Exception {
            if (bpp == 16) {
                int v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
                if (bigEndian) {
                    o.writeByte((v >> 8) & 0xff);
                    o.writeByte(v & 0xff);
                } else {
                    o.writeByte(v & 0xff);
                    o.writeByte((v >> 8) & 0xff);
                }
            } else {
                // 32bpp, shifts red=16 green=8 blue=0, little-endian standard
                if (bigEndian) {
                    o.writeByte(0);
                    o.writeByte(r);
                    o.writeByte(g);
                    o.writeByte(b);
                } else {
                    o.writeByte(b);
                    o.writeByte(g);
                    o.writeByte(r);
                    o.writeByte(0);
                }
            }
        }

        private void writeZRLE(DataOutputStream out, byte[] raw) throws Exception {
            out.writeInt(raw.length);
            Deflater def = new Deflater(Deflater.BEST_SPEED);
            def.setInput(raw);
            def.finish();
            byte[] buf = new byte[8192];
            while (!def.finished()) {
                int n = def.deflate(buf);
                if (n == 0) {
                    break;
                }
                out.write(buf, 0, n);
            }
            def.end();
        }
    }
}
