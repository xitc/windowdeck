package io.github.xitc.windowdeck;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import android.graphics.PointF;

/**
 * Symbol table for the launcher build the swipe hook patches.
 *
 * Target: Android 17 (C17) — {@code PJZ110_17.0.0.100}, launcher 17.3.9 / versionCode 170030009
 * ({@code /system_ext/priv-app/OplusLauncher/OplusLauncher.apk}, 70,614,851 B).
 *
 * That build is R8-minified. {@code SplitFloatParams} became {@code panelparams.b},
 * {@code OplusBaseSwipeUpHandler} became {@code gesture.a}, and nearly every method and field
 * lost its name. R8 reshuffles on every OTA, so no symbol is written inline in the hook: they
 * all live here, one entry per ROM touchpoint, next to the evidence that identifies it.
 * {@link #validate} then proves at install time that every entry still resolves; when it does
 * not, the hook installs nothing instead of throwing inside {@code handleLoadPackage}.
 *
 * How the names below were established (from the shipped dex — see 增量分析/hook移植难度评估.md):
 * <ul>
 *   <li>Kotlin {@code @Metadata} keeps the original source file name, so
 *       {@code gesture/a.java} announces {@code compiled from: OplusBaseSwipeUpHandler.kt};</li>
 *   <li>{@code R$dimen} resource names survive minification, so {@code panelparams/b.t} is
 *       readable as {@code rapid_reaction_double_rect_background_horizontal_interval};</li>
 *   <li>log strings keep the original method names, so {@code ob.H(boolean)} prints
 *       {@code "finishRunningRecentsAnimation: "};</li>
 *   <li>{@code toString()} keeps the original field names, so {@code TriggerPanelParams$b}
 *       prints {@code [bgNormalRect=…, bgExpandRect=…, bgZoomOutRect=…]}.</li>
 * </ul>
 */
final class RomSymbols {

    private RomSymbols() {}

    /** Launcher package that hosts the swipe panel. */
    static final String LAUNCHER_PACKAGE = "com.android.launcher";

    /** The one class name that survived minification intact. */
    static final String PANEL_CLASS = "com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView";

    // ------------------------------------------------------------------ panel: MultiTriggerPanelView

    /** {@code int} — panel status, 0 means hidden. (C93: {@code mPanelStatus}) */
    static final String PANEL_STATUS = "K";

    /** {@code int} — horizontal drag offset of the finger from the screen centre. (C93: {@code centerPointHorizontalOffset}) */
    static final String PANEL_CENTER_OFFSET = "x";

    /** {@code int} — current rotation, 0/1/2/3. (C93: {@code currentRotation}) */
    static final String PANEL_ROTATION = "D";

    /** {@code WeakReference<gesture.a>} — the swipe-up handler driving this panel. (C93: {@code swipeUpHandlerRef}) */
    static final String PANEL_HANDLER_REF = "N";

    /** {@code MultiTriggerAnimController} — owns the panel params. (C93: {@code mMultiTriggerPanelController}) */
    static final String PANEL_CONTROLLER = "O";

    /** {@code ArrayList} — the ROM's own entrance chips. More than two means the centre is taken. (C93: {@code entranceViewInfoList}) */
    static final String PANEL_ENTRANCES = "n";

    /** {@code void (float)} — progress callback. Signature is unique once {@code setAlpha(float)} is excluded. (C93: {@code updateProgress}) */
    static final String PANEL_UPDATE_PROGRESS = "i";

    /** {@code void (int)} — centre-selection callback. (C93: {@code updateHorizontalOffset}) */
    static final String PANEL_UPDATE_OFFSET = "g";

    /** {@code onLayout} — a View override, so the name survived. */
    static final String PANEL_ON_LAYOUT = "onLayout";

    // ------------------------------------------------------------------ MultiTriggerAnimController

    /** {@code TriggerPanelParams} — read as a field on C17; C93 used {@code getMTriggerParams()}. */
    static final String CONTROLLER_PARAMS = "c";

    // ------------------------------------------------------------------ params: TriggerPanelParams / panelparams.b

