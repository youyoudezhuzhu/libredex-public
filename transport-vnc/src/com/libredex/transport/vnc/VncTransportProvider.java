package com.libredex.transport.vnc;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.job.InputRouting;
import com.connect_screen.mirror.job.SunshineMouse;
import com.connect_screen.mirror.shizuku.ShizukuUtils;
import com.connect_screen.mirror.transport.OptionalTransportProvider;
import com.connect_screen.mirror.transport.TransportResultCallback;

/**
 * VNC transport plugin for LibreDeX.
 *
 * <p>Registers itself through the {@code optionalTransportModule} mechanism in
 * {@code app/build.gradle}. Instead of encoding the DeX virtual display with
 * Sunshine/GameStream, this transport feeds the display created by
 * {@code createDexMirror} into an {@code ImageReader}; that same Surface is
 * the virtual display's output target. The reader hands every RGBA frame to a
 * {@link RfbServer}, which streams it to any RFB 3.8 client (noVNC / TightVNC /
 * RealVNC) as ZRLE. Mouse/keyboard from the client is injected back into the
 * DeX display via {@link VncInputInjector}.
 */
public final class VncTransportProvider implements OptionalTransportProvider {

    public static final VncTransportProvider INSTANCE = new VncTransportProvider();

    private static final int DEFAULT_W = 1920;
    private static final int DEFAULT_H = 1080;
    private static final int DEFAULT_FPS = 60;
    private static final int DEFAULT_PORT = 5900;

    private DexVncFrameSource frameSource;
    private RfbServer rfbServer;
    private VncHttpServer vncHttpServer;
    private volatile boolean running;
    private volatile int activeDisplayId = -1;

    @Override
    public String id() {
        return "vnc";
    }

    @Override
    public String label() {
        return "VNC";
    }

    /** Web (noVNC) port served by {@link VncHttpServer}. */
    public static int webPort() {
        return VncHttpServer.webPort();
    }

    @Override
    public Fragment createFragment() {
        return new VncControlFragment();
    }

    @Override
    public boolean isActive() {
        return running;
    }

    @Override
    public int activeDisplayId() {
        return activeDisplayId;
    }

    @Override
    public synchronized boolean restart(boolean dexSource, @Nullable TransportResultCallback callback) {
        return startVnc(dexSource, callback);
    }

    @Override
    public synchronized void stop() {
        State.log("[VNC] stop");
        running = false;
        activeDisplayId = -1;
        if (vncHttpServer != null) {
            vncHttpServer.stop();
            vncHttpServer = null;
        }
        if (rfbServer != null) {
            rfbServer.stop();
            rfbServer = null;
        }
        if (frameSource != null) {
            frameSource.close();
            frameSource = null;
        }
        VncInputInjector.release();
        TransportOutputHelper.stopForeground();
    }

    private boolean startVnc(boolean dexSource, TransportResultCallback callback) {
        State.log("[VNC] restart dexSource=" + dexSource);
        stop();
        try {
            if (!ShizukuUtils.hasPermission()) {
                return fail(callback, "需要 Shizuku(root) 权限才能创建 DeX 虚拟屏");
            }
            if (!State.isUserServiceAlive()) {
                State.ensureUserServiceBound();
                long deadline = System.currentTimeMillis() + 5000;
                while (!State.isUserServiceAlive() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
            }
            if (!State.isUserServiceAlive()) {
                return fail(callback, "LibreDeX UserService 未就绪，确认 Shizuku 已用 root 启动");
            }

            int w = DEFAULT_W;
            int h = DEFAULT_H;
            int fps = DEFAULT_FPS;

            rfbServer = new RfbServer(w, h, new RfbServer.InputSink() {
                @Override
                public void onPointer(int x, int y, int buttons) {
                    int action = decodePointerAction(buttons);
                    VncInputInjector.injectPointer(action, x, y, convertButtons(buttons));
                }

                @Override
                public void onKey(boolean down, int keysym) {
                    int keyCode = VncInputInjector.keysymToKeyCode(keysym);
                    if (keyCode > 0) {
                        VncInputInjector.injectKey(down, keyCode);
                    }
                }
            });

            frameSource = new DexVncFrameSource(w, h, (rgba, fw, fh) -> {
                if (rfbServer != null) {
                    rfbServer.setFrame(rgba, fw, fh);
                }
            });
            frameSource.start();

            TransportOutputHelper.ensureForeground();

            State.log("[VNC] calling createDexMirror w=" + w + " h=" + h + " fps=" + fps);
            int vdId = State.userService.createDexMirror(
                    "libredex-vnc-dex", w, h, fps, frameSource.getSurface());
            State.log("[VNC] createDexMirror id=" + vdId);
            if (vdId < 0) {
                releaseLocal();
                return fail(callback, "createDexMirror 失败（" + vdId + "），可能是 Shizuku 未以 root 启动");
            }

            activeDisplayId = vdId;
            running = true;

            VncInputInjector.setTargetDisplayId(vdId);
            SunshineMouse.setDexTargetDisplayId(vdId);
            try {
                InputRouting.bindAllExternalInputToDisplay(vdId);
            } catch (Throwable t) {
                State.log("[VNC] bind external input failed: " + t.getMessage());
            }
            VncInputInjector.focus();

            rfbServer.startAsync(DEFAULT_PORT);
            android.content.Context httpCtx = State.getContext();
            if (httpCtx != null) {
                vncHttpServer = new VncHttpServer(httpCtx, rfbServer);
                vncHttpServer.start();
                State.log("[VNC] noVNC web server on :" + VncHttpServer.webPort());
            }
            State.log("[VNC] server listening on :" + DEFAULT_PORT + " displayId=" + vdId);

            if (callback != null) {
                callback.onResult(vdId, null);
            }
            return true;
        } catch (Throwable t) {
            releaseLocal();
            return fail(callback, "VNC 启动失败: " + t.getClass().getSimpleName() + " " + t.getMessage());
        }
    }

    private void releaseLocal() {
        activeDisplayId = -1;
        running = false;
        if (vncHttpServer != null) {
            vncHttpServer.stop();
            vncHttpServer = null;
        }
        if (rfbServer != null) {
            rfbServer.stop();
            rfbServer = null;
        }
        if (frameSource != null) {
            frameSource.close();
            frameSource = null;
        }
    }

    private boolean fail(TransportResultCallback callback, String error) {
        State.log("[VNC] " + error);
        if (callback != null) {
            callback.onResult(null, error);
        }
        return false;
    }

    private static int decodePointerAction(int buttons) {
        int buttonMask = buttons & 0xff;
        if (buttonMask == 0) {
            return android.view.MotionEvent.ACTION_HOVER_MOVE;
        }
        return android.view.MotionEvent.ACTION_MOVE;
    }

    /** VNC buttons mask (bit0 left, bit1 middle, bit2 right) -> Android button state. */
    private static int convertButtons(int buttons) {
        int b = buttons & 0xff;
        int state = 0;
        if ((b & 1) != 0) {
            state |= android.view.MotionEvent.BUTTON_PRIMARY;
        }
        if ((b & 2) != 0) {
            state |= android.view.MotionEvent.BUTTON_SECONDARY;
        }
        if ((b & 4) != 0) {
            state |= android.view.MotionEvent.BUTTON_TERTIARY;
        }
        return state;
    }
}
