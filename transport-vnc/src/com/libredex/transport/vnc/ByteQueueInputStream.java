package com.libredex.transport.vnc;

import java.io.InputStream;
import java.util.ArrayDeque;

/**
 * Thread-safe FIFO byte queue exposed as an {@link InputStream}. Data is
 * pushed by the WebSocket reader thread (nanohttpd onMessage) and consumed by
 * the RFB session thread. {@link #closeEof()} signals end-of-stream.
 */
final class ByteQueueInputStream extends InputStream {

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private byte[] current;
    private int pos;
    private boolean eof;

    synchronized void push(byte[] data) {
        if (data.length == 0) {
            return;
        }
        queue.add(data);
        notifyAll();
    }

    synchronized void closeEof() {
        eof = true;
        notifyAll();
    }

    @Override
    public int read() {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : (one[0] & 0xff);
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
        if (len == 0) {
            return 0;
        }
        while (true) {
            if (current != null && pos < current.length) {
                int copy = Math.min(len, current.length - pos);
                System.arraycopy(current, pos, b, off, copy);
                pos += copy;
                off += copy;
                len -= copy;
                if (pos >= current.length) {
                    current = null;
                    pos = 0;
                }
                return copy;
            }
            if (!queue.isEmpty()) {
                current = queue.poll();
                pos = 0;
                continue;
            }
            if (eof) {
                return -1;
            }
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }

    @Override
    public void close() {
        closeEof();
    }
}
