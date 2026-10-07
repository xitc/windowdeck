package io.github.xitc.windowdeck;

import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adds a center drop zone to the ROM's existing split/float swipe panel.
 *
 * <p>Target build: Android 17 (C17), launcher 17.3.9 / versionCode 170030009. That launcher is
 * R8-minified, so every ROM touchpoint is read from {@link RomSymbols} instead of being spelled
 * out here, and {@link RomSymbols#validate} refuses to install on a build whose shape does not
 * match. Nothing in this class may name a minified member directly.
 *
 * <p>Two things changed shape between the Android 16 launcher and this one, and both are handled
 * explicitly below:
 * <ul>
 *   <li>{@code onGestureEnded} is no longer an instance method. R8 inlined it into a Kotlin
 *       default-argument bridge, so the receiver became {@code args[0]} and every argument moved
 *       one slot; see {@link #release}.</li>
 *   <li>the middle-column gap cannot be widened through the ROM's own maths any more, because the
 *       interval field became {@code final} and its setter was deleted. It is opened by rewriting
 *       the capsule rectangles after the ROM computed them; see {@link #anchorCapsules}.</li>
 * </ul>
 */
final class LauncherSwipeHook {
 private static final String TAG="WindowDeck";
 private static final WeakHashMap<View,State> STATES=new WeakHashMap<>();
 private static final WeakHashMap<Object,Boolean> ELIGIBLE_PARAMS=new WeakHashMap<>();
 private static final Set<Class<?>> HOOKED_PARAMS=new HashSet<>();
 /**
  * True while the hook wants the 128dp middle gap. It is armed when a gesture starts with a valid
  * candidate and cleared when the gesture ends, so the ROM lays out its three columns for exactly
  * as long as the card can be shown.
  */
 private static volatile boolean MID_ACTIVE;
/**
 * True while the card is actually in the panel. It is what lets the hook answer {@code CAPSULE} for
 * the launcher's empty centre column: that claim is only honest while there is a card standing in
 * the column, otherwise the launcher would retire both capsules for nothing.
 */
private static volatile boolean CARD_SHOWN;
/**
 * True only for the duration of {@link #retireCapsules}'s own call into the launcher's animator.
 *
 * <p>The claim cannot be a standing answer. The launcher reads the centre column while it is
 * deciding whether it may run a selection animation at all
 * ({@code MultiTriggerPanelView.k()} asks for {@code f(51)} and bails when the option it names is
 * not available), and answering {@code CAPSULE} there would trade one blocked branch for another --
 * {@code g(CAPSULE)} is false on a two-capsule layout, so the same early return fires. The claim is
 * therefore raised around the one call that needs it and nowhere else, which leaves the launcher's
 * own gate reading {@code NONE} exactly as before.
 */
private static volatile boolean CLAIM_CENTRE;
/** Cached {@link RomSymbols#claimProblem} result, so the skip is reported once per install. */
private static volatile String CLAIM_PROBLEM;
private static volatile boolean CLAIM_PROBED,TRACED_CLAIM_ANSWERED;
/** One-shot per gesture, so "the gate is off" is visible without logging every frame. */
private static volatile boolean TRACED_MID;
private static final class State { final SourceMotion sourceMotion=new SourceMotion(); int sourceGeneration,sourceTask=-1; Bitmap sourceFrame; LinearLayout zone; View icon; TextView label; WorkbenchMark mark; PanelBg background; SwipeCandidate.Match candidate; float progress,trigger,startShow; boolean tracedProgress,tracedZone,selected,fadedForWindowAnim,claimedCentre,tracedClaimStop; float expansion; ValueAnimator expansionAnimator; Runnable hover; float alpha=1; boolean alphaOn=true,tracedRetire,tracedRestore; ValueAnimator alphaAnimator; }
private static final GestureHandoffOwners HANDOFF_OWNERS=new GestureHandoffOwners();
 /** @return whether the swipe hooks were installed. A ROM mismatch returns false and installs nothing. */
 static boolean install(ClassLoader loader){
  Class<?> panel;
  try{panel=XposedHelpers.findClass(RomSymbols.PANEL_CLASS,loader);}
  catch(Throwable e){XposedBridge.log("WindowDeck: 桌面面板类不存在，跳过 "+RomSymbols.PANEL_CLASS);return false;}
  // Anchor probe. setSwipeUpHandler survived minification and its only parameter is, by
  // definition, the handler class of this build. This replaces the hard-coded findClass that used
  // to abort the whole install with ClassNotFoundException on any new ROM.
  Class<?> handler=RomSymbols.probeHandlerClass(panel);
  if(handler==null){XposedBridge.log("WindowDeck: setSwipeUpHandler 不存在，跳过安装");return false;}
  String problem=RomSymbols.validate(panel,handler);
  if(problem!=null){XposedBridge.log("WindowDeck: 桌面版本未适配("+problem+")，跳过安装；handler="+handler.getName());return false;}
  Method bridge=RomSymbols.findGestureEndedBridge(handler);
  Method initAnimation=RomSymbols.findInitAnimation(panel);
  if(bridge==null||initAnimation==null){XposedBridge.log("WindowDeck: 手势入口不完整，跳过安装");return false;}
  Class<?> concrete=XposedHelpers.findClassIfExists(RomSymbols.PARAMS_CLASS_HINT,loader);
  if(concrete==null)XposedBridge.log("WindowDeck: 提示类 "+RomSymbols.PARAMS_CLASS_HINT+" 不存在，中列间距将只做矩形锚定");
  XposedBridge.log("WindowDeck: ROM 适配 C17 handler="+handler.getName()+" bridge="+bridge.getName()+" init="+initAnimation.getName());
  // Resolved here rather than on the first gesture so the verdict is in the log from boot, next to
  // the rest of the symbol report, instead of after a swipe that already looked wrong.
  try{claimProblem(RomSymbols.field(panel,RomSymbols.PANEL_CONTROLLER).getType());}catch(Throwable ignored){}
  installHooks(panel,bridge,initAnimation);
  return true;
 }
 private static void installHooks(Class<?> panel,Method bridge,Method initAnimation){
  // Install ownership first: a failed contract must not leave a competing handoff hook enabled.
  XposedHelpers.findAndHookMethod(bridge.getParameterTypes()[0],RomSymbols.HANDLER_END_TARGET,
    float.class,boolean.class,PointF.class,boolean.class,boolean.class,boolean.class,boolean.class,new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){
    if(!HANDOFF_OWNERS.owns(p.thisObject)){
     // Read existing gesture geometry only; do not query tasks or capture pixels on release.
     int handles=-1;RectF rect=null;
     try{
      Object[] source=(Object[])XposedHelpers.getObjectField(p.thisObject,RomSymbols.SOURCE_HANDLES);
      handles=source==null?0:source.length;
      if(handles==1){
       Object simulator=XposedHelpers.callMethod(source[0],RomSymbols.SOURCE_SIMULATOR);
       rect=new RectF((RectF)XposedHelpers.callMethod(simulator,RomSymbols.SOURCE_CROP));
       ((android.graphics.Matrix)XposedHelpers.getObjectField(simulator,RomSymbols.SOURCE_MATRIX)).mapRect(rect);
      }
     }catch(Throwable e){Log.w(TAG,"swipe_native_source_unavailable",e);}
     Log.i(TAG,"swipe_native_end_allowed task="+CandidateProbe.runningTask(p.thisObject)+" source_handles="+handles+" source_rect="+rect);
     return;
    }
    // Keep q3/F0's finger-up, scroll and depth cleanup. Only Z's second end-target animation
    // is redundant: the independent cover and Recents-finish callback own this gesture now.
    p.setResult(null);
    Log.i(TAG,"swipe_native_end_suppressed handler="+System.identityHashCode(p.thisObject));
   }
  });
  XposedHelpers.findAndHookMethod(panel,RomSymbols.PANEL_UPDATE_PROGRESS,float.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){try{progress((View)p.thisObject,(Float)p.args[0]);}catch(Throwable e){Log.w(TAG,"swipe_panel_update_failed",e);XposedBridge.log(e);}}
  });
  XposedHelpers.findAndHookMethod(panel,RomSymbols.PANEL_ON_LAYOUT,boolean.class,int.class,int.class,int.class,int.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){try{positionZone((View)p.thisObject);}catch(Throwable e){Log.w(TAG,"zone_layout_failed",e);}}
  });
  XposedHelpers.findAndHookMethod(panel,RomSymbols.PANEL_UPDATE_OFFSET,int.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){try{updateSelection((View)p.thisObject);}catch(Throwable e){Log.w(TAG,"center_selection_failed",e);}}
  });
  XposedBridge.hookMethod(initAnimation,new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){try{onGestureStart((View)p.thisObject);}catch(Throwable e){Log.w(TAG,"swipe_init_gate_failed",e);XposedBridge.log(e);}}
  });
  // The bridge is static: the handler is args[0], not thisObject.
  XposedBridge.hookMethod(bridge,new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){try{release(p);}catch(Throwable e){Log.w(TAG,"swipe_release_failed",e);}}
   @Override protected void afterHookedMethod(MethodHookParam p){try{onGestureEnd((View)XposedHelpers.getObjectField(p.args[0],RomSymbols.HANDLER_TRIGGER_PANEL));}catch(Throwable e){XposedBridge.log(e);}}
  });
  Log.i(TAG,"launcher_swipe_hook_ready");
 }
 // ------------------------------------------------------------------ ROM accessors
 private static Object swipeUpHandler(View panel){
  Object ref=XposedHelpers.getObjectField(panel,RomSymbols.PANEL_HANDLER_REF);
  return ref instanceof WeakReference?((WeakReference<?>)ref).get():null;
 }
 private static Object params(View panel){
  Object controller=XposedHelpers.getObjectField(panel,RomSymbols.PANEL_CONTROLLER);
  return controller==null?null:XposedHelpers.getObjectField(controller,RomSymbols.CONTROLLER_PARAMS);
 }
 private static int panelStatus(View panel){return XposedHelpers.getIntField(panel,RomSymbols.PANEL_STATUS);}
 private static int centerOffset(View panel){return XposedHelpers.getIntField(panel,RomSymbols.PANEL_CENTER_OFFSET);}
 private static int rotation(View panel){try{return XposedHelpers.getIntField(panel,RomSymbols.PANEL_ROTATION);}catch(Throwable ignored){return 0;}}
 private static List<?> entrances(View panel){return (List<?>)XposedHelpers.getObjectField(panel,RomSymbols.PANEL_ENTRANCES);}
 private static float bgHeight(Object params){return ((Number)XposedHelpers.callMethod(params,RomSymbols.PARAMS_BG_HEIGHT)).floatValue();}
 private static float bgMarginTop(Object params){return ((Number)XposedHelpers.callMethod(params,RomSymbols.PARAMS_BG_MARGIN_TOP)).floatValue();}
 private static Map<?,?> bgRects(Object params){return (Map<?,?>)XposedHelpers.callMethod(params,RomSymbols.PARAMS_BG_RECT_MAP);}
 private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 private static int dp(Resources r,int n){return Math.round(n*r.getDisplayMetrics().density);}
 /**
  * Diagnostics go to both sinks on purpose: {@code android.util.Log} is what {@code adb logcat}
  * shows, {@code XposedBridge.log} is what the module manager shows. A line that reaches only one of
  * them reads as "there is no log at all" to whoever is watching the other.
  */
 private static void log(String message){
  Log.i(TAG,message);
  try{XposedBridge.log("WindowDeck "+message);}catch(Throwable ignored){}
 }
 private static boolean canShowThird(Context context,int rotation){
  if(context.getResources().getConfiguration().smallestScreenWidthDp>=600)return false;
  return rotation>=0&&rotation<=3;
 }
 // ------------------------------------------------------------------ gesture lifecycle
