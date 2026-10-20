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

/** Owns fullscreen state for the workbench. The activity is only the nested Host. */
final class FullscreenController {
 interface Host {
  Button addCard();
  void apply(WorkbenchSlot s,int[] r);
  void cancelAnimation();
  void cancelAnimation(boolean preservePose);
  void cancelDrag();
  void cancelSwitchPreparation(String reason);
  void clearRecoveryCovers();
  void closeEntranceCover(String why);
  boolean closing();
  Handler contentWorker();
  Context context();
  CardMotion.Pose currentPose(WorkbenchSlot s);
  void detachCaption();
  void dismissPrimaryMenu();
  void finishAndRemoveTask();
  boolean finishing();
  void poseCard(WorkbenchSlot s,CardMotion.Pose pose,CardMotion.Pose target);
  int[] presentationSize(WorkbenchSlot s);
  void publishState(int count);
  void publishState(int count,int generation,int attempt);
  void releaseSlot(WorkbenchSlot s);
  void releaseSlot(WorkbenchSlot s,boolean toFront);
  void releaseSlot(WorkbenchSlot s,boolean toFront,boolean retainPlate);
  void releaseWindows();
  void setClosing(boolean value);
  CanvasStage stage();
  void startActivity(Intent intent);
  void startActivity(Intent intent,Bundle options);
  TextView status();
  void stopIme();
  boolean stopped();
  void submitMotionFit(SurfaceControl.Transaction fit);
  int taskId();
  TaskIdentityController.RotationRun taskRotationRun();
  WindowManager windowManager();
  boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 FullscreenController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 FullscreenRun fullscreenRun;
 FullscreenRotationBridge rotatingHandoff;

 static final class FullscreenRun {
  final WorkbenchSlot main;final int task;final FullscreenMotion motion;
  ValueAnimator animator;ViewTreeObserver.OnPreDrawListener fitListener;Runnable timeout;
  final FullscreenLifecycle lifecycle=new FullscreenLifecycle();
  int frames,fitFailures;long lastFrame,maxFrameGap;
  FullscreenRun(WorkbenchSlot main,FullscreenMotion motion){this.main=main;task=main.taskId;this.motion=motion;}
 }


