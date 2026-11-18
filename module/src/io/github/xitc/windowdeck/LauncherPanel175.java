package io.github.xitc.windowdeck;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.FloatProperty;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.content.res.Resources;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Three slots in the native two-option panel. Native end targets are retained outside the selected centre. */
final class LauncherPanel175 {
    private final FloatProperty<?> property;
    private final Field handlerPanel, status, rotation, axis, entrances, controller, params;
    private final Field startShow, trigger, rects, normalRect, zoomRect;
    private final Method updateRects, selectionOf, cancelAnimators, switchAxis;
    private final Field previousAxis, activeAxis, helpers, paint, paintBase, background;
    private final Object none, capsule;
    private final ThreadLocal<Object> driving = new ThreadLocal<>();
    private final LauncherRelease175 release;
    private final WeakHashMap<Object, WeakReference<Preview>> owners = new WeakHashMap<>();

    static boolean matches(Class<?> panel, Class<?> handler) {
        String base = panel.getSuperclass().getName();
        return handler.getName().equals("com.oplus.quickstep.gesture.c")
                && (base.equals("ba7") || base.equals("t97"));
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field f = owner.getDeclaredField(name);
        if (f.getType() != type || java.lang.reflect.Modifier.isStatic(f.getModifiers()))
            throw new NoSuchFieldException(owner.getName() + "." + name);
        f.setAccessible(true);
        return f;
    }

    private LauncherPanel175(Class<?> panel, Class<?> handler, LauncherRecents175 recentsLifecycle) throws Exception {
        ClassLoader loader = panel.getClassLoader();
        boolean older = panel.getSuperclass().getName().equals("ba7");
        Object value = loader.loadClass(older ? "uk" : "xk").getDeclaredField("a").get(null);
        property = (FloatProperty<?>) value;
        if (!"trigger_panel_enter".equals(property.getName()) || !FrameLayout.class.isAssignableFrom(panel))
            throw new IllegalArgumentException("trigger panel property");
        handlerPanel = field(handler, "a2", panel.getSuperclass());
        status = field(panel, "Q", int.class);
        rotation = field(panel, "J", int.class);
        axis = field(panel, "L", int.class);
        entrances = field(panel, "t", ArrayList.class);
        controller = field(panel, "U", loader.loadClass(older ? "dq6" : "xp6"));
        Class<?> controllerType = controller.getType();
        params = controllerType.getDeclaredField("c");
        params.setAccessible(true);
        Class<?> type = params.getType();
        startShow = field(type, "h", float.class);
        trigger = field(type, "g", float.class);
        rects = field(type, "q", HashMap.class);
        Class<?> info = type.getDeclaredMethod("b", int.class).getReturnType();
        normalRect = field(info, "a", RectF.class);
        zoomRect = field(info, "c", RectF.class);
        Class<?> twoOptions = loader.loadClass(older ? "r6a" : "g6a");
        if (twoOptions.getSuperclass() != type) throw new IllegalArgumentException("two-option params");
        selectionOf = twoOptions.getDeclaredMethod("d", int.class);
        Class<?> options = selectionOf.getReturnType();
        none = options.getField("c").get(null);
        capsule = options.getField("s").get(null);
        if (!((Enum<?>)none).name().equals("NONE") || !((Enum<?>)capsule).name().equals("CAPSULE"))
            throw new IllegalArgumentException("centre selection options");
        cancelAnimators = controllerType.getDeclaredMethod("d");
        switchAxis = controllerType.getDeclaredMethod("e", int.class);
        previousAxis = field(controllerType, "f", int.class);
        activeAxis = field(controllerType, "g", int.class);
        helpers = field(controllerType, "i", HashMap.class);
        Class<?> painter = loader.loadClass(older ? "vo6" : "po6");
        paint = field(loader.loadClass(older ? "sp6" : "mp6"), "a", painter);
        paintBase = field(painter, "c", RectF.class);
        background = field(panel, "w", loader.loadClass(older ? "wo6" : "qo6"));
        release = new LauncherRelease175(handler, handlerPanel, field(panel, "T", WeakReference.class), recentsLifecycle, view -> {
            Preview state = preview((FrameLayout)view);
            if (state == null || !state.dodging || state.row == null || !state.chrome.zone().isShown()
                    || !state.chrome.zone().isSelected() || status.getInt(view) == 0) return -1;
            return SwipePanelPolicy.selectable(state.progress, trigger.getFloat(state.geometry))
                    && SwipePanelPolicy.claimsCentre(axis.getInt(view)) ? state.task : -1;
        }, view -> {
            Preview state = preview((FrameLayout) view);
            return state == null ? null : state.wallpaper;
        }, view -> {
            Preview state = preview((FrameLayout) view);
            if (state != null) {
                state.task = -1;
                state.reset();
            }
        });
        updateRects = twoOptions.getDeclaredMethod("e", boolean.class, boolean.class, Resources.class,
                int.class, int.class);
    }

