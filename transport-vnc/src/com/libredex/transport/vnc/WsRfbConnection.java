package com.libredex.transport.vnc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import fi.iki.elonen.NanoWSD;

/**
 * Bridges a noVNC WebSocket to the {@link RfbServer} RFB session logic.
 * Client frames are pushed into a {@link ByteQueueInputStream}; the RFB
 * session's writes are forwarded as WebSocket binary messages.
 */
final class WsRfbConnection implements RfbConnection {

    private final NanoWSD.WebSocket ws;
    private final ByteQueueInputStream in = new ByteQueueInputStream();
    private volatile boolean closed;

    WsRfbConnection(NanoWSD.WebSocket ws) {
        this.ws = ws;
    }

    void onData(byte[] payload) {
        if (!closed && payload != null && payload.length > 0) {
            in.push(payload);
        }
    }

    void onClose() {
        closed = true;
        in.closeEof();
    }

    @Override
    public InputStream input() {
        return in;
    }

    @Override
    public OutputStream output() {
        return new OutputStream() {
            private boolean logFailed;

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (closed) {
                    return;
                }
                byte[] data = new byte[len];
                System.arraycopy(b, off, data, 0, len);
                try {
                    ws.send(data);
                } catch (IOException e) {
                    if (!logFailed) {
                        logFailed = true;
                    }
                    throw e;
                }
            }
        };
    }

    @Override
    public void close() {
        onClose();
    }
}