    /**
     * Concrete params class for the split/float panel ({@code SplitFloatParams} before minification).
     * Only a hint: it is used to validate the subclass fields up front. Everything is hooked on
     * {@code params.getClass()} at runtime, so the capsule variant ({@code panelparams.a}) is
     * covered too even though its name is not listed here.
     */
    static final String PARAMS_CLASS_HINT = "com.oplus.quickstep.rapidreaction.panelparams.b";

    /** {@code HashMap<Integer, TriggerPanelParams$b>} — the capsule rectangles, keyed by axis code. (C93: {@code getMBgRectMap}) */
    static final String PARAMS_BG_RECT_MAP = "g";

    /** {@code float} — capsule height. (C93: {@code getMBgHeightInNormal}) */
    static final String PARAMS_BG_HEIGHT = "e";

    /** {@code float} — capsule top margin. (C93: {@code getMBgNormalMarginTop}) */
    static final String PARAMS_BG_MARGIN_TOP = "f";

    /** {@code float} — progress threshold that arms the panel. Getter was inlined, read the field. (C93: {@code getMStartTriggerP}) */
    static final String PARAMS_START_TRIGGER = "g";

    /**
     * {@code float} — progress at which the panel starts becoming visible, the lower end of the
     * reveal. (C93: {@code getMStartShowP})
     *
     * {@code MultiTriggerPanelView.i(float)} scrubs the panel's alpha and the option contents with
     * {@code Utilities.getProgress(progress, mStartShowP, mStartTriggerP)}, and the contents only
     * scale in over the last 10% of that span ({@code mapRange(f, 0.9f, 1.0f)}). The hook needs the
     * same two bounds so its card appears the way the launcher's own two options do.
     */
    static final String PARAMS_START_SHOW = "h";

    /**
     * {@code float} — width of the centre "no select" band. C93 exposed
     * {@code setMMidIntervalInTriggerRow(float)}; C17 deleted the setter and left a plain field,
     * which is the value {@code panelparams.b.a(float,int,int,int)} uses to size the centre slot.
     */
    static final String PARAMS_MID_INTERVAL = "i";

    /**
     * {@code float} — gap the ROM leaves between the two capsules
     * ({@code rapid_reaction_double_rect_background_horizontal_interval}, 8dp by default).
     * C93 kept it writable so the hook could steer the ROM's own layout maths; C17 made it
     * {@code final}, so the rectangles have to be rewritten after the fact instead.
     */
    static final String PARAMS_INTERVAL = "t";

    /** {@code float} — capsule width ({@code rapid_reaction_double_rect_background_normal_width}). (C93: {@code leftOrRightBgWidthInNormal}) */
    static final String PARAMS_SIDE_WIDTH = "w";

    /** {@code void (Context,int,int)} — rotation/config refresh; recomputes every rectangle. (C93: {@code updateParams}) */
    static final String PARAMS_UPDATE = "l";

    /** {@code void (boolean,boolean,Resources,int,int)} — recomputes the capsule rectangles. (C93: {@code updateBgRect}) */
    static final String PARAMS_UPDATE_BG_RECT = "k";

    // ------------------------------------------------------------------ TriggerPanelParams$b (bg rect info)

    /** {@code RectF} — the rectangle actually drawn for a capsule. (C93: {@code getBgNormalRect}) */
    static final String RECT_NORMAL = "b";

    /**
     * {@code RectF} — where a non-selected capsule shrinks to. (C93: {@code getBgZoomOutRect})
     *
     * Rewritten together with {@link #RECT_NORMAL}. The animation never reads this rectangle on
     * its own: it reads the ratio {@code zoomOut.width() / normal.width()} and the centre
     * difference against the normal rectangle ({@code getBgTargetProperties},
     * {@code getContentTargetProperties}). Shrinking the normal rectangle without shrinking this
     * one would therefore turn the ROM's 0.5 zoom-out into 80/104.
     */
    static final String RECT_ZOOM_OUT = "c";

    /**
     * {@code RectF} — the window a capsule morphs into once its option is selected: the full-width
     * upper half for 分屏, a right-hand panel for 浮窗. (C93: {@code getBgExpandRect})
     *
     * Deliberately left alone. It is the scale/translation <em>target</em> of the selected capsule
     * in {@code getBgTargetProperties}, so pinning it to the capsule geometry would stop the
     * capsule growing into the window. The hook only reads it; it never writes it.
     */
    static final String RECT_EXPAND = "a";

