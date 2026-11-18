package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Capture selection at finger-up; allow Launcher to clean up before replacing its end target. */
final class LauncherRelease175 {
    interface Selection { int selectedTask(View panel) throws Exception; }
    private final Field panel, panelHandler, recents, manager, active, finishing;
    private final Method finish;
    private final LauncherRecents175 recentsLifecycle;
    private final Selection selection;
    private final Function<View, CompletableFuture<Bitmap>> wallpaper;
    private final Consumer<View> closePreview;
    private PortraitSource portrait;
    private final boolean centrePortrait;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Object pendingHandler, pendingController;
    private SwipeCandidate.Match pending;
    private CompletableFuture<Bitmap> pendingWallpaper;
    private long released, generation;

    LauncherRelease175(Class<?> handler, Field panel, Field panelHandler, LauncherRecents175 recentsLifecycle, Selection selection,
            Function<View, CompletableFuture<Bitmap>> wallpaper, Consumer<View> closePreview) throws Exception {
        this.panel = panel;
        this.panelHandler = panelHandler;
        this.recentsLifecycle = recentsLifecycle;
        this.selection = selection;
        this.wallpaper = wallpaper;
        this.closePreview = closePreview;
        recents = RomSymbols.field(handler, "V");
        centrePortrait = recents.getType().getName().equals("lz8");
        manager = RomSymbols.field(handler, "m0");
        active = manager.getType().getDeclaredField("B");
        if (active.getType() != recents.getType()) throw new NoSuchFieldException("active Recents controller");
        finishing = recents.getType().getDeclaredField("i");
        if (finishing.getType() != boolean.class) throw new NoSuchFieldException("Recents finish flag");
        finish = recents.getType().getDeclaredMethod("h", boolean.class, Runnable.class, boolean.class,
                handler.getClassLoader().loadClass("com.android.quickstep.GestureState$GestureEndTarget"), boolean.class);
        for (Field field : new Field[]{recents, manager, active, finishing}) field.setAccessible(true);
        finish.setAccessible(true);
    }

    void reset() {
        generation++;
        pendingHandler = pendingController = pending = null;
        pendingWallpaper = null;
    }