private static void onGestureStart(View panel) throws Exception {
 // This runs inside the panel's own initAnimation, before its first frame. The task list was
 // prepared on a background thread; this hook only reads that snapshot and a local task id.
 long began=SystemClock.uptimeMillis();
 LiveWorkbenchState.register(panel.getContext());
 State state=state(panel);
 state.tracedProgress=false;state.tracedZone=false;state.fadedForWindowAnim=false;TRACED_MID=false;resetExpansion(panel,state);
 state.candidate=null;state.sourceMotion.reset();CARD_SHOWN=false;
 // Two things fade the card to alpha 0 -- the exit animation when the window opens, and the retire
 // edge when the finger settles on one of the launcher's own options -- and neither puts it back, so
 // the gesture boundary is where it is restored. Doing it anywhere later would fight the fade.
 resetCardAlpha(panel,state);
 if(state.zone!=null)state.zone.setVisibility(View.GONE);
 Object params=params(panel);
 if(params!=null)hookParamsOnce(params);
 Object swipe=swipeUpHandler(panel);
 SwipeCandidate.Match candidate=swipe==null?null:CandidateProbe.match(swipe);
 boolean room=centreFree(panel);
 if(params!=null)ELIGIBLE_PARAMS.put(params,candidate!=null&&room);
 // The ROM recomputes every capsule rectangle further down this very call, so arming the layout
 // here is what makes the middle gap appear together with the panel instead of one frame later.
 // A panel that already has its own centre entrance must not have those rectangles rewritten.
 MID_ACTIVE=candidate!=null&&room&&canShowThird(panel.getContext(),rotation(panel));
 log("swipe_init version="+Version.NAME+" handler="+(swipe!=null)+" candidate="+(candidate!=null)+" room="+room
     +" cache="+(CandidateProbe.ready()?"warm":"cold")+" total="+(SystemClock.uptimeMillis()-began)+"ms");
 int shield=GestureShield.begin();
 final int sourceGeneration=++state.sourceGeneration;
 HandoffCover.recycle(state.sourceFrame);state.sourceFrame=null;state.sourceTask=-1;
 if(MID_ACTIVE&&candidate!=null&&candidate.container>=0&&SwipePanelPolicy.landscapeStack(rotation(panel))){
  final int sourceTask=candidate.task;
  final long captureStarted=SystemClock.uptimeMillis();
  new Thread(()->{
   Bitmap frame=HandoffCover.captureTask(sourceTask);
   panel.post(()->{
    if(state.sourceGeneration!=sourceGeneration||CandidateProbe.runningTask(swipe)!=sourceTask){
     HandoffCover.recycle(frame);return;
    }
    if(!HandoffCover.taskFrame(frame)){
     Log.w(TAG,"swipe_source_snapshot_unavailable task="+sourceTask+" ms="+(SystemClock.uptimeMillis()-captureStarted));
     HandoffCover.recycle(frame);return;
    }
    state.sourceFrame=frame;state.sourceTask=sourceTask;
    Log.i(TAG,"swipe_source_snapshot task="+sourceTask+" ms="+(SystemClock.uptimeMillis()-captureStarted));
   });
  },"windowdeck-source").start();
 }
 if(MID_ACTIVE&&SwipePanelPolicy.landscapeStack(rotation(panel))){
  Context shieldContext=panel.getContext();
  int displayId=0;
  try{android.view.Display display=shieldContext.getDisplay();if(display!=null)displayId=display.getDisplayId();}catch(Throwable ignored){}
  final int shieldDisplay=displayId;
  new Thread(()->{
   Bitmap shot=HandoffCover.captureNow(shieldDisplay);
   panel.post(()->GestureShield.show(shieldContext,shot,shield));
  },"windowdeck-shield").start();
 }
}
 private static void onGestureEnd(View panel){
  GestureShield.dismiss(false);
  HandoffCover.forgetWarm();
  State state=STATES.get(panel);if(state!=null){state.sourceMotion.reset();state.sourceGeneration++;HandoffCover.recycle(state.sourceFrame);state.sourceFrame=null;state.sourceTask=-1;}
  Object params=params(panel);
  if(params!=null)ELIGIBLE_PARAMS.remove(params);
  MID_ACTIVE=false;CARD_SHOWN=false;
  CandidateProbe.kick();
 }
 /** The ROM's own centre entrance already occupies the column the card would take. */
 private static boolean centreFree(View panel){
  List<?> list=entrances(panel);
  return list==null||list.size()<=2;
 }
 /**
  * Hooks the params object's own class the first time it is seen.
  *
  * Doing it lazily instead of at install time keeps the install path free of minified class names
  * and automatically covers the capsule panel variant, which the split/float class does not
  * handle.
  */
 private static void hookParamsOnce(Object params){
  final Class<?> type=params.getClass();
  synchronized(HOOKED_PARAMS){if(!HOOKED_PARAMS.add(type))return;}
  boolean concrete=RomSymbols.concreteFieldsPresent(type);
  log("params 类 = "+type.getName()+" 布局字段="+(concrete?"完整":"缺失(只做矩形锚定)"));
  try{
   XposedHelpers.findAndHookMethod(type,RomSymbols.PARAMS_UPDATE,Context.class,int.class,int.class,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){try{
     boolean eligible=Boolean.TRUE.equals(ELIGIBLE_PARAMS.get(p.thisObject));
     MID_ACTIVE=eligible&&canShowThird((Context)p.args[0],(Integer)p.args[1]);
     log("swipe_params rotation="+p.args[1]+" eligible="+eligible+" show="+MID_ACTIVE);
    }catch(Throwable e){MID_ACTIVE=false;Log.w(TAG,"swipe_geometry_gate_failed",e);XposedBridge.log(e);}}
    @Override protected void afterHookedMethod(MethodHookParam p){try{
     if(!MID_ACTIVE)return;
     // C17 deleted setMMidIntervalInTriggerRow(); the field behind it is still there and is what
     // calculateSelectArea() reads to size the centre slot, so widen the slot the same way.
     Resources res=((Context)p.args[0]).getResources();
     XposedHelpers.setFloatField(p.thisObject,RomSymbols.PARAMS_MID_INTERVAL,dp(res,RomSymbols.MID_GAP_DP));
    }catch(Throwable e){Log.w(TAG,"swipe_mid_interval_failed",e);}}
   });
   XposedHelpers.findAndHookMethod(type,RomSymbols.PARAMS_UPDATE_BG_RECT,boolean.class,boolean.class,Resources.class,int.class,int.class,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){
     if(!MID_ACTIVE)return;
     // Best effort only: C17 made the interval field final, so this usually does nothing. It is
     // kept because when the ROM does accept it, its own maths stays consistent for free.
     try{XposedHelpers.setFloatField(p.thisObject,RomSymbols.PARAMS_INTERVAL,dp((Resources)p.args[2],RomSymbols.MID_GAP_DP));}catch(Throwable ignored){}
    }
    @Override protected void afterHookedMethod(MethodHookParam p){try{
     if(!MID_ACTIVE)return;
     anchorCapsules(p.thisObject,(Resources)p.args[2],(Integer)p.args[3]);
    }catch(Throwable e){Log.w(TAG,"swipe_side_rect_failed",e);}}
   });
   hookCentreColumn(type);
  }catch(Throwable e){Log.w(TAG,"swipe_params_hook_failed",e);XposedBridge.log(e);}
 }
/**
 * Hands the launcher's empty centre column to the card, for the duration of one call into its
 * animator.
 *
 * <p>The two-capsule layout registers columns 2 and 4 and answers {@code NONE} for column 3, so
 * {@code MultiTriggerAnimController.e(int)} -- which retires every option whose axis code is not the
 * current one -- finds nothing to retire while the finger is in the middle. The two capsules then
 * stay lit exactly where the card expands over them, and the capsule icon, the capsule title and
 * the card's own pair are all drawn in the same box.
 *
 * <p>The answer is raised only while {@link #retireCapsules} is inside that one call, not while the
 * launcher is deciding whether it may select at all. See {@link #CLAIM_CENTRE} for why a standing
 * answer would be self-defeating.
 */
private static void hookCentreColumn(Class<?> type){
 final Class<?> optionType=returnType(type,RomSymbols.PARAMS_SELECTION_OF,int.class);
 if(optionType==null||!optionType.isEnum()){log("centre claim skipped: "+RomSymbols.PARAMS_SELECTION_OF+"(int) not found");return;}
 final Object none=enumConstant(optionType,"NONE"),capsule=enumConstant(optionType,"CAPSULE");
 if(none==null||capsule==null){log("centre claim skipped: NONE/CAPSULE missing on "+optionType.getName());return;}
 XposedHelpers.findAndHookMethod(type,RomSymbols.PARAMS_SELECTION_OF,int.class,new XC_MethodHook(){
  @Override protected void afterHookedMethod(MethodHookParam p){
   try{
    if(!CLAIM_CENTRE||!CARD_SHOWN||p.getResult()!=none)return;
    if(!SwipePanelPolicy.claimsCentre((Integer)p.args[0]))return;
    p.setResult(capsule);
    // Proves the override reached the launcher's own read of the column, once per process. The
    // claim can be reported as driven and still not retire anything if this never fires, and the
    // two failures look identical from the outside.
    if(!TRACED_CLAIM_ANSWERED){TRACED_CLAIM_ANSWERED=true;log("centre claim answered axis="+p.args[0]+" -> CAPSULE");}
   }catch(Throwable e){Log.w(TAG,"swipe_centre_claim_failed",e);}
  }
 });
}
/**
 * Pushes the launcher's two capsules out of the card's column, on the frame the card gets there.
 *
 * <p>Nothing in the launcher will do this on its own. Both doors into
 * {@code MultiTriggerAnimController.e(int)} are shut while the centre column reads {@code NONE}:
 * {@code MultiTriggerPanelView.k()} leaves at its first-select branch because
 * {@code f(51).d()} is false for an option this layout does not offer, and the other caller in
 * {@code i(float)} only runs for a zone below pickable. So the hook calls the animator itself,
 * with the same two calls the launcher makes on every axis change.
 *
 * <p>Edge triggered, like the launcher's own: see {@link SwipePanelPolicy#claimEdge}. The launcher
 * takes over again the moment the finger leaves the column -- {@code mCurSelectAxisCode} is written
 * back to the card's column here precisely so that {@code k()}'s comparison sees a change and runs
 * its own restore, with its own callback and its own haptic.
 *
 * <p>Failing to find the animator is not fatal. The card then draws over the capsules as it did
 * before this existed, and the reason is logged once.
 */
