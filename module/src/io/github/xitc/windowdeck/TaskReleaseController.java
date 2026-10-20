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

/** Owns release state for the workbench. The activity is only the nested Host. */
final class TaskReleaseController {
 interface Host {
  boolean backgroundWorkbench(String reason);
  boolean backgrounded();
  void cancelAnimation();
  void cancelAnimation(boolean preservePose);
  void cancelDrag();
  void abortSideSort(String reason);
  void cancelSwitchPreparation(String reason);
  int cardRadius(boolean isPrimary);
  void clearProjection(WorkbenchSlot s);
  void clearRecoveryCovers();
  boolean closing();
  boolean detachLeashBeforeTransition();
  void fail(Throwable e);
  void finishTaskRotation(TaskIdentityController.RotationRun run,String result);
  boolean finishing();
  int hangTask();
  void layoutCards(boolean animate);
  void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source);
  boolean recovering();
  Runnable recoveryFinish();
  void releasePendingEntrance(WorkbenchSlot s);
  void removeSlot(WorkbenchSlot s);
  void removeSlot(WorkbenchSlot s,boolean taskGone);
  ReplacementController<WorkbenchSlot> replacements();
  boolean rotatePresentation(WorkbenchSlot s);
  void setRecovering(boolean value);
  void setRecoveryFinish(Runnable value);
  CanvasStage stage();
  boolean stopped();
  void syncSurface(WorkbenchSlot s);
  int taskId();
  String taskIds();
  TaskIdentityController.RotationRun taskRotationRun();
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 TaskReleaseController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";


void detachLeashForTransition(WorkbenchSlot s){
  if(s.surface==null||rom.taskLeash==null)return;
  try{
   SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface);
   if(leash==null||!leash.isValid())return;
   try(SurfaceControl.Transaction detach=new SurfaceControl.Transaction()){
    detach.reparent(leash,null);detach.apply();
   }
   Log.i(TAG,"transition_leash_detached slot="+s.id+" task="+s.taskId);
  }catch(Throwable e){Log.w(TAG,"transition_leash_detach_failed slot="+s.id,e);}
}
void releaseSlot(WorkbenchSlot s){releaseSlot(s,false);}
 void releaseSlot(WorkbenchSlot s,boolean toFront){
  releaseSlot(s,toFront,false);
 }
 void releaseSlot(WorkbenchSlot s,boolean toFront,boolean retainPlate){
  host.abortSideSort("task_release");
  if(host.taskRotationRun()!=null&&host.taskRotationRun().slot==s)host.finishTaskRotation(host.taskRotationRun(),"released");
  if(s.released)return;
  s.drawEvidence.invalidate();
  // The retained SurfaceView keeps showing the same task during fullscreen
  // handoff. Remove our projective transform while the leash is still ours;
  // clearing only the Java reference leaves the trapezoid on WM's task layer.
  host.clearProjection(s);
  EmbeddedTaskGuard.forget(s.surface);s.embedded=false;
  if(s.transaction!=null){
   LeashTransactions.unregister(s.transaction);
   try{
    SurfaceControl leash=rom.taskLeash==null?null:(SurfaceControl)rom.taskLeash.get(s.surface);
    if(!retainPlate&&leash!=null&&leash.isValid()){
     if(rom.leashMatrix4!=null)rom.leashMatrix4.invoke(s.transaction,leash,(Object)TaskSurfaceRenderer.identity4());
     if(rom.leashRadius!=null)rom.leashRadius.invoke(s.transaction,leash,0f);
    }
    if(!retainPlate)s.transaction.apply();
   }catch(Throwable e){Log.w(TAG,"leash_reset_failed",e);}
   s.perspectiveApplied=false;s.transaction=null;
  }
  s.released=true;
  if(!host.closing()&&!s.quietDetach)host.setRecovering(true);
  View released=s.surface;
  if(released!=null){
   try{
    // TODO B2-1: during the hand-off the ROM's zoom transition (startZoomWindowFromSplit, the
    // third layer) and our own plate can both hold this task surface, which is what draws the
    // same card in two places for four frames (t=2.54–2.62). Detach ours first so only the ROM
    // is drawing. Behind a switch: it still needs a device pass to prove the task keeps drawing
    // once the plate is gone.
    if(host.detachLeashBeforeTransition()&&toFront&&!retainPlate)detachLeashForTransition(s);
    // Unlink first: moving a still-linked task can background the whole group.
    // The ROM's move-to-back path resets it without the unsafe direct
    // fullscreen transition used by resetFlexibleTask.
    if(s.taskId>=0){
     rom.canvas.viewMethod("interceptBackPressedOnTaskRoot").invoke(released,false);
     rom.canvas.managerMethod("removeEmbeddedContainerTask").invoke(rom.manager,s.taskId,host.taskId());
     if(!toFront){
      Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
      atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),s.taskId,true);
     }
    }
    // moveTaskToBack owns the server-side transition. Do not race it with
    // detachFromTaskView's second resetFlexibleTask / fullscreen transition.
    Method extra=rom.canvas.viewMethod("releaseExtraView");extra.setAccessible(true);extra.invoke(released);
   }catch(Throwable e){Log.w(TAG,"detach_failed slot="+s.id,e);}
   try{rom.canvas.viewMethod("release").invoke(released);}catch(Throwable e){Log.w(TAG,"release_failed slot="+s.id,e);}
   s.surface=null;
  }
  host.releasePendingEntrance(s);
  // The view reports task visibility from its surface-destroyed callback, and it
  // posts that callback to the same executor release() just used to clear the
  // view's own task token. The callback therefore runs with a null token: the
  // server takes its null-token branch, walks the container's embedded children
  // and moves every one of them to back -- the whole workbench drops to the
  // launcher -- and throws inside updateTaskVisibility, taking the pcanvas process
  // with it. EmbeddedTaskGuard.forget() marks this instance before any release,
  // so even onDetachedFromWindow's second release cannot re-enable that callback.
  // Keep the ROM flag as a supplementary guard; it is reset during card removal.
  if(released!=null&&rom.superLocked!=null){
   try{rom.superLocked.setBoolean(released,true);}catch(Throwable e){Log.w(TAG,"super_lock_failed slot="+s.id,e);}
  }
  if(s.card!=null&&!retainPlate){host.stage().removeView(s.card);s.card.resetGesture();}
  if(s.recoveryCover!=null){s.recoveryCover.setBitmap(null);s.recoveryCover=null;}
  if(s.taskId>=0&&!toFront&&!host.closing()&&!s.quietDetach){
   Runnable restore=()->{
    if(host.closing())return;
    for(WorkbenchSlot active:session.slots)if(!active.released&&active.taskId==s.taskId)return;
    try{reorderTask(s.taskId,false);restoreContainerFocus();Log.i(TAG,"detached_task_restored task="+s.taskId);}
    catch(Throwable e){Log.w(TAG,"task_restore_failed id="+s.taskId,e);}
   };
   restore.run();handler.postDelayed(restore,600);
  }
  if(!host.closing()&&!s.quietDetach){
   if(host.recoveryFinish()!=null)handler.removeCallbacks(host.recoveryFinish());
   host.setRecoveryFinish(()->{host.setRecoveryFinish(null);if(host.closing())return;resumeRemainingTasks();
    // Allow resumed task layers to join a submitted frame before uncovering them.
    host.stage().postOnAnimation(()->host.stage().postOnAnimation(()->{if(host.closing())return;
     for(WorkbenchSlot active:session.slots)host.syncSurface(active);
     host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{if(host.closing())return;host.clearRecoveryCovers();host.setRecovering(false);host.layoutCards(false);Log.i(TAG,"recovery_ready tasks="+host.taskIds());}));host.stage().invalidate();
    }));
   });
   handler.postDelayed(host.recoveryFinish(),650);
   handler.postDelayed(()->{if(!host.recovering()||host.closing())return;Log.w(TAG,"recovery_forced");host.clearRecoveryCovers();host.setRecovering(false);host.layoutCards(false);},1800);
  }
 }
 void resumeRemainingTasks(){
  if(host.closing()||host.finishing())return;
  for(WorkbenchSlot active:new ArrayList<>(session.slots)){
   if(active.released||active.surface==null||active.taskId<0)continue;
   try{
    Method resume=rom.viewApi.getDeclaredMethod("startActivityAndReparent");resume.setAccessible(true);resume.invoke(active.surface);
    Log.i(TAG,"embedded_resume task="+active.taskId);
   }catch(Throwable e){Log.w(TAG,"task_resume_failed task="+active.taskId,e);}
  }
 }
 void restoreContainerFocus(){
  if(host.closing()||host.finishing())return;
  try{reorderTask(host.taskId(),true);for(WorkbenchSlot s:session.slots)if(!s.released&&s.taskId>=0){reorderTask(s.taskId,true);host.syncSurface(s);}Log.i(TAG,"container_reordered task="+host.taskId());}catch(Throwable e){host.fail(e);}
 }
 void reorderTask(int id,boolean front) throws Exception {
  java.util.List<?> tasks=rom.canvas.tasks();
  for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;if(task.taskId!=id)continue;
   Class<?> token=Class.forName("android.window.WindowContainerToken"),wct=Class.forName("android.window.WindowContainerTransaction");
   Object tx=wct.getConstructor().newInstance(),t=task.getClass().getField("token").get(task);
   if(front)wct.getMethod("setHidden",token,boolean.class).invoke(tx,t,false);
   if(!front)wct.getMethod("setAlwaysOnTop",token,boolean.class).invoke(tx,t,false);
   wct.getMethod("reorder",token,boolean.class).invoke(tx,t,front);
   Class<?> organizer=Class.forName("android.window.WindowOrganizer");organizer.getMethod("applyTransaction",wct).invoke(organizer.getConstructor().newInstance(),tx);return;
  }
  throw new IllegalStateException("Missing task "+id);
 }
 void releaseWindows(){if(host.replacements().current()!=null)host.replacements().cancel(host.replacements().current(),"release");host.cancelSwitchPreparation("release");host.clearRecoveryCovers();host.cancelDrag();host.cancelAnimation();handler.removeCallbacksAndMessages(null);for(WorkbenchSlot s:new ArrayList<>(session.slots))releaseSlot(s);}
 void onEmbeddedTaskVanished(WorkbenchSlot s,int task){
  if(host.replacements().current()!=null&&(host.replacements().current().fresh==s||host.replacements().current().old==s)){
   ReplacementController.Run<WorkbenchSlot> run=host.replacements().current();if(run.old==s)s.pendingTask=task>=0?task:s.taskId;
   host.replacements().cancel(run,run.fresh==s?"new_task_vanished":"old_task_vanished");return;
  }
  if(host.closing()||s.released||session.slots.isEmpty())return;
  // Root back detaches the primary task. Keep the group and show the launcher.
  if(session.slots.get(session.primary)==s){
   int kept=task>=0?task:s.taskId;
   if(kept<0){host.removeSlot(s,true);return;}
   Log.i(TAG,"back_keep_group slot="+s.id+" task="+kept+" container="+host.taskId());
   if(host.backgrounded()||host.stopped()){s.pendingTask=kept;return;}
   if(host.backgroundWorkbench("primary_vanished")){s.pendingTask=kept;return;}
   if(taskExists(kept))rebindEmbedded(s,kept);else host.removeSlot(s,true);
   return;
  }
  host.removeSlot(s,true);
 }
 void restorePendingTasks(){
  if(host.closing()||host.stopped()||host.backgrounded()||host.hangTask()>=0)return;
  for(WorkbenchSlot s:new ArrayList<>(session.slots)){
   if(s.pendingTask<0||s.released)continue;
   int id=s.pendingTask;s.pendingTask=-1;
   if(taskExists(id))rebindEmbedded(s,id);else host.removeSlot(s,true);
  }
 }
 boolean taskExists(int id){
  if(id<0)return false;
  try{
   java.util.List<?> tasks=rom.canvas.tasks();
   for(Object value:tasks)if(((ActivityManager.RunningTaskInfo)value).taskId==id)return true;
  }catch(Throwable e){Log.w(TAG,"task_exists_failed id="+id,e);}
  return false;
 }
 void rebindEmbedded(WorkbenchSlot s,int task){
  if(s.surface==null||s.released||host.closing()||s.renderBounds.isEmpty())return;
  s.sourceTaskId=task;s.taskId=-1;s.failed=false;s.windowDrawn=false;
  try{
   Bundle config=new Bundle();config.putInt("scenario",2);config.putParcelable("launchBounds",new Rect(s.renderBounds));
   config.putBoolean("flexible_embeed_task_config_with_container",false);config.putBoolean("use_default_background_color",false);
   config.putBoolean("need_rotate_task_leash",host.rotatePresentation(s));config.putBoolean("zorder_on_top",false);config.putInt("taskId",task);
   config.putInt("userId",s.sourceUserId>=0?s.sourceUserId:android.os.Process.myUid()/100000);config.putFloat("cornerRadius",host.cardRadius(session.slots.indexOf(s)==session.primary));config.putInt("reparent_align",session.slots.indexOf(s)==session.primary?0:2);
   config.putBoolean("intercept_input_event",false);config.putBoolean("allow_task_detach_from_embedding",true);config.putBoolean("key_intercept_back_key",true);config.putBoolean("flexible_key_remove_task_detach",false);config.putInt("use_view_snapshot",1);
   rom.canvas.viewMethod("init").invoke(s.surface,config);rom.canvas.viewMethod("setEnforceStart").invoke(s.surface,true);
   Method start=rom.viewApi.getDeclaredMethod("startActivityAndReparent");start.setAccessible(true);start.invoke(s.surface);
   Log.i(TAG,"back_rebind slot="+s.id+" task="+task);
  }catch(Throwable e){Log.w(TAG,"back_rebind_failed slot="+s.id,e);}
 }
}
