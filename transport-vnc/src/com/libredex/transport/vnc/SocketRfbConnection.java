package com.libredex.transport.vnc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** {@link RfbConnection} backed by a plain TCP socket (TightVNC/RealVNC/...). */
public final class SocketRfbConnection implements RfbConnection {

    private final Socket socket;

    public SocketRfbConnection(Socket socket) {
        this.socket = socket;
    }

    @Override
    public InputStream input() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream output() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
