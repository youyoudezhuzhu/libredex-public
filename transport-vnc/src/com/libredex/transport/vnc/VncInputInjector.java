package com.libredex.transport.vnc;

import android.hardware.input.IInputManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.KeyEventHidden;
import android.view.MotionEvent;
import android.view.MotionEventHidden;

import com.connect_screen.mirror.State;
import com.connect_screen.mirror.job.InputRouting;
import com.connect_screen.mirror.shizuku.ServiceUtils;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import dev.rikka.tools.refine.Refine;

/**
 * Injects pointer and keyboard events produced by a VNC client into the DeX
 * virtual display (the display id returned by {@code createDexMirror}).
 *
 * <p>Uses the same mechanism libredex's SunshineMouse/SunshineKeyboard rely on:
 * the hidden {@code InputEvent.setDisplayId()} via refine, then
 * {@code IInputManager.injectInputEvent}. The IInputManager is obtained through
 * Shizuku (shell/binder), so it can inject to a non-default display.</p>
 */
public final class VncInputInjector {

    private static IInputManager inputManager;
    private static volatile int targetDisplayId = -1;
    private static long mouseDownTime = 0;

    private VncInputInjector() {
    }

    public static void init() {
        if (inputManager == null && ShizukuUtils.hasPermission()) {
            try {
                inputManager = ServiceUtils.getInputManager();
            } catch (Throwable t) {
                State.log("[VNC] getInputManager failed: " + t.getMessage());
            }
        }
    }

    public static boolean isReady() {
        return inputManager != null;
    }

    public static void setTargetDisplayId(int displayId) {
        targetDisplayId = displayId;
    }

    public static void focus() {
        init();
        if (inputManager != null && targetDisplayId > 0) {
            try {
                InputRouting.setFocus(inputManager, targetDisplayId);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void release() {
        targetDisplayId = -1;
    }

    // ------------------------------------------------------------------
    // pointer
    // ------------------------------------------------------------------
    public static void injectPointer(int action, float x, float y, int buttonState) {
        init();
        if (inputManager == null || targetDisplayId < 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS) {
            mouseDownTime = now;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            if (mouseDownTime == 0) {
                mouseDownTime = now;
            }
        }
        long downTime = mouseDownTime != 0 ? mouseDownTime : now;

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = buttonState == 0 ? 0.0f : 1.0f;

        MotionEvent event = MotionEvent.obtain(
                downTime, now, action, 1, props, coords,
                0, buttonState, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        setEventDisplayId(event, targetDisplayId);
        try {
            inputManager.injectInputEvent(event, 0);
        } catch (Throwable t) {
            State.log("[VNC] injectPointer failed: " + t.getMessage());
        } finally {
            event.recycle();
        }
    }

    // ------------------------------------------------------------------
    // keyboard
    // ------------------------------------------------------------------
    public static void injectKey(boolean down, int keyCode) {
        init();
        if (inputManager == null || targetDisplayId < 0 || keyCode <= 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent keyEvent = new KeyEvent(
                now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        KeyEventHidden hidden = Refine.unsafeCast(keyEvent);
        if (targetDisplayId > 0) {
            hidden.setDisplayId(targetDisplayId);
        }
        try {
            inputManager.injectInputEvent(keyEvent, 0);
        } catch (Throwable t) {
            State.log("[VNC] injectKey failed: " + t.getMessage());
        }
    }

    /** Maps an X11 keysym (as sent by a VNC client) to an Android key code. */
    public static int keysymToKeyCode(int keysym) {
        // printable ASCII
        if (keysym >= 'a' && keysym <= 'z') {
            return KeyEvent.KEYCODE_A + (keysym - 'a');
        }
        if (keysym >= 'A' && keysym <= 'Z') {
            return KeyEvent.KEYCODE_A + (keysym - 'A');
        }
        if (keysym >= '0' && keysym <= '9') {
            return KeyEvent.KEYCODE_0 + (keysym - '0');
        }
        return specialKeysym(keysym);
    }

    private static int specialKeysym(int k) {
        switch (k) {
            case 0x20: return KeyEvent.KEYCODE_SPACE;
            case 0xff08: return KeyEvent.KEYCODE_DEL;          // BackSpace
            case 0xff09: return KeyEvent.KEYCODE_TAB;
            case 0xff0d: return KeyEvent.KEYCODE_ENTER;        // Return
            case 0xff1b: return KeyEvent.KEYCODE_ESCAPE;
            case 0xff51: return KeyEvent.KEYCODE_MOVE_HOME;
            case 0xff52: return KeyEvent.KEYCODE_DPAD_UP;
            case 0xff53: return KeyEvent.KEYCODE_PAGE_UP;
            case 0xff54: return KeyEvent.KEYCODE_DPAD_LEFT;
            case 0xff55: return KeyEvent.KEYCODE_DPAD_RIGHT;
            case 0xff56: return KeyEvent.KEYCODE_DPAD_DOWN;
            case 0xff57: return KeyEvent.KEYCODE_PAGE_DOWN;
            case 0xff5b: return KeyEvent.KEYCODE_MOVE_END;
            case 0xffff: return KeyEvent.KEYCODE_FORWARD_DEL;  // Delete
            case 0xffe1: return KeyEvent.KEYCODE_SHIFT_LEFT;   // Shift
            case 0xffe2: return KeyEvent.KEYCODE_SHIFT_RIGHT;  // Shift (unsure)
            case 0xffe3: return KeyEvent.KEYCODE_CTRL_LEFT;    // Control
            case 0xffe4: return KeyEvent.KEYCODE_CTRL_RIGHT;
            case 0xffe5: return KeyEvent.KEYCODE_CAPS_LOCK;
            case 0xffe9: return KeyEvent.KEYCODE_ALT_LEFT;     // Alt
            case 0xffea: return KeyEvent.KEYCODE_ALT_RIGHT;
            // '(' ')' '.' ',' etc.
            case 0x2d: return KeyEvent.KEYCODE_MINUS;
            case 0x2e: return KeyEvent.KEYCODE_PERIOD;
            case 0x2c: return KeyEvent.KEYCODE_COMMA;
            case 0x3b: return KeyEvent.KEYCODE_SEMICOLON;
            case 0x27: return KeyEvent.KEYCODE_APOSTROPHE;
            case 0x2f: return KeyEvent.KEYCODE_SLASH;
            case 0x5c: return KeyEvent.KEYCODE_BACKSLASH;
            case 0x5b: return KeyEvent.KEYCODE_LEFT_BRACKET;
            case 0x5d: return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case 0x60: return KeyEvent.KEYCODE_GRAVE;
            case 0x3d: return KeyEvent.KEYCODE_EQUALS;
            default: return -1;
        }
    }
}