    private interface Update { void run(XC_MethodHook.MethodHookParam p) throws Exception; }

    private static XC_MethodHook after(Update update) {
        return new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!p.hasThrowable()) update.run(p);
            }
        };
    }

    static boolean install(Class<?> panel, Class<?> handler, LauncherRecents175 recentsLifecycle) {
        ArrayList<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            LauncherPanel175 c = new LauncherPanel175(panel, handler, recentsLifecycle);
            Method progress = c.property.getClass().getDeclaredMethod("setValue", Object.class, float.class);
            Method tracking = handler.getDeclaredMethod("I2");
            Method task = panel.getDeclaredMethod("setTaskIdForDodge", int.class);
            Method visibility = panel.getDeclaredMethod("setVisibility", int.class);
            c.release.install(handler, hooks);
            Method calculateAxis = c.selectionOf.getDeclaringClass().getDeclaredMethod("a",
                    float.class, int.class, int.class, int.class);
            hooks.add(XposedBridge.hookMethod(calculateAxis, after(p -> {
                WeakReference<Preview> owner = c.owners.get(p.thisObject);
                Preview state = owner == null ? null : owner.get();
                if (state == null || state.geometry != p.thisObject || state.row == null) return;
                if (!SwipePanelPolicy.stillCurrent((Integer)p.getResult())) {
                    state.closeRow();
                    state.display(false, false);
                }
            })));
            hooks.add(XposedBridge.hookMethod(c.selectionOf, after(p -> {
                if (c.driving.get() == p.thisObject && (Integer)p.args[0] == 0x33 && p.getResult() == c.none)
                    p.setResult(c.capsule);
            })));
            hooks.add(XposedBridge.hookMethod(c.updateRects, after(p -> {
                WeakReference<Preview> owner = c.owners.get(p.thisObject);
                Preview state = owner == null ? null : owner.get();
                if (state != null && state.geometry == p.thisObject && state.row != null) {
                    // e() creates fresh rectangles before the native animator reads them.
                    state.row = c.openRow((FrameLayout)state.chrome.zone().getParent(), p.thisObject);
                }
            })));
            hooks.add(XposedBridge.hookMethod(task, after(p -> {
                FrameLayout view = (FrameLayout) p.thisObject;
                LiveWorkbenchState.register(view.getContext());
                Preview state = preview(view);
                if (state == null) state = new Preview(view);
                c.release.reset();
                c.dodge(view, state, false);
                state.reset();
                state.task = (Integer) p.args[0];
                CandidateProbe.kick();
            })));
            hooks.add(XposedBridge.hookMethod(progress, after(p -> {
                if (p.thisObject != c.property || !panel.isInstance(p.args[0])) return;
                FrameLayout view = (FrameLayout) p.args[0];
                Preview state = preview(view);
                if (state != null) {
                    state.progress = (Float) p.args[1];
                    c.render(view, state);
                }
            })));
            // Playback can keep the same vertical fraction while I2 updates the horizontal offset.
            hooks.add(XposedBridge.hookMethod(tracking, after(p -> {
                Object target = c.handlerPanel.get(p.thisObject);
                if (!panel.isInstance(target)) return;
                FrameLayout view = (FrameLayout) target;
                Preview state = preview(view);
                if (state != null) c.render(view, state);
            })));
            hooks.add(XposedBridge.hookMethod(visibility, after(p -> {
                FrameLayout view = (FrameLayout) p.thisObject;
                if (view.getVisibility() != View.VISIBLE) {
                    Preview state = preview(view);
                    if (state != null) { c.dodge(view, state, false); state.reset(); }
                }
            })));
            LauncherSwipeHook.log("launcher_175_preview_ready property=" + c.property.getName());
            CornerSwipeHook.install(handler.getClassLoader(), handler, c.handlerPanel);
            return true;
        } catch (Throwable e) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            Log.w("WindowDeck", "launcher_175_preview_contract_failed", e);
            return false;
        }
    }

    private static final class Preview {
        final SwipeZoneChrome chrome;
        Object geometry;
        Row row;
        int task = -1;
        float progress, expansion;
        boolean dodging;
        CompletableFuture<Bitmap> wallpaper;
        ValueAnimator spring;
        Preview(FrameLayout panel) {
            chrome = SwipeZoneChrome.attach(panel);
            chrome.zone().setTag(this);
            chrome.zone().setVisibility(View.GONE);
            chrome.apply(0);
        }
        void reset() {
            closeRow();
            progress = 0;
            wallpaper = null;
            display(false, false);
        }
        void position() {
            if (row == null) return;
            RectF card = row.card;
            View zone = chrome.zone();
            FrameLayout panel = (FrameLayout)zone.getParent();
            float density = panel.getResources().getDisplayMetrics().density;
            zone.setRotation(row.angle);
            float w = card.width(), h = card.height(), x = card.centerX(), y = card.top;
            if (row.vertical) {
                float available = Math.min(card.centerY(), panel.getHeight() - card.centerY()) * 2;
                h += Math.max(0, available - 24 * density - h) * expansion;
                y = card.centerY() - h / 2;
            } else {
                float[] full = SwipePanelPolicy.box(panel.getWidth(), panel.getHeight(), density, y, h, 1);
                w += Math.max(0, full[2] - w) * expansion;
                h += Math.max(0, full[3] - h) * expansion;
            }
            if (row.angle == 90 || row.angle == 270) {
                float cy = y + h / 2, oldWidth = w;
                w = h; h = oldWidth; y = cy - h / 2;
            }
            int left = Math.round(x - w / 2), top = Math.round(y);
            int width = Math.round(w), height = Math.round(h);
            if (zone.isLayoutRequested() || zone.getLeft() != left || zone.getTop() != top ||
                    zone.getWidth() != width || zone.getHeight() != height) {
                zone.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                zone.layout(left, top, left + width, top + height);
            }
        }
        void closeRow() {
            if (row == null) return;
            row.restore();
            row = null;
            geometry = null;
            chrome.zone().requestLayout();
        }
        void display(boolean shown, boolean selected) {
            View zone = chrome.zone();
            int visibility = shown ? View.VISIBLE : View.GONE;
            if (zone.getVisibility() != visibility || zone.isSelected() != selected)
                Log.i("WindowDeck", "launcher_175_preview shown=" + shown + " selected=" + selected);
            if (zone.isSelected() != selected) {
                zone.setSelected(selected);
                if (spring != null) spring.cancel();
                if (shown) {
                    spring = ValueAnimator.ofFloat(expansion, selected ? 1 : 0);
                    spring.setDuration(SwipePanelPolicy.ROM_SPRING_MILLIS);
                    spring.setInterpolator(SwipePanelPolicy::spring);
                    spring.addUpdateListener(anim -> {
                        expansion = (Float) anim.getAnimatedValue();
                        chrome.apply(expansion);
                        position();
                    });
                    spring.start();
                }
            }
            if (!shown && spring != null) {
                spring.cancel();
                spring = null;
                expansion = 0;
                chrome.apply(0);
            }
            if (zone.getVisibility() != visibility) zone.setVisibility(visibility);
        }
    }

    private static Preview preview(FrameLayout panel) {
        for (int i = panel.getChildCount() - 1; i >= 0; i--) {
            Object tag = panel.getChildAt(i).getTag();
            if (tag instanceof Preview) return (Preview) tag;
        }
        return null;
    }

    /** Native geometry objects belong to one layout generation and are replaced together. */
    private static final class Row {
        final RectF[] live;
        final RectF[] original;
        final boolean vertical;
        final float angle;
        final RectF firstBase, lastBase;
        final View background;
        final RectF card = new RectF();

        Row(RectF first, RectF firstZoom, RectF last, RectF lastZoom, boolean vertical,
                float angle, float desired, RectF firstBase, RectF lastBase, View background) {
            this.angle = angle;
            this.firstBase = firstBase;
            this.lastBase = lastBase;
            this.background = background;
            this.vertical = vertical;
            live = new RectF[]{first, firstZoom, last, lastZoom};
            original = new RectF[]{new RectF(first), new RectF(firstZoom), new RectF(last), new RectF(lastZoom)};
            float gap = vertical ? last.top - first.bottom : last.left - first.right;
            float span = vertical ? last.bottom - first.top : last.right - first.left;
            if (gap >= desired) {
                if (vertical) {
                    float mid = (first.bottom + last.top) / 2;
                    card.set(Math.max(first.left, last.left), mid - desired / 2,
                            Math.min(first.right, last.right), mid + desired / 2);
                } else {
                    float mid = (first.right + last.left) / 2;
                    card.set(mid - desired / 2, Math.max(first.top, last.top),
                            mid + desired / 2, Math.min(first.bottom, last.bottom));
                }
                return;
            }
            float size = (span - 2 * gap) / 3;
            if (gap < 0 || size <= 0) return;
            if (vertical) {
                card.set(Math.max(first.left, last.left), first.top + size + gap,
                        Math.min(first.right, last.right), last.bottom - size - gap);
            } else {
                card.set(first.left + size + gap, Math.max(first.top, last.top),
                        last.right - size - gap, Math.min(first.bottom, last.bottom));
            }
            if (card.isEmpty()) return;
            resize(first, firstZoom, size, vertical, true);
            resize(last, lastZoom, size, vertical, false);
            syncPaint();
        }

        private void syncPaint() {
            firstBase.set(live[0]);
            lastBase.set(live[2]);
            background.invalidate();
        }

        private static void resize(RectF normal, RectF zoom, float size, boolean vertical, boolean first) {
            if (vertical) {
                float anchor = first ? normal.top : normal.bottom;
                float factor = size / normal.height();
                zoom.top = anchor + (zoom.top - anchor) * factor;
                zoom.bottom = anchor + (zoom.bottom - anchor) * factor;
                if (first) normal.bottom = anchor + size; else normal.top = anchor - size;
            } else {
                float anchor = first ? normal.left : normal.right;
                float factor = size / normal.width();
                zoom.left = anchor + (zoom.left - anchor) * factor;
                zoom.right = anchor + (zoom.right - anchor) * factor;
                if (first) normal.right = anchor + size; else normal.left = anchor - size;
            }
        }

        void restore() {
            for (int i = 0; i < live.length; i++) live[i].set(original[i]);
            syncPaint();
        }
    }

    private Row openRow(FrameLayout view, Object geometry) throws Exception {
        HashMap<?, ?> map = (HashMap<?, ?>) rects.get(geometry);
        Object a = map.get(0x32), b = map.get(0x34);
        if (map.size() != 2 || a == null || b == null) return null;
        RectF first = (RectF) normalRect.get(a), last = (RectF) normalRect.get(b);
        if (first.isEmpty() || last.isEmpty()) return null;
        boolean vertical = Math.abs(first.centerY() - last.centerY()) > Math.abs(first.centerX() - last.centerX());
        RectF az = (RectF) zoomRect.get(a), bz = (RectF) zoomRect.get(b);
        boolean ordered = vertical ? first.top < last.top : first.left < last.left;
        HashMap<?, ?> byAxis = (HashMap<?, ?>)helpers.get(controller.get(view));
        Object left = byAxis.get(0x32), right = byAxis.get(0x34);
        if (left == null || right == null || paint.get(left) == null || paint.get(right) == null) return null;
        RectF leftBase = (RectF)paintBase.get(paint.get(left)), rightBase = (RectF)paintBase.get(paint.get(right));
        float desired = 112 * view.getResources().getDisplayMetrics().density;
        float angle = SwipePanelPolicy.chipRotation(rotation.getInt(view));
        View bg = (View)background.get(view);
        Row row = ordered ? new Row(first, az, last, bz, vertical, angle, desired, leftBase, rightBase, bg)
                : new Row(last, bz, first, az, vertical, angle, desired, rightBase, leftBase, bg);
        return row.card.isEmpty() ? null : row;
    }

    private void dodge(FrameLayout panel, Preview state, boolean wanted) throws Exception {
        Object animator = controller.get(panel);
        int current = activeAxis.getInt(animator);
        if ((!wanted && !state.dodging) || (wanted && state.dodging && current == 0x33)) return;
        int target = wanted ? 0x33 : SwipePanelPolicy.centreDodgeRestoreAxis(axis.getInt(panel));
        if (target == SwipePanelPolicy.RESTORE_BY_LAUNCHER) {
            // Launcher compares the old animator axis before calling its native selection callback.
            // Until it takes over, retain the centre latch in case the finger retreats or cancels.
            if (current != 0x33) state.dodging = false;
            return;
        }
        state.dodging = wanted;
        if (!wanted && current != 0x33) return; // Native selection already restored both lists.
        if (wanted) driving.set(state.geometry);
        try {
            cancelAnimators.invoke(animator);
            previousAxis.setInt(animator, current);
            activeAxis.setInt(animator, target);
            switchAxis.invoke(animator, target);
        } finally {
            driving.remove();
        }
    }

    private void render(FrameLayout view, Preview state) throws Exception {
        if (state.task < 0) return;
        Object geometry = params.get(controller.get(view));
        if (geometry != state.geometry) state.closeRow();
        if (geometry == null || (view.getDisplay() != null && CornerSwipeHook.mode(view.getContext(), rotation.getInt(view)))
                || view.getVisibility() != View.VISIBLE || status.getInt(view) == 0
                || view.getResources().getConfiguration().smallestScreenWidthDp >= 600
                || ((ArrayList<?>) entrances.get(view)).size() > 2
                || !updateRects.getDeclaringClass().isInstance(geometry)
                || !CandidateProbe.showEntryForTask(state.task)) {
            dodge(view, state, false);
            state.closeRow();
            state.display(false, false);
            return;
        }
        if (!SwipePanelPolicy.visible(state.progress, startShow.getFloat(geometry))
                || !SwipePanelPolicy.stillCurrent(axis.getInt(view))) {
            dodge(view, state, false);
            state.closeRow();
            state.display(false, false);
            return;
        }
        if (state.row == null) {
            state.row = openRow(view, geometry);
            if (state.row == null) { dodge(view, state, false); state.display(false, false); return; }
            state.geometry = geometry;
            owners.put(geometry, new WeakReference<>(state));
            view.requestLayout();
            view.invalidate();
        }
        boolean selected = SwipePanelPolicy.selectable(state.progress, trigger.getFloat(geometry))
                && SwipePanelPolicy.claimsCentre(axis.getInt(view));
        if (state.wallpaper == null) state.wallpaper = release.prepareWallpaper(view, state.task);
        dodge(view, state, selected);
        state.display(true, selected);
        state.position();
    }
}
