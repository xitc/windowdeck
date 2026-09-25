package dev.windowdeck.app;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
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
import android.text.TextUtils;
import java.util.Map;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.WeakHashMap;

/** Adds a center drop zone to the ROM's existing split/float swipe panel. */
final class LauncherSwipeHook {
 private static final String TAG="WindowDeck";
 private static final WeakHashMap<View,State> STATES=new WeakHashMap<>();
 private static final WeakHashMap<Object,float[]> ORIGINAL_GEOMETRY=new WeakHashMap<>();
 private static final WeakHashMap<Object,Boolean> ELIGIBLE_PARAMS=new WeakHashMap<>();
 private static final ThreadLocal<Boolean> THREE_CHOICES=new ThreadLocal<>();
 private static final class Candidate { final int task,container; Candidate(int t,int c){task=t;container=c;} }
 private static final class State { LinearLayout zone; TextView label; WorkbenchMark mark; PanelBg background; Candidate candidate; long checked; float progress,trigger; boolean tracedProgress,tracedZone,selected; float expansion; ValueAnimator expansionAnimator; Runnable hover; }
 static void install(ClassLoader loader){
  Class<?> panel=XposedHelpers.findClass("com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView",loader);
  Class<?> handler=XposedHelpers.findClass("com.oplus.quickstep.gesture.OplusBaseSwipeUpHandler",loader);
  XposedHelpers.findAndHookMethod(panel,"updateProgress",float.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){try{progress((View)p.thisObject,(Float)p.args[0]);}catch(Throwable e){Log.w(TAG,"swipe_panel_update_failed",e);XposedBridge.log(e);}}
  });
  XposedHelpers.findAndHookMethod(panel,"onLayout",boolean.class,int.class,int.class,int.class,int.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){try{positionZone((View)p.thisObject);}catch(Throwable e){Log.w(TAG,"zone_layout_failed",e);}}
  });
  XposedHelpers.findAndHookMethod(panel,"updateHorizontalOffset",int.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){try{updateSelection((View)p.thisObject);}catch(Throwable e){Log.w(TAG,"center_selection_failed",e);}}
  });
  XposedBridge.hookAllMethods(panel,"initAnimation",new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){
    try{View view=(View)p.thisObject;State state=state(view);state.tracedProgress=false;state.tracedZone=false;resetExpansion(view,state);state.candidate=null;state.checked=0;if(state.zone!=null)state.zone.setVisibility(View.GONE);Object ref=XposedHelpers.getObjectField(view,"swipeUpHandlerRef");Object swipe=ref instanceof WeakReference?((WeakReference<?>)ref).get():null;Candidate candidate=swipe==null?null:findCandidate(view.getContext(),swipe);Object controller=XposedHelpers.getObjectField(view,"mMultiTriggerPanelController");Object params=XposedHelpers.callMethod(controller,"getMTriggerParams");ELIGIBLE_PARAMS.put(params,candidate!=null);XposedBridge.log("WindowDeck swipe_init handler="+(swipe!=null)+" candidate="+(candidate!=null));}
    catch(Throwable e){Log.w(TAG,"swipe_init_gate_failed",e);XposedBridge.log(e);}
   }
  });
  XposedHelpers.findAndHookMethod(handler,"onGestureEnded",float.class,PointF.class,PointF.class,PointF.class,boolean.class,boolean.class,new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){try{release(p);}catch(Throwable e){Log.w(TAG,"swipe_release_failed",e);}}
   @Override protected void afterHookedMethod(MethodHookParam p){try{View panel=(View)XposedHelpers.getObjectField(p.thisObject,"triggerPanel");Object controller=XposedHelpers.getObjectField(panel,"mMultiTriggerPanelController");ELIGIBLE_PARAMS.remove(XposedHelpers.callMethod(controller,"getMTriggerParams"));}catch(Throwable e){XposedBridge.log(e);}}
  });
  Class<?> params=XposedHelpers.findClass("com.oplus.quickstep.rapidreaction.panelparams.SplitFloatParams",loader);
  XposedHelpers.findAndHookMethod(params,"updateParams",Context.class,int.class,int.class,new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){try{boolean eligible=Boolean.TRUE.equals(ELIGIBLE_PARAMS.get(p.thisObject));boolean show=eligible&&canShowThird((Context)p.args[0],(Integer)p.args[1]);THREE_CHOICES.set(show);XposedBridge.log("WindowDeck swipe_params rotation="+p.args[1]+" eligible="+eligible+" show="+show);}catch(Throwable e){THREE_CHOICES.set(false);Log.w(TAG,"swipe_geometry_gate_failed",e);XposedBridge.log(e);}}
   @Override protected void afterHookedMethod(MethodHookParam p){THREE_CHOICES.remove();}
  });
  XposedHelpers.findAndHookMethod(params,"updateBoundaryProgress",boolean.class,boolean.class,Resources.class,int.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){try{if(Boolean.TRUE.equals(THREE_CHOICES.get()))XposedHelpers.callMethod(p.thisObject,"setMMidIntervalInTriggerRow",(float)dp(((Resources)p.args[2]),128));}catch(Throwable e){Log.w(TAG,"swipe_center_width_failed",e);}}
  });
  XposedHelpers.findAndHookMethod(params,"updateBgRect",boolean.class,boolean.class,Resources.class,int.class,int.class,new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){try{
    float[] original=ORIGINAL_GEOMETRY.get(p.thisObject);
    if(original==null){original=new float[]{XposedHelpers.getFloatField(p.thisObject,"leftOrRightBgWidthInNormal"),XposedHelpers.getFloatField(p.thisObject,"horizontalIntervalSizeInNormal")};ORIGINAL_GEOMETRY.put(p.thisObject,original);}
    boolean active=Boolean.TRUE.equals(THREE_CHOICES.get());Resources res=(Resources)p.args[2];
    // ROM overwrites width; adjust output rectangles in the after hook.
    XposedHelpers.setFloatField(p.thisObject,"horizontalIntervalSizeInNormal",active?dp(res,128):original[1]);
   }catch(Throwable e){Log.w(TAG,"swipe_side_geometry_failed",e);}}
   @Override protected void afterHookedMethod(MethodHookParam p){try{
    if(!Boolean.TRUE.equals(THREE_CHOICES.get()))return;
    Resources res=(Resources)p.args[2];int rotation=(Integer)p.args[3];
    if(SwipePanelPolicy.landscapeStack(rotation)){openLandscapeGap(p.thisObject,dp(res,104));return;}
    float width=dp(res,104);
    Map<?,?> rects=(Map<?,?>)XposedHelpers.callMethod(p.thisObject,"getMBgRectMap");
    float center=res.getDisplayMetrics().widthPixels/2f;
    for(Object info:rects.values()){
     RectF normal=(RectF)XposedHelpers.callMethod(info,"getBgNormalRect");
     boolean left=normal.centerX()<center;
     if(left)normal.left=normal.right-width;else normal.right=normal.left+width;
     RectF zoom=(RectF)XposedHelpers.callMethod(info,"getBgZoomOutRect");
     if(left)zoom.left=zoom.right-width/2f;else zoom.right=zoom.left+width/2f;
    }
    XposedHelpers.setFloatField(p.thisObject,"leftOrRightBgWidthInNormal",width);
   }catch(Throwable e){Log.w(TAG,"swipe_side_rect_failed",e);}}
  });
  Log.i(TAG,"launcher_swipe_hook_ready");
 }
 private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 private static int dp(Resources r,int n){return Math.round(n*r.getDisplayMetrics().density);}
 private static boolean canShowThird(Context context,int rotation){
  if(context.getResources().getConfiguration().smallestScreenWidthDp>=600)return false;
  return rotation>=0&&rotation<=3;
 }
 private static void openLandscapeGap(Object params,float length) throws Exception {
  Map<?,?> rects=(Map<?,?>)XposedHelpers.callMethod(params,"getMBgRectMap");
  RectF first=null,second=null;
  for(Object info:rects.values()){RectF normal=(RectF)XposedHelpers.callMethod(info,"getBgNormalRect");if(normal.isEmpty())continue;if(first==null)first=normal;else second=normal;}
  if(first==null||second==null)return;
  RectF upper=first.centerY()<=second.centerY()?first:second,lower=upper==first?second:first;
  float[] top=new float[]{upper.top,upper.bottom},bottom=new float[]{lower.top,lower.bottom};
  SwipePanelPolicy.separateVertical(top,bottom,length);
  upper.top=top[0];upper.bottom=top[1];lower.top=bottom[0];lower.bottom=bottom[1];
 }
 private static State state(View panel){State s=STATES.get(panel);if(s==null){s=new State();STATES.put(panel,s);}return s;}
 private static void progress(View panel,float value){
  State s=state(panel);s.progress=value;
  if(value>0&&!s.tracedProgress){s.tracedProgress=true;XposedBridge.log("WindowDeck swipe_progress status="+XposedHelpers.getIntField(panel,"mPanelStatus")+" visible="+(panel.getVisibility()==View.VISIBLE)+" value="+value);}
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
   ((FrameLayout)panel).addView(zone,lp);s.zone=zone;s.label=label;s.mark=mark;s.background=bg;applyChrome(s);
  }
  // Launcher keeps this fullscreen panel at a fixed size during the gesture, so a newly added
  // child may not receive another layout pass before the user releases their finger.
  positionZone(panel);
  if(value<=0||panel.getVisibility()!=View.VISIBLE||XposedHelpers.getIntField(panel,"mPanelStatus")==0){s.zone.setVisibility(View.GONE);return;}
  // The center is occupied by the ROM capsule on devices that expose it.
  List<?> entrances=(List<?>)XposedHelpers.getObjectField(panel,"entranceViewInfoList");
  if(entrances!=null&&entrances.size()>2){s.zone.setVisibility(View.GONE);return;}
  Object ref=XposedHelpers.getObjectField(panel,"swipeUpHandlerRef");
  Object handler=ref instanceof WeakReference?((WeakReference<?>)ref).get():null;
  if(handler==null){s.zone.setVisibility(View.GONE);return;}
  Object controller=XposedHelpers.getObjectField(panel,"mMultiTriggerPanelController");
  Object params=XposedHelpers.callMethod(controller,"getMTriggerParams");
  if(XposedHelpers.getFloatField(params,"horizontalIntervalSizeInNormal")<dp(panel.getContext(),100)){s.zone.setVisibility(View.GONE);return;}
  s.trigger=((Number)XposedHelpers.callMethod(params,"getMStartTriggerP")).floatValue();
  if(value<s.trigger){resetExpansion(panel,s);s.zone.setVisibility(View.GONE);return;}
  if(SystemClock.uptimeMillis()-s.checked>750){s.checked=SystemClock.uptimeMillis();s.candidate=findCandidate(panel.getContext(),handler);}
  s.zone.setVisibility(s.candidate==null?View.GONE:View.VISIBLE);
  if(s.candidate!=null)s.zone.bringToFront();
  updateSelection(panel);
  if(s.candidate!=null&&!s.tracedZone){s.tracedZone=true;View zone=s.zone;float trigger=s.trigger;float interval=XposedHelpers.getFloatField(params,"horizontalIntervalSizeInNormal");zone.post(()->{int[] xy=new int[2];zone.getLocationOnScreen(xy);XposedBridge.log("WindowDeck zone shown="+zone.isShown()+" alpha="+zone.getAlpha()+" panelAlpha="+panel.getAlpha()+" bounds="+xy[0]+","+xy[1]+","+zone.getWidth()+","+zone.getHeight()+" panel="+panel.getWidth()+","+panel.getHeight()+" progress="+s.progress+" trigger="+trigger+" interval="+interval);});}
 }
 private static void positionZone(View panel){
  State s=STATES.get(panel);if(s==null||s.zone==null||panel.getWidth()<=0)return;
  Object controller=XposedHelpers.getObjectField(panel,"mMultiTriggerPanelController");
  Object params=XposedHelpers.callMethod(controller,"getMTriggerParams");
  applyChrome(s);
  s.zone.setTranslationX(0);s.zone.setTranslationY(0);s.zone.setScaleX(1);s.zone.setScaleY(1);
  int rotation=0;try{rotation=XposedHelpers.getIntField(panel,"currentRotation");}catch(Throwable ignored){}
  int left,top,width,height;
  if(SwipePanelPolicy.landscapeStack(rotation)){
   float[] span=landscapeSpan(params);if(span==null){s.zone.setRotation(0);return;}
   float[] box=SwipePanelPolicy.landscapeChip(span[0],span[1],span[2],span[3],dp(panel.getContext(),112),s.expansion);
   left=Math.round(box[0]);top=Math.round(box[1]);width=Math.round(box[2]);height=Math.round(box[3]);
  }else{
   width=dp(panel.getContext(),112);height=Math.round(((Number)XposedHelpers.callMethod(params,"getMBgHeightInNormal")).floatValue());
   top=Math.round(((Number)XposedHelpers.callMethod(params,"getMBgNormalMarginTop")).floatValue());
   float[] box=SwipePanelPolicy.box(panel.getWidth(),panel.getHeight(),panel.getResources().getDisplayMetrics().density,top,height,s.expansion);
   left=Math.round(box[0]);width=Math.round(box[2]);height=Math.round(box[3]);
  }
  s.zone.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
  s.zone.layout(left,top,left+width,top+height);
  s.zone.setPivotX(width/2f);s.zone.setPivotY(height/2f);s.zone.setRotation(SwipePanelPolicy.chipRotation(rotation));
 }
 private static float[] landscapeSpan(Object params){
  Map<?,?> rects=(Map<?,?>)XposedHelpers.callMethod(params,"getMBgRectMap");
  RectF first=null,second=null;
  for(Object info:rects.values()){RectF normal=(RectF)XposedHelpers.callMethod(info,"getBgNormalRect");if(normal.isEmpty())continue;if(first==null)first=normal;else second=normal;}
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
  boolean selected=s.candidate!=null&&s.zone.getVisibility()==View.VISIBLE&&SwipePanelPolicy.selected(s.progress,s.trigger,XposedHelpers.getIntField(panel,"centerPointHorizontalOffset"),dp(panel.getContext(),64));
  if(!selected&&s.hover!=null){panel.removeCallbacks(s.hover);s.hover=null;}
  if(selected==s.selected)return;
  if(selected){
   if(s.hover!=null)return;
   s.hover=()->{s.hover=null;if(s.candidate!=null&&s.zone.isShown()&&SwipePanelPolicy.selected(s.progress,s.trigger,XposedHelpers.getIntField(panel,"centerPointHorizontalOffset"),dp(panel.getContext(),64)))animateExpansion(panel,s,true);};panel.postDelayed(s.hover,100);
  }else animateExpansion(panel,s,false);
 }
 private static void animateExpansion(View panel,State s,boolean selected){
  s.selected=selected;if(s.expansionAnimator!=null)s.expansionAnimator.cancel();
  ValueAnimator animator=ValueAnimator.ofFloat(s.expansion,selected?1:0);s.expansionAnimator=animator;
  animator.setDuration(383);animator.setInterpolator(f->1-(1-f)*(1-f)*(1-f));
  animator.addUpdateListener(a->{s.expansion=(Float)a.getAnimatedValue();positionZone(panel);});animator.start();
  XposedBridge.log("WindowDeck center_expand selected="+selected);
 }
 private static Candidate findCandidate(Context context,Object handler){
  try{
   Object gesture=XposedHelpers.getObjectField(handler,"mGestureState");
   int source=((Number)XposedHelpers.callMethod(gesture,"getRunningTaskId")).intValue();
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
   Bundle state=containerInfo==null?Bundle.EMPTY:context.getContentResolver().call(Uri.parse("content://dev.windowdeck.app.state"),"state",null,null);
   if(state==null||!IngressPolicy.canEnter(containerInfo==null?-1:containerInfo.taskId,state.getInt("container",-1),state.getInt("count",0))){XposedBridge.log("WindowDeck candidate=state_mismatch stateContainer="+(state==null?-1:state.getInt("container",-1))+" taskContainer="+(containerInfo==null?-1:containerInfo.taskId)+" count="+(state==null?-1:state.getInt("count",-1)));return null;}
   if(!sourceInfo.baseActivity.getPackageName().equals(sourceInfo.topActivity.getPackageName())){XposedBridge.log("WindowDeck candidate=mixed_package source="+source);return null;}
   if(sourceInfo.getClass().getField("userId").getInt(sourceInfo)!=0||(containerInfo!=null&&containerInfo.getClass().getField("userId").getInt(containerInfo)!=0)){XposedBridge.log("WindowDeck candidate=non_primary_user");return null;}
   if(((Number)sourceInfo.getClass().getMethod("getWindowingMode").invoke(sourceInfo)).intValue()!=1){XposedBridge.log("WindowDeck candidate=non_fullscreen");return null;}
   String pkg=sourceInfo.topActivity.getPackageName();if("com.oplus.pscanvas".equals(pkg)||"com.android.launcher".equals(pkg)||"dev.windowdeck.app".equals(pkg)){XposedBridge.log("WindowDeck candidate=excluded_package");return null;}
   XposedBridge.log("WindowDeck candidate=ready source="+source+" container="+(containerInfo==null?-1:containerInfo.taskId));
   return new Candidate(source,containerInfo==null?-1:containerInfo.taskId);
  }catch(Throwable e){Log.w(TAG,"swipe_candidate_unavailable",e);XposedBridge.log(e);return null;}
 }
 private static void release(XC_MethodHook.MethodHookParam p){
  View panel=(View)XposedHelpers.getObjectField(p.thisObject,"triggerPanel");
  State s=STATES.get(panel);if(s==null||s.zone==null||s.zone.getVisibility()!=View.VISIBLE||s.progress<s.trigger)return;
  if(Boolean.TRUE.equals(p.args[4])||Boolean.TRUE.equals(p.args[5]))return;
  PointF up=(PointF)p.args[3];if(up==null)return;
  int rotation=0;try{rotation=XposedHelpers.getIntField(panel,"currentRotation");}catch(Throwable ignored){}
  int[] pos=new int[2];s.zone.getLocationOnScreen(pos);
  boolean onChip=s.selected&&(SwipePanelPolicy.landscapeStack(rotation)||(Math.abs(up.x-panel.getWidth()/2f)<=dp(panel.getContext(),64)&&up.y>=pos[1]&&up.y<=pos[1]+s.zone.getHeight()));
  if(!onChip)return;
  Candidate candidate=findCandidate(panel.getContext(),p.thisObject);
  if(candidate==null||s.candidate==null||candidate.task!=s.candidate.task||candidate.container!=s.candidate.container)return;
  Object manager=XposedHelpers.getObjectField(p.thisObject,"mTaskAnimationManager");
  if(manager==null)return;
  // Finish the existing recents animation back to its app before handing the task over.
  XposedHelpers.callMethod(manager,"finishRunningRecentsAnimation",false);
  Bundle message=new Bundle();message.putInt("taskId",candidate.task);message.putInt("userId",0);message.putInt("containerTaskId",candidate.container);
  Context context=panel.getContext().getApplicationContext();
  new Handler(Looper.getMainLooper()).postDelayed(()->{
   try{if(context.getContentResolver().call(Uri.parse("content://dev.windowdeck.app.state"),"add_task",null,message)!=null)return;}
   catch(Throwable e){Log.w(TAG,"swipe_provider_fallback",e);}
   try{
    Intent request=new Intent().setComponent(new ComponentName("dev.windowdeck.app","dev.windowdeck.app.IngressActivity"));
    request.putExtras(message);request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    context.startActivity(request,ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
   }catch(Throwable e){Log.e(TAG,"swipe_add_dispatch_failed",e);}
  },250);
  Log.i(TAG,"swipe_add_requested task="+candidate.task+" container="+candidate.container);
  resetExpansion(panel,s);s.zone.setVisibility(View.GONE);s.candidate=null;p.setResult(null);
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
