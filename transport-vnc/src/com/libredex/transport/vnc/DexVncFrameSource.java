package com.libredex.transport.vnc;

import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

/**
 * Grabs frames rendered into a Surface produced by {@code createDexMirror(...)}.
 *
 * <p>The libredex {@code createDexMirror} path builds a {@code VirtualDisplayConfig}
 * whose {@code setSurface(surface)} target is this reader's Surface: the whole
 * DeX desktop (SecondaryLauncher) is composited into it. This source converts
 * each RGBA_8888 frame into a packed RGBA byte array for the RFB/ZRLE encoder,
 * exactly the same trick scrcpy uses to capture an arbitrary virtual display
 * without MediaProjection.</p>
 */
public final class DexVncFrameSource implements AutoCloseable {

    public interface Listener {
        void onFrame(byte[] rgba, int width, int height);
    }

    private final int width;
    private final int height;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;
    private ImageReader reader;
    private volatile boolean running;

    public DexVncFrameSource(int width, int height, Listener listener) {
        this.width = width;
        this.height = height;
        this.listener = listener;
        this.thread = new HandlerThread("libredex-vnc-frames");
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /** The Surface handed to {@code createDexMirror(...)}. */
    public Surface getSurface() {
        return reader.getSurface();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::onImageAvailable, handler);
    }

    private void onImageAvailable(ImageReader r) {
        if (!running) {
            return;
        }
        Image image = null;
        try {
            image = r.acquireLatestImage();
            if (image == null) {
                return;
            }
            int rowStride = image.getPlanes()[0].getRowStride();
            int pixelStride = image.getPlanes()[0].getPixelStride();
            int w = image.getWidth();
            int h = image.getHeight();
            byte[] packed = new byte[w * h * 4]; // RGBA, 4 bytes per pixel
            if (pixelStride == 4) {
                java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                buffer.rewind();
                // Copy plane with row stride handling -> tightly packed RGBA.
                for (int row = 0; row < h; row++) {
                    buffer.position(row * rowStride);
                    buffer.get(packed, row * w * 4, w * 4);
                }
            } else {
                // Fallback: iterate pixels to repack RGBA (rare; RGBA_8888 is 4).
                java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                buffer.rewind();
                for (int row = 0; row < h; row++) {
                    int rowStart = row * rowStride;
                    for (int col = 0; col < w; col++) {
                        int p = rowStart + col * pixelStride;
                        byte r0 = buffer.get(p);
                        byte g = buffer.get(p + 1);
                        byte b = buffer.get(p + 2);
                        byte a = buffer.get(p + 3);
                        int dst = (row * w + col) * 4;
                        packed[dst] = r0;
                        packed[dst + 1] = g;
                        packed[dst + 2] = b;
                        packed[dst + 3] = a;
                    }
                }
            }
            listener.onFrame(packed, w, h);
        } catch (Throwable t) {
            // logger unavailable here; drop the frame silently to keep VNC alive
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
            reader = null;
        }
        if (thread != null) {
            thread.quitSafely();
        }
    }
}
