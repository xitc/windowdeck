package io.github.xitc.windowdeck;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Carries the 17.5.5 Shell transition ID through the native Recents listener dispatch. */
final class LauncherRecents175 {
    private static final String TAG = "WindowDeck";
    private static final String RETRY = "windowdeck_recents_retry";
    private static final String PREVIOUS_FINISH = "windowdeck_previous_recents_finish";
    // The native TransitionTracker expires pending entries after five seconds.
    private static final long TRANSITION_TIMEOUT_MS = 5000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Integer> finishId = new ThreadLocal<>();
    private final Field proxy, standardOwner, gestureOwner, controller, remote, snapshot, shellId;
    private final Field activityTracker;
    private final Method createdActivity, transitionManager, setProxy;

    private static final class Retry {
        final int transition;
        final String launchId;
        final AtomicBoolean pending = new AtomicBoolean(true);

        Retry(int transition, String launchId) {
            this.transition = transition;
            this.launchId = launchId;
        }

        void record(HandoffDiagnostics.Event event, String reason) {
            HandoffDiagnostics.timeline(event, reason, -1, launchId);
        }
    }

    private LauncherRecents175(Class<?> handler, Class<?> manager) throws ReflectiveOperationException {
        ClassLoader loader = handler.getClassLoader();
        Class<?> baseHandler = loader.loadClass("i3");
        Class<?> proxyType = loader.loadClass(manager.getName() + "$RecentTransitionProxy");
        proxy = field(manager, "mRecentTransitionProxy", proxyType);
        standardOwner = field(loader.loadClass("b3"), "a", Object.class);
        gestureOwner = field(loader.loadClass("m77"), "a", handler);
        controller = field(baseHandler, "V", loader.loadClass("lz8"));
        remote = field(controller.getType(), "a", loader.loadClass("mz8"));
        snapshot = field(remote.getType(), "d", loader.loadClass("ffb"));
        shellId = field(snapshot.getType(), "a", int.class);

        Class<?> launcher = loader.loadClass("com.android.launcher3.Launcher");
        activityTracker = field(launcher, "ACTIVITY_TRACKER",
                loader.loadClass("com.android.launcher3.util.ActivityTracker"));
        createdActivity = activityTracker.getType().getMethod("getCreatedActivity");
        transitionManager = launcher.getMethod("getAppTransitionManager");
        setProxy = manager.getMethod("setRecentTransitionProxy", proxyType);
        if (transitionManager.getReturnType() != manager || setProxy.getReturnType() != void.class)
            throw new NoSuchMethodException("Recents proxy contract");
    }

