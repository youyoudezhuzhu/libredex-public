package com.libredex.transport.vnc;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

import fi.iki.elonen.NanoWSD;
import fi.iki.elonen.NanoHTTPD;

/**
 * Serves the noVNC web client and bridges its WebSocket to the {@link RfbServer}.
 *
 * <ul>
 *   <li>{@code /}               -> redirects to {@code /vnc.html} pre-filled
 *                                  with {@code host} + {@code port} + {@code path=/rfb}
 *                                  and {@code autoconnect=1} so a browser can open
 *                                  {@code http://&lt;phone-ip&gt;:6080/} directly.</li>
 *   <li>{@code /vnc.html}, {@code /core/*}, {@code /app/*}, {@code /vendor/*}
 *                                  -> static noVNC assets vendored in the APK.</li>
 *   <li>{@code /rfb}              -> WebSocket tunnel; noVNC speaks the RFB
 *                                  protocol directly over it.</li>
 * </ul>
 */
public final class VncHttpServer extends NanoWSD {

    private static final String WS_PATH = "/rfb";
    private static final int WS_PORT;

    static {
        // fixed web port that also carries the WebSocket endpoint
        WS_PORT = 6080;
    }

    private final Context context;
    private final RfbServer rfbServer;

    public VncHttpServer(Context context, RfbServer rfbServer) {
        super(VncHttpServer.WS_PORT);
        this.context = context;
        this.rfbServer = rfbServer;
    }

    public static int webPort() {
        return WS_PORT;
    }

    @Override
    protected NanoWSD.WebSocket openWebSocket(NanoHTTPD.IHTTPSession handshake) {
        return new RfbWebSocket(handshake, rfbServer);
    }

    @Override
    protected NanoHTTPD.Response serveHttp(NanoHTTPD.IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null || uri.equals("/")) {
            String host = "127.0.0.1";
            String hostHeader = session.getHeaders().get("host");
            if (hostHeader != null && !hostHeader.isEmpty()) {
                host = hostHeader.split(":")[0];
            }
            String target = "/vnc.html?host=" + host
                    + "&port=" + WS_PORT
                    + "&path=" + WS_PATH
                    + "&autoconnect=1&reconnect=true&resize=scale";
            return nanohttpdRedirect(target);
        }
        // static noVNC assets
        if (uri.startsWith("/vnc.html") || uri.startsWith("/core/")
                || uri.startsWith("/app/") || uri.startsWith("/vendor/")) {
            return serveStatic(session, uri);
        }
        return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found");
    }

    private NanoHTTPD.Response nanohttpdRedirect(String target) {
        NanoHTTPD.Response res = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FOUND, "text/html",
                "<html><body>LibreDeX VNC - <a href='" + target + "'>open noVNC</a></body></html>");
        res.addHeader("Location", target);
        return res;
    }

    private NanoHTTPD.Response serveStatic(NanoHTTPD.IHTTPSession session, String uri) {
        // strip trailing query
        String path = uri.split("\\?")[0];
        String assetPath = "vnc" + path; // assets/vnc/<...>
        AssetManager am = context.getAssets();
        try {
            InputStream is = am.open(assetPath);
            String mime = mimeFor(path);
            return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, mime, is);
        } catch (IOException e) {
            return NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Not found: " + path);
        }
    }

    private static String mimeFor(String path) {
        if (path.endsWith(".html")) {
            return "text/html";
        }
        if (path.endsWith(".js")) {
            return "application/javascript";
        }
        if (path.endsWith(".css")) {
            return "text/css";
        }
        if (path.endsWith(".json")) {
            return "application/json";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".gif")) {
            return "image/gif";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (path.endsWith(".woff") || path.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (path.endsWith(".ttf")) {
            return "font/ttf";
        }
        if (path.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (path.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------
    // WebSocket handler
    // ------------------------------------------------------------------
    private final class RfbWebSocket extends NanoWSD.WebSocket {

        private final RfbServer rfb;
        private WsRfbConnection conn;

        RfbWebSocket(NanoHTTPD.IHTTPSession handshake, RfbServer rfb) {
            super(handshake);
            this.rfb = rfb;
        }

        @Override
        protected void onOpen() {
            conn = new WsRfbConnection(this);
            rfb.addConnection(conn);
        }

        @Override
        protected void onMessage(NanoWSD.WebSocketFrame message) {
            if (conn == null) {
                return;
            }
            if (message.getOpCode() == NanoWSD.WebSocketFrame.OpCode.Binary) {
                byte[] payload = message.getBinaryPayload();
                if (payload != null) {
                    conn.onData(payload);
                }
            }
        }

        @Override
        protected void onClose(NanoWSD.WebSocketFrame.CloseCode code,
                               String reason, boolean initiatedByRemote) {
            if (conn != null) {
                conn.onClose();
            }
        }

        @Override
        protected void onPong(NanoWSD.WebSocketFrame pong) {
        }

        @Override
        protected void onException(IOException exception) {
            if (conn != null) {
                conn.onClose();
            }
        }
    }
}
