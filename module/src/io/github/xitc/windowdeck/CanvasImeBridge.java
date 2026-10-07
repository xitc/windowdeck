package io.github.xitc.windowdeck;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.SurfaceControl;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Gives the IME back to the canvas.
 *
 * <p><b>Why.</b> A card is a task with {@code launchScenario == 2} that the ROM marks embedded, so
 * {@code FlexibleWindowUtils.inPsSplitMode(task)} (:1455 — {@code isTaskEmbedded() && launchScenario
 * == 2}) is true and {@code WindowState.shouldControlIme()} (services/WindowState.java:4755)
 * returns false. WMS therefore decides the IME is <em>not</em> controlled by the app and
 * {@code DisplayContent.computeImeControlTarget()} (:4294) falls back to the display-level
 * {@code mRemoteInsetsControlTarget}, whose {@code requestedVisibleTypes} still holds its
 * constructor default {@code WindowInsets.Type.defaultVisible()} — hard-coded 503, i.e.
 * {@code all() 511 - ime() 8}.
 *
 * <p><b>What the stock ROM does.</b> The container closes that loop. {@code G0}
 * (DisplayInsetsController) is built by {@code OplusPsCanvasApp.onCreate} and registered through
 * {@code IWindowManager.setDisplayWindowInsetsController}, so WMS routes every IME message to the
 * canvas. {@code y0} (DisplayImeController) — built only at {@code ContainerActivity:1732} — is the
 * listener that acts on them, and it does two things: {@code updateDisplayWindowRequestedVisibleTypes}
 * (y0.java:822) and driving the insets animation leash (y0.java:1029-1039).
 *
 * <p>Replacing {@code ContainerActivity} with {@link WorkbenchActivity} means {@code y0} is never
 * built, {@code G0}'s listener list stays empty, and every message WMS pushes into the canvas is
 * dropped by an empty {@code for (G0.b : listeners)} loop. Two symptoms follow, both reproduced on
 * device:
 * <ol>
 *   <li>{@code requestedVisibleTypes} stays 503, so the IME source is never requested. logcat shows
 *       {@code DisplayInsetsController: setImeInputTargetRequestedVisibility: true} on every
 *       input-field tap while {@code mInputShown} stays false.</li>
 *   <li>Once the request is pushed, WMS and IMMS agree the IME is visible ({@code mImeWindowVis=3},
 *       {@code mInputShown=true}) and the app even reserves space for it — but no keyboard is drawn.
 *       The IME window hangs under an {@code animation-leash of insets_animation} that
 *       {@code SurfaceAnimator} created with {@code hidden=true}, i.e.
 *       {@code setAlpha(leash, ColorScheme.CONTRAST)} = <b>alpha 0</b> (SurfaceAnimator.java:366).
 *       The control target is expected to raise it; nothing did. dumpsys SurfaceFlinger:
 *       {@code Layer [70708] ... animation-leash of insets_animation} → {@code invisible reason=alpha
 *       = 0 and no blur}, {@code alpha=0.000000}, with its child {@code Layer [127] fcbb2b2
 *       InputMethod#127} inheriting the same.</li>
 * </ol>
 *
 * <p><b>How we hook.</b> We do not hard-code {@code G0$a}: {@code G0} is minified and free to be
 * renamed by the next OTA. {@code onTransact} is hooked once purely to <em>discover</em> the stub
 * class from {@code param.thisObject}, and the two methods we care about are then hooked by name on
 * that class — both are {@code @Override}s of AIDL interface methods, so neither is obfuscated.
 * {@code G0$a} is the only {@code IDisplayWindowInsetsController.Stub} in the pscanvas process, so
 * whatever we discover here is the canvas. Hooking the concrete methods also means the arguments
 * arrive typed, and no transaction parcel has to be parsed by hand.
 *
 * <p><b>Threading is not optional.</b> These run on a binder thread while system_server may still
 * hold the window-manager global lock and be blocked waiting for our reply; calling back into WMS
 * from that thread would deadlock. The ROM's own {@code G0} posts to the main thread before
 * notifying its listeners, and so do we.
 */
final class CanvasImeBridge {

    private static final String TAG = "WindowDeck";

    /** {@code WindowInsets.Type.ime()}. */
    private static final int IME = 8;

    private static volatile boolean installed;
    private static volatile boolean methodsHooked;
    private static boolean visibilityHooked, insetsHooked, loggedHookFailure;
    /** Classes that are not the canvas stub. A later, different stub can still be hooked. */
    private static final Set<Class<?>> skipped = Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>());
    private static Class<?> pendingClass;

    // --- IWindowManager plumbing (bound once, on first use) ------------------------------------
    private static Method pushVisibleTypes;
    private static Object windowManager;

    // --- the IME's insets-animation leash, re-captured on every insetsControlChanged ------------
    private static volatile SurfaceControl imeLeash;
    private static volatile int leashX, leashY;
    /** Last state the app asked for, so it can be re-applied if the leash arrives afterwards. */
    private static volatile Boolean wantVisible;
    private static Method setVisibility;

    private CanvasImeBridge() {}

    /**
     * Installs the discovery hook. Safe to call more than once; failure leaves the module usable.
     *
     * @return whether the discovery hook itself is installed. The canvas methods are hooked later,
     *         on the first stub transaction that actually declares both of them.
     */
    static boolean install() {
        if (installed) return true;
        try {
            Class<?> stub = Class.forName("android.view.IDisplayWindowInsetsController$Stub");
            XposedHelpers.findAndHookMethod(stub, "onTransact",
                    int.class, Parcel.class, Parcel.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (methodsHooked || param.thisObject == null) return;
                    // The stub instance is the canvas: no obfuscated name needed to find its class.
                    hookStubMethods(param.thisObject.getClass());
                }
            });
            installed = true;
            Log.i(TAG, "canvas_ime_bridge_ready");
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "canvas_ime_bridge_failed", e);
            return false;
        }
    }

    private static void hookStubMethods(Class<?> stubImpl) {
        synchronized (CanvasImeBridge.class) {
            if (methodsHooked || stubImpl == null || skipped.contains(stubImpl)) return;
            if (pendingClass != null && pendingClass != stubImpl) return;
            try {
                Class<?> token = Class.forName("android.view.inputmethod.ImeTracker$Token");
                Class<?> insetsState = Class.forName("android.view.InsetsState");
                Class<?> control = Class.forName("android.view.InsetsSourceControl");
                Class<?> controlArray = Array.newInstance(control, 0).getClass();
                try {
                    stubImpl.getMethod("setImeInputTargetRequestedVisibility", boolean.class, token);
                    stubImpl.getMethod("insetsControlChanged", insetsState, controlArray);
                } catch (NoSuchMethodException missing) {
                    skipped.add(stubImpl);
                    Log.i(TAG, "canvas_ime_stub_skipped " + stubImpl.getName());
                    return;
                }
                pendingClass = stubImpl;
                if (!visibilityHooked) {
                    XposedHelpers.findAndHookMethod(stubImpl, "setImeInputTargetRequestedVisibility",
                            boolean.class, token, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!WorkbenchActivity.foreground) return;
                            post(((Boolean) param.args[0]).booleanValue());
                        }
                    });
                    visibilityHooked = true;
                }
                if (!insetsHooked) {
                    XposedHelpers.findAndHookMethod(stubImpl, "insetsControlChanged",
                            insetsState, controlArray, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!WorkbenchActivity.foreground) return;
                            captureLeash((Object[]) param.args[1]);
                        }
                    });
                    insetsHooked = true;
                }
                methodsHooked = visibilityHooked && insetsHooked;
                Log.i(TAG, "canvas_ime_stub_hooked " + stubImpl.getName());
            } catch (Throwable e) {
                // Leave methodsHooked false so a later transaction can finish a partial hook.
                if (!loggedHookFailure) { loggedHookFailure = true; Log.e(TAG, "canvas_ime_stub_hook_failed", e); }
            }
        }
    }

    /** Code 3 carries the IME's {@code InsetsSourceControl}, which owns the animation leash. */
    private static void captureLeash(Object[] controls) {
        if (controls == null) return;
        try {
            Class<?> control = Class.forName("android.view.InsetsSourceControl");
            Method getType = control.getMethod("getType");
            Method getLeash = control.getMethod("getLeash");
            Method getSurfacePosition = control.getMethod("getSurfacePosition");
            Class<?> point = Class.forName("android.graphics.Point");
            Field px = point.getField("x"), py = point.getField("y");
            for (Object c : controls) {
                if (c == null) continue;
                if (((Integer) getType.invoke(c)).intValue() != IME) continue;
                SurfaceControl leash = (SurfaceControl) getLeash.invoke(c);
                Object position = getSurfacePosition.invoke(c);
                if (leash == null || position == null) continue;
                // Publish the leash last so a reader cannot observe it with the previous coordinates.
                leashX = ((Integer) px.get(position)).intValue();
                leashY = ((Integer) py.get(position)).intValue();
                imeLeash = leash;
                Log.i(TAG, "canvas_ime_leash " + leash + " at " + leashX + "," + leashY);
                Boolean wanted = wantVisible;
                if (wanted != null) post(wanted.booleanValue());   // request may predate the control
                return;
            }
        } catch (Throwable e) {
            Log.w(TAG, "canvas_ime_leash_failed", e);
        }
    }

    private static void post(final boolean visible) {
        wantVisible = Boolean.valueOf(visible);
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() { apply(visible); }
            });
        } catch (Throwable e) {
            Log.w(TAG, "canvas_ime_post_failed", e);
        }
    }

    /** What {@code y0.D()} plus y0's leash pass would have done, minus the slide animation. */
    private static void apply(boolean visible) {
        try {
            if (pushVisibleTypes == null) bindWindowManager();
            if (pushVisibleTypes != null) {
                pushVisibleTypes.invoke(windowManager, Integer.valueOf(0),
                        Integer.valueOf(visible ? IME : 0), Integer.valueOf(IME), null);
            }
        } catch (Throwable e) {
            Log.w(TAG, "canvas_ime_push_failed", e);
        }
        SurfaceControl leash = imeLeash;
        if (leash == null) {
            Log.i(TAG, "canvas_ime_push visible=" + visible + " leash=pending");
            return;
        }
        try {
            if (!leash.isValid()) { imeLeash = null; return; }
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setPosition(leash, leashX, leashY);
            t.setAlpha(leash, visible ? 1.0f : 0.0f);
            // show()/hide() exist at runtime but were dropped from the public SDK stubs; the
            // supported spelling is setVisibility(sc, visible), which is exactly show() or hide().
            visibility().invoke(t, leash, Boolean.valueOf(visible));
            t.apply();
            t.close();
            Log.i(TAG, "canvas_ime_push visible=" + visible + " leash=applied");
        } catch (Throwable e) {
            Log.w(TAG, "canvas_ime_leash_apply_failed", e);
        }
    }

    private static Method visibility() throws Exception {
        if (setVisibility == null) {
            setVisibility = SurfaceControl.Transaction.class.getMethod(
                    "setVisibility", SurfaceControl.class, boolean.class);
        }
        return setVisibility;
    }

    private static void bindWindowManager() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager.getMethod("getService", String.class)
                    .invoke(null, "window");
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            windowManager = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> token = Class.forName("android.view.inputmethod.ImeTracker$Token");
            pushVisibleTypes = windowManager.getClass().getMethod(
                    "updateDisplayWindowRequestedVisibleTypes",
                    int.class, int.class, int.class, token);
            Log.i(TAG, "canvas_ime_bridge_bound " + windowManager.getClass().getName());
        } catch (Throwable e) {
            Log.w(TAG, "canvas_ime_bind_failed", e);
        }
    }
}
