package com.libredex.transport.vnc;

import android.content.Context;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.TransportOutputService;

/** Keeps the shared foreground service alive while the VNC transport runs. */
public final class TransportOutputHelper {

    private TransportOutputHelper() {
    }

    public static void ensureForeground() {
        Context ctx = State.getContext();
        if (ctx != null) {
            TransportOutputService.start(ctx);
        }
    }

    public static void stopForeground() {
        Context ctx = State.getContext();
        if (ctx != null) {
            TransportOutputService.stop(ctx);
        }
    }
}
