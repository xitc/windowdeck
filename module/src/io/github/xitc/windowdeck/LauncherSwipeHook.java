package io.github.xitc.windowdeck;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
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
 /** One-shot per gesture, so "the gate is off" is visible without logging every frame. */
 private static volatile boolean TRACED_MID;
 private static final class Candidate { final int task,container; Candidate(int t,int c){task=t;container=c;} }
 private static final class State { LinearLayout zone; View icon; TextView label; WorkbenchMark mark; PanelBg background; Candidate candidate; long checked; float progress,trigger,startShow; boolean tracedProgress,tracedZone,selected; float expansion,pop=1; ValueAnimator expansionAnimator; Runnable hover; }
 static void install(ClassLoader loader){
  LiveWorkbenchState.installLauncher();
  LauncherExistingAppHook.install();
  Class<?> panel;
  try{panel=XposedHelpers.findClass(RomSymbols.PANEL_CLASS,loader);}
  catch(Throwable e){XposedBridge.log("WindowDeck: 桌面面板类不存在，跳过 "+RomSymbols.PANEL_CLASS);return;}
  // Anchor probe. setSwipeUpHandler survived minification and its only parameter is, by
  // definition, the handler class of this build. This replaces the hard-coded findClass that used
  // to abort the whole install with ClassNotFoundException on any new ROM.
  Class<?> handler=RomSymbols.probeHandlerClass(panel);
  if(handler==null){XposedBridge.log("WindowDeck: setSwipeUpHandler 不存在，跳过安装");return;}
  String problem=RomSymbols.validate(panel,handler);
  if(problem!=null){XposedBridge.log("WindowDeck: 桌面版本未适配("+problem+")，跳过安装；handler="+handler.getName());return;}
  Method bridge=RomSymbols.findGestureEndedBridge(handler);
  Method initAnimation=findInitAnimation(panel);
  if(bridge==null||initAnimation==null){XposedBridge.log("WindowDeck: 手势入口不完整，跳过安装");return;}
  Class<?> concrete=XposedHelpers.findClassIfExists(RomSymbols.PARAMS_CLASS_HINT,loader);
  if(concrete==null)XposedBridge.log("WindowDeck: 提示类 "+RomSymbols.PARAMS_CLASS_HINT+" 不存在，中列间距将只做矩形锚定");
  XposedBridge.log("WindowDeck: ROM 适配 C17 handler="+handler.getName()+" bridge="+bridge.getName()+" init="+initAnimation.getName());
  installHooks(panel,bridge,initAnimation);
 }
 /** The C17 method name did not survive, but its Kotlin lambda parameter did. */
 private static Method findInitAnimation(Class<?> panel){
  for(Method method:panel.getDeclaredMethods()){
   if(method.getParameterCount()!=1)continue;
   if("kotlin.jvm.functions.Function4".equals(method.getParameterTypes()[0].getName()))return method;
  }
  return null;
 }
 private static void installHooks(Class<?> panel,Method bridge,Method initAnimation){
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
  LiveWorkbenchState.register(panel.getContext());
  State state=state(panel);
  state.tracedProgress=false;state.tracedZone=false;TRACED_MID=false;resetExpansion(panel,state);
  state.candidate=null;state.checked=0;
  if(state.zone!=null)state.zone.setVisibility(View.GONE);
  Object params=params(panel);
  if(params!=null)hookParamsOnce(params);
  Object swipe=swipeUpHandler(panel);
  Candidate candidate=swipe==null?null:findCandidate(panel.getContext(),swipe);
  if(params!=null)ELIGIBLE_PARAMS.put(params,candidate!=null);
  // The ROM recomputes every capsule rectangle further down this very call, so arming the layout
  // here is what makes the middle gap appear together with the panel instead of one frame later.
  MID_ACTIVE=candidate!=null&&canShowThird(panel.getContext(),rotation(panel));
  log("swipe_init handler="+(swipe!=null)+" candidate="+(candidate!=null));
 }
 private static void onGestureEnd(View panel){
  Object params=params(panel);
  if(params!=null)ELIGIBLE_PARAMS.remove(params);
  MID_ACTIVE=false;
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
  }catch(Throwable e){Log.w(TAG,"swipe_params_hook_failed",e);XposedBridge.log(e);}
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
  // Launcher keeps this fullscreen panel at a fixed size during the gesture, so a newly added
  // child may not receive another layout pass before the user releases their finger.
  positionZone(panel);
  if(value<=0||panel.getVisibility()!=View.VISIBLE||panelStatus(panel)==0){s.zone.setVisibility(View.GONE);return;}
  // The center is occupied by the ROM capsule on devices that expose it.
  List<?> entrances=entrances(panel);
  if(entrances!=null&&entrances.size()>2){s.zone.setVisibility(View.GONE);return;}
  Object handler=swipeUpHandler(panel);
  if(handler==null){s.zone.setVisibility(View.GONE);return;}
  Object params=params(panel);
  if(params==null){s.zone.setVisibility(View.GONE);return;}
  hookParamsOnce(params);
  // MID_ACTIVE means "a candidate exists and the ROM has been laid out with the wide middle gap".
  // The old probe against the ROM's interval field cannot be used any more: on C17 that field is
  // 8dp by design and would keep the card hidden forever.
  if(!MID_ACTIVE){if(!TRACED_MID){TRACED_MID=true;log("zone skipped: mid gap inactive (no candidate, or the tablet/rotation gate)");}s.zone.setVisibility(View.GONE);return;}
  s.trigger=XposedHelpers.getFloatField(params,RomSymbols.PARAMS_START_TRIGGER);
  if(value<s.trigger){resetExpansion(panel,s);s.zone.setVisibility(View.GONE);return;}
  // The launcher reveals its own options by scrubbing the panel alpha with the finger and scaling
  // each option's icon and title in over the last tenth of that span. The card inherits the alpha
  // by being a child of the panel; this reproduces the rest of it.
  s.startShow=XposedHelpers.getFloatField(params,RomSymbols.PARAMS_START_SHOW);
  s.pop=SwipePanelPolicy.popIn(SwipePanelPolicy.reveal(value,s.startShow,s.trigger));
  applyPop(s);
  if(SystemClock.uptimeMillis()-s.checked>750){s.checked=SystemClock.uptimeMillis();s.candidate=findCandidate(panel.getContext(),handler);}
  s.zone.setVisibility(s.candidate==null?View.GONE:View.VISIBLE);
  if(s.candidate!=null)s.zone.bringToFront();
  updateSelection(panel);
  if(s.candidate!=null&&!s.tracedZone){s.tracedZone=true;View zone=s.zone;float trigger=s.trigger;zone.post(()->{int[] xy=new int[2];zone.getLocationOnScreen(xy);log("zone shown="+zone.isShown()+" alpha="+zone.getAlpha()+" panelAlpha="+panel.getAlpha()+" bounds="+xy[0]+","+xy[1]+","+zone.getWidth()+","+zone.getHeight()+" panel="+panel.getWidth()+","+panel.getHeight()+" progress="+s.progress+" trigger="+trigger);});}
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
  applyPop(s);
 }
 /**
  * Scales the card's icon and label, leaving the card itself alone.
 *
  * Only the contents move, because that is what the launcher does: the capsule outline is part of
  * the background layer and merely fades with the panel, while the icon and title scale up from
  * nothing. Scaling the whole card would add motion the ROM's own two options do not have.
  */
 private static void applyPop(State s){
  if(s.icon!=null){s.icon.setScaleX(s.pop);s.icon.setScaleY(s.pop);}
  if(s.label!=null){s.label.setScaleX(s.pop);s.label.setScaleY(s.pop);}
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
 private static Candidate findCandidate(Context context,Object handler){
  try{
   Object gesture=XposedHelpers.getObjectField(handler,RomSymbols.HANDLER_GESTURE_STATE);
   int source=((Number)XposedHelpers.callMethod(gesture,RomSymbols.GESTURE_RUNNING_TASK_ID)).intValue();
   if(source<0){XposedBridge.log("WindowDeck candidate=no_running_task");return null;}
   Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
   // The workbench marker lives in baseIntent extras. Android strips extras unless keepIntentExtra is true.
   List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,true,0);
   ActivityManager.RunningTaskInfo sourceInfo=null,containerInfo=null;boolean otherCanvas=false;
   for(Object value:tasks){ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)value;
    if(info.taskId==source)sourceInfo=info;
    if(info.baseActivity!=null&&"com.oplus.pscanvas".equals(info.baseActivity.getPackageName())&&(info.baseIntent==null||!info.baseIntent.getBooleanExtra("windowdeck_workbench_v1",false)))otherCanvas=true;
    if(info.baseIntent!=null&&info.baseIntent.getBooleanExtra("windowdeck_workbench_v1",false)&&info.topActivity!=null&&"com.oplus.pscanvas".equals(info.topActivity.getPackageName()))containerInfo=info;
   }
   if(containerInfo==null&&otherCanvas)return null;
   if(sourceInfo==null||(containerInfo!=null&&source==containerInfo.taskId)||sourceInfo.baseActivity==null||sourceInfo.topActivity==null){XposedBridge.log("WindowDeck candidate=task_lookup source="+source+" sourceFound="+(sourceInfo!=null)+" containerFound="+(containerInfo!=null));return null;}
   Bundle state=containerInfo==null?Bundle.EMPTY:LiveWorkbenchState.state(containerInfo.taskId);
   if(state==null)state=context.getContentResolver().call(Uri.parse("content://io.github.xitc.windowdeck.state"),"state",null,null);
   if(state==null||!IngressPolicy.canEnter(containerInfo==null?-1:containerInfo.taskId,state.getInt("container",-1),state.getInt("count",0))){XposedBridge.log("WindowDeck candidate=state_mismatch stateContainer="+(state==null?-1:state.getInt("container",-1))+" taskContainer="+(containerInfo==null?-1:containerInfo.taskId)+" count="+(state==null?-1:state.getInt("count",-1)));return null;}
   if(!sourceInfo.baseActivity.getPackageName().equals(sourceInfo.topActivity.getPackageName())){XposedBridge.log("WindowDeck candidate=mixed_package source="+source);return null;}
   if(sourceInfo.getClass().getField("userId").getInt(sourceInfo)!=0||(containerInfo!=null&&containerInfo.getClass().getField("userId").getInt(containerInfo)!=0)){XposedBridge.log("WindowDeck candidate=non_primary_user");return null;}
   if(((Number)sourceInfo.getClass().getMethod("getWindowingMode").invoke(sourceInfo)).intValue()!=1){XposedBridge.log("WindowDeck candidate=non_fullscreen");return null;}
   String pkg=sourceInfo.topActivity.getPackageName();if("com.oplus.pscanvas".equals(pkg)||"com.android.launcher".equals(pkg)||"io.github.xitc.windowdeck".equals(pkg)){XposedBridge.log("WindowDeck candidate=excluded_package");return null;}
   XposedBridge.log("WindowDeck candidate=ready source="+source+" container="+(containerInfo==null?-1:containerInfo.taskId));
   return new Candidate(source,containerInfo==null?-1:containerInfo.taskId);
  }catch(Throwable e){Log.w(TAG,"swipe_candidate_unavailable",e);XposedBridge.log(e);return null;}
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
  if(s.progress<s.trigger){log("release bail=below_trigger");return;}
  if(Boolean.TRUE.equals(p.args[RomSymbols.BRIDGE_ARG_BOOLEAN])||Boolean.TRUE.equals(p.args[RomSymbols.BRIDGE_ARG_BOOLEAN+1])){log("release bail=cancelled");return;}
  PointF up=(PointF)p.args[RomSymbols.BRIDGE_ARG_UP_POS];if(up==null){log("release bail=no_up");return;}
  int rotation=rotation(panel);
  int[] pos=new int[2];s.zone.getLocationOnScreen(pos);
  boolean onChip=s.selected&&(SwipePanelPolicy.landscapeStack(rotation)||(Math.abs(up.x-panel.getWidth()/2f)<=dp(panel.getContext(),64)&&up.y>=pos[1]&&up.y<=pos[1]+s.zone.getHeight()));
  if(!onChip){log("release bail=off_chip up="+up.x+","+up.y+" selected="+s.selected+" zone="+pos[0]+","+pos[1]+" "+s.zone.getWidth()+"x"+s.zone.getHeight()+" panelW="+panel.getWidth());return;}
  Candidate candidate=findCandidate(panel.getContext(),handler);
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
  final HandoffCover cover=HandoffCover.create(panel.getContext());
  java.util.concurrent.atomic.AtomicBoolean recentsEnded=new java.util.concurrent.atomic.AtomicBoolean();
  Runnable endRecents=()->{
   if(!recentsEnded.compareAndSet(false,true))return;
   try{
    if(gestureController!=null&&XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER)==gestureController)
     XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_FINISH_RECENTS,false);
   }catch(Throwable e){Log.w(TAG,"swipe_handoff_finish_failed",e);}
  };
  java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
  Runnable finish=()->{
   if(!finished.compareAndSet(false,true))return;
   endRecents.run();cover.close("handoff_finished");
   Log.i(TAG,"swipe_handoff_finish ms="+(SystemClock.uptimeMillis()-released));
  };
  android.os.ResultReceiver localReady=new android.os.ResultReceiver(main){
   @Override protected void onReceiveResult(int code,Bundle data){
    Log.i(TAG,"swipe_host_ready result="+code+" ms="+(SystemClock.uptimeMillis()-released));
    finish.run();
   }
  };
  // Parcel the framework base class, not this module's anonymous subclass: the system host
  // cannot load launcher-private module classes while unmarshalling its launch Intent.
  android.os.Parcel replyParcel=android.os.Parcel.obtain();
  android.os.ResultReceiver ready;
  try{localReady.writeToParcel(replyParcel,0);replyParcel.setDataPosition(0);ready=android.os.ResultReceiver.CREATOR.createFromParcel(replyParcel);}
  finally{replyParcel.recycle();}
  // The independent cover survives system cancellation of the recents leash.
  // This is a failure watchdog, not a normal-path animation/settle timer.
  main.postDelayed(()->{if(!finished.get()){Log.w(TAG,"swipe_host_ready_timeout");finish.run();}},2000);
  cover.dispatchWhenDrawn(()->{
   // Retire recents BEFORE embedding: its later cleanup must not undo the host's reparent.
   // The committed independent cover remains visible across this ownership change.
   endRecents.run();main.post(()->dispatchHandoff(context,candidate,released,ready,finish));
  });
  Log.i(TAG,"swipe_add_requested task="+candidate.task+" container="+candidate.container);
  resetExpansion(panel,s);s.zone.setVisibility(View.GONE);s.candidate=null;MID_ACTIVE=false;p.setResult(null);
 }
 private static void dispatchHandoff(Context context,Candidate candidate,long released,android.os.ResultReceiver ready,Runnable finish){
  Log.i(TAG,"swipe_handoff_begin ms="+(SystemClock.uptimeMillis()-released));
  if(candidate.container<0){
   try{
    Intent create=new Intent().setComponent(new ComponentName("com.oplus.pscanvas","com.oplus.pscanvas.canvasmode.canvas.ContainerActivity"));
    create.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
    create.putExtra("windowdeck_workbench_v1",true);
    create.putExtra("windowdeck_create_source_task",candidate.task);
    create.putExtra("windowdeck_create_source_user",0);
    create.putExtra("windowdeck_handoff_ready",ready);
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
 private static void dispatchRootHandoff(Context context,Candidate candidate){
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
