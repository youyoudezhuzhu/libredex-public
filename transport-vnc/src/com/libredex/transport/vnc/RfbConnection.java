package com.libredex.transport.vnc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Abstraction of a single VNC client transport. Both the legacy TCP socket
 * clients and the browser noVNC WebSocket clients funnel through the same RFB
 * session logic via this byte-stream interface.
 */
public interface RfbConnection {

    InputStream input() throws IOException;

    OutputStream output() throws IOException;

    void close();
}
