package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;

/** Optional corner entry, using the existing launcher input consumer and Recents ownership. */
final class CornerSwipeHook {
 private static CornerGestureContract contract;
 private static boolean installed;
 private static long revision;
 private static Session current;
 private static final ThreadLocal<CornerTouch.History> INPUT=ThreadLocal.withInitial(CornerTouch.History::new);
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static final class Session {
  final Object consumer;final Context context;final CornerGesture gesture;
  final CornerTouch down;
  final long revision,downTime;
  final SourceMotion motion=new SourceMotion();
  Object handler,simulator,params,manager,controller;
  SwipeCandidate.Match candidate;
  View panel;float panelAlpha;
  boolean hidden,rendered,rebounding,handedOff,haptic;
  final Matrix matrix=new Matrix();
  final RectF renderedBox=new RectF();float renderedRadius;
  Session(Object consumer,Context context,CornerGesture gesture,CornerTouch down){
   this.consumer=consumer;this.context=context;this.gesture=gesture;this.down=down;this.downTime=down.downTime;this.revision=CornerSwipeHook.revision;
  }
 }
 static boolean install(ClassLoader loader,Class<?> handler,java.lang.reflect.Field panel){
  ArrayList<XC_MethodHook.Unhook> hooks=new ArrayList<>();
  try{
   contract=new CornerGestureContract(loader,handler,panel);
   hooks.add(XposedBridge.hookMethod(contract.serviceInput,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){
     if(!(p.args[0] instanceof MotionEvent)||(!CornerEntrySettings.enabled()&&current==null))return;
     try{
      MotionEvent event=(MotionEvent)p.args[0];
      if(!event.isFromSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)||(Integer)contract.eventDisplay.invoke(event)!=0)return;
      int[] display=display();
      INPUT.get().remember(new CornerTouch(display[0],display[1],display[2],event.getDeviceId(),0,
        event.getActionMasked(),event.getPointerCount(),event.getDownTime(),event.getEventTime(),event.getX(),event.getY()));
     }catch(Throwable e){fail("display_input",e);}
    }
   }));
   hooks.add(XposedBridge.hookMethod(contract.input,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){
     if(!(p.args[0] instanceof MotionEvent))return;
     try{input(p);}catch(Throwable e){fail("input",e);}
    }
    @Override protected void afterHookedMethod(MethodHookParam p){
     if(!(p.args[0] instanceof MotionEvent))return;
     try{if(current!=null&&current.consumer==p.thisObject)bind(current);}catch(Throwable e){fail("bind",e);}
     Session s=current;int action=((MotionEvent)p.args[0]).getActionMasked();
     if(s!=null&&s.consumer==p.thisObject&&!s.rendered&&(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)){
      restorePanel(s);current=null;
     }
     if(contract.modern&&s!=null&&s.consumer==p.thisObject&&(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)){
      MAIN.postDelayed(()->{if(current==s&&owns(s.handler)&&!s.rebounding)rebound(s);},1500);
     }
     Object copied=p.getObjectExtra("windowdeck_corner_cancel");if(copied instanceof MotionEvent)((MotionEvent)copied).recycle();
    }
   }));
   hooks.add(XposedBridge.hookMethod(contract.start,new XC_MethodHook(){
    @Override protected void afterHookedMethod(MethodHookParam p){try{if(current!=null&&current.consumer==p.thisObject)bind(current);}catch(Throwable e){fail("start",e);}}
   }));
   hooks.add(XposedBridge.hookMethod(contract.build,new XC_MethodHook(){
    @Override protected void afterHookedMethod(MethodHookParam p){try{if(!p.hasThrowable()&&(contract.modern||!PortraitRecentsHandoff.retainPose(p.thisObject,p.args[0],p.args[1],contract)))draw(p);}catch(Throwable e){fail("frame",e);}}
   }));
   if(!contract.modern){
   hooks.add(XposedBridge.hookMethod(contract.crop,new XC_MethodHook(){
    @Override protected void afterHookedMethod(MethodHookParam p){Session s=owner(p.thisObject);if(s!=null)p.setResult(new RectF(0,0,s.gesture.width,s.gesture.height));}
   }));
   hooks.add(XposedBridge.hookMethod(contract.radius,new XC_MethodHook(){
    @Override protected void afterHookedMethod(MethodHookParam p){Session s=owner(p.thisObject);if(s!=null)p.setResult(s.gesture.radius());}
   }));
   }
   // Some regions already have an assistant wrapper. Route only a supported right-corner
   // stream to its existing OtherActivity delegate; never invoke the assistant's completion.
   hooks.add(XposedBridge.hookMethod(contract.assistantInput,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){try{
     if(!(p.args[0] instanceof MotionEvent))return;
     Object delegate=contract.delegate.get(p.thisObject);if(!contract.consumer.isInstance(delegate))return;
     MotionEvent event=(MotionEvent)p.args[0];
     boolean routed=Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject,"windowdeck_corner_stream"));
     if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
      routed=canStart((Context)delegate,event);XposedHelpers.setAdditionalInstanceField(p.thisObject,"windowdeck_corner_stream",routed);
     }
     if(routed){contract.input.invoke(delegate,event);p.setResult(null);}
     if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)
      XposedHelpers.removeAdditionalInstanceField(p.thisObject,"windowdeck_corner_stream");
    }catch(Throwable e){fail("assistant_delegate",e);}}
   }));
   installed=true;LauncherSwipeHook.log("corner_entry_hook_ready consumer="+contract.consumer.getName()
     +" simulator="+contract.simulator.getName()+" builder="+contract.build.getParameterTypes()[0].getName()
     +" input="+contract.serviceInput.getName()+" rotations=0,1,3");return true;
  }catch(Throwable e){for(XC_MethodHook.Unhook hook:hooks)hook.unhook();contract=null;installed=false;Log.w("WindowDeck","corner_entry_contract_unavailable native_swipe=true",e);return false;}
 }
 static boolean available(){return installed;}
 static long revision(){return revision;}
 static boolean mode(Context context,int rotation){
  return installed&&CornerEntrySettings.enabled()&&CornerTouch.supported(rotation)&&context.getResources().getConfiguration().smallestScreenWidthDp<600;
 }
 static void settingChanged(){
  revision++;LauncherSwipeHook.entrySettingChanged();
  Session s=current;if(s==null||s.handedOff)return;
  s.gesture.cancel();
  if(s.rendered)try{contract.cancel.invoke(s.handler);}catch(Throwable e){rebound(s);}
 }
 /** Launcher can freeze its Context display adjustments to portrait during landscape Recents. */
 private static int[] display() throws Exception {
  Object info=contract.displayInfo.invoke(contract.displayGlobal.invoke(null),0);
  return new int[]{contract.displayRotation.getInt(info),contract.displayWidth.getInt(info),contract.displayHeight.getInt(info)};
 }
 private static boolean canStart(Context context,MotionEvent event) throws Exception {
  CornerTouch touch=touch(event);
  if(touch==null||!touch.valid()||!mode(context,touch.rotation))return false;
  return CornerGesture.hot(touch.width,touch.height,context.getResources().getDisplayMetrics().density,hasNavigationBar(context),touch.x,touch.y);
 }
 private static CornerTouch touch(MotionEvent event) throws Exception {
  return INPUT.get().find(event.getDeviceId(),(Integer)contract.eventDisplay.invoke(event),event.getActionMasked(),event.getPointerCount(),event.getDownTime(),event.getEventTime(),SystemClock.uptimeMillis());
 }
 private static boolean sameDisplay(Session s){
  try{int[] display=display();return s.down.sameDisplay(display[0],display[1],display[2]);}
  catch(Exception e){return false;}
 }
 private static boolean hasNavigationBar(Context context){
  try{return Boolean.TRUE.equals(contract.navigationBar.invoke(null));}catch(Exception e){return true;}
 }
 private static void input(XC_MethodHook.MethodHookParam p) throws Exception {
  MotionEvent event=(MotionEvent)p.args[0];int action=event.getActionMasked();
  if(action==MotionEvent.ACTION_DOWN){
   if(current!=null){if(!current.handedOff)finish(current,"next_down");else{restorePanel(current);current=null;}}
   Context context=(Context)p.thisObject;
   if(!canStart(context,event))return;
   CornerTouch down=touch(event);
   current=new Session(p.thisObject,context,new CornerGesture(down.width,down.height,context.getResources().getDisplayMetrics().density,down.x,down.y,ViewConfiguration.get(context).getScaledTouchSlop()),down);
   LauncherSwipeHook.log("corner_down rotation="+down.rotation+" display="+down.width+"x"+down.height+" xy="+down.x+","+down.y+" consumer_xy="+event.getX()+","+event.getY());
  }
  Session s=current;if(s==null||s.consumer!=p.thisObject||s.handedOff||s.rebounding||s.downTime!=event.getDownTime())return;
  bind(s);
  CornerTouch touch=touch(event);
  if(s.revision!=revision||touch==null||!touch.valid()||!sameDisplay(s)||action==MotionEvent.ACTION_CANCEL||event.getPointerCount()!=1){
   if(!s.gesture.cancelled)LauncherSwipeHook.log("corner_cancel action="+action+" input="+(touch!=null)+" display_same="+sameDisplay(s)+" revision_same="+(s.revision==revision));
   s.gesture.cancel();
   if(action!=MotionEvent.ACTION_CANCEL){MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);p.args[0]=cancel;p.setObjectExtra("windowdeck_corner_cancel",cancel);}
   return;
  }
  if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP)s.gesture.move(touch.x,touch.y);
  if(s.gesture.rejected){restorePanel(s);current=null;return;}
  if(s.gesture.claimed&&s.candidate!=null&&s.panel!=null){
   if(!s.hidden){s.panelAlpha=s.panel.getAlpha();s.panel.setAlpha(0);s.hidden=true;}
   if(s.gesture.ready()&&!s.haptic){s.panel.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE);s.haptic=true;}
   else if(!s.gesture.ready())s.haptic=false;
  }
 }
 private static void bind(Session s) throws Exception {
  if(s.handedOff||s.rebounding)return;
  Object handler=contract.interaction.get(s.consumer);if(handler==null)return;
  if(s.handler!=null&&s.handler!=handler){s.gesture.cancel();return;}
  s.handler=handler;
  int task=contract.modern?(Integer)contract.runningTask.invoke(contract.gestureState.get(handler)):CandidateProbe.runningTask(handler);
  if(contract.modern&&task<0)return;
  if(s.candidate==null)s.candidate=contract.modern?CandidateProbe.matchPanelTask(task):CandidateProbe.match(handler);
  if(s.candidate==null){if(!s.gesture.rejected)LauncherSwipeHook.log("corner_rejected reason=candidate task="+task);s.gesture.rejected=true;return;}
  s.panel=contract.modern?(View)contract.panel.get(handler):LauncherSwipeHook.panelOf(handler);
  Object[] handles=(Object[])contract.handles.get(handler);
  if(handles==null||handles.length!=1||handles[0]==null)return;
  s.simulator=contract.modern?contract.simulatorField.get(handles[0]):contract.simulatorGetter.invoke(handles[0]);
  s.params=contract.modern?contract.paramsField.get(handles[0]):contract.paramsGetter.invoke(handles[0]);
  s.manager=contract.manager.get(handler);
 }
 private static Session owner(Object simulator){
  Session s=current;return s!=null&&s.rendered&&s.simulator==simulator?s:null;
 }
 private static void draw(XC_MethodHook.MethodHookParam p) throws Exception {
  Session s=current;
  if(s==null||s.simulator!=p.thisObject||!s.gesture.claimed||s.candidate==null||s.manager==null)return;
  // The live handoff now owns the simulator transaction and writes its own pose.
  if(contract.modern&&(s.handedOff||p.args[2]!=s.params))return;
  if(!s.handedOff&&!sameDisplay(s)){s.gesture.cancel();finish(s,"display_changed");return;}
  if(!s.rendered&&!Boolean.TRUE.equals(contract.gestureStarted.invoke(s.handler)))return;
  Object target=p.args[1];int task=contract.targetTask.getInt(target);
  Rect bounds=(Rect)contract.targetBounds.get(target);
  if(task!=s.candidate.task||bounds==null||!bounds.equals(new Rect(0,0,s.gesture.width,s.gesture.height))){
   if(!s.gesture.rejected)LauncherSwipeHook.log("corner_rejected reason=source task="+task+" bounds="+bounds+" display="+s.gesture.width+"x"+s.gesture.height);
   if(s.handedOff){restorePanel(s);current=null;}
   else if(s.rendered)finish(s,"source_changed");else{s.gesture.rejected=true;restorePanel(s);}return;
  }
  Object controller=controller(s);
  if(controller==null)return;
  if(s.controller!=null&&s.controller!=controller){restorePanel(s);current=null;return;}
  s.controller=controller;
  s.matrix.setScale(s.gesture.scale(),s.gesture.scale());s.matrix.postTranslate(s.gesture.left,s.gesture.top);
  Object builder=p.args[0];
  contract.matrix.invoke(builder,s.matrix);contract.clip.invoke(builder,new Rect(0,0,s.gesture.width,s.gesture.height));
  contract.corners.invoke(builder,s.gesture.radius(),2.7f);
  // The accepted handoff may already have veiled this source. Keep its final geometry,
  // but never undo the cover's alpha transaction while waiting for Recents to finish.
  if(!s.handedOff)contract.alpha.invoke(builder,1f);
  // Keep release geometry identical to the transaction just submitted, including full crop.
  if(!contract.modern)((Matrix)contract.sourceMatrix.get(p.thisObject)).set(s.matrix);
  if(!s.rendered)LauncherSwipeHook.log("corner_render task="+task+" rotation="+s.down.rotation+" bounds="+bounds);
  s.rendered=true;s.renderedBox.set(s.gesture.left,s.gesture.top,s.gesture.right,s.gesture.bottom);
  s.renderedRadius=s.gesture.radius()*s.gesture.scale();
  s.motion.sample(task,SystemClock.uptimeMillis(),new float[]{s.gesture.left,s.gesture.top,s.gesture.right,s.gesture.bottom});
 }
 private static Object controller(Session s) throws Exception {
  return contract.modern?contract.active.get(s.manager):contract.controllerGetter.invoke(s.manager);
 }
 static boolean owns(Object handler){Session s=current;return s!=null&&s.handler==handler&&s.rendered&&!s.handedOff;}
 static boolean tracking(Object handler){Session s=current;return s!=null&&s.handler==handler&&s.gesture.claimed&&!s.gesture.cancelled&&!s.gesture.rejected;}
 static boolean ready(Object handler){return owns(handler)&&current.gesture.ready()&&current.revision==revision&&!current.rebounding&&sameDisplay(current);}
 static RectF releaseBox(Object handler){
  if(!owns(handler))return null;
  return new RectF(current.renderedBox);
 }
 static float releaseRadius(Object handler){return owns(handler)?current.renderedRadius:0;}
 static void rebound(Object handler){if(owns(handler))rebound(current);}
 static int rotation(Object handler){return owns(handler)?current.down.rotation:-1;}
 static int task(Object handler){return owns(handler)?current.candidate.task:-1;}
 static SwipeCandidate.Match candidate(Object handler){return owns(handler)?current.candidate:null;}
 static float[] velocity(Object handler,long now){return owns(handler)?current.motion.velocity(current.candidate.task,now):null;}
 static boolean hidePanel(View panel){Session s=current;return s!=null&&s.panel==panel&&s.hidden&&!s.handedOff;}
 static void handedOff(Object handler,long deadline){if(owns(handler)){
  Session s=current;s.handedOff=true;
  MAIN.post(new Runnable(){public void run(){
   if(current!=s||!s.handedOff)return;
   try{if(controller(s)!=s.controller
      ||SystemClock.uptimeMillis()>deadline){restorePanel(s);current=null;return;}}
   catch(Throwable e){restorePanel(s);current=null;return;}
   MAIN.postDelayed(this,16);
  }});
 }}
 /** A capture failure before Recents release still owns the original task pose. */
 static boolean cancelHandoff(Object handler){
  Session s=current;if(s==null||s.handler!=handler||!s.handedOff)return false;
  s.handedOff=false;rebound(s);return true;
 }
 static void afterRelease(XC_MethodHook.MethodHookParam p){
  if(owns(p.args[0])){rebound(current);p.setResult(null);}
 }
 static boolean endTarget(Object handler){if(!owns(handler))return false;current.gesture.cancel();rebound(current);return true;}
 private static void rebound(Session s){
  if(s.rebounding||s.handedOff)return;
  s.rebounding=true;s.gesture.cancel();int bottom=s.gesture.bottom;long began=SystemClock.uptimeMillis();
  Log.i("WindowDeck","corner_rebound task="+s.candidate.task);
  MAIN.post(new Runnable(){public void run(){
   if(current!=s||s.handedOff)return;
   try{
    if(!sameDisplay(s)){finish(s,"display_changed");return;}
    if(controller(s)!=s.controller){restorePanel(s);current=null;return;}
    long elapsed=SystemClock.uptimeMillis()-began;s.gesture.rebound(bottom,elapsed);if(contract.modern)contract.apply.invoke(s.simulator,s.params,false);else contract.apply.invoke(s.simulator,s.params);
    if(s.gesture.reboundDone(bottom,elapsed)||elapsed>=1500){finish(s,"rebounded");return;}
    if(s.panel!=null)s.panel.postOnAnimation(this);else MAIN.postDelayed(this,16);
   }catch(Throwable e){Log.w("WindowDeck","corner_rebound_failed",e);finish(s,"rebound_failed");}
  }});
 }
 private static void finish(Session s,String reason){
  if(!contract.modern&&s.rendered&&!s.handedOff)LauncherSwipeHook.claimCornerReturn(s.handler);
  try{if(s.controller!=null&&s.manager!=null&&controller(s)==s.controller){
   if(contract.modern){
    contract.finishDirection.invoke(s.controller,false);
    contract.finish.invoke(s.manager,false);
   }else{
    XposedHelpers.callMethod(s.controller,RomSymbols.RECENTS_WILL_FINISH_TO_HOME,false);
    XposedHelpers.callMethod(s.manager,RomSymbols.TASK_ANIM_FINISH_RECENTS,false);
   }}
  }catch(Throwable e){Log.w("WindowDeck","corner_finish_failed",e);}
  restorePanel(s);if(current==s)current=null;CandidateProbe.kick();Log.i("WindowDeck","corner_finished reason="+reason);
 }
 private static void restorePanel(Session s){if(s.hidden&&s.panel!=null){s.panel.setAlpha(s.panelAlpha);s.hidden=false;}}
 private static void fail(String phase,Throwable e){Log.w("WindowDeck","corner_"+phase+"_failed",e);if(current!=null){current.gesture.cancel();if(current.rendered)finish(current,"hook_failure");else{restorePanel(current);current=null;}}}
 private CornerSwipeHook(){}
}
