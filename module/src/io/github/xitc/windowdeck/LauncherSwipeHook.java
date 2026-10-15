package io.github.xitc.windowdeck;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
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
 *       one slot; see {@link SwipeReleaseHandoff#release}.</li>
 *   <li>the middle-column gap cannot be widened through the ROM's own maths any more, because the
 *       interval field became {@code final} and its setter was deleted. It is opened by rewriting
 *       the capsule rectangles after the ROM computed them; see {@link SwipeCapsuleMotion#anchor}.</li>
 * </ul>
 *
 * <p>This class installs those hooks and runs the gesture lifecycle. The card paints live in
 * {@link SwipeZoneChrome}, the springs and capsule rectangles in {@link SwipeCapsuleMotion}, the
 * centre-column claim in {@link SwipeCentreClaim}, source pixels in {@link SwipeSourceFrame}, and
 * the accepted handoff in {@link SwipeReleaseHandoff}.
 */
final class LauncherSwipeHook {
 private static final String TAG="WindowDeck";
 private static final WeakHashMap<View,State> STATES=new WeakHashMap<>();
 private static final WeakHashMap<Object,Boolean> ELIGIBLE_PARAMS=new WeakHashMap<>();
 private static final Set<Class<?>> HOOKED_PARAMS=new HashSet<>();
 /**
  * True while the hook wants the 128dp middle gap. Display eligibility arms it at gesture start;
  * the stricter task candidate is checked independently when the user releases.
  */
 private static volatile boolean MID_ACTIVE;
 /** One-shot per gesture, so "the gate is off" is visible without logging every frame. */
 private static volatile boolean TRACED_MID;
 /**
  * Per-panel gesture record. Paints, springs, the centre claim and source frames keep their own
  * fields; this only remembers the progress gate and which objects belong to the panel.
  */
 static final class State {
  final SwipeCapsuleMotion motion=new SwipeCapsuleMotion();
  final SwipeSourceFrame source=new SwipeSourceFrame();
  final SwipeCentreClaim.Latch claim=new SwipeCentreClaim.Latch();
  SwipeZoneChrome chrome;
  SwipeCandidate.Match candidate;
  int gestureTask=-1;
  float progress,trigger,startShow;
  boolean tracedProgress,tracedZone;
  View zone(){return chrome==null?null:chrome.zone();}
 }
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
  try{SwipeCentreClaim.problem(RomSymbols.field(panel,RomSymbols.PANEL_CONTROLLER).getType());}catch(Throwable ignored){}
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
   @Override protected void beforeHookedMethod(MethodHookParam p){try{SwipeReleaseHandoff.release(p);}catch(Throwable e){Log.w(TAG,"swipe_release_failed",e);}}
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
 private static int rotation(View panel){try{return XposedHelpers.getIntField(panel,RomSymbols.PANEL_ROTATION);}catch(Throwable ignored){return 0;}}
 private static List<?> entrances(View panel){return (List<?>)XposedHelpers.getObjectField(panel,RomSymbols.PANEL_ENTRANCES);}
 private static int dp(Resources r,int n){return Math.round(n*r.getDisplayMetrics().density);}
 /**
  * Diagnostics go to both sinks on purpose: {@code android.util.Log} is what {@code adb logcat}
  * shows, {@code XposedBridge.log} is what the module manager shows. A line that reaches only one of
  * them reads as "there is no log at all" to whoever is watching the other.
  */
 static void log(String message){
  Log.i(TAG,message);
  try{XposedBridge.log("WindowDeck "+message);}catch(Throwable ignored){}
 }
 private static boolean canShowThird(Context context,int rotation){
  if(context.getResources().getConfiguration().smallestScreenWidthDp>=600)return false;
  return rotation>=0&&rotation<=3;
 }
 static State stateOf(View panel){return STATES.get(panel);}
 static boolean midActive(){return MID_ACTIVE;}
 static void deactivateMid(){MID_ACTIVE=false;}
 static boolean claimHandoff(Object handler,boolean coverUsable){return HANDOFF_OWNERS.claim(handler,coverUsable);}
 // ------------------------------------------------------------------ gesture lifecycle
private static void onGestureStart(View panel) throws Exception {
 // This runs inside the panel's own initAnimation, before its first frame. The task list was
 // prepared on a background thread; this hook only reads that snapshot and a local task id.
 long began=SystemClock.uptimeMillis();
 LiveWorkbenchState.register(panel.getContext());
 State state=state(panel);
 state.tracedProgress=false;state.tracedZone=false;state.motion.clearWindowFade();TRACED_MID=false;state.motion.resetExpansion();
 state.candidate=null;state.source.resetMotion();SwipeCentreClaim.clearShown();
 // Two things fade the card to alpha 0 -- the exit animation when the window opens, and the retire
 // edge when the finger settles on one of the launcher's own options -- and neither puts it back, so
 // the gesture boundary is where it is restored. Doing it anywhere later would fight the fade.
 state.motion.resetAlpha(state.chrome);
 SwipeCentreClaim.reset(state.claim);
 if(state.chrome!=null)state.chrome.zone().setVisibility(View.GONE);
 Object params=params(panel);
 if(params!=null)hookParamsOnce(params);
 Object swipe=swipeUpHandler(panel);
 state.gestureTask=CandidateProbe.runningTask(swipe);
 SwipeCandidate.Match candidate=swipe==null?null:CandidateProbe.match(swipe);
 boolean room=centreFree(panel);
 boolean offered=CandidateProbe.showEntry(swipe);
 if(params!=null)ELIGIBLE_PARAMS.put(params,offered&&room);
 // The ROM recomputes every capsule rectangle further down this very call, so arming the layout
 // here is what makes the middle gap appear together with the panel instead of one frame later.
 // A panel that already has its own centre entrance must not have those rectangles rewritten.
 MID_ACTIVE=offered&&room&&canShowThird(panel.getContext(),rotation(panel));
 log("swipe_init version="+Version.NAME+" handler="+(swipe!=null)+" candidate="+(candidate!=null)+" room="+room
     +" offered="+offered+" source="+CandidateProbe.runningTask(swipe)
     +" cache="+(CandidateProbe.ready()?"warm":"cold")+" total="+(SystemClock.uptimeMillis()-began)+"ms");
 CandidateProbe.kick();
 // HOME/RECENTS/cancel still belong to Launcher. Never cover the finger-driven
 // task with a display snapshot before release selects a workbench handoff.
 final int sourceGeneration=state.source.openGeneration();
 if(MID_ACTIVE&&candidate!=null&&candidate.container>=0&&SwipePanelPolicy.landscapeStack(rotation(panel)))
  state.source.schedule(panel,swipe,candidate.task,sourceGeneration);
}
 private static void onGestureEnd(View panel){
  State state=STATES.get(panel);
  if(state!=null)state.source.endGesture();
  Object params=params(panel);
  if(params!=null)ELIGIBLE_PARAMS.remove(params);
  MID_ACTIVE=false;
  SwipeCentreClaim.clearShown();
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
     SwipeCapsuleMotion.anchor(p.thisObject,(Resources)p.args[2],(Integer)p.args[3]);
    }catch(Throwable e){Log.w(TAG,"swipe_side_rect_failed",e);}}
   });
   SwipeCentreClaim.hook(type);
  }catch(Throwable e){Log.w(TAG,"swipe_params_hook_failed",e);XposedBridge.log(e);}
 }
 private static State state(View panel){State s=STATES.get(panel);if(s==null){s=new State();STATES.put(panel,s);}return s;}
 private static void progress(View panel,float value){
  State s=state(panel);s.progress=value;
  try{
   Object gate=params(panel);
   if(gate!=null)s.startShow=XposedHelpers.getFloatField(gate,RomSymbols.PARAMS_START_SHOW);
  }catch(Throwable ignored){}
  if(value>0&&!s.tracedProgress){s.tracedProgress=true;XposedBridge.log("WindowDeck swipe_progress status="+panelStatus(panel)+" visible="+(panel.getVisibility()==View.VISIBLE)+" value="+value);}
  if(!(panel instanceof FrameLayout))return;
  if(s.chrome==null){s.chrome=SwipeZoneChrome.attach((FrameLayout)panel);s.chrome.apply(s.motion.expansion());}
  // The ROM retires its whole panel the instant the window starts animating open. The card is not
  // in the ROM's fade set, so it has to retire itself; see fadeForWindow. Checked ahead of
  // every gate below, because the card has to go even when the gates that put it there have flipped.
  if(s.motion.fadeForWindow(panel,s.chrome,s.progress))return;
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
  // MID_ACTIVE means the entry is offered and the ROM has the wide middle gap. Cache/state
  // readiness only gates release; a cache miss at init must not hide this entire gesture.
  // The old probe against the ROM's interval field cannot be used any more: on C17 that field is
  // 8dp by design and would keep the card hidden forever.
  if(!MID_ACTIVE){if(!TRACED_MID){TRACED_MID=true;log("zone skipped: mid gap inactive (entry blocked, centre taken, or the tablet/rotation gate)");}hideCard(s);return;}
  if(!CandidateProbe.showEntry(handler)){hideCard(s);return;}
  s.trigger=XposedHelpers.getFloatField(params,RomSymbols.PARAMS_START_TRIGGER);
  s.startShow=XposedHelpers.getFloatField(params,RomSymbols.PARAMS_START_SHOW);
  // Two thresholds, not one. The launcher fades an option in from mStartShowP (0.95) and only lets
  // it be picked from mStartTriggerP (1.15); between the two it is visible but not selectable.
  // Gating the card on the trigger instead put it on screen 0.20 of progress after the two options
  // it sits between, and left it absent entirely for a swipe that came to rest in between -- which
  // is the "the third option turns up a few seconds late" report.
  if(!SwipePanelPolicy.visible(value,s.startShow)){s.motion.resetExpansion();hideCard(s);return;}
  // The launcher retires the option it did not pick the instant one of them becomes pickable, and
  // moves the one it did pick to the centre of the screen -- which is the card's own column. Fading
  // the card on that same edge is what keeps the two from being drawn on top of each other; without
  // it the capsule's icon lands on the card's icon and both titles are legible at once, in the same
  // box. See SwipePanelPolicy.stillCurrent for the rule and where it comes from.
  int axis=axisCode(panel);
  if(!SwipePanelPolicy.stillCurrent(axis)){s.motion.cardCurrent(s.chrome,false,axis,s.progress);updateSelection(panel);SwipeCentreClaim.retire(panel,s.claim,axis,s.progress);return;}
  s.motion.cardCurrent(s.chrome,true,axis,s.progress);
  // Nothing is animated here on purpose. The launcher resets each option view to scale 1.0 and
  // alpha 1.0 for the whole gesture (initAnimation -> resetViewScale/resetViewAlpha), and its show
  // animator writes only View.ALPHA on the panel root. The icon and the title therefore ride that
  // one fade and never scale, which is why the box and its text arrive on the same frame. A card
  // that scaled its own contents in over the last tenth of the reveal -- as this one used to -- put
  // the text on screen noticeably after the box it sits in.
  s.candidate=CandidateProbe.match(handler);
  if(s.candidate!=null)s.source.sample(handler,s.candidate.task);
  SwipeCentreClaim.showCard();
  s.chrome.zone().setVisibility(View.VISIBLE);
  s.chrome.zone().bringToFront();
  // The other half of the dodge. With the card in the column, the two capsules have to leave it;
  // nothing in the launcher does that on its own here, so the hook drives its animator. See
  // SwipeCentreClaim.retire. Ordered after the card is shown so the claim and the card always agree.
  SwipeCentreClaim.retire(panel,s.claim,axis,s.progress);
  updateSelection(panel);
  if(!s.tracedZone){s.tracedZone=true;View zone=s.chrome.zone();float trigger=s.trigger,startShow=s.startShow;zone.post(()->{int[] xy=new int[2];zone.getLocationOnScreen(xy);log("zone shown="+zone.isShown()+" alpha="+zone.getAlpha()+" panelAlpha="+panel.getAlpha()+" candidate="+(s.candidate!=null)+" bounds="+xy[0]+","+xy[1]+","+zone.getWidth()+","+zone.getHeight()+" panel="+panel.getWidth()+","+panel.getHeight()+" progress="+s.progress+" startShow="+startShow+" trigger="+trigger);});}
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
 * <p>{@link #progress} reaches the claim through {@link SwipeCentreClaim#showCard}, and every
 * path that hides the card goes through here. Release hides the view without this method: the
 * gesture-end hook clears the shown flag after the handoff has already started.
 */
private static void hideCard(State s){
 if(s!=null&&s.chrome!=null)s.chrome.zone().setVisibility(View.GONE);
 SwipeCentreClaim.cardHidden(s==null?null:s.claim);
}
 static void positionZone(View panel){
  State s=STATES.get(panel);
  if(s==null||s.chrome==null||panel.getWidth()<=0)return;
  s.motion.position(panel,s.chrome);
 }
 private static void updateSelection(View panel){
  State s=STATES.get(panel);
  if(s==null||s.chrome==null)return;
  s.motion.updateSelection(panel,s.chrome,s.progress,s.trigger);
 }
}