private static void retireCapsules(View panel,State s,int axis){
 boolean wanted=CARD_SHOWN&&SwipePanelPolicy.claimsCentre(axis);
 int step=SwipePanelPolicy.claimStep(wanted,s.claimedCentre);
 if(step==SwipePanelPolicy.CLAIM_NONE)return;
 // The latch follows the level on both edges. Only the drive is edge triggered; recording a stop
 // is what lets the next entry into the column be read as an entry again.
 s.claimedCentre=wanted;
 if(step==SwipePanelPolicy.CLAIM_STOP){if(!s.tracedClaimStop){s.tracedClaimStop=true;log("centre claim dropped axis="+axis+" progress="+s.progress);}return;}
 Object controller;
 try{controller=XposedHelpers.getObjectField(panel,RomSymbols.PANEL_CONTROLLER);}
 catch(Throwable e){Log.w(TAG,"swipe_controller_read_failed",e);return;}
 if(controller==null)return;
 if(claimProblem(controller.getClass())!=null)return;
 CLAIM_CENTRE=true;
 try{
  XposedHelpers.callMethod(controller,RomSymbols.CONTROLLER_CANCEL);
  XposedHelpers.callMethod(controller,RomSymbols.CONTROLLER_RETIRE,axis);
  XposedHelpers.setIntField(controller,RomSymbols.CONTROLLER_AXIS_CODE,axis);
  log("centre claim axis="+axis+" progress="+s.progress+" helpers="+helperCount(controller));
 }catch(Throwable e){Log.w(TAG,"swipe_centre_retire_failed",e);}
 finally{CLAIM_CENTRE=false;}
}
/**
 * How many content helpers the controller has registered, for the claim log.
 *
 * <p>Reads the helper map directly rather than through a symbol: it is diagnostics only, and the
 * count is what says whether the claim had anything to retire. A layout that registers only the
 * card's own column would leave this at one and the claim would have nothing to do.
 */
private static String helperCount(Object controller){
 try{Object map=XposedHelpers.getObjectField(controller,"h");return map instanceof Map?Integer.toString(((Map<?,?>)map).size()):"?";}
 catch(Throwable ignored){return "?";}
}
/**
 * The controller's suitability for the claim, resolved once and reported once.
 *
 * @return {@code null} when the claim can run, otherwise {@link RomSymbols#claimProblem}'s reason
 */
private static String claimProblem(Class<?> controller){
 if(!CLAIM_PROBED){
  CLAIM_PROBED=true;
  CLAIM_PROBLEM=RomSymbols.claimProblem(controller);
  if(CLAIM_PROBLEM!=null)log("centre claim disabled: "+CLAIM_PROBLEM);
 }
 return CLAIM_PROBLEM;
}
/** The declared return type of a method, wherever it sits in the hierarchy. */
private static Class<?> returnType(Class<?> owner,String name,Class<?>... args){
 for(Class<?> c=owner;c!=null;c=c.getSuperclass()){
  try{return c.getDeclaredMethod(name,args).getReturnType();}catch(NoSuchMethodException ignored){}
 }
 return null;
}
/** An enum constant by name, or {@code null} if this build renamed or dropped it. */
private static Object enumConstant(Class<?> type,String name){
 Object[] constants=type.getEnumConstants();
 if(constants==null)return null;
 for(Object c:constants)if(((Enum<?>)c).name().equals(name))return c;
 return null;
}
 // ------------------------------------------------------------------ layout
/**
 * Pushes the two ROM capsules apart so the hook's card fits between them.
 *
 * C17 derives the gap from a {@code final} field, so it cannot be steered before the fact.
 * Rewriting the output rectangles afterwards is the ROM's own mechanism -- its split/float and
 * three-rect layouts do exactly the same thing -- and {@code MultiTriggerAnimController} copies
 * the rectangles into the background paints as soon as this call returns, so the change is always
 * read.
 *
 * Only {@code bgNormalRect} and {@code bgZoomOutRect} are touched. {@code bgExpandRect} is the
 * window the capsule morphs into once selected, so it is the one rectangle that must keep the
 * ROM's value; see {@link RomSymbols#RECT_EXPAND}.
 *
 * In landscape the two capsules are stacked, so the gap opens vertically instead.
 *
 * Absolute edges only. {@code onLayout} runs repeatedly and {@code offset()} would drift.
 */
private static void anchorCapsules(Object params,Resources res,int rotation) throws Exception {
 Map<?,?> rects=bgRects(params);
 if(rects==null||rects.isEmpty())return;
 if(SwipePanelPolicy.landscapeStack(rotation)){openLandscapeGap(params,dp(res,RomSymbols.CAPSULE_WIDTH_DP));return;}
 float center=res.getDisplayMetrics().widthPixels/2f;
 float screen=res.getDisplayMetrics().widthPixels;
 float gap=dp(res,RomSymbols.MID_GAP_DP);
 // The ROM pins the capsules to the screen centre, so this layout needs 2*(104+64)=336dp of
 // screen before the outer edges cross the display. On anything narrower, take the width down
 // instead of letting the capsule run off the edge.
 float width=SwipePanelPolicy.capsuleWidth(screen,gap,dp(res,RomSymbols.CAPSULE_WIDTH_DP),
                                           dp(res,RomSymbols.MIN_OUTER_MARGIN_DP));
 if(width<=0f)return;
 for(Object info:rects.values()){
  RectF normal=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_NORMAL);
  if(normal==null||normal.isEmpty())continue;
  boolean left=normal.centerX()<center;
  float inner=SwipePanelPolicy.capsuleInner(screen,gap,left);
  anchor(normal,left,inner,width);
  // The zoom-out rectangle is only ever read as a ratio and a centre difference against the
  // normal one, so it has to shrink with it or the ROM's 0.5 zoom-out becomes 80/104. Its outer
  // edge is shared with the normal rectangle because that is the direction the ROM collapses in.
  RectF zoom=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_ZOOM_OUT);
  if(zoom!=null&&!zoom.isEmpty())anchor(zoom,left,left?inner-width/2f:inner+width/2f,width/2f);
 }
 // Keeps the ROM's own width bookkeeping in step; the rectangles above are already correct
 // whether or not this field survived the OTA.
 try{XposedHelpers.setFloatField(params,RomSymbols.PARAMS_SIDE_WIDTH,width);}catch(Throwable ignored){}
}
/** Pins one capsule-shaped rect: the edge facing the screen centre lands on {@code inner}. */
private static void anchor(RectF rect,boolean left,float inner,float width){
 if(left){rect.right=inner;rect.left=inner-width;}
 else{rect.left=inner;rect.right=inner+width;}
}
 private static void openLandscapeGap(Object params,float length) throws Exception {
  Map<?,?> rects=bgRects(params);
  RectF first=null,second=null;
  for(Object info:rects.values()){RectF normal=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_NORMAL);if(normal.isEmpty())continue;if(first==null)first=normal;else second=normal;}
  if(first==null||second==null)return;
  RectF upper=first.centerY()<=second.centerY()?first:second,lower=upper==first?second:first;
  float[] top=new float[]{upper.top,upper.bottom},bottom=new float[]{lower.top,lower.bottom};
  SwipePanelPolicy.separateVertical(top,bottom,length);
  upper.top=top[0];upper.bottom=top[1];lower.top=bottom[0];lower.bottom=bottom[1];
 }
 private static State state(View panel){State s=STATES.get(panel);if(s==null){s=new State();STATES.put(panel,s);}return s;}
 private static void progress(View panel,float value){
  State s=state(panel);s.progress=value;
  try{
   Object gate=params(panel);
   if(gate!=null)s.startShow=XposedHelpers.getFloatField(gate,RomSymbols.PARAMS_START_SHOW);
  }catch(Throwable ignored){}
  if(s.startShow>0f&&value>=s.startShow+0.12f)GestureShield.dismiss(true);
  if(value>0&&!s.tracedProgress){s.tracedProgress=true;XposedBridge.log("WindowDeck swipe_progress status="+panelStatus(panel)+" visible="+(panel.getVisibility()==View.VISIBLE)+" value="+value);}
  if(!(panel instanceof FrameLayout))return;
  if(s.zone==null){
   Context c=panel.getContext();
   LinearLayout zone=new LinearLayout(c);zone.setOrientation(LinearLayout.VERTICAL);zone.setGravity(Gravity.CENTER_HORIZONTAL);zone.setClickable(false);zone.setFocusable(false);zone.setElevation(0);zone.setForceDarkAllowed(false);
   zone.setPadding(dp(c,8),launcherPx(c,"rapid_reaction_icon_margin_top_v15",12),dp(c,8),0);
   PanelBg bg=new PanelBg(launcherFloat(c,"rapid_reaction_double_rect_background_smooth_corner_radius",9));zone.setBackground(bg);
   ImageView icon=new ImageView(c);WorkbenchMark mark=new WorkbenchMark();icon.setImageDrawable(mark);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setBackgroundColor(0);icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);icon.setForceDarkAllowed(false);
   int iconPx=launcherPx(c,"icon_content_size",24);zone.addView(icon,new LinearLayout.LayoutParams(iconPx,iconPx));
   TextView label=new TextView(c);label.setText("添加到工作台");label.setGravity(Gravity.CENTER);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);label.setForceDarkAllowed(false);
   int style=c.getResources().getIdentifier("couiTextButtonS","style",c.getPackageName());if(style!=0)label.setTextAppearance(style);
   label.setTextSize(12);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
   LinearLayout.LayoutParams textLp=new LinearLayout.LayoutParams(-1,-2);textLp.topMargin=launcherPx(c,"rapid_reaction_text_content_margin_top",4);zone.addView(label,textLp);
   zone.setContentDescription("添加到工作台");
   FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(c,112),dp(c,68),Gravity.TOP|Gravity.CENTER_HORIZONTAL);lp.topMargin=dp(c,40);
   ((FrameLayout)panel).addView(zone,lp);s.zone=zone;s.label=label;s.mark=mark;s.background=bg;s.icon=icon;applyChrome(s);
  }
  // The ROM retires its whole panel the instant the window starts animating open. The card is not
  // in the ROM's fade set, so it has to retire itself; see fadeForWindowAnimation. Checked ahead of
  // every gate below, because the card has to go even when the gates that put it there have flipped.
  if(fadeForWindowAnimation(panel,s))return;
  // Launcher keeps this fullscreen panel at a fixed size during the gesture, so a newly added
  // child may not receive another layout pass before the user releases their finger.
  positionZone(panel);
  if(value<=0||panel.getVisibility()!=View.VISIBLE||panelStatus(panel)==0){hideCard(s);return;}
  // The center is occupied by the ROM capsule on devices that expose it.
  List<?> entrances=entrances(panel);
  if(entrances!=null&&entrances.size()>2){
   MID_ACTIVE=false;
   Object blocked=params(panel);
   if(blocked!=null)ELIGIBLE_PARAMS.put(blocked,Boolean.FALSE);
   hideCard(s);
   return;
  }
  Object handler=swipeUpHandler(panel);
  if(handler==null){hideCard(s);return;}
  Object params=params(panel);
  if(params==null){hideCard(s);return;}
  hookParamsOnce(params);
  // MID_ACTIVE means "a candidate exists and the ROM has been laid out with the wide middle gap".
  // The old probe against the ROM's interval field cannot be used any more: on C17 that field is
  // 8dp by design and would keep the card hidden forever.
  if(!MID_ACTIVE){if(!TRACED_MID){TRACED_MID=true;log("zone skipped: mid gap inactive (no candidate, centre taken, or the tablet/rotation gate)");}hideCard(s);return;}
  s.trigger=XposedHelpers.getFloatField(params,RomSymbols.PARAMS_START_TRIGGER);
  s.startShow=XposedHelpers.getFloatField(params,RomSymbols.PARAMS_START_SHOW);
  // Two thresholds, not one. The launcher fades an option in from mStartShowP (0.95) and only lets
  // it be picked from mStartTriggerP (1.15); between the two it is visible but not selectable.
  // Gating the card on the trigger instead put it on screen 0.20 of progress after the two options
  // it sits between, and left it absent entirely for a swipe that came to rest in between -- which
  // is the "the third option turns up a few seconds late" report.
  if(!SwipePanelPolicy.visible(value,s.startShow)){resetExpansion(panel,s);hideCard(s);return;}
  // The launcher retires the option it did not pick the instant one of them becomes pickable, and
  // moves the one it did pick to the centre of the screen -- which is the card's own column. Fading
  // the card on that same edge is what keeps the two from being drawn on top of each other; without
  // it the capsule's icon lands on the card's icon and both titles are legible at once, in the same
  // box. See SwipePanelPolicy.stillCurrent for the rule and where it comes from.
  int axis=axisCode(panel);
  if(!SwipePanelPolicy.stillCurrent(axis)){cardCurrent(panel,s,false,axis);retireCapsules(panel,s,axis);return;}
  cardCurrent(panel,s,true,axis);
  // Nothing is animated here on purpose. The launcher resets each option view to scale 1.0 and
  // alpha 1.0 for the whole gesture (initAnimation -> resetViewScale/resetViewAlpha), and its show
  // animator writes only View.ALPHA on the panel root. The icon and the title therefore ride that
  // one fade and never scale, which is why the box and its text arrive on the same frame. A card
  // that scaled its own contents in over the last tenth of the reveal -- as this one used to -- put
  // the text on screen noticeably after the box it sits in.
  s.candidate=CandidateProbe.match(handler);
  if(s.candidate!=null)sampleSource(handler,s,s.candidate.task);
  CARD_SHOWN=s.candidate!=null;
  s.zone.setVisibility(CARD_SHOWN?View.VISIBLE:View.GONE);
  if(s.candidate!=null)s.zone.bringToFront();
  // The other half of the dodge. With the card in the column, the two capsules have to leave it;
  // nothing in the launcher does that on its own here, so the hook drives its animator. See
  // retireCapsules. Ordered after CARD_SHOWN so the claim and the card always agree.
  retireCapsules(panel,s,axis);
  updateSelection(panel);
  if(s.candidate!=null&&!s.tracedZone){s.tracedZone=true;View zone=s.zone;float trigger=s.trigger,startShow=s.startShow;zone.post(()->{int[] xy=new int[2];zone.getLocationOnScreen(xy);log("zone shown="+zone.isShown()+" alpha="+zone.getAlpha()+" panelAlpha="+panel.getAlpha()+" bounds="+xy[0]+","+xy[1]+","+zone.getWidth()+","+zone.getHeight()+" panel="+panel.getWidth()+","+panel.getHeight()+" progress="+s.progress+" startShow="+startShow+" trigger="+trigger);});}
 }
 /**
 * Takes the card off the screen the moment the window starts animating open.
 *
 * <p>{@code MultiTriggerPanelView.i(float)} calls {@code createExitAnimation()} on the rising edge
 * of {@code isWindowAnimationStarted}, ahead of every other branch, and that is what keeps the
 * panel from covering the window it just launched. The ROM fades its own content helpers and
 * background paints to alpha 0 and touches nothing else -- the card is a direct child of the panel
 * and is in neither set, so without this it stays on screen over the expanding window. It is
 * reachable on any device: pick either of the ROM's own two options and {@link #release} bails out
 * before the card is ever hidden, because only this module's own path hides it.
 *
 * <p>Faded rather than hidden, matching {@code createExitAnimation}'s spring. Once the window
 * animation starts, {@code i(float)} stops driving the panel root's alpha (it guards the
 * {@code setCurrentFraction} call on {@code !isWindowAnimationStarted}), so this fade is the only
 * thing left that can take the card off the screen.
 *
 * <p>The latch is this class's own rather than the ROM's {@code hasCreateExitAnim}: the ROM sets
 * that flag inside the very {@code i(float)} call this hook runs after, so by the time it is read
 * it is always already true. {@link #onGestureStart} clears both the latch and the faded alpha.
 *
 * @return true once the card is being retired, so the caller leaves it alone.
 */
