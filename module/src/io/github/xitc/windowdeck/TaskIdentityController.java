package io.github.xitc.windowdeck;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.window.OnBackAnimationCallback;
import android.window.BackEvent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.window.OnBackInvokedDispatcher;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Owns identity state for the workbench. The activity is only the nested Host. */
final class TaskIdentityController {
 interface Host {
  ValueAnimator addAnimation();
  boolean backgrounded();
  boolean closing();
  Context context();
  CardMotion.Pose currentPose(WorkbenchSlot s);
  WorkbenchSlot dragging();
  boolean initialized();
  void inputRole(WorkbenchSlot s,boolean isPrimary);
  boolean layoutPosted();
  PackageManager packageManager();
  String packageName();
  WorkbenchSlot pendingEntrance();
  boolean poseHeld();
  boolean recovering();
  Resources resources();
  void scheduleLayout();
  void scheduleLayoutEvidence(String reason);
  boolean screenArrival();
  CanvasStage stage();
  boolean stopped();
  void submitMotionFit(SurfaceControl.Transaction fit);
  boolean switching();
  void syncSurface(WorkbenchSlot s);
  Rect taskBuffer(WorkbenchSlot slot);
  void updateRenderBounds(WorkbenchSlot s);
  boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t);
  void runOnUiThread(Runnable action);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final LiveContentPump pump;
 final Host host;
 TaskIdentityController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom,LiveContentPump pump){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;this.pump=pump;
 }

 private static final String TAG="WindowDeck";

 TaskOrientationObserver orientationObserver;
 SystemRotationObserver systemRotationObserver;
 boolean directionPosted,immersiveForeground;
 final Runnable immersivePoll=this::pollImmersiveDirection;
 final Runnable applyDirections=this::applyTaskDirections;
 RotationRun taskRotationRun;
 int rotationGeneration;
 RotationTransactions.Host rotationTransactions;
 final RotationSubmission<WorkbenchSlot> rotationSubmission=new RotationSubmission<WorkbenchSlot>();

 void recordRotationSubmission(RotationRun run){if(run==null)return;rotationSubmission.record(run.changingLayout,run.task,run.generation,run.slot);}
 int rotationSubmitTask(){return rotationSubmission.task();}
 int rotationSubmitGeneration(){return rotationSubmission.generation();}
 WorkbenchSlot rotationSubmitSlot(){return rotationSubmission.slot();}

 static final class RotationRun {
  final WorkbenchSlot slot;final int task,axis,turn,layer,width,height;
  int generation,lastLoggedStep=-1;
  NativeTaskRotation nativeMotion;ValueAnimator animator;
  float fraction;boolean changingLayout,started,probing,commitLogged;
  RotationRun(WorkbenchSlot s,int next,int layer){this.slot=s;task=s.taskId;axis=next;turn=next==2?1:-1;this.layer=layer;width=s.renderBounds.width();height=s.renderBounds.height();}
 }

 static final class ResolvedRotation {
  final RotationIdentity identity; final int manifestAxis;
  ResolvedRotation(RotationIdentity identity,int manifestAxis){this.identity=identity;this.manifestAxis=manifestAxis;}
 }


 WorkbenchSlot validate(ComponentName c,WorkbenchSlot replacing) throws Exception {
  if(c==null||c.getPackageName().equals(host.packageName())||ModulePackages.isModule(c.getPackageName()))throw new IllegalArgumentException("应用参数无效");
  for(WorkbenchSlot s:session.slots)if(s!=replacing&&s.component.getPackageName().equals(c.getPackageName()))throw new IllegalArgumentException("该应用已在工作台中");
  ActivityInfo ai=host.packageManager().getActivityInfo(c,0);
  Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c);
  if(!Boolean.TRUE.equals(rom.canvas.managerMethod("isAppSupportPocketStudio").invoke(rom.manager,intent,-1)))throw new IllegalArgumentException(ai.loadLabel(host.packageManager())+" 不支持此窗口模式");
  WorkbenchSlot slot=new WorkbenchSlot(session.nextId++, c,ai.loadLabel(host.packageManager()).toString());slot.activeComponent=c;slot.orientationAxis=OrientationPolicy.axis(ai.screenOrientation);Log.i(TAG,"app_orientation component="+c.flattenToShortString()+" requested="+ai.screenOrientation+" axis="+slot.orientationAxis);return slot;
 }
 ActivityManager.RunningTaskInfo runningTask(int id) throws Exception {
  java.util.List<?> tasks=rom.canvas.tasks();
  for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;if(task.taskId==id)return task;}
  return null;
 }
 WorkbenchSlot validateSourceTask(int id,int user) throws Exception {
   ActivityManager.RunningTaskInfo task=runningTask(id);
   int taskUser=task==null?-1:task.getClass().getField("userId").getInt(task);
   if(task==null||taskUser!=user||taskUser!=android.os.Process.myUid()/100000||task.baseActivity==null)throw new IllegalArgumentException("任务身份或用户不匹配");
   RotationIdentity identity=readIdentity(task);
   if("cross_package".equals(identity.unavailable)){Log.i(TAG,"slot=- task="+id+" "+identity.record());throw new IllegalArgumentException("任务身份或用户不匹配");}
   if(((Integer)task.getClass().getMethod("getWindowingMode").invoke(task))!=1)throw new IllegalArgumentException("仅接收全屏任务");
   String pkg=task.baseActivity.getPackageName();
   Intent launcher=host.packageManager().getLaunchIntentForPackage(pkg);
   if(launcher==null||launcher.getComponent()==null)throw new IllegalArgumentException("任务应用没有桌面入口");
   if(!Boolean.TRUE.equals(rom.canvas.managerMethod("isAppSupportPocketStudio").invoke(rom.manager,launcher,id)))throw new IllegalArgumentException("任务不支持嵌入");
   WorkbenchSlot fresh=validate(launcher.getComponent(),null);fresh.sourceTaskId=id;fresh.sourceUserId=user;
   ResolvedRotation resolved=resolveRotation(identity);
   if(resolved.identity.rotationClass==null){fresh.activeComponent=null;fresh.orientationAxis=0;}
   else{
    ComponentName chosen=ComponentName.unflattenFromString(resolved.identity.rotationClass);
    if(chosen!=null)fresh.activeComponent=chosen;
    fresh.orientationAxis=resolved.manifestAxis;
   }
   if(resolved.identity.unavailable!=null)Log.i(TAG,"slot="+fresh.id+" task="+id+" "+resolved.identity.record());
   return fresh;
 }
 void onTaskOrientationRequested(int task,int requested){
  if(host.closing())return;
  for(WorkbenchSlot s:session.slots)if(!s.released&&s.taskId==task){
   applyRotationIdentity(s,task,null,true);
   if(s.taskOrientation.request(task,requested)){
    Log.i(TAG,"task_orientation_requested slot="+s.id+" task="+task+" requested="+requested+" axis="+s.taskOrientation.axis()+" "+s.taskOrientation.record());
    applyTaskDirections();
   }
   break;
  }
 }
 ResolvedRotation resolveRotation(RotationIdentity identity){
  if(identity.rotationClass==null)return new ResolvedRotation(identity,0);
  ComponentName component=ComponentName.unflattenFromString(identity.rotationClass);
  if(component==null)return new ResolvedRotation(identity.nameNotFound(),0);
  try{return new ResolvedRotation(identity,OrientationPolicy.axis(host.packageManager().getActivityInfo(component,0).screenOrientation));}
  catch(PackageManager.NameNotFoundException ignored){return new ResolvedRotation(identity.nameNotFound(),0);}
 }
 void applyRotationIdentity(WorkbenchSlot slot,int taskId,ActivityManager.RunningTaskInfo snapshot,boolean reread){
  if(slot==null||taskId<0||(slot.taskId>=0&&slot.taskId!=taskId))return;
  RotationIdentity identity=reread?readTaskIdentity(taskId,snapshot):snapshot==null?null:readIdentity(snapshot);
  if(identity!=null)commitRotation(slot,taskId,identity);
 }
 void commitRotation(WorkbenchSlot slot,int taskId,RotationIdentity identity){
  int manifestAxis=0;
  if(identity.rotationClass!=null){ResolvedRotation resolved=resolveRotation(identity);identity=resolved.identity;manifestAxis=resolved.manifestAxis;}
  if(!slot.taskOrientation.needsRebind(taskId,identity))return;
  slot.taskOrientation.bind(taskId,identity,manifestAxis);
  slot.immersiveOrientation.bind(taskId,identity.rotationClass,"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility()));
  slot.activeComponent=identity.rotationClass==null?null:ComponentName.unflattenFromString(identity.rotationClass);
  Log.i(TAG,"slot="+slot.id+" task="+taskId+" "+identity.record());
 }
 RotationIdentity readTaskIdentity(int taskId,ActivityManager.RunningTaskInfo fallback){
  try{ActivityManager.RunningTaskInfo fresh=runningTask(taskId);if(fresh!=null)return readIdentity(fresh);}
  catch(Throwable e){Log.w(TAG,"rotation_activity_read_failed task="+taskId,e);}
  if(fallback!=null&&fallback.taskId==taskId)return readIdentity(fallback);
  return null;
 }
 RotationIdentity readIdentity(ActivityManager.RunningTaskInfo task){
  if(task==null)return RotationIdentity.choose(null,null,null,null,null);
  String base=task.baseActivity==null?null:task.baseActivity.getPackageName();
  return RotationIdentity.choose(flatten(task.topActivity),flatten(task.origActivity),flatten(componentField(task,"realActivity")),activityInfoComponent(task),base);
 }
 static String flatten(ComponentName component){return component==null?null:component.flattenToString();}
 static ComponentName componentField(Object task,String name){
  try{Object value=task.getClass().getField(name).get(task);return value instanceof ComponentName?(ComponentName)value:null;}
  catch(Throwable ignored){return null;}
 }
 static String activityInfoComponent(Object task){
  try{
   Object info=task.getClass().getField("topActivityInfo").get(task);
   if(info==null)return null;
   String pkg=(String)info.getClass().getField("packageName").get(info);
   String name=(String)info.getClass().getField("name").get(info);
   if(pkg==null||pkg.isEmpty()||name==null||name.isEmpty())return null;
   if(name.startsWith("."))name=pkg+name;
   return pkg+"/"+name;
  }catch(Throwable ignored){return null;}
 }
 void updateDirectionEnvironment(WorkbenchSlot slot){
  SystemRotationObserver observer=systemRotationObserver;
  slot.taskOrientation.environment(observer==null?-1:observer.proposed,observer!=null&&observer.settingsKnown,observer!=null&&observer.locked,observer==null?-1:observer.user);
 }
 void pollImmersiveDirection(){
  handler.removeCallbacks(immersivePoll);
  if(host.closing()||host.stopped()||host.backgrounded()||!immersiveForeground||!host.initialized())return;
  boolean eligible=false;
  for(WorkbenchSlot slot:session.slots)if(!slot.released&&slot.taskId>=0&&"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility()))eligible=true;
  if(!eligible)return;
  try{
   java.util.List<?> tasks=rom.canvas.tasks();
   for(Object value:tasks){ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)value;
    for(WorkbenchSlot slot:session.slots)if(!slot.released&&slot.taskId==info.taskId&&"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility())){
     applyRotationIdentity(slot,info.taskId,info,false);
     String identity=slot.taskOrientation.rotationClass();
     if(!"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility())||identity==null){
      if(slot.immersiveOrientation.clear()){slot.taskOrientation.immersive(false);queueTaskDirections();}
      continue;
     }
     int types=info.getClass().getField("requestedVisibleTypes").getInt(info);
     if(slot.lastRequestedVisibleTypes!=types){slot.lastRequestedVisibleTypes=types;Log.i(TAG,"youtube_system_bars task="+info.taskId+" component="+identity+" requested_visible_types="+types);}
     if(slot.immersiveOrientation.observe(info.taskId,identity,types,SystemClock.uptimeMillis())){
      slot.taskOrientation.immersive(slot.immersiveOrientation.landscape());
      Log.i(TAG,"youtube_immersive task="+info.taskId+" component="+identity+" requested_visible_types="+types+" landscape="+slot.immersiveOrientation.landscape());
      queueTaskDirections();
     }
    }
   }
  }catch(Throwable e){
   for(WorkbenchSlot slot:session.slots)if(slot.immersiveOrientation.clear())slot.taskOrientation.immersive(false);
   queueTaskDirections();Log.w(TAG,"youtube_immersive_probe failed",e);return;
  }
  handler.postDelayed(immersivePoll,350);
 }
 void queueTaskDirections(){
  if(host.closing()||host.stage()==null||directionPosted)return;
  directionPosted=true;handler.post(applyDirections);
 }
 void applyTaskDirections(){
  handler.removeCallbacks(applyDirections);directionPosted=false;if(host.closing()||host.stopped()||host.backgrounded()||!host.initialized())return;
  // Keep the latest request while a transition owns geometry and input.
  if(taskRotationRun!=null||host.pendingEntrance()!=null||host.screenArrival()||host.poseHeld()||host.recovering()||host.switching()||host.addAnimation()!=null||host.dragging()!=null){
   directionPosted=true;handler.postDelayed(applyDirections,50);return;
  }
  boolean changed=false;
  for(WorkbenchSlot s:session.slots)if(!s.released){updateDirectionEnvironment(s);int next=s.taskOrientation.axis();if(s.orientationAxis!=next){if(beginTaskRotation(s,next))return;s.orientationAxis=next;s.fittedPose=null;changed=true;}}
  if(changed)host.scheduleLayout();else host.scheduleLayoutEvidence("task_orientation");
 }
 boolean beginTaskRotation(WorkbenchSlot s,int axis){
  android.util.DisplayMetrics dm=host.resources().getDisplayMetrics();
  int visual=OrientationPolicy.committedAxis(s.orientationAxis,s.renderBounds.width(),s.renderBounds.height(),dm.widthPixels,dm.heightPixels);
  // Axis 0 is still an unknown request. The committed plate only supplies the start pose.
  if((visual!=1&&visual!=2)||(axis!=1&&axis!=2)||visual==axis||s.surface==null||!s.embedded)return false;
  CardMotion.Pose from=host.currentPose(s);if(from==null)return false;
  try{
   SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface);
   int layer=LiveTaskContent.layerId(leash);if(layer<0)return false;
   int plateW=s.surface.getWidth(),plateH=s.surface.getHeight();if(plateW<2||plateH<2)return false;
   if(rotationTransactions==null||!rotationTransactions.available()){
    Log.w(TAG,"task_rotation_unavailable task="+s.taskId+" reason=permitted_submitter_missing");return false;
   }
   RotationRun run=new RotationRun(s,axis,layer);
   run.generation=++rotationGeneration;
   taskRotationRun=run;
   for(int i=0;i<session.slots.size();i++)host.inputRole(session.slots.get(i),i==session.primary);
   Log.i(TAG,"task_rotation_prepare task="+run.task+" from_axis="+s.orientationAxis+" visual_axis="+visual+" to_axis="+axis+" layer="+layer+" generation="+run.generation);

   // The app may have reconfigured before this callback. Capturing now and
   // showing that frame later would cover the new player with an old page.
   // Until a true pre-change frame is available, only animate the live enter.
   applyRotationLayout(run);
   handler.postDelayed(()->{if(taskRotationRun==run&&!run.started)finishTaskRotation(run,"prepare_timeout");},RotationMotion.TIMEOUT_MS);
   return true;
  }catch(Throwable e){if(taskRotationRun!=null)finishTaskRotation(taskRotationRun,"prepare_failed");Log.w(TAG,"task_rotation_prepare_failed",e);return false;}
 }
 boolean rotationCurrent(RotationRun run){
  if(taskRotationRun!=run||host.closing()||host.stopped()||host.backgrounded()||host.stage()==null||!host.stage().isAttachedToWindow()||run.slot.released||run.slot.surface==null||run.slot.taskId!=run.task||!session.slots.contains(run.slot))return false;
  try{SurfaceView view=(SurfaceView)run.slot.surface;return view.getHolder().getSurface().isValid()&&LiveTaskContent.layerId(view.getSurfaceControl())>=0&&run.layer==LiveTaskContent.layerId((SurfaceControl)rom.taskLeash.get(view));}catch(Throwable e){return false;}
 }
 void applyRotationLayout(RotationRun run){
  if(!rotationCurrent(run)||run.changingLayout)return;
  run.changingLayout=true;run.slot.orientationAxis=run.axis;run.slot.fittedPose=null;
  // A native reparent may be intercepted before the queued layout traversal.
  // Its first fit must already sample the animation in the NEW buffer space.
  host.updateRenderBounds(run.slot);
  host.scheduleLayout();host.stage().postOnAnimation(()->startTaskRotation(run));
 }
 void ensureRotationMotion(RotationRun run){
  int w=run.slot.renderBounds.width(),h=run.slot.renderBounds.height();
  if(run.nativeMotion==null||run.nativeMotion.width!=w||run.nativeMotion.height!=h)
   run.nativeMotion=new NativeTaskRotation(host.context(),run.turn,w,h,run.width,run.height);
 }
 void startTaskRotation(RotationRun run){
  if(!rotationCurrent(run)){if(taskRotationRun==run)finishTaskRotation(run,"invalidated");return;}
  if(host.layoutPosted()||host.stage().isLayoutRequested()||run.slot.surface.isLayoutRequested()){host.stage().postOnAnimation(()->startTaskRotation(run));return;}
  WorkbenchSlot s=run.slot;
  ensureRotationMotion(run);
  long duration=run.nativeMotion.duration;
  ValueAnimator animator=ValueAnimator.ofFloat(0,1);run.animator=animator;run.started=true;animator.setDuration(duration);
  // The resource AnimationSet owns each child's curve. This clock stays linear.
  animator.setInterpolator(new android.view.animation.LinearInterpolator());
  // ValueAnimator already applies the global animator scale. Do not scale twice
  // or let the watchdog cut a deliberately slow developer animation short.
  long timeout=Math.max(RotationMotion.TIMEOUT_MS,Math.round(duration*ValueAnimator.getDurationScale())+1000);
  handler.postDelayed(()->{if(taskRotationRun==run)finishTaskRotation(run,"timeout");},timeout);
  animator.addUpdateListener(a->{
   if(!rotationCurrent(run)){finishTaskRotation(run,"invalidated");return;}
   run.fraction=(Float)a.getAnimatedValue();
   try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){if(!host.writeLeashFit(s,t)){finishTaskRotation(run,"fit_failed");return;}host.submitMotionFit(t);host.stage().invalidate();}
   catch(Throwable e){Log.w(TAG,"task_rotation_frame_failed",e);finishTaskRotation(run,"fit_failed");}
  });
  animator.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){if(taskRotationRun==run&&run.animator==a){run.fraction=1;commitTaskRotation(run);}}});
  Log.i(TAG,"task_rotation_start task="+run.task+" generation="+run.generation+" duration_ms="+duration+" animation="+run.nativeMotion.source+" exit=none reason=no_prechange_frame");animator.start();
 }
 void commitTaskRotation(RotationRun run){
  if(!rotationCurrent(run)){if(taskRotationRun==run)finishTaskRotation(run,"invalidated");return;}
  if(!run.commitLogged){run.commitLogged=true;Log.i(TAG,"task_rotation_commit task="+run.task+" generation="+run.generation+" render="+run.slot.renderBounds+" actual="+host.taskBuffer(run.slot));}
  WorkbenchSlot s=run.slot;
  if(!run.probing){
   // The new task configuration and child pixels must exist; target geometry alone
   // does not establish a rendered video frame.
   if(!s.renderBounds.equals(host.taskBuffer(s))){host.stage().postOnAnimation(()->commitTaskRotation(run));return;}
   final SurfaceControl copy;
   try{copy=(SurfaceControl)de.robv.android.xposed.XposedHelpers.newInstance(SurfaceControl.class,rom.taskLeash.get(s.surface),"WindowDeckRotationReady");}
   catch(Throwable e){finishTaskRotation(run,"probe_failed");return;}
   run.probing=true;
   final int width=s.renderBounds.width(),height=s.renderBounds.height();
   pump.worker().post(()->{
    boolean ready=false;try{ready=LiveTaskContent.presented(copy,width,height);}catch(Throwable e){Log.w(TAG,"task_rotation_probe_failed",e);}finally{copy.release();}
    final boolean shown=ready;handler.post(()->{
     if(!rotationCurrent(run)){if(taskRotationRun==run)finishTaskRotation(run,"invalidated");return;}
     run.probing=false;if(!shown){handler.postDelayed(()->commitTaskRotation(run),50);return;}
     try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
      if(!host.writeLeashFit(s,t)){finishTaskRotation(run,"fit_failed");return;}
      t.addTransactionCommittedListener(host::runOnUiThread,()->{
       if(!rotationCurrent(run))return;
       host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{if(rotationCurrent(run))finishTaskRotation(run,"completed");}));host.stage().invalidate();
      });host.submitMotionFit(t);host.stage().invalidate();
     }catch(Throwable e){Log.w(TAG,"task_rotation_commit_failed",e);finishTaskRotation(run,"commit_failed");}
    });
   });
  }
 }
 void finishTaskRotation(RotationRun run,String result){
  if(taskRotationRun!=run)return;taskRotationRun=null;
  if(rotationGeneration==run.generation)rotationGeneration++;
  ValueAnimator old=run.animator;run.animator=null;if(old!=null)old.cancel();
  if(!host.closing()&&!host.stopped()&&!host.backgrounded()&&!run.slot.released&&run.slot.surface!=null)host.syncSurface(run.slot);
  for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).surface!=null&&session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);
  Log.i(TAG,"task_rotation_result task="+run.task+" generation="+run.generation+" fraction="+run.fraction+" result="+result);
  if(!host.closing()&&!host.stopped()&&!host.backgrounded())queueTaskDirections();
 }
 static int taskRotation(ActivityManager.RunningTaskInfo info){
  try{Object config=info.getClass().getMethod("getConfiguration").invoke(info);Object window=config.getClass().getField("windowConfiguration").get(config);return (Integer)window.getClass().getMethod("getRotation").invoke(window);}
  catch(Throwable ignored){return -1;}
 }
}