    void install(Class<?> handler, ArrayList<XC_MethodHook.Unhook> hooks) throws Exception {
        try {
            PortraitSource source = new PortraitSource(handler);
            hooks.add(source.install());
            portrait = source;
        } catch (Exception | LinkageError e) {
            Log.w("WindowDeck", "launcher_175_portrait_contract_failed", e);
        }
        Method up = handler.getDeclaredMethod("U1", handler, float.class, PointF.class, PointF.class, PointF.class, int.class);
        Method end = handler.getDeclaredMethod("F", float.class, boolean.class, PointF.class,
                boolean.class, boolean.class, boolean.class, boolean.class);
        Method windowAnimationStart = handler.getDeclaredMethod("Z1");
        hooks.add(XposedBridge.hookMethod(windowAnimationStart, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                View view = (View) panel.get(p.thisObject);
                if (view != null && stillOwnsPanel(view, p.thisObject)) closePreview.accept(view);
            }
        }));
        hooks.add(XposedBridge.hookMethod(up, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Object owner = p.args[0];
                View view = (View) panel.get(owner);
                if (view != null && !stillOwnsPanel(view, owner)) return;
                reset();
                if (view == null) return;
                try {
                    PointF position = (PointF) p.args[4];
                    if ((Integer) p.args[5] != 0x30 || position == null ||
                            !Float.isFinite(position.x) || !Float.isFinite(position.y)) return;
                    boolean corner = CornerSwipeHook.owns(owner);
                    if (corner && !CornerSwipeHook.ready(owner)) return;
                    if (!corner && view.getDisplay() != null &&
                            CornerSwipeHook.mode(view.getContext(), view.getDisplay().getRotation())) return;
                    SwipeCandidate.Match candidate;
                    if (corner) {
                        candidate = CornerSwipeHook.candidate(owner);
                        if (!same(candidate, CandidateProbe.matchPanelTask(candidate.task))) return;
                    } else {
                        int task = selection.selectedTask(view);
                        if (task < 0) return;
                        candidate = CandidateProbe.matchPanelTask(task);
                    }
                    Object controller = recents.get(owner);
                    if (candidate == null || !current(owner, controller)) {
                        Log.i("WindowDeck", "launcher_175_release_native candidate=" + (candidate != null));
                        return;
                    }
                    pending = candidate;
                    pendingHandler = owner;
                    pendingController = controller;
                    released = SystemClock.uptimeMillis();
                    pendingWallpaper = corner ? prepareWallpaper(view, candidate.task) : wallpaper.apply(view);
                    long ticket = generation;
                    main.postDelayed(() -> { if (ticket == generation && pending != null) reset(); }, 1500);
                } finally {
                    closePreview.accept(view);
                }
            }
        }));
        hooks.add(XposedBridge.hookMethod(end, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                boolean corner = CornerSwipeHook.owns(p.thisObject);
                if (pending == null || pendingHandler != p.thisObject) {
                    if (corner && CornerSwipeHook.endTarget(p.thisObject)) p.setResult(null);
                    return;
                }
                SwipeCandidate.Match candidate = pending;
                Object controller = pendingController;
                CompletableFuture<Bitmap> background = pendingWallpaper;
                pendingHandler = pendingController = pending = null;
                pendingWallpaper = null;
                View view = (View) panel.get(p.thisObject);
                if (!current(p.thisObject, controller) || !same(candidate, CandidateProbe.matchPanelTask(candidate.task)) ||
                        view == null || view.getDisplay() == null ||
                        (corner && (Boolean.TRUE.equals(p.args[3]) || !CornerSwipeHook.ready(p.thisObject)))) {
                    if (corner && CornerSwipeHook.endTarget(p.thisObject)) p.setResult(null);
                    return;
                }
                int rotation = corner ? CornerSwipeHook.rotation(p.thisObject) : view.getDisplay().getRotation();
                RectF cornerBox = corner ? CornerSwipeHook.releaseBox(p.thisObject) : null;
                if (portrait != null && (corner || centrePortrait) && rotation == 0 && candidate.container < 0 &&
                        startPortrait(view, p.thisObject, controller, candidate, released, background, cornerBox)) {
                    if (corner) CornerSwipeHook.handedOff(p.thisObject, released + MotionSpec.COVER_TIMEOUT_MS);
                    p.setResult(null);
                    return;
                }
                Context context = view.getContext().getApplicationContext();
                long ticket = generation, at = released;
                Runnable completed = () -> main.post(() -> {
                    if (ticket != generation) return;
                    generation++;
                    if (same(candidate, CandidateProbe.matchPanelTask(candidate.task)))
                        SwipeReleaseHandoff.dispatchAfterRecents(context, candidate, at, rotation);
                });
                finishHandoff(p.thisObject, controller, completed);
                if (corner) CornerSwipeHook.handedOff(p.thisObject, released + MotionSpec.COVER_TIMEOUT_MS);
                p.setResult(null);
                Log.i("WindowDeck", "launcher_175_release_finish task=" + candidate.task);
            }
        }));
    }

    private void finishHandoff(Object owner, Object controller, Runnable completed) throws ReflectiveOperationException {
        Runnable retire = recentsLifecycle == null ? null : recentsLifecycle.proxyRetirement(owner, controller);
        finish.invoke(controller, false, completed, false, null, true);
        if (retire != null) retire.run();
    }

    private boolean stillOwnsPanel(View view, Object handler) throws IllegalAccessException {
        WeakReference<?> reference = (WeakReference<?>) panelHandler.get(view);
        Object current = reference == null ? null : reference.get();
        return current == null || current == handler;
    }

    CompletableFuture<Bitmap> prepareWallpaper(View view, int task) {
        if (portrait == null || view.getDisplay() == null || view.getDisplay().getRotation() != 0) return null;
        SwipeCandidate.Match candidate = CandidateProbe.matchPanelTask(task);
        if (candidate == null || candidate.container >= 0) return null;
        Context context = view.getContext().getApplicationContext();
        WindowManager windows = view.getContext().getSystemService(WindowManager.class);
        if (windows == null) return null;
        Rect bounds = windows.getMaximumWindowMetrics().getBounds();
        int width = bounds.width(), height = bounds.height();
        if (width <= 0 || width >= height) return null;
        return CompletableFuture.supplyAsync(() -> WorkbenchBackdrop.preparePortrait(context, width, height));
    }

    private boolean startPortrait(View panel, Object owner, Object controller, SwipeCandidate.Match candidate, long at,
            CompletableFuture<Bitmap> background, RectF cornerBox) {
        SwipeSourceFrame.Capture source = null;
        boolean started = false;
        try {
            if (portrait.finishDirectionLocked.getBoolean(controller)) return false;
            Object[] handles = (Object[]) portrait.handles.get(owner);
            if (handles == null || handles.length != 1 || handles[0] == null) return false;
            Object simulator = portrait.simulator.get(handles[0]);
            Object parameters = portrait.parameters.get(handles[0]);
            Object targets = parameters == null ? null : portrait.targets.get(parameters);
            Object[] apps = targets == null ? null : (Object[]) portrait.apps.get(targets);
            if (simulator == null || apps == null || apps.length != 1 || apps[0] == null ||
                    portrait.task.getInt(apps[0]) != candidate.task) return false;
            if (portrait.adjustedMatrix.get(simulator) != null || portrait.adjustedCrop.getBoolean(simulator) ||
                    portrait.hidden.getBoolean(simulator) || portrait.alpha.getFloat(simulator) != 1f) return false;
            Rect bounds = (Rect) portrait.bounds.get(apps[0]);
            if (bounds == null || bounds.left != 0 || bounds.top != 0) return false;
            RectF box;
            float[] crop;
            float radius;
            if (cornerBox != null) {
                box = cornerBox;
                crop = new float[]{0, 0, bounds.width(), bounds.height()};
                radius = CornerSwipeHook.releaseRadius(owner);
            } else {
                Matrix matrix = new Matrix((Matrix) portrait.matrix.get(simulator));
                Rect rect = new Rect((Rect) portrait.crop.get(simulator));
                box = new RectF(rect);
                matrix.mapRect(box);
                crop = new float[]{rect.left, rect.top, rect.right, rect.bottom};
                float[] values = new float[9];
                matrix.getValues(values);
                if (!PortraitContentGeometry.sourceValid(bounds.width(), bounds.height(), crop,
                        new float[]{box.left, box.top, box.right, box.bottom}, values)) return false;
                // The shared pose writer expresses screen radius in units of the maximum leash scale.
                radius = (Float) portrait.radius.invoke(simulator) * Math.max(values[0], values[4]);
            }
            source = SwipeSourceFrame.captureTaskLeash((SurfaceControl) portrait.leash.get(apps[0]), bounds, candidate.task);
            if (source == null) return false;
            Object animation = manager.get(owner);
            started = PortraitRecentsHandoff.start(panel, simulator, controller, candidate, source, box, crop, radius,
                    at, null, background, true, () -> true, new PortraitRecentsHandoff.Recents() {
                        public Object active() throws IllegalAccessException { return active.get(animation); }
                        public boolean keepSourceVisible() throws Exception {
                            return portrait.setFinishDirection(controller, false);
                        }
                        public boolean commitDestination() throws Exception {
                            return portrait.setFinishDirection(controller, true);
                        }
                        public void finish(Runnable completed) throws Exception {
                            finishHandoff(owner, controller, completed);
                        }
                    });
            return started;
        } catch (Exception | LinkageError e) {
            Log.w("WindowDeck", "launcher_175_portrait_source_failed", e);
            return false;
        } finally {
            if (!started && source != null) {
                source.leash.release();
                source.closeVisibility();
            }
            if (!started) Log.i("WindowDeck", "launcher_175_portrait_after_recents task=" + candidate.task);
        }
    }

    /** 17.5.5 single-task simulator and the builder it writes before submitting its transaction. */
    private final class PortraitSource {
        final Field handles, simulator, parameters, targets, apps, task, bounds, leash;
        final Field matrix, crop, adjustedMatrix, adjustedCrop, hidden, alpha;
        final Field finishDirection, finishDirectionLocked;
        final Method radius, willFinishToHome, build, writeMatrix, writeCrop, writeCorners;

        PortraitSource(Class<?> handler) throws Exception {
            ClassLoader loader = handler.getClassLoader();
            Class<?> handle = loader.loadClass(centrePortrait ? "ka9" : "qa9");
            Class<?> sim = loader.loadClass(centrePortrait ? "wya" : "vza");
            Class<?> params = loader.loadClass(centrePortrait ? "geb" : "afb");
            Class<?> targetList = loader.loadClass(centrePortrait ? "z99" : "ea9");
            Class<?> target = loader.loadClass(centrePortrait ? "w99" : "ba9");
            Class<?> builder = loader.loadClass(centrePortrait ? "wka" : "jla");
            Class<?> handleArray = java.lang.reflect.Array.newInstance(handle, 0).getClass();
            Class<?> targetArray = java.lang.reflect.Array.newInstance(target, 0).getClass();
            handles = field(handler, "u", handleArray);
            simulator = field(handle, "a", sim);
            parameters = field(handle, "b", params);
            targets = field(params, "f", targetList);
            apps = field(targetList, "d", targetArray);
            task = field(target, "a", int.class);
            bounds = field(target, "i", Rect.class);
            leash = field(target, "d", SurfaceControl.class);
            matrix = field(sim, "x", Matrix.class);
            crop = field(sim, "b0", Rect.class);
            adjustedMatrix = field(sim, "F", Matrix.class);
            adjustedCrop = field(sim, "L0", boolean.class);
            hidden = field(sim, "t", boolean.class);
            alpha = field(sim, "P0", float.class);
            finishDirection = field(recents.getType(), "k", boolean.class);
            finishDirectionLocked = field(recents.getType(), "l", boolean.class);
            radius = sim.getDeclaredMethod("l");
            willFinishToHome = recents.getType().getDeclaredMethod("o", boolean.class);
            build = sim.getDeclaredMethod("e", builder, target, params);
            writeMatrix = builder.getDeclaredMethod("i", Matrix.class);
            writeCrop = builder.getDeclaredMethod("n", Rect.class);
            writeCorners = builder.getDeclaredMethod("e", float.class, float.class);
            for (Method method : new Method[]{radius, willFinishToHome, writeMatrix, writeCrop, writeCorners})
                method.setAccessible(true);
        }

        boolean setFinishDirection(Object controller, boolean toHome) throws Exception {
            if (finishDirectionLocked.getBoolean(controller)) return false;
            willFinishToHome.invoke(controller, toHome);
            return finishDirection.getBoolean(controller) == toHome;
        }

        XC_MethodHook.Unhook install() {
            return XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    if (!PortraitRecentsHandoff.ownsSimulator(p.thisObject) ||
                            p.hasThrowable() || p.args[0] == null || p.args[1] == null) return;
                    Object target = p.args[1];
                    PortraitRecentsHandoff.retainPose(p.thisObject, p.args[0], task.getInt(target),
                            (SurfaceControl) leash.get(target), writeMatrix, writeCrop, writeCorners);
                }
            });
        }
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field field = RomSymbols.field(owner, name);
        if (field.getType() != type || java.lang.reflect.Modifier.isStatic(field.getModifiers()))
            throw new NoSuchFieldException(owner.getName() + "." + name);
        field.setAccessible(true);
        return field;
    }

    private boolean current(Object owner, Object controller) throws IllegalAccessException {
        Object animation = manager.get(owner);
        return controller != null && animation != null && recents.get(owner) == controller
                && active.get(animation) == controller && !finishing.getBoolean(controller);
    }

    private static boolean same(SwipeCandidate.Match a, SwipeCandidate.Match b) {
        return b != null && a.task == b.task && a.user == b.user && a.container == b.container && a.existingTask == b.existingTask;
    }
}