private static boolean fadeForWindowAnimation(View panel,State s){
 boolean started;
 try{started=XposedHelpers.getBooleanField(panel,RomSymbols.PANEL_WINDOW_ANIM_STARTED);}
 catch(Throwable e){Log.w(TAG,"swipe_window_anim_read_failed",e);return false;}
 if(!started||s.zone==null)return false;
 if(s.fadedForWindowAnim)return true;
 s.fadedForWindowAnim=true;
 log("zone exit=window_anim progress="+s.progress);
 View zone=s.zone;
 ValueAnimator animator=ValueAnimator.ofFloat(zone.getAlpha(),0f);
 animator.setDuration(SwipePanelPolicy.ROM_SPRING_MILLIS);
 animator.setInterpolator(f->SwipePanelPolicy.spring(f));
 animator.addUpdateListener(a->zone.setAlpha((Float)a.getAnimatedValue()));
 animator.start();
 return true;
}
/**
 * The panel's current axis code, or 0 when it cannot be read.
 *
 * <p>0 is the launcher's own "nothing pickable yet" value, and {@link
 * SwipePanelPolicy#stillCurrent} reads it as "the card stays". A field that cannot be read is not a
 * reason to take the card off the screen.
 */
private static int axisCode(View panel){
 try{return XposedHelpers.getIntField(panel,RomSymbols.PANEL_AXIS_CODE);}
 catch(Throwable e){Log.w(TAG,"swipe_axis_read_failed",e);return 0;}
}
/**
 * Takes the card out of the panel and drops the claim on the launcher's centre column.
 *
 * <p>The two travel together: the claim only makes sense while there is a card in the column, and
 * leaving it up after the card is gone would have the launcher retire both capsules for nothing.
 * {@link #progress} reaches the claim through the two lines that set {@code CARD_SHOWN}, and every
 * path that hides the card goes through here.
 */
private static void hideCard(State s){
 if(s!=null&&s.zone!=null)s.zone.setVisibility(View.GONE);
 CARD_SHOWN=false;
 // The claim is over with the card. A card that comes back into the same column re-drives the
 // animator, which re-targets the values it already has; leaving the latch set would instead
 // leave the capsules off screen with nothing standing in their column.
 if(s!=null)s.claimedCentre=false;
}
/** Puts the card back at full alpha for the next gesture. The gesture boundary owns this. */
private static void resetCardAlpha(View panel,State s){
 if(s.alphaAnimator!=null){s.alphaAnimator.cancel();s.alphaAnimator=null;}
 s.alpha=1f;s.alphaOn=true;s.tracedRetire=false;s.tracedRestore=false;s.claimedCentre=false;s.tracedClaimStop=false;
 if(s.zone!=null)s.zone.setAlpha(1f);
}
/**
 * Fades the card in or out as the launcher lights one option and retires the others.
 *
 * <p>Reversible on purpose, and driven by its own spring rather than hidden outright: the finger can
 * cross back into the middle column, and the launcher brings its own capsules back over the same
 * 440ms. Cancelling the running animator and re-targeting from wherever the alpha currently is
 * keeps a reversal from snapping.
 *
 * @param axis the axis code this decision was made on, logged so the two thresholds and the column
 *             split can be read back from a real gesture
 */
private static void cardCurrent(View panel,State s,boolean on,int axis){
 if(s.zone==null||s.alphaOn==on)return;
 s.alphaOn=on;
 if(on){if(!s.tracedRestore){s.tracedRestore=true;log("zone restore=centre axis="+axis+" progress="+s.progress);}}
 else if(!s.tracedRetire){s.tracedRetire=true;log("zone retire=other_option axis="+axis+" progress="+s.progress);}
 if(s.alphaAnimator!=null)s.alphaAnimator.cancel();
 ValueAnimator animator=ValueAnimator.ofFloat(s.alpha,on?1f:0f);s.alphaAnimator=animator;
 animator.setDuration(SwipePanelPolicy.ROM_SPRING_MILLIS);
 animator.setInterpolator(f->SwipePanelPolicy.spring(f));
 animator.addUpdateListener(a->{s.alpha=(Float)a.getAnimatedValue();if(s.zone!=null)s.zone.setAlpha(s.alpha);});
 animator.start();
}
private static void positionZone(View panel){
  State s=STATES.get(panel);if(s==null||s.zone==null||panel.getWidth()<=0)return;
  Object params=params(panel);
  if(params==null)return;
  applyChrome(s);
  s.zone.setTranslationX(0);s.zone.setTranslationY(0);s.zone.setScaleX(1);s.zone.setScaleY(1);
  int rotation=rotation(panel);
  int left,top,width,height;
  if(SwipePanelPolicy.landscapeStack(rotation)){
   float[] span=landscapeSpan(params);if(span==null){s.zone.setRotation(0);return;}
   float[] box=SwipePanelPolicy.landscapeChip(span[0],span[1],span[2],span[3],dp(panel.getContext(),112),s.expansion);
   left=Math.round(box[0]);top=Math.round(box[1]);width=Math.round(box[2]);height=Math.round(box[3]);
  }else{
   width=dp(panel.getContext(),112);height=Math.round(bgHeight(params));
   top=Math.round(bgMarginTop(params));
   float[] box=SwipePanelPolicy.box(panel.getWidth(),panel.getHeight(),panel.getResources().getDisplayMetrics().density,top,height,s.expansion);
   left=Math.round(box[0]);width=Math.round(box[2]);height=Math.round(box[3]);
  }
  s.zone.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
  s.zone.layout(left,top,left+width,top+height);
  s.zone.setPivotX(width/2f);s.zone.setPivotY(height/2f);s.zone.setRotation(SwipePanelPolicy.chipRotation(rotation));
 }
private static float[] landscapeSpan(Object params){
  Map<?,?> rects=bgRects(params);
  RectF first=null,second=null;
  for(Object info:rects.values()){RectF normal=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_NORMAL);if(normal.isEmpty())continue;if(first==null)first=normal;else second=normal;}
  if(first==null||second==null)return null;
  RectF upper=first.centerY()<=second.centerY()?first:second,lower=upper==first?second:first;
  return new float[]{upper.left,upper.right,upper.bottom,lower.top};
 }
 private static void resetExpansion(View panel,State s){
  if(s.hover!=null){panel.removeCallbacks(s.hover);s.hover=null;}
  if(s.expansionAnimator!=null){s.expansionAnimator.cancel();s.expansionAnimator=null;}
  s.selected=false;s.expansion=0;
 }
 private static void updateSelection(View panel){
  State s=STATES.get(panel);if(s==null||s.zone==null)return;
  Object params=params(panel);if(params==null)return;
  boolean selected=s.candidate!=null&&s.zone.getVisibility()==View.VISIBLE&&SwipePanelPolicy.selected(s.progress,s.trigger,centerOffset(panel),dp(panel.getContext(),64));
  if(!selected&&s.hover!=null){panel.removeCallbacks(s.hover);s.hover=null;}
  if(selected==s.selected)return;
  if(selected){
   if(s.hover!=null)return;
   s.hover=()->{s.hover=null;if(s.candidate!=null&&s.zone.isShown()&&SwipePanelPolicy.selected(s.progress,s.trigger,centerOffset(panel),dp(panel.getContext(),64)))animateExpansion(panel,s,true);};panel.postDelayed(s.hover,100);
  }else animateExpansion(panel,s,false);
 }