 boolean writeFullscreenSideHidden(WorkbenchSlot s,SurfaceControl.Transaction t){
  if(s.released||!s.embedded||!(s.surface instanceof SurfaceView))return false;
  SurfaceControl plate=((SurfaceView)s.surface).getSurfaceControl();
  if(plate==null||!plate.isValid())return false;
  // Hide our plate, not the task leash: a detached task must retain its own alpha.
  t.setAlpha(plate,0f);return true;
 }
 void hideFullscreenSides(WorkbenchSlot main){
  for(WorkbenchSlot s:session.slots)if(s!=main&&!s.released){
   s.fullscreenHidden=true;
   // Keep the SurfaceView attached until normal handoff; INVISIBLE would destroy it.
   if(s.card!=null){s.card.setAlpha(0f);s.card.preview=true;s.card.gesturesEnabled=false;}
  }
  try(SurfaceControl.Transaction hide=new SurfaceControl.Transaction()){
   int hidden=0;for(WorkbenchSlot s:session.slots)if(s.fullscreenHidden&&writeFullscreenSideHidden(s,hide))hidden++;
   // This draw also contains the main source pose. Its existing frame-commit
   // barrier starts expansion only after the side plates and chrome disappear.
   try{host.submitMotionFit(hide);}
   catch(Throwable e){hide.apply();Log.w(TAG,"fullscreen_sides_hide_immediate",e);}
   host.stage().invalidate();Log.i(TAG,"fullscreen_sides_hide_submitted main="+main.taskId+" plates="+hidden);
  }catch(Throwable e){Log.w(TAG,"fullscreen_sides_hide_failed",e);}
 }
 void exitToFullscreen(){
  if(host.closing()||session.slots.isEmpty())return;
  WorkbenchSlot main=session.slots.get(session.primary);
  if(host.taskRotationRun()!=null||main.taskId<0){Toast.makeText(host.context(),"请等待应用窗口就绪",Toast.LENGTH_SHORT).show();return;}
  host.dismissPrimaryMenu();host.detachCaption();
  host.cancelSwitchPreparation("fullscreen");host.cancelDrag();host.cancelAnimation(true);
  CardMotion.Pose source=host.currentPose(main);FullscreenMotion motion=null;
  try{
   int[] origin=new int[2];host.stage().getLocationOnScreen(origin);
   Rect bounds=host.windowManager().getMaximumWindowMetrics().getBounds();int[] buffer=host.presentationSize(main);
   motion=new FullscreenMotion(source,new float[]{bounds.left-origin[0],bounds.top-origin[1],bounds.width(),bounds.height()},buffer[0],buffer[1]);
  }catch(Throwable e){Log.w(TAG,"fullscreen_animation_degraded reason=source_geometry task="+main.taskId,e);}
  host.stopIme();host.setClosing(true);host.publishState(0);host.clearRecoveryCovers();host.closeEntranceCover("exit_fullscreen");handler.removeCallbacksAndMessages(null);
  hideFullscreenSides(main);
  if(motion==null){handoffFullscreen(main,false,"source_unavailable");return;}
  FullscreenRun run=new FullscreenRun(main,motion);fullscreenRun=run;
  try{prepareFullscreen(run);}catch(Throwable e){Log.w(TAG,"fullscreen_prepare_failed task="+run.task,e);finishFullscreen(run,"prepare_failed");}
 }
 void prepareFullscreen(FullscreenRun run){
  WorkbenchSlot main=run.main;CardMotion.Pose source=run.motion.from;
  run.timeout=()->finishFullscreen(run,"commit_timeout");handler.postDelayed(run.timeout,MotionSpec.FULLSCREEN_MS+MotionSpec.ARRIVAL_UNLOCK_MS);
  for(WorkbenchSlot s:session.slots){s.card.preview=true;s.card.gesturesEnabled=false;try{rom.interceptInput.setBoolean(s.surface,true);}catch(Throwable e){Log.w(TAG,"fullscreen_input_block_failed",e);}}
  // Use one fixed full-screen plate, so the task buffer is not resized every frame.
  // The same draw submits its source pose and the newly measured SurfaceView.
  for(ViewParent p=host.stage();p instanceof ViewGroup;p=p.getParent()){ViewGroup group=(ViewGroup)p;group.setClipChildren(false);group.setClipToPadding(false);}
  main.motionPose=source;float[] target=run.motion.to.box;
  host.apply(main,new int[]{Math.round(target[0]),Math.round(target[1]),Math.round(target[2]),Math.round(target[3])});
  host.poseCard(main,source,run.motion.to);main.card.bringToFront();host.addCard().setVisibility(View.GONE);host.status().setVisibility(View.GONE);
  run.animator=ValueAnimator.ofFloat(0,1);run.animator.setDuration(MotionSpec.FULLSCREEN_MS);run.animator.setInterpolator(EntranceMotion::c17Fraction);
  run.animator.addUpdateListener(a->{
   if(fullscreenRun!=run||run.lifecycle.closed())return;
   long now=SystemClock.uptimeMillis();if(run.lastFrame!=0)run.maxFrameGap=Math.max(run.maxFrameGap,now-run.lastFrame);run.lastFrame=now;run.frames++;
   host.poseCard(main,run.motion.frame((Float)a.getAnimatedValue()),run.motion.to);host.stage().invalidate();
  });
  run.animator.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){
   if(fullscreenRun!=run||!run.lifecycle.animationEnded())return;host.poseCard(main,run.motion.to,run.motion.to);host.stage().invalidate();
  }});
  run.fitListener=()->{fitFullscreenFrame(run);return true;};host.stage().getViewTreeObserver().addOnPreDrawListener(run.fitListener);
  host.stage().invalidate();
 }
 void fitFullscreenFrame(FullscreenRun run){
  if(fullscreenRun!=run||run.lifecycle.closed()||host.stopped()||host.finishing())return;
  if(run.main.surface.getWidth()!=Math.round(run.motion.to.box[2])||run.main.surface.getHeight()!=Math.round(run.motion.to.box[3]))return;
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   if(!host.writeLeashFit(run.main,fit)){run.fitFailures++;return;}
   final int attempt=run.lifecycle.requestCommit();
   if(attempt!=0){
    fit.addTransactionCommittedListener(Runnable::run,()->handler.post(()->{
     if(fullscreenRun!=run||host.stopped()||!run.lifecycle.acceptCommit(attempt))return;
     host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->finishFullscreen(run,"frame_committed")));host.stage().invalidate();
    }));
   }
   host.submitMotionFit(fit);
   if(run.lifecycle.requestSource()){
    host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(fullscreenRun!=run||host.stopped()||!run.lifecycle.acceptSource())return;
     Log.i(TAG,"fullscreen_animation_start task="+run.task+" duration_ms="+MotionSpec.FULLSCREEN_MS+" curve=c17_zoom_full_path from="+Arrays.toString(run.motion.from.box)+" to="+Arrays.toString(run.motion.to.box)+" live=true");run.animator.start();
    }));
   }
  }catch(Throwable e){run.lifecycle.rollbackCommit();run.fitFailures++;if(run.fitFailures==1)Log.w(TAG,"fullscreen_frame_fit_failed task="+run.task,e);}
 }
 void clearFullscreenRun(FullscreenRun run){
  if(run.timeout!=null)handler.removeCallbacks(run.timeout);
  if(run.fitListener!=null&&host.stage().getViewTreeObserver().isAlive())host.stage().getViewTreeObserver().removeOnPreDrawListener(run.fitListener);
  if(run.animator!=null)run.animator.cancel();
 }
 void finishFullscreen(FullscreenRun run,String reason){
  if(fullscreenRun!=run||run.lifecycle.closed())return;
  if(host.stopped()||host.finishing()||run.main.released||run.main.taskId!=run.task){abortFullscreen(run,"host_or_task_changed",!host.finishing());return;}
  if(!run.lifecycle.finish())return;clearFullscreenRun(run);
  Log.i(TAG,"fullscreen_animation_result task="+run.task+" result="+(reason.equals("frame_committed")&&run.fitFailures==0?"frame_committed":"degraded")+" reason="+reason+" frames="+run.frames+" max_frame_gap_ms="+run.maxFrameGap+" fit_failures="+run.fitFailures);
  handoffFullscreen(run.main,run.lifecycle.started(),reason);fullscreenRun=null;
 }
 void abortFullscreen(FullscreenRun run,String reason,boolean finishHost){
  if(fullscreenRun!=run||run.lifecycle.closed())return;
  if(!run.lifecycle.abort())return;clearFullscreenRun(run);fullscreenRun=null;
  // An external owner may already have taken this task; never send that owner
  // to the back or overwrite its transform when cancelling our pending exit.
  if(!run.main.embedded)host.releaseSlot(run.main,true,true);
  host.releaseWindows();
  Log.i(TAG,"fullscreen_animation_result task="+run.task+" result=cancelled reason="+reason);
  if(finishHost&&!host.finishing())host.finishAndRemoveTask();
 }
 void handoffFullscreen(WorkbenchSlot main,boolean retainPlate,String reason){
  if(prepareFullscreenRotation(main,retainPlate,reason))return;
  completeFullscreenHandoff(main,retainPlate,reason);
 }
 boolean prepareFullscreenRotation(WorkbenchSlot main,boolean retainPlate,String reason){
  if(rotatingHandoff!=null)return true;
  if(main.orientationAxis!=2||!(host.context() instanceof Activity)||main.surface==null||main.released)return false;
  Activity activity=(Activity)host.context();
  android.util.DisplayMetrics metrics=activity.getResources().getDisplayMetrics();
  if(metrics.widthPixels>=metrics.heightPixels)return false;
  int requested=main.taskOrientation.requested();
  if(requested==-2)try{requested=activity.getPackageManager().getActivityInfo(main.activeComponent==null?main.component:main.activeComponent,0).screenOrientation;}catch(Exception e){return false;}
  if(OrientationPolicy.axis(requested)!=2)return false;
  try{
   FullscreenRotationBridge bridge=new FullscreenRotationBridge(activity,handler,host.contentWorker(),main.taskId,main.renderBounds.width(),main.renderBounds.height(),requested,(finished,ready,why)->{
    if(rotatingHandoff!=finished){finished.close();return;}
    rotatingHandoff=null;
    if(host.stopped()||host.finishing()||main.released){finished.close();host.releaseWindows();if(!host.finishing())host.finishAndRemoveTask();return;}
    if(!ready)finished.close();
    // A ready picture remains in the host until the original task covers it.
    // Its detach callback recycles it after the host window is removed.
    completeFullscreenHandoff(main,retainPlate,reason+"_rotation_"+(ready?"ready":why));
   });
   rotatingHandoff=bridge;
   bridge.start((SurfaceControl)rom.taskLeash.get(main.surface));
   return true;
  }catch(Throwable e){if(rotatingHandoff!=null)rotatingHandoff.close();rotatingHandoff=null;Log.w(TAG,"fullscreen_rotation_prepare_failed task="+main.taskId,e);return false;}
 }
 void cancelFullscreenHandoff(String reason){
  FullscreenRotationBridge bridge=rotatingHandoff;if(bridge==null)return;
  rotatingHandoff=null;bridge.close();host.releaseWindows();
  Log.i(TAG,"fullscreen_rotation_result result=cancelled reason="+reason);
  if(!host.finishing())host.finishAndRemoveTask();
 }
 void completeFullscreenHandoff(WorkbenchSlot main,boolean retainPlate,String reason){
  // Unlink the main task first, but keep its full-screen source SurfaceView alive
  // until WM reparents that same task to its normal root. No module writer or
  // FlexibleTaskView listener may reset the surface after WM has taken ownership.
  host.releaseSlot(main,true,retainPlate);
  for(WorkbenchSlot s:new ArrayList<>(session.slots))if(s!=main)host.releaseSlot(s);
  boolean launched=bringTaskFullscreen(main.taskId,main.component);
  Log.i(TAG,"workbench_fullscreen task="+main.taskId+" launched="+launched+" container="+host.taskId()+" source_plate_retained="+retainPlate+" reason="+reason);
  try{
   Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
   atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),host.taskId(),true);
  }catch(Throwable e){Log.w(TAG,"container_to_back_failed",e);}
  handler.post(()->{if(!host.finishing())host.finishAndRemoveTask();});
 }
 Bundle fullscreenOptions(){
  ActivityOptions options=ActivityOptions.makeCustomAnimation(host.context(),0,0);
  Bundle extra=new Bundle();
  extra.putInt("androidx.activity.LaunchScenario",0);
  extra.putBoolean("androidx.flexible.activityNoAnimation",true);
  extra.putBoolean("androidx.task.ForceOpaqueSplitTask",true);
  extra.putString("androidx.activity.startReason","startTargetActivityAsFullScreen");
  try{
   // C17 ContainerActivity.u2 uses the vendor extra bundle, not just a zero
   // resource Activity animation: the latter still permits a Shell TO_FRONT.
   Object prepared=rom.managerApi.getMethod("setExtraBundle",ActivityOptions.class,Bundle.class).invoke(rom.manager,options,extra);
   if(!(prepared instanceof Bundle))throw new IllegalStateException("Missing fullscreen options");
   Log.i(TAG,"fullscreen_launch_options protocol=c17 scenario=0 no_animation=true force_opaque=true");
   return (Bundle)prepared;
  }catch(Throwable e){Log.w(TAG,"fullscreen_launch_options_degraded protocol=activity_only",e);return options.toBundle();}
 }
 boolean bringTaskFullscreen(int taskId,ComponentName component){
  Bundle options=fullscreenOptions();
  if(taskId>=0){
   // No ActivityManager.moveTaskToFront here on purpose. uid 10243 holds no REORDER_TASKS, so the
   // call was refused on every attempt (9/9 in logs/logcat_cap2.txt) and only added a Permission
   // Denial before falling through to the branch below. Calling an API that is known to fail
   // cannot make the transition more correct, and it kept the acceptance grep non-zero (TODO B1).
   try{
    Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    Object result=Class.forName("android.app.IActivityTaskManager").getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(service,taskId,options);
    int code=result instanceof Integer?(Integer)result:Integer.MIN_VALUE;
    boolean accepted=TransitionPolicy.startAccepted(code);
    Log.i(TAG,"fullscreen_recents task="+taskId+" result="+result+" accepted="+accepted);
    // Reporting success on the call alone is what made this silent: START_CANCELED (-96) and
    // START_NOT_ACTIVITY (-95) fell outside the band but were swallowed by a hard-coded
    // return true (TODO B1-1). Anything rejected now falls through to the intent fallback.
    if(accepted)return true;
   }catch(Throwable e){Log.w(TAG,"fullscreen_recents_failed",e);}
  }
  if(component==null)return false;
  try{
   host.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_NO_ANIMATION),options);
   Log.i(TAG,"fullscreen_intent component="+component.flattenToShortString());
   return true;
  }catch(Throwable e){Log.w(TAG,"fullscreen_intent_failed",e);return false;}
 }
}