    // ------------------------------------------------------------------ handler: gesture.a

    /** {@code OplusBaseTriggerPanelView} — the panel this handler drives. (C93: {@code triggerPanel}) */
    static final String HANDLER_TRIGGER_PANEL = "U1";

    /** {@code GestureState} — declared on the superclass {@code com.android.quickstep.va}. (C93: {@code mGestureState}) */
    static final String HANDLER_GESTURE_STATE = "n";

    /** {@code com.android.quickstep.ob} — task animation manager, declared on {@code com.android.quickstep.a1}. (C93: {@code mTaskAnimationManager}) */
    static final String HANDLER_TASK_ANIM = "g0";

    /** {@code void (boolean)} — {@code ob.H} logs {@code "finishRunningRecentsAnimation: "}. (C93: {@code finishRunningRecentsAnimation}) */
    static final String TASK_ANIM_FINISH_RECENTS = "H";
    static final String TASK_ANIM_CONTROLLER = "I";
    /** s9.m(false, callback, false, null, true): callback after the finish request completes. */
    static final String RECENTS_FINISH_CALLBACK = "m";

    /** {@code int} — running task id, {@code -1} when there is none. (C93: {@code GestureState.getRunningTaskId}) */
    static final String GESTURE_RUNNING_TASK_ID = "n";

    /**
     * C17 turned {@code onGestureEnded} into a Kotlin default-argument bridge: R8 saw every call
     * site pass the defaults, deleted the instance method and inlined the body into
     * {@code static void q3(gesture.a receiver, float endVelocity, PointF velocity, PointF downPos,
     * PointF upPos, boolean, boolean, int mask)}. The receiver is now {@code args[0]}, every other
     * argument shifted by one, and the two booleans have to be recovered from the mask.
     */
    static final int BRIDGE_ARG_COUNT = 8;

    /** Index of {@code upPos} inside the bridge argument list (C93 instance method used index 3). */
    static final int BRIDGE_ARG_UP_POS = 4;

    /** Index of the first {@code boolean} inside the bridge argument list (C93 used index 4). */
    static final int BRIDGE_ARG_BOOLEAN = 5;

    // ------------------------------------------------------------------ geometry

    /**
     * Gap opened between the two capsules so the hook's card fits in the middle.
     *
     * The ROM's own gap is {@code rapid_reaction_double_rect_background_horizontal_interval} —
     * 8dp on both C93 and C17, verified against the shipped resource table — while the card is
     * 112dp wide, so the middle column cannot exist until the gap is widened. 128dp leaves 8dp of
     * breathing room on each side of the card.
     */
    static final int MID_GAP_DP = 128;

    /**
     * Width the two capsules are pinned to while the middle gap is open.
     *
     * Not the ROM's value. {@code rapid_reaction_double_rect_background_normal_width} is 160dp
     * (verified), and the ROM anchors the capsules to the screen centre rather than to the
     * margins, so 160 + 128 + 160 cannot fit on a phone. Narrowing them is what creates the room:
     * on a 421dp-wide screen (OnePlus 13) this puts the capsule at 42.5dp–146.5dp and the right
     * one at 274.5dp–378.5dp, leaving a 128dp middle column and a 42.5dp outer margin. On a 360dp
     * screen the outer margin falls to 12dp; see {@link #MIN_OUTER_MARGIN_DP} for what happens
     * below that.
     */
    static final int CAPSULE_WIDTH_DP = 104;

    /**
     * Floor for the outer margin.
     *
     * The ROM centres the capsules, so a 128dp gap plus two 104dp capsules needs 336dp of screen
     * before the outer edges start crossing the display. Below that the capsule is narrowed
     * further instead of being allowed off-screen, so an unusually narrow device degrades rather
     * than breaks.
     */
    static final int MIN_OUTER_MARGIN_DP = 8;

    // ------------------------------------------------------------------ validation