private static void animateExpansion(View panel,State s,boolean selected){
 s.selected=selected;if(s.expansionAnimator!=null)s.expansionAnimator.cancel();
 ValueAnimator animator=ValueAnimator.ofFloat(s.expansion,selected?1:0);s.expansionAnimator=animator;
 // The launcher's own expand spring, not a stock easing curve. A cubic ease-out front-loads 60%
 // of the travel into the first 100ms, which is what made the card look like it snapped open;
 // this curve is still at 38% there and settles with the launcher's small 2% overshoot.
 animator.setDuration(SwipePanelPolicy.ROM_SPRING_MILLIS);
 animator.setInterpolator(f->SwipePanelPolicy.spring(f));
 animator.addUpdateListener(a->{s.expansion=(Float)a.getAnimatedValue();positionZone(panel);});animator.start();
 XposedBridge.log("WindowDeck center_expand selected="+selected);
}

 private static void sampleSource(Object handler,State state,int task){
  try{
   Object[] handles=(Object[])XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES);
   if(handles==null||handles.length!=1)return;
   Object simulator=XposedHelpers.callMethod(handles[0],RomSymbols.SOURCE_SIMULATOR);
   android.graphics.Matrix matrix=new android.graphics.Matrix((android.graphics.Matrix)XposedHelpers.getObjectField(simulator,RomSymbols.SOURCE_MATRIX));
   RectF rect=new RectF((RectF)XposedHelpers.callMethod(simulator,RomSymbols.SOURCE_CROP));matrix.mapRect(rect);
   state.sourceMotion.sample(task,SystemClock.uptimeMillis(),new float[]{rect.left,rect.top,rect.right,rect.bottom});
  }catch(Throwable ignored){state.sourceMotion.reset();}
 }
 /**
  * Runs before the ROM's own gesture-end logic.
  *
  * C17's bridge is {@code static void (handler, float, PointF, PointF, PointF, boolean, boolean, int)},
  * so the receiver is {@code args[0]} and the {@code PointF}s sit one slot further along than they
  * did when this was an instance method. The trailing {@code int} is the Kotlin default-argument
  * mask; the two booleans are read raw, which is the conservative reading.
  */
 private static void release(XC_MethodHook.MethodHookParam p){
  Object handler=p.args[0];
  if(handler==null){log("release bail=no_handler");return;}
  View panel=(View)XposedHelpers.getObjectField(handler,RomSymbols.HANDLER_TRIGGER_PANEL);
  State s=STATES.get(panel);
  // Every bail below used to be silent, which made "no log" indistinguishable from "the hook never
  // ran". One line on entry plus one per reason is what tells those two apart.
  log("release zone="+(s==null||s.zone==null?"none":s.zone.getVisibility())+" progress="+(s==null?"-":s.progress)+" trigger="+(s==null?"-":s.trigger)+" selected="+(s!=null&&s.selected));
  if(s==null||s.zone==null){log("release bail=no_state");return;}
  if(s.zone.getVisibility()!=View.VISIBLE){log("release bail=zone_hidden");return;}
  if(!SwipePanelPolicy.selectable(s.progress,s.trigger)){log("release bail=below_trigger");return;}
  if(Boolean.TRUE.equals(p.args[RomSymbols.BRIDGE_ARG_BOOLEAN])||Boolean.TRUE.equals(p.args[RomSymbols.BRIDGE_ARG_BOOLEAN+1])){log("release bail=cancelled");return;}
  PointF up=(PointF)p.args[RomSymbols.BRIDGE_ARG_UP_POS];if(up==null){log("release bail=no_up");return;}
  int rotation=rotation(panel);
  int[] pos=new int[2];s.zone.getLocationOnScreen(pos);
  boolean onChip=s.selected&&(SwipePanelPolicy.landscapeStack(rotation)||(Math.abs(up.x-panel.getWidth()/2f)<=dp(panel.getContext(),64)&&up.y>=pos[1]&&up.y<=pos[1]+s.zone.getHeight()));
  if(!onChip){log("release bail=off_chip up="+up.x+","+up.y+" selected="+s.selected+" zone="+pos[0]+","+pos[1]+" "+s.zone.getWidth()+"x"+s.zone.getHeight()+" panelW="+panel.getWidth());return;}
  SwipeCandidate.Match candidate=CandidateProbe.match(handler);
  if(candidate==null||s.candidate==null||candidate.task!=s.candidate.task||candidate.container!=s.candidate.container){
   log("release bail=candidate fresh="+(candidate==null?"null":candidate.task+"/"+candidate.container)+" cached="+(s.candidate==null?"null":s.candidate.task+"/"+s.candidate.container));return;
  }
  Object manager=XposedHelpers.getObjectField(handler,RomSymbols.HANDLER_TASK_ANIM);
  if(manager==null){log("release bail=no_manager");return;}
  Context context=panel.getContext().getApplicationContext();
  final long released=SystemClock.uptimeMillis();
  Handler main=new Handler(Looper.getMainLooper());
  Object activeController;
  try{activeController=XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER);}
  catch(Throwable e){Log.w(TAG,"swipe_controller_unavailable",e);return;}
  final Object gestureController=activeController;
  RectF sourceRect=null;float sourceRadius=0;
  try{
   Object[] handles=(Object[])XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES);
   // A fullscreen single task only. Split/multi-target geometry is deliberately not guessed.
   if(handles!=null&&handles.length==1){
    Object simulator=XposedHelpers.callMethod(handles[0],RomSymbols.SOURCE_SIMULATOR);
    android.graphics.Matrix matrix=new android.graphics.Matrix((android.graphics.Matrix)XposedHelpers.getObjectField(simulator,RomSymbols.SOURCE_MATRIX));
    RectF rect=new RectF((RectF)XposedHelpers.callMethod(simulator,RomSymbols.SOURCE_CROP));matrix.mapRect(rect);
    sourceRect=rect;sourceRadius=matrix.mapRadius(((Number)XposedHelpers.callMethod(simulator,RomSymbols.SOURCE_RADIUS)).floatValue());
   }
  }catch(Throwable e){Log.w(TAG,"swipe_source_geometry_unavailable",e);}
  final RectF releaseRect=sourceRect;final float releaseRadius=sourceRadius;
  final float[] releaseVelocity=s.sourceMotion.velocity(candidate.task,released);
  Log.i(TAG,"swipe_source_velocity task="+candidate.task+" measured="+(releaseVelocity!=null)+" edges_px_ms="+java.util.Arrays.toString(releaseVelocity));
  final boolean landscape=SwipePanelPolicy.landscapeStack(rotation);
  final boolean deferPortrait=landscape&&candidate.container<0;
  final SourceRelease sourceRelease=deferPortrait?new SourceRelease():null;
  final Runnable[] releaseSource={null};
  final android.os.ResultReceiver[] sourceReleaseReply={null};
  android.app.Activity sourceActivity=deferPortrait?sourceActivity(panel):null;
  SourceCapture capture=deferPortrait?sourceCapture(handler,candidate.task):null;
  if(deferPortrait&&(sourceActivity==null||capture==null||releaseRect==null)){
   if(capture!=null){capture.leash.release();capture.closeVisibility();}
   Log.w(TAG,"swipe_add_degraded reason=release_source_unavailable native_release=true");return;
  }
  final HandoffCover[] sourceCover={null};
  // Run in parallel with captureDisplay. The reply cannot run on the main
  // thread until this release hook has attached the source-token window.
  if(capture!=null)captureSourcePixels(capture,manager,gestureController,main,sourceCover);
  final Bitmap warm=GestureShield.detach();
  final Bitmap taskFrame=s.sourceTask==candidate.task?s.sourceFrame:null;
  if(taskFrame!=null)s.sourceFrame=null;
  final HandoffCover cover;
  try{
   if(deferPortrait){HandoffCover.recycle(taskFrame);cover=HandoffCover.createSource(sourceActivity,warm);}
   else cover=HandoffCover.create(panel.getContext(),landscape,warm,taskFrame,false);
  }catch(Throwable e){if(capture!=null)capture.closeVisibility();HandoffCover.recycle(warm);Log.w(TAG,"swipe_add_degraded reason=source_window_unavailable native_release=true",e);return;}
  sourceCover[0]=cover;
  if(!HANDOFF_OWNERS.claim(handler,!cover.isClosed())){
   if(capture!=null)capture.closeVisibility();
   Log.w(TAG,"swipe_add_degraded reason=cover_unavailable native_release=true");return;
  }
  if(deferPortrait)keepSourceVisible(gestureController);
  Log.i(TAG,"swipe_handoff_claimed task="+candidate.task+" handler="+System.identityHashCode(handler));
  final View underlayRoot=panel.getRootView();
  final java.util.concurrent.atomic.AtomicBoolean underlayStop=new java.util.concurrent.atomic.AtomicBoolean();
  if(landscape&&!cover.isClosed()){
   // The source-token window sits in Launcher. Hide only this release's task
   // above it once the matching cover frame is drawn; native finish keeps ownership.
   cover.concealUnderlay(()->concealLauncher(handler,underlayRoot,underlayStop,!deferPortrait,capture));
   cover.hideUnderlay();
  }
  if(landscape&&releaseRect!=null)
   planLandscapeRelease(panel,cover,releaseRect,releaseRadius,releaseVelocity,released);
  java.util.concurrent.atomic.AtomicBoolean recentsEnded=new java.util.concurrent.atomic.AtomicBoolean();
  Runnable endRecents=()->{
   if(!recentsEnded.compareAndSet(false,true))return;
   try{
    if(gestureController!=null&&XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER)==gestureController)
     XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_FINISH_RECENTS,false);
   }catch(Throwable e){Log.w(TAG,"swipe_handoff_finish_failed",e);}
  };
  java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
  java.util.concurrent.atomic.AtomicBoolean hostAnswered=new java.util.concurrent.atomic.AtomicBoolean();
  java.util.concurrent.atomic.AtomicBoolean motionTransferred=new java.util.concurrent.atomic.AtomicBoolean();
  final android.os.ResultReceiver[] completion={null};
  final Bundle[] preparedGeometry={null};
  final int[] outcome={HandoffProtocol.FAILED};
  Runnable finish=()->{
   if(!finished.compareAndSet(false,true))return;
   if(sourceRelease!=null)sourceRelease.cancel();
   android.os.ResultReceiver ack=completion[0];completion[0]=null;
   underlayStop.set(true);restoreLauncher(underlayRoot);
   if(capture!=null)capture.closeVisibility();
   endRecents.run();cover.close("handoff_finished");
   if(ack!=null)ack.send(outcome[0],Bundle.EMPTY);
   Log.i(TAG,"swipe_handoff_result result="+outcome[0]+" task="+candidate.task);
   Log.i(TAG,"swipe_handoff_finish ms="+(SystemClock.uptimeMillis()-released));
  };
  cover.onClosed(why->{
   if(!"handoff_finished".equals(why)){
    underlayStop.set(true);
    restoreLauncher(underlayRoot);
    Log.w(TAG,"swipe_cover_failed reason="+why);finish.run();
   }
  });
  android.os.ResultReceiver localReady=new android.os.ResultReceiver(main){
   @Override protected void onReceiveResult(int code,Bundle data){
    Log.i(TAG,(code==HandoffProtocol.BACKGROUND?"swipe_host_background":code==HandoffProtocol.PREPARED?"swipe_host_prepared":code==HandoffProtocol.POSE_QUERY?"swipe_pose_query":code==HandoffProtocol.POSE_COMMITTED?"swipe_pose_committed":"swipe_host_ready")+" result="+code+" ms="+(SystemClock.uptimeMillis()-released));
    if(code==HandoffProtocol.BOOTSTRAP_COMMITTED){
     if(finished.get()||sourceRelease==null||data==null||data.getInt(HandoffProtocol.TASK,-1)!=candidate.task)return;
     android.os.ResultReceiver reply=data.getParcelable(HandoffProtocol.POSE_REPLY,android.os.ResultReceiver.class);
     if(reply==null||releaseSource[0]==null){finish.run();return;}
     if(!sourceRelease.commitBootstrap())return;
     // A submitted host frame can still be behind Recents. Keep the source
     // application window; native finish exchanges application visibility.
     Log.i(TAG,"swipe_bootstrap_cover_retained task="+candidate.task+" source_release=false live=false");
     if(!sourceRelease.requestRelease())return;
     sourceReleaseReply[0]=reply;
     Log.i(TAG,"swipe_bootstrap_committed task="+candidate.task+" source_release=false");
     releaseSource[0].run();return;
    }
    if(code==HandoffProtocol.POSE_QUERY||code==HandoffProtocol.POSE_COMMITTED){
     if(finished.get()||data==null||data.getInt(HandoffProtocol.TASK,-1)!=candidate.task)return;
     android.os.ResultReceiver poseReply=data.getParcelable(HandoffProtocol.POSE_REPLY,android.os.ResultReceiver.class);
     if(poseReply==null){Log.w(TAG,"swipe_pose_reply_missing");return;}
     if(code==HandoffProtocol.POSE_QUERY){
      Runnable send=new Runnable(){public void run(){
       if(finished.get())return;
       if(cover.posePending()){main.postDelayed(this,MotionSpec.SURFACE_RETRY_MS);return;}
       android.os.Bundle pose=cover.samplePose();
       if(pose==null){Log.w(TAG,"swipe_pose_unavailable");poseReply.send(HandoffProtocol.FAILED,android.os.Bundle.EMPTY);return;}
       pose.putInt(HandoffProtocol.TASK,candidate.task);
       Log.i(TAG,"swipe_pose_sent corners="+java.util.Arrays.toString(pose.getFloatArray(HandoffProtocol.CORNERS))+" crop="+java.util.Arrays.toString(pose.getFloatArray(HandoffProtocol.CROP))+" radius="+pose.getFloat(HandoffProtocol.RADIUS)+" snapshot=true live=false");
       android.hardware.HardwareBuffer buffer=pose.getParcelable(HandoffProtocol.SNAPSHOT,android.hardware.HardwareBuffer.class);
       try{poseReply.send(HandoffProtocol.POSE_QUERY,pose);}
       catch(Throwable e){Log.w(TAG,"swipe_pose_send_failed",e);poseReply.send(HandoffProtocol.FAILED,android.os.Bundle.EMPTY);}
       finally{if(buffer!=null)buffer.close();}
      }};send.run();return;
     }
     if(sourceRelease!=null&&!sourceRelease.mayRetireCover()){
      Log.w(TAG,"swipe_pose_before_source_release task="+candidate.task);poseReply.send(HandoffProtocol.FAILED,Bundle.EMPTY);finish.run();return;
     }
     if(!cover.detachVisual()){poseReply.send(HandoffProtocol.FAILED,Bundle.EMPTY);finish.run();return;}
     motionTransferred.set(true);
     Log.i(TAG,"swipe_motion_ownership_released task="+candidate.task);
     poseReply.send(HandoffProtocol.OWNERSHIP,android.os.Bundle.EMPTY);return;
    }
    if(finished.get())return;
    if(code!=HandoffProtocol.READY&&code!=HandoffProtocol.PREPARED&&code!=HandoffProtocol.BACKGROUND){finish.run();return;}
    if(data==null||data.getInt(HandoffProtocol.TASK,-1)!=candidate.task||data.getInt(HandoffProtocol.CONTAINER,-1)<0
      ||(candidate.container>=0&&data.getInt(HandoffProtocol.CONTAINER,-1)!=candidate.container)){Log.w(TAG,"swipe_host_geometry_identity_failed");finish.run();return;}
    // The host owns a visible task surface now. Do not restart animation/reveal on the
    // detached overlay; complete only after the host's final live/frame barrier.
    if(motionTransferred.get()){
     if(code!=HandoffProtocol.READY)return;
     completion[0]=data.getParcelable(HandoffProtocol.COMPLETE,android.os.ResultReceiver.class);
     if(completion[0]==null){Log.w(TAG,"swipe_live_completion_missing");finish.run();return;}
     outcome[0]=HandoffProtocol.READY;finish.run();return;
    }
    int[] proposed=data.getIntArray(HandoffProtocol.TARGET);
    RectF proposedRect=proposed!=null&&proposed.length==4?new RectF(proposed[0],proposed[1],proposed[2],proposed[3]):null;
    if(code==HandoffProtocol.BACKGROUND){
     Bundle first=preparedGeometry[0];
     if(first==null)return;
     if(!sameHandoffGeometry(first,data)){Log.w(TAG,"swipe_host_background_geometry_changed");finish.run();return;}
     cover.prepareBackdrop(data.getParcelable(HandoffProtocol.BACKDROP,android.graphics.Bitmap.class));return;
    }
    if(code==HandoffProtocol.PREPARED&&!cover.ownsTravel()&&!cover.canPrepare(releaseRect,proposedRect,data.getInt(HandoffProtocol.ROTATION),data.getFloat(HandoffProtocol.RADIUS),data.containsKey(HandoffProtocol.BACKDROP))){
     Log.i(TAG,"swipe_prepared_wait reason=background_exposed");return;
    }
    if(!hostAnswered.compareAndSet(false,true)){
     Bundle first=preparedGeometry[0];
     if(code!=HandoffProtocol.READY||first==null)return;
     if(!sameHandoffGeometry(first,data)){
      Log.w(TAG,"swipe_host_geometry_changed");finish.run();return;
     }
     cover.allowReveal(data.getParcelable(HandoffProtocol.BACKDROP,android.graphics.Bitmap.class));return;
    }
    preparedGeometry[0]=data;
    completion[0]=data.getParcelable(HandoffProtocol.COMPLETE,android.os.ResultReceiver.class);
    int[] target=data.getIntArray(HandoffProtocol.TARGET);
    RectF rect=target!=null&&target.length==4?new RectF(target[0],target[1],target[2],target[3]):null;
    Log.i(TAG,"swipe_snapshot_motion_requested task="+candidate.task);
    if(completion[0]==null||!data.containsKey(HandoffProtocol.ROTATION)||!cover.animateTo(releaseRect,releaseRadius,rect,data.getFloat(HandoffProtocol.RADIUS),data.getParcelable(HandoffProtocol.BACKDROP,android.graphics.Bitmap.class),data.getInt(HandoffProtocol.ROTATION),releaseVelocity,released,code==HandoffProtocol.READY,ok->{outcome[0]=ok?HandoffProtocol.READY:HandoffProtocol.FAILED;finish.run();})){
     Log.w(TAG,"swipe_snapshot_degraded reason=source_or_target_unavailable");finish.run();
    }
   }
  };
  // Parcel the framework base class, not this module's anonymous subclass: the system host
  // cannot load launcher-private module classes while unmarshalling its launch Intent.
  android.os.ResultReceiver ready=HandoffProtocol.transport(localReady);
  // The first landscape cover belongs to the source application's native handoff.
  // This is a failure watchdog, not a normal-path animation/settle timer.
  long hostWait=landscape?MotionSpec.LANDSCAPE_REVEAL_MS:MotionSpec.COVER_TIMEOUT_MS;
  main.postDelayed(()->{if(!finished.get()){Log.w(TAG,"swipe_host_ready_timeout");finish.run();}},hostWait);
  releaseSource[0]=()->{
   // H(false) posts the finish request; returning from it does not release the recents leash.
   // C17's controller queues this Runnable until finishControllerDone, including when H has
   // already requested the same finish. Only then may FlexibleTaskView take the task surface.
   Runnable embed=()->main.post(()->{
    if(finished.get())return;
    try{
     Object current=XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER);
     if(current!=null&&current!=gestureController){Log.w(TAG,"swipe_source_release_superseded");finish.run();return;}
    }catch(Throwable e){Log.w(TAG,"swipe_source_release_identity_failed",e);finish.run();return;}
    Log.i(TAG,"swipe_source_released task="+candidate.task+" ms="+(SystemClock.uptimeMillis()-released));
    if(deferPortrait)logSourceLeashes(handler);
    if(deferPortrait){
     if(sourceReleaseReply[0]==null||!sourceRelease.completeRelease()){finish.run();return;}
     // FlexibleTaskView may now use the same task surface. Stop source alpha
     // writes before handing it over and release only our retained handle.
     underlayStop.set(true);restoreLauncher(underlayRoot);
     if(capture!=null)capture.closeVisibility();
     Bundle done=new Bundle();done.putInt(HandoffProtocol.TASK,candidate.task);
     sourceReleaseReply[0].send(HandoffProtocol.SOURCE_RELEASED,done);return;
    }
    Runnable launch=()->{if(!finished.get())dispatchHandoff(context,candidate,released,ready,finish,deferPortrait,rotation);};
    launch.run();
   });
   // The destination has already committed its snapshot. Returning to the pausing
   // app now restores the old transient order and backgrounds that destination.
   if(deferPortrait&&!commitDestination(gestureController)){finish.run();return;}
   endRecents.run();
   if(gestureController==null){embed.run();return;}
   try{XposedHelpers.callMethod(gestureController,RomSymbols.RECENTS_FINISH_CALLBACK,false,embed,false,null,true);}
   catch(Throwable e){
    // Starting the embed after an arbitrary delay reintroduces simultaneous ownership.
    // Keep the source task intact and retire the cover if this ROM contract fails.
    Log.w(TAG,"swipe_source_release_failed",e);finish.run();
   }
  };
  cover.dispatchWhenDrawn(()->{
   if(finished.get()||cover.isClosed())return;
   if(deferPortrait){
    // Keep the recents task at its release geometry until the destination has
    // committed its snapshot frame. The host cannot embed before our finish callback.
    dispatchHandoff(context,candidate,released,ready,finish,true,rotation);
   }else releaseSource[0].run();
  });
  Log.i(TAG,"swipe_add_requested task="+candidate.task+" container="+candidate.container);
  resetExpansion(panel,s);s.zone.setVisibility(View.GONE);s.candidate=null;MID_ACTIVE=false;p.setResult(null);
 }
 /** Window.getAttributes().token can still be null before WindowManager's
  * adjustment. Read the attached decor's actual application LayoutParams,
  * never View.getWindowToken(), which is an IWindow binder rather than an app token. */
 private static android.app.Activity sourceActivity(View panel){
  try{
   Context context=panel.getContext();android.app.Activity activity=null;
   for(int i=0;i<16&&context!=null;i++){
    if(context instanceof android.app.Activity){activity=(android.app.Activity)context;break;}
    if(!(context instanceof android.content.ContextWrapper))break;
    Context next=((android.content.ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
   }
   if(activity==null){
    Class<?> launcher=XposedHelpers.findClass(RomSymbols.LAUNCHER_CLASS,panel.getContext().getClassLoader());
    Object tracker=XposedHelpers.getStaticObjectField(launcher,RomSymbols.LAUNCHER_TRACKER);
    Object tracked=tracker==null?null:XposedHelpers.callMethod(tracker,RomSymbols.TRACKER_ACTIVITY);
    if(tracked instanceof android.app.Activity)activity=(android.app.Activity)tracked;
   }
   if(activity==null||activity.isFinishing()||activity.isDestroyed())return null;
   View decor=activity.getWindow().getDecorView();
   if(!decor.isAttachedToWindow()||!(decor.getLayoutParams() instanceof android.view.WindowManager.LayoutParams))return null;
   android.view.WindowManager.LayoutParams attached=(android.view.WindowManager.LayoutParams)decor.getLayoutParams();
   return attached.type>=1&&attached.type<=99&&attached.token!=null?activity:null;
  }catch(Throwable e){Log.w(TAG,"swipe_source_window_unavailable",e);return null;}
 }
 private static final class SourceCapture {
  final SurfaceControl leash,visibility;final int task,layer,width,height;private boolean visibilityClosed,hidden;
  SourceCapture(SurfaceControl leash,SurfaceControl visibility,int task,int layer,int width,int height){this.leash=leash;this.visibility=visibility;this.task=task;this.layer=layer;this.width=width;this.height=height;}
  void conceal(SurfaceControl.Transaction transaction){
   if(!visibilityClosed&&visibility.isValid()){transaction.setAlpha(visibility,0f);hidden=true;}
  }
  void closeVisibility(){
   if(visibilityClosed)return;visibilityClosed=true;
   try{if(hidden&&visibility.isValid())try(SurfaceControl.Transaction restore=new SurfaceControl.Transaction()){restore.setAlpha(visibility,1f).apply();}}
   catch(Throwable e){Log.w(TAG,"swipe_source_visibility_restore_failed",e);}
   finally{visibility.release();}
  }
 }
 /** Only the single fullscreen handle and the uniquely matching task target. */
 private static SourceCapture sourceCapture(Object handler,int task){
  SurfaceControl copied=null,visibility=null;
  try{
   Object[] handles=(Object[])XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES);
   if(handles==null||handles.length!=1)return null;
   Object params=XposedHelpers.callMethod(handles[0],RomSymbols.SOURCE_PARAMS);
   Object targets=params==null?null:XposedHelpers.callMethod(params,RomSymbols.SOURCE_TARGETS);
   Object[] apps=targets==null?null:(Object[])XposedHelpers.getObjectField(targets,RomSymbols.SOURCE_APPS);
   Object match=null;
   if(apps!=null)for(Object app:apps)if(XposedHelpers.getIntField(app,RomSymbols.SOURCE_TASK)==task){if(match!=null)return null;match=app;}
   if(match==null)return null;
   SurfaceControl leash=(SurfaceControl)XposedHelpers.getObjectField(match,RomSymbols.SOURCE_LEASH);
   android.graphics.Rect bounds=(android.graphics.Rect)XposedHelpers.getObjectField(match,RomSymbols.SOURCE_BOUNDS);
   if(leash==null||!leash.isValid()||bounds==null||bounds.width()<2||bounds.height()<2)return null;
   int layer=LiveTaskContent.layerId(leash);if(layer<0)return null;
   copied=(SurfaceControl)XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckReleasePixels");
   if(!copied.isValid()||LiveTaskContent.layerId(copied)!=layer){copied.release();return null;}
   visibility=(SurfaceControl)XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckReleaseVisibility");
   return new SourceCapture(copied,visibility,task,layer,bounds.width(),bounds.height());
  }catch(Throwable e){if(copied!=null)copied.release();if(visibility!=null)visibility.release();Log.w(TAG,"swipe_release_layer_unavailable",e);return null;}
 }
 private static void captureSourcePixels(SourceCapture source,Object manager,Object controller,Handler main,HandoffCover[] cover){
  final long began=SystemClock.uptimeMillis();
  Log.i(TAG,"swipe_release_capture task="+source.task+" layer="+source.layer+" buffer="+source.width+"x"+source.height);
  new Thread(()->{
   Bitmap pixels=null;
   try{pixels=LiveTaskContent.captureFrame(source.leash,source.width,source.height,Math.max(source.width,source.height));}
   catch(Throwable e){Log.w(TAG,"swipe_release_capture_failed task="+source.task,e);}
   finally{source.leash.release();}
   final Bitmap frame=pixels;
   main.post(()->{
    HandoffCover current=cover[0];
    if(current==null||current.isClosed()){HandoffCover.recycle(frame);return;}
    try{
     if(XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER)!=controller){
      HandoffCover.recycle(frame);current.close("release_capture_superseded");return;
     }
    }catch(Throwable e){HandoffCover.recycle(frame);current.close("release_capture_identity_failed");return;}
    Log.i(TAG,"swipe_release_pixels task="+source.task+" layer="+source.layer+" ms="+(SystemClock.uptimeMillis()-began));
    current.acceptSourcePixels(frame);
   });
  },"windowdeck-release-pixels").start();
 }
 private static boolean sameHandoffGeometry(Bundle first,Bundle next){
  return first.getInt(HandoffProtocol.TASK)==next.getInt(HandoffProtocol.TASK)
   &&first.getInt(HandoffProtocol.CONTAINER)==next.getInt(HandoffProtocol.CONTAINER)
   &&first.getInt(HandoffProtocol.ROTATION)==next.getInt(HandoffProtocol.ROTATION)
   &&Float.compare(first.getFloat(HandoffProtocol.RADIUS),next.getFloat(HandoffProtocol.RADIUS))==0
   &&java.util.Arrays.equals(first.getIntArray(HandoffProtocol.TARGET),next.getIntArray(HandoffProtocol.TARGET));
 }
 /** Clear {@code willFinishToHome} so finish(toHome=false) takes the return-to-app branch and shows the pausing task. */
 private static void keepSourceVisible(Object controller){
  if(controller==null){Log.w(TAG,"swipe_keep_source skipped=no_controller");return;}
  try{
   XposedHelpers.callMethod(controller,RomSymbols.RECENTS_WILL_FINISH_TO_HOME,false);
   // JADX's f19633k/f19634l are reconstructed names, not runtime field contracts.
   // The Shell finish log is authoritative for the actual remote state.
   Log.i(TAG,"swipe_keep_source requested=false remote_state=unverified");
  }catch(Throwable e){Log.w(TAG,"swipe_keep_source_failed",e);}
 }
 /** C17 skips return-to-app when willFinishToHome is true. The finish request itself
  *  remains toHome=false, so Shell retains the opening host without reordering Home. */
 private static boolean commitDestination(Object controller){
  if(controller==null){Log.w(TAG,"swipe_commit_destination_failed reason=no_controller");return false;}
  try{
   XposedHelpers.callMethod(controller,RomSymbols.RECENTS_WILL_FINISH_TO_HOME,true);
   Log.i(TAG,"swipe_commit_destination requested_will_finish=true to_home=false remote_state=unverified");
   return true;
  }catch(Throwable e){Log.w(TAG,"swipe_commit_destination_failed",e);return false;}
 }
 /** Names and native layer ids of the recents leashes, without changing alpha. */
 private static void logSourceLeashes(Object handler){
  StringBuilder names=new StringBuilder();
  try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){veilSurfaces(handler,transaction,names,false);}
  catch(Throwable e){Log.w(TAG,"swipe_source_layer_unavailable",e);}
  Log.i(TAG,"swipe_source_layer name="+names);
 }
 /** Hide launcher UI. First landscape entry veils its exact retained source;
  *  the caller stops these writes at the native release callback. */
 private static void concealLauncher(Object handler,View root,java.util.concurrent.atomic.AtomicBoolean stop,boolean veilLeash,SourceCapture source){
  if(root==null)return;
  final int[] ticks={0};
  final boolean[] logged={false};
  Runnable step=new Runnable(){public void run(){
   if(stop.get())return;
   StringBuilder names=new StringBuilder();
   try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){
    Object vri=XposedHelpers.callMethod(root,"getViewRootImpl");
    SurfaceControl window=vri==null?null:(SurfaceControl)XposedHelpers.callMethod(vri,"getSurfaceControl");
    if(window!=null&&window.isValid())transaction.setAlpha(window,0f);
    if(source!=null)source.conceal(transaction);
    veilSurfaces(handler,transaction,names,veilLeash);
    transaction.apply();
   }catch(Throwable e){Log.w(TAG,"handoff_underlay_failed",e);}
   if(!logged[0]){logged[0]=true;Log.i(TAG,"handoff_underlay_alpha name="+names);}
   if(ticks[0]++<90)root.postDelayed(this,16);
   else restoreLauncher(root);
  }};
  root.post(step);
 }
 private static void restoreLauncher(View root){
  if(root==null)return;
  try{
   Object vri=XposedHelpers.callMethod(root,"getViewRootImpl");
   SurfaceControl window=vri==null?null:(SurfaceControl)XposedHelpers.callMethod(vri,"getSurfaceControl");
   if(window==null||!window.isValid())return;
   try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){transaction.setAlpha(window,1f).apply();}
  }catch(Throwable e){Log.w(TAG,"handoff_underlay_restore_failed",e);}
 }
 private static void veilSurfaces(Object handler,SurfaceControl.Transaction transaction,StringBuilder names,boolean apply){
  int[] budget={8},visits={0};
  java.util.IdentityHashMap<Object,Boolean> seen=new java.util.IdentityHashMap<>();
  Object manager=null;
  try{manager=XposedHelpers.getObjectField(handler,RomSymbols.HANDLER_TASK_ANIM);}
  catch(Throwable e){Log.w(TAG,"handoff_underlay_failed",e);}
  veilObject(manager,transaction,names,budget,visits,0,seen,apply);
  if(manager!=null)try{veilObject(XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER),transaction,names,budget,visits,0,seen,apply);}
  catch(Throwable e){Log.w(TAG,"handoff_underlay_failed",e);}
  try{veilObject(XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES),transaction,names,budget,visits,0,seen,apply);}
  catch(Throwable e){Log.w(TAG,"handoff_underlay_failed",e);}
 }
 private static void veilObject(Object node,SurfaceControl.Transaction transaction,StringBuilder names,int[] budget,int[] visits,int depth,java.util.IdentityHashMap<Object,Boolean> seen,boolean apply){
  if(node==null||budget[0]<=0||visits[0]>=48||depth>3||seen.containsKey(node))return;
  seen.put(node,Boolean.TRUE);visits[0]++;
  if(node instanceof SurfaceControl){
   SurfaceControl sc=(SurfaceControl)node;
   String name=String.valueOf(sc);
   if(skipSurface(name)||!sc.isValid())return;
   try{
    if(apply)transaction.setAlpha(sc,0f);
    budget[0]--;if(names.length()>0)names.append(',');names.append(name).append("#layer=").append(LiveTaskContent.layerId(sc));
   }catch(Throwable e){Log.w(TAG,"handoff_underlay_failed",e);}
   return;
  }
  if(node instanceof Object[]){for(Object child:(Object[])node)veilObject(child,transaction,names,budget,visits,depth+1,seen,apply);return;}
  if(skipNode(node))return;
  for(Class<?> type=node.getClass();type!=null&&type!=Object.class;type=type.getSuperclass()){
   if(type.getName().startsWith("java."))break;
   Field[] fields;try{fields=type.getDeclaredFields();}catch(Throwable e){return;}
   Field[] interesting=new Field[fields.length];int n=0,m=0;
   for(Field field:fields){
    if(Modifier.isStatic(field.getModifiers()))continue;
    Class<?> ft=field.getType();
    if(ft.isPrimitive()||ft==String.class)continue;
    if(ft==SurfaceControl.class||ft.isArray())interesting[n++]=field;else interesting[fields.length-(++m)]=field;
   }
   for(int i=0;i<n&&budget[0]>0;i++)readField(node,interesting[i],transaction,names,budget,visits,depth,seen,apply);
   for(int i=0;i<m&&budget[0]>0&&visits[0]<48;i++)readField(node,interesting[fields.length-1-i],transaction,names,budget,visits,depth,seen,apply);
  }
 }
 private static void readField(Object node,Field field,SurfaceControl.Transaction transaction,StringBuilder names,int[] budget,int[] visits,int depth,java.util.IdentityHashMap<Object,Boolean> seen,boolean apply){
  try{field.setAccessible(true);veilObject(field.get(node),transaction,names,budget,visits,depth+1,seen,apply);}catch(Throwable ignored){}
 }
 private static boolean skipNode(Object node){
  if(node instanceof View||node instanceof Context||node instanceof Bitmap)return true;
  String name=node.getClass().getName();
  return name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("android.widget.")
   ||name.startsWith("android.graphics.")||name.startsWith("android.os.")||name.startsWith("android.util.")
   ||name.startsWith("android.content.");
 }
 private static boolean skipSurface(String name){
  return name==null||name.contains("WindowDeck")||name.contains("Display")||name.contains("Wallpaper")
   ||name.contains("StatusBar")||name.contains("NavigationBar")||name.contains("InputMethod")||name.contains("ScreenDecor")
   ||name.contains("pscanvas");
 }
 private static void planLandscapeRelease(View panel,HandoffCover cover,RectF releaseRect,float releaseRadius,float[] velocity,long released){
  try{
   android.view.WindowManager wm=panel.getContext().getSystemService(android.view.WindowManager.class);
   if(wm==null)return;
   android.graphics.Rect bounds=wm.getMaximumWindowMetrics().getBounds();
   int shortEdge=Math.min(bounds.width(),bounds.height()),longEdge=Math.max(bounds.width(),bounds.height());
   int unit=Math.max(1,Ui.dp(panel.getContext(),1));
   int top=portraitTopInset(panel,unit);
   int[] card=LandscapeEntrance.card(shortEdge,longEdge-top,0,top,unit,longEdge,shortEdge);
   if(card==null)return;
   RectF predicted=new RectF(card[0],card[1],card[2],card[3]);
   Log.i(TAG,"swipe_landscape_plan stage="+shortEdge+"x"+(longEdge-top)+" origin=0,"+top+" card="+predicted);
   cover.planRelease(releaseRect,releaseRadius,predicted,Ui.dp(panel.getContext(),Ui.MAIN_RADIUS),0,velocity,released);
  }catch(Throwable e){Log.w(TAG,"swipe_landscape_plan_failed",e);}
 }
 /** Portrait status/cutout only. A landscape nav-bar inset must not become the stage origin. */
 private static int portraitTopInset(View panel,int unit){
  android.view.WindowInsets insets=panel.getRootWindowInsets();
  if(insets!=null){
   android.graphics.Insets status=insets.getInsets(android.view.WindowInsets.Type.statusBars());
   android.graphics.Insets cutout=insets.getInsets(android.view.WindowInsets.Type.displayCutout());
   int top=Math.max(status.top,cutout.top);
   if(top>0)return top;
  }
  return unit*40;
 }
 private static void dispatchHandoff(Context context,SwipeCandidate.Match candidate,long released,android.os.ResultReceiver ready,Runnable finish,boolean deferPortrait,int sourceRotation){
  Log.i(TAG,"swipe_handoff_begin ms="+(SystemClock.uptimeMillis()-released));
  if(candidate.container<0){
   try{
    Intent create=new Intent().setComponent(new ComponentName("com.oplus.pscanvas","com.oplus.pscanvas.canvasmode.canvas.ContainerActivity"));
    create.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
    create.putExtra("windowdeck_workbench_v1",true);
    create.putExtra("windowdeck_create_source_task",candidate.task);
    create.putExtra("windowdeck_create_source_user",0);
    create.putExtra("windowdeck_handoff_ready",ready);
    if(deferPortrait){create.putExtra("windowdeck_defer_portrait",true);create.putExtra("windowdeck_defer_rotation",sourceRotation);create.putExtra("windowdeck_wait_source_release",true);}
    context.startActivity(create,TaskFrontBinder.handoffOptions(context));
    Log.i(TAG,"swipe_create_direct task="+candidate.task+" ms="+(SystemClock.uptimeMillis()-released));
    return;
   }catch(Throwable e){Log.w(TAG,"swipe_create_direct_failed",e);}
  }else try{
   Intent add=new Intent("io.github.xitc.windowdeck.LAUNCHER_ADD_TO_WORKBENCH").setPackage("com.oplus.pscanvas");
   add.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
   add.putExtra("windowdeck_add_task_id",candidate.task);
   add.putExtra("windowdeck_add_user_id",0);
   add.putExtra("windowdeck_container_task_id",candidate.container);
   add.putExtra("windowdeck_handoff_ready",ready);
   android.app.BroadcastOptions options=android.app.BroadcastOptions.makeBasic();
   options.setShareIdentityEnabled(true);
   context.sendOrderedBroadcast(add,null,options.toBundle(),new android.content.BroadcastReceiver(){
    @Override public void onReceive(Context ignored,Intent intent){
     // Only lack of a receiver uses root. An explicit rejection (3) is never retried:
     // the host answered, so raise the container it owns instead of forcing a new one.
     int result=getResultCode();
     Log.i(TAG,"swipe_add_direct result="+result+" task="+candidate.task+" ms="+(SystemClock.uptimeMillis()-released));
     if(result==1){
      // Prepare the new primary before resuming the container. Otherwise onResume
      // restores the old primary at full size while the add broadcast is in flight.
      try{TaskFrontBinder.moveToFront(context,candidate.container);}
      catch(Throwable e){Log.w(TAG,"swipe_prepared_front_failed",e);finish.run();}
     }
     // 3 = the host already holds this app and refused the add. The original raises the
     // window it has instead of opening a second one, so raise the container and stop.
     else if(result==3){
      finish.run();
      try{TaskFrontBinder.moveToFront(context,candidate.container);}
      catch(Throwable e){Log.w(TAG,"swipe_existing_front_failed",e);}
     }
     else if(result==0){finish.run();dispatchRootHandoff(context,candidate);}
     else if(result!=1)finish.run();
    }
   },new Handler(Looper.getMainLooper()),0,null,null);
   return;
  }catch(Throwable e){Log.w(TAG,"swipe_front_or_add_direct_failed",e);}
  finish.run();dispatchRootHandoff(context,candidate);
 }
 private static void dispatchRootHandoff(Context context,SwipeCandidate.Match candidate){
  Bundle message=new Bundle();message.putInt("taskId",candidate.task);message.putInt("userId",0);message.putInt("containerTaskId",candidate.container);
  try{if(context.getContentResolver().call(Uri.parse("content://io.github.xitc.windowdeck.state"),"add_task",null,message)!=null)return;}
  catch(Throwable e){Log.w(TAG,"swipe_provider_fallback",e);}
  try{
   Intent request=new Intent().setComponent(new ComponentName("io.github.xitc.windowdeck","io.github.xitc.windowdeck.IngressActivity"));
   request.putExtras(message);request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
   context.startActivity(request,ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
  }catch(Throwable e){Log.e(TAG,"swipe_add_dispatch_failed",e);}
 }
 private static int launcherPx(Context c,String name,int fallbackDp){
  int id=c.getResources().getIdentifier(name,"dimen",c.getPackageName());
  return id==0?dp(c,fallbackDp):c.getResources().getDimensionPixelSize(id);
 }
 private static float launcherFloat(Context c,String name,float fallbackDp){
  int id=c.getResources().getIdentifier(name,"dimen",c.getPackageName());
  return id==0?fallbackDp*c.getResources().getDisplayMetrics().density:c.getResources().getDimension(id);
 }
 private static void applyChrome(State s){
  if(s.background!=null)s.background.setExpansion(s.expansion);
  int color=SwipePanelPolicy.glyph(s.expansion);
  if(s.label!=null)s.label.setTextColor(color);
  if(s.mark!=null)s.mark.setGlyph(color);
 }
 /** White fill, alpha 0.2–0.6, 9dp smooth corner. Same path the launcher uses for 分屏 and 浮窗. */
 private static final class PanelBg extends Drawable {
  private final float radius;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  private final RectF rect=new RectF();
  private final Object oplusPath;
  private final java.lang.reflect.Method smooth;
  private float expansion;
  private boolean smoothFailed;
  PanelBg(float radius){
   this.radius=radius;paint.setStyle(Paint.Style.FILL);
   Object pathObject=null;java.lang.reflect.Method method=null;
   try{Class<?> type=Class.forName("com.oplus.graphics.OplusPath");pathObject=type.getConstructor(Path.class).newInstance(path);method=type.getMethod("addSmoothRoundRect",RectF.class,float.class,float.class,float.class,Path.Direction.class);}catch(Throwable ignored){}
   oplusPath=pathObject;smooth=method;
  }
  void setExpansion(float expansion){this.expansion=expansion;invalidateSelf();}
  public void draw(Canvas canvas){
   android.graphics.Rect b=getBounds();if(b.isEmpty())return;
   paint.setColor(SwipePanelPolicy.background(expansion));
   rect.set(b);path.reset();
   if(smooth!=null&&!smoothFailed){try{smooth.invoke(oplusPath,rect,radius,radius,0.99f,Path.Direction.CCW);}catch(Throwable e){smoothFailed=true;path.reset();path.addRoundRect(rect,radius,radius,Path.Direction.CCW);}}
   else path.addRoundRect(rect,radius,radius,Path.Direction.CCW);
   canvas.drawPath(path,paint);
  }
  public void setAlpha(int alpha){}
  public void setColorFilter(ColorFilter filter){}
  public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }
 /** 24dp window-plus glyph, stroke 1.6 at 90% like rapid_reaction_float_window_v15. */
 private static final class WorkbenchMark extends Drawable {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private int color=0xe6ffffff;
  WorkbenchMark(){paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
  void setGlyph(int opaque){color=0xe6000000|(opaque&0x00ffffff);invalidateSelf();}
  public void draw(Canvas canvas){
   android.graphics.Rect b=getBounds();if(b.isEmpty())return;
   canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);
   paint.setColor(color);paint.setStrokeWidth(1.6f);
   canvas.drawRoundRect(4.3f,4.3f,19.7f,19.7f,2.2f,2.2f,paint);
   canvas.drawLine(12f,8.7f,12f,15.3f,paint);canvas.drawLine(8.7f,12f,15.3f,12f,paint);
   canvas.restore();
  }
  public int getIntrinsicWidth(){return 24;}
  public int getIntrinsicHeight(){return 24;}
  public void setAlpha(int alpha){}
  public void setColorFilter(ColorFilter filter){}
  public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }
}