    static LauncherRecents175 install(Class<?> handler) {
        ArrayList<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            if (!"h84".equals(RomSymbols.field(handler, "t").getType().getName())) return null;
            ClassLoader loader = handler.getClassLoader();
            Class<?> manager = loader.loadClass("com.android.launcher3.QuickstepTransitionManager");
            LauncherRecents175 lifecycle = new LauncherRecents175(handler, manager);
            lifecycle.installRetryHooks(loader, manager, hooks);
            Log.i(TAG, "launcher_recents_ownership_ready");
            return lifecycle;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            Log.w(TAG, "launcher_recents_ownership_unavailable", e);
            return null;
        }
    }

    private void installRetryHooks(ClassLoader loader, Class<?> manager, ArrayList<XC_MethodHook.Unhook> hooks)
            throws ReflectiveOperationException {
        Class<?> reason = loader.loadClass("com.oplus.animation.core.anim.SpringAnimConstants$ReasonEndTransition");
        Class<?> callback = loader.loadClass("jf5");
        Class<?> listener = loader.loadClass(manager.getName() + "$22");
        Class<?> listenerType = loader.loadClass("mta");
        Class<?> listeners = loader.loadClass("rta");
        Class<?> broadcast = loader.loadClass("ota");
        Field intent = field(callback, "r", Intent.class);
        Field consumer = field(listener, "val$onCompleteRunnable", Consumer.class);
        Method end = manager.getDeclaredMethod("endAppTransition", reason, boolean.class, Consumer.class);
        Method add = listeners.getDeclaredMethod("a", listenerType);
        Method remove = listeners.getDeclaredMethod("b", listenerType);
        Method receiveFinish = loader.loadClass("qta").getDeclaredMethod("onTransitionFinished", Bundle.class);
        Method execute = loader.loadClass("b56").getDeclaredMethod("execute", Runnable.class);
        Method dispatchFinish = listener.getDeclaredMethod("onTransitionFinish", boolean.class);
        if (!reason.isEnum() || !Consumer.class.isAssignableFrom(callback) ||
                !listenerType.isAssignableFrom(listener) || !Runnable.class.isAssignableFrom(broadcast))
            throw new NoSuchMethodException("Recents retry contract");
        remove.setAccessible(true);

        hooks.add(XposedBridge.hookMethod(receiveFinish, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra(PREVIOUS_FINISH, finishId.get());
                Bundle info = (Bundle) p.args[0];
                int id = info != null && info.getBoolean("isRecent") ? info.getInt("transitionId", -1) : -1;
                setFinishId(id >= 0 ? id : null);
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                setFinishId((Integer) p.getObjectExtra(PREVIOUS_FINISH));
            }
        }));
        hooks.add(XposedBridge.hookMethod(execute, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Integer id = finishId.get();
                if (id == null || !broadcast.isInstance(p.args[0])) return;
                Runnable original = (Runnable) p.args[0];
                p.args[0] = (Runnable) () -> {
                    Integer previous = finishId.get();
                    finishId.set(id);
                    try {
                        original.run();
                    } finally {
                        setFinishId(previous);
                    }
                };
            }
        }));
        hooks.add(XposedBridge.hookMethod(dispatchFinish, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (!Boolean.TRUE.equals(p.args[0])) return;
                Retry retry = retryOf(consumer.get(p.thisObject));
                if (retry == null) return;
                Integer id = finishId.get();
                if (id == null || id != retry.transition) {
                    retry.record(HandoffDiagnostics.Event.APP_CALLBACK, "icon_retry_other_transition");
                    p.setResult(null);
                } else if (retry.pending.compareAndSet(true, false)) {
                    retry.record(HandoffDiagnostics.Event.APP_CALLBACK, "icon_retry_matching_transition");
                } else {
                    p.setResult(null);
                }
            }
        }));
        hooks.add(XposedBridge.hookMethod(add, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (p.hasThrowable() || !listener.isInstance(p.args[0])) return;
                Retry retry = retryOf(consumer.get(p.args[0]));
                if (retry == null) return;
                WeakReference<Object> registered = new WeakReference<>(p.args[0]);
                main.postDelayed(() -> {
                    Object stale = registered.get();
                    if (stale == null || !retry.pending.compareAndSet(true, false)) return;
                    try {
                        remove.invoke(null, stale);
                        retry.record(HandoffDiagnostics.Event.APP_CALLBACK, "icon_retry_transition_expired");
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        Log.w(TAG, "launcher_recents_retry_expiry_failed", e);
                    }
                }, TRANSITION_TIMEOUT_MS);
            }
        }));
        hooks.add(XposedBridge.hookMethod(end, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (!(p.args[0] instanceof Enum<?>) || !"START_NEW_APP".equals(((Enum<?>)p.args[0]).name()) ||
                        Boolean.TRUE.equals(p.args[1]) || !callback.isInstance(p.args[2])) return;
                Intent target = (Intent) intent.get(p.args[2]);
                if (!LauncherExistingAppHook.launcherIcon(target)) return;
                Object owner = proxyOwner(proxy.get(p.thisObject));
                if (owner == null || !controller.getDeclaringClass().isInstance(owner)) return;
                int id = transitionId(controller.get(owner));
                if (id < 0) return;
                Retry retry = new Retry(id, LauncherStartDiagnostics.currentId(target));
                XposedHelpers.setAdditionalInstanceField(p.args[2], RETRY, retry);
                retry.record(HandoffDiagnostics.Event.APP_DEFERRED, "icon_transition_bound");
            }
        }));
    }

    private static Retry retryOf(Object consumer) {
        return consumer == null ? null : (Retry) XposedHelpers.getAdditionalInstanceField(consumer, RETRY);
    }

    private void setFinishId(Integer id) {
        if (id == null) finishId.remove();
        else finishId.set(id);
    }

    private Object proxyOwner(Object value) throws IllegalAccessException {
        if (standardOwner.getDeclaringClass().isInstance(value)) return standardOwner.get(value);
        if (gestureOwner.getDeclaringClass().isInstance(value)) return gestureOwner.get(value);
        return null;
    }

    private int transitionId(Object value) throws IllegalAccessException {
        Object remoteController = value == null ? null : remote.get(value);
        Object details = remoteController == null ? null : snapshot.get(remoteController);
        return details == null ? -1 : shellId.getInt(details);
    }

    /** Capture before requesting finish. A later gesture may install a different proxy. */
    Runnable proxyRetirement(Object owner, Object finishingController) {
        try {
            Object activity = createdActivity.invoke(activityTracker.get(null));
            if (!transitionManager.getDeclaringClass().isInstance(activity)) return null;
            Object manager = transitionManager.invoke(activity);
            if (manager == null) return null;
            Object owned = proxy.get(manager);
            if (proxyOwner(owned) != owner || controller.get(owner) != finishingController) return null;
            return () -> {
                try {
                    if (proxy.get(manager) != owned) return;
                    setProxy.invoke(manager, (Object) null);
                    Log.i(TAG, "launcher_recents_proxy_retired");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    Log.w(TAG, "launcher_recents_proxy_retire_failed", e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "launcher_recents_proxy_owner_unavailable", e);
            return null;
        }
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
        Field field = RomSymbols.field(type, name);
        if (field.getType() != expected) throw new NoSuchFieldException(type.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }
}