    /**
     * Finds the handler class without naming it.
     *
     * {@code MultiTriggerPanelView.setSwipeUpHandler} survived minification, and its single
     * parameter is by definition "the handler class of this build". This one probe replaces the
     * {@code findClass("…gesture.OplusBaseSwipeUpHandler")} call that used to abort the whole
     * install with {@code ClassNotFoundException} on any new ROM.
     */
    static Class<?> probeHandlerClass(Class<?> panel) {
        for (Method method : panel.getDeclaredMethods()) {
            if (!"setSwipeUpHandler".equals(method.getName())) continue;
            if (method.getParameterCount() != 1) continue;
            return method.getParameterTypes()[0];
        }
        return null;
    }

    /**
     * Locates the {@code onGestureEnded} bridge by shape rather than by name.
     *
     * @return the static bridge method, or {@code null} when this is not a C17-style handler.
     */
    static Method findGestureEndedBridge(Class<?> handler) {
        for (Method method : handler.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            if (method.getReturnType() != void.class) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != BRIDGE_ARG_COUNT) continue;
            if (!types[0].isAssignableFrom(handler)) continue;
            if (types[1] != float.class) continue;
            if (types[BRIDGE_ARG_UP_POS] != PointF.class) continue;
            if (types[BRIDGE_ARG_BOOLEAN] != boolean.class) continue;
            if (types[BRIDGE_ARG_COUNT - 1] != int.class) continue;
            return method;
        }
        return null;
    }

    /** Finds the params class the controller actually uses, via the declared field type. */
    static Class<?> paramsClass(Class<?> panelClass) {
        try {
            Class<?> controller = field(panelClass, PANEL_CONTROLLER).getType();
            return field(controller, CONTROLLER_PARAMS).getType();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Walks the hierarchy for a field, the way XposedHelpers does. */
    static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // keep walking
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    /** Walks the hierarchy for a method with an exact parameter list. */
    static Method method(Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                // keep walking
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    /**
     * Proves that every symbol the hook needs still resolves against the classes the launcher
     * actually loaded.
     *
     * Only the anchors that cannot be re-derived at runtime are fatal here: the panel state, the
     * handler, the gesture bridge and the params members declared on the shared abstract base.
     * {@code PARAMS_INTERVAL} and {@code PARAMS_SIDE_WIDTH} live on the concrete subclass, which is
     * only known once a params instance exists, so they are reported by
     * {@link #concreteFieldsPresent} instead of blocking the install.
     *
     * @return {@code null} when the build is supported, otherwise a short description of the
     *         first missing anchor — the hook then logs it and installs nothing.
     */
    static String validate(Class<?> panel, Class<?> handler) {
        try {
            field(panel, PANEL_STATUS);
            field(panel, PANEL_CENTER_OFFSET);
            field(panel, PANEL_ROTATION);
            field(panel, PANEL_HANDLER_REF);
            field(panel, PANEL_CONTROLLER);
            field(panel, PANEL_ENTRANCES);
            method(panel, PANEL_UPDATE_PROGRESS, float.class);
            method(panel, PANEL_UPDATE_OFFSET, int.class);
            method(panel, PANEL_ON_LAYOUT, boolean.class, int.class, int.class, int.class, int.class);
            if (findGestureEndedBridge(handler) == null) return "找不到 onGestureEnded static bridge";

            field(handler, HANDLER_TRIGGER_PANEL);
            field(handler, HANDLER_GESTURE_STATE);
            field(handler, HANDLER_TASK_ANIM);

            Class<?> params = paramsClass(panel);
            if (params == null) return "无法从 controller 解析 params 类";
            method(params, PARAMS_BG_RECT_MAP);
            method(params, PARAMS_BG_HEIGHT);
            method(params, PARAMS_BG_MARGIN_TOP);
            field(params, PARAMS_START_TRIGGER);
            field(params, PARAMS_START_SHOW);
            field(params, PARAMS_MID_INTERVAL);
            method(params, PARAMS_UPDATE, android.content.Context.class, int.class, int.class);
            method(params, PARAMS_UPDATE_BG_RECT, boolean.class, boolean.class,
                    android.content.res.Resources.class, int.class, int.class);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** True when the concrete params class still carries the two subclass-only layout fields. */
    static boolean concreteFieldsPresent(Class<?> concrete) {
        try {
            field(concrete, PARAMS_INTERVAL);
            field(concrete, PARAMS_SIDE_WIDTH);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
