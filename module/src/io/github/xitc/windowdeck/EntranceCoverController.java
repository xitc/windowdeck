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

/** Owns cover state for the workbench. The activity is only the nested Host. */
final class EntranceCoverController {
 interface Host {
  MotionSpec.Scene arrivalScene();
  WorkbenchSlot arrivalSlot();
  WorkbenchBackdrop backdrop();
  boolean backgrounded();
  void cancelHandoffPresentation();
  boolean closing();
  boolean consumeInputBlock();
  Context context();
  void failHandoff(WorkbenchSlot expected,String reason);
  void failSwitchPreparation(int token,String reason);
  void finishArrival(String result);
  boolean finishing();
  boolean gestureHandoff();
  Bitmap hangFace(WorkbenchSlot s);
  Bitmap hangSourceFrame();
  int hangSourceTask();
  int hangTask();
  void inputRole(WorkbenchSlot s,boolean isPrimary);
  boolean layoutPosted();
  void maybeAnimateAddedCard();
  WorkbenchSlot pendingEntrance();
  Resources resources();
  WorkbenchSlot releaseEntrancePresentation();
  boolean screenArrival();
  CanvasStage stage();
  boolean stopped();
  void submitMotionFit(SurfaceControl.Transaction fit);
  SwitchPreparation switchPreparation();
  int switchPrepareToken();
  WindowManager windowManager();
  boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final LiveContentPump pump;
 final Host host;
 EntranceCoverController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom,LiveContentPump pump){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;this.pump=pump;
 }

 private static final String TAG="WindowDeck";

 HandoffCover entranceCover;
 int coverWaitGeneration;
 boolean coverReleasePending;
 String entranceWaitReason="none";


void releaseEntrance(){
  host.cancelHandoffPresentation();
  if(host.pendingEntrance()!=null&&host.gestureHandoff())host.failHandoff(host.pendingEntrance(),"preempted");
  WorkbenchSlot shown=host.releaseEntrancePresentation();
  if(shown!=null&&shown.card!=null)shown.card.setAlpha(1f);
  for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);
}
void unlockEntranceInput(){
  if(!host.consumeInputBlock())return;
  for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);
  Log.i(TAG,"entrance_input_unlocked slots="+session.slots.size());
}
void raiseEntranceCover(String reason){
  if(entranceCover!=null&&entranceCover.isClosed())entranceCover=null;
  if(entranceCover!=null||host.closing()||host.finishing()||host.stage()==null)return;
  Bitmap source="hang_front".equals(reason)&&host.hangSourceTask()==host.hangTask()?host.hangSourceFrame():null;
  entranceCover=HandoffCover.create(host.context(),reason,this::snapshotWorkbench,source);
  entranceCover.onClosed(why->{
   if(host.screenArrival()&&("watchdog".equals(why)||"commit_timeout".equals(why)))host.finishArrival("cover_"+why);
   if(host.switchPreparation().pending()&&("watchdog".equals(why)||"commit_timeout".equals(why)))host.failSwitchPreparation(host.switchPrepareToken(),"cover_"+why);
  });
}
void withEntranceCover(String reason,Runnable action){
  if(host.closing()||host.finishing())return;
  if(host.stopped()||host.backgrounded())raiseEntranceCover(reason);
  HandoffCover cover=entranceCover;
  Runnable guarded=()->{if(!host.closing()&&!host.finishing())action.run();};
  if(cover==null)guarded.run();else cover.dispatchWhenDrawn(guarded);
}
void closeEntranceCover(String why){
  coverWaitGeneration++;coverReleasePending=false;
  HandoffCover cover=entranceCover;if(cover==null)return;
  entranceCover=null;cover.close(why);
}
void releaseEntranceCoverWhenDrawn(){
  if(entranceCover==null)return;
  if(host.stage()==null||host.stage().getWidth()==0){closeEntranceCover("no_stage");return;}
  if(coverReleasePending)return;
  coverReleasePending=true;
  final int generation=++coverWaitGeneration;
  awaitCoveredSurfaces(generation);
}
void awaitCoveredSurfaces(int generation){
  if(generation!=coverWaitGeneration||entranceCover==null)return;
  if(entranceCover.isClosed()){closeEntranceCover("cover_expired");return;}
  String key=entranceSurfaceKey();
  if(key==null){handler.postDelayed(()->awaitCoveredSurfaces(generation),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(WorkbenchSlot s:session.slots)if(!host.writeLeashFit(s,fit))throw new IllegalStateException("Unfitted entrance slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->commitCoveredFrame(generation,key));
   host.submitMotionFit(fit);host.stage().invalidate();
  }catch(Throwable e){Log.w(TAG,"entrance_surface_commit_failed",e);handler.postDelayed(()->awaitCoveredSurfaces(generation),MotionSpec.SURFACE_RETRY_MS);}
}
String entranceSurfaceKey(){
  if(host.closing()||host.stopped()||host.backgrounded()||host.stage()==null||host.layoutPosted()||host.stage().isLayoutRequested()||session.slots.isEmpty()||host.stage().getWidth()>=host.stage().getHeight()||host.resources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT){
   entranceWaitReason="host closing="+host.closing()+" stopped="+host.stopped()+" backgrounded="+host.backgrounded()+" layout="+host.layoutPosted();
   return null;
  }
  StringBuilder key=new StringBuilder().append(session.primary).append(':').append(host.stage().getWidth()).append('x').append(host.stage().getHeight());
  for(WorkbenchSlot s:session.slots){
   if(s.released||s.failed||s.taskId<0||s.surface==null||s.card==null||s.card.isLayoutRequested()||s.surface.isLayoutRequested()){
    entranceWaitReason="slot="+s.id+" task="+s.taskId+" released="+s.released+" failed="+s.failed+" layout_pending";return null;
   }
   try{
    SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface),plate=((SurfaceView)s.surface).getSurfaceControl();
    int state=taskDrawState(s);
    if(!TaskSurfaceEvidence.ready(state)||leash==null||!leash.isValid()||plate==null||!plate.isValid()
     ||!((SurfaceView)s.surface).getHolder().getSurface().isValid()){
     entranceWaitReason="slot="+s.id+" task="+s.taskId+" draw="+state+" "+s.drawDetail+" leash="+(leash!=null&&leash.isValid())+" plate="+(plate!=null&&plate.isValid())+" holder="+((SurfaceView)s.surface).getHolder().getSurface().isValid();return null;
    }
    key.append('|').append(s.id).append(':').append(s.taskId).append(':').append(LiveTaskContent.layerId(leash))
     .append(':').append(LiveTaskContent.layerId(plate)).append(':').append(s.surface.getWidth()).append('x').append(s.surface.getHeight())
     .append(':').append(s.orientationAxis).append(':').append(s.renderBounds);
   }catch(Throwable e){entranceWaitReason="slot="+s.id+" task="+s.taskId+" exception="+e.getClass().getSimpleName();return null;}
  }
  entranceWaitReason="ready";
  return key.toString();
}
int taskDrawState(WorkbenchSlot s) throws Exception {
  Field draw=rom.canvas.drawState;draw.setAccessible(true);int cached=draw.getInt(s.surface);
  Field info=rom.canvas.taskInfo;info.setAccessible(true);Object task=info.get(s.surface);
  int actual=task==null?-1:task.getClass().getField("taskId").getInt(task);
  boolean hasState=false;int bundleState=-1;
  if(task!=null){
   Object value=task.getClass().getField("mOplusExtraBundle").get(task);
   Field key=rom.viewApi.getDeclaredField("HAS_DRAWN_WINDOW");key.setAccessible(true);String name=(String)key.get(null);
   if(value instanceof Bundle){hasState=((Bundle)value).containsKey(name);bundleState=((Bundle)value).getInt(name,-1);}
  }
  s.drawDetail="cached="+cached+" info_task="+actual+" bundle_has_draw="+hasState+" bundle_draw="+bundleState;
  if(actual!=s.taskId||!s.embedded||!((SurfaceView)s.surface).getHolder().getSurface().isValid()) {s.drawEvidence.invalidate();return -1;}
  SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface),plate=((SurfaceView)s.surface).getSurfaceControl();
  if(!s.drawEvidence.bind(actual,LiveTaskContent.layerId(leash),LiveTaskContent.layerId(plate),s.surface.getWidth(),s.surface.getHeight(),s.renderBounds.width(),s.renderBounds.height()))return -1;
  boolean arrival=host.screenArrival()&&s==host.arrivalSlot()&&(host.arrivalScene()==MotionSpec.Scene.ADD||host.arrivalScene()==MotionSpec.Scene.SIDE_ADD);
  boolean cold=arrival&&s.sourceTaskId<0;
  int state=arrival?s.drawEvidence.resolveArrival(cached,bundleState,hasState,cold):s.drawEvidence.resolve(cached,bundleState,hasState);
  if(state<0&&(!arrival||s.drawEvidence.canProbeArrival(cold)))probeTaskContent(s,leash);
  if(arrival)s.drawDetail+=" cold="+cold+" application_window="+s.drawEvidence.canProbeArrival(true);
  return state;
}
void probeTaskContent(WorkbenchSlot s,SurfaceControl leash){
 if(host.closing()||host.stopped()||host.backgrounded()||s.released||s.contentProbeUnavailable||SystemClock.uptimeMillis()<s.nextContentProbe)return;
 int token=s.drawEvidence.beginProbe();if(token<0)return;
 final SurfaceControl captured;
 try{captured=(SurfaceControl)de.robv.android.xposed.XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckContentProbe");}
 catch(Throwable e){s.drawEvidence.completeProbe(token,false);s.contentProbeUnavailable=true;Log.w(TAG,"task_content_probe_copy_failed",e);return;}

 final int task=s.taskId,width=s.renderBounds.width(),height=s.renderBounds.height();
 s.nextContentProbe=SystemClock.uptimeMillis()+80;
 pump.worker().post(()->{
  boolean content=false;Throwable failure=null;
  try{content=s.orientationAxis==2?LiveTaskContent.presented(captured,width,height):LiveTaskContent.sample(captured,width,height);}catch(Throwable e){failure=e;}finally{captured.release();}
  final boolean ready=content;final Throwable error=failure;
  handler.post(()->{
   if(host.closing()||host.stopped()||host.backgrounded()||s.released||s.taskId!=task)return;
   try{
    // Rebind first: a replacement layer/plate invalidates an in-flight sample.
    taskDrawState(s);
    if(!s.drawEvidence.completeProbe(token,ready))return;
    if(error!=null){s.contentProbeUnavailable=true;Log.w(TAG,"task_content_probe_unavailable slot="+s.id+" task="+task,error);}
    else if(ready){Log.i(TAG,"task_content_ready slot="+s.id+" task="+task+" evidence=live_child_layers "+s.drawDetail);host.maybeAnimateAddedCard();}
   }catch(Throwable e){s.drawEvidence.invalidate();Log.w(TAG,"task_content_probe_stale slot="+s.id,e);}
  });
 });
}
void probeLandscapePresented(WorkbenchSlot s,SurfaceControl leash){
 if(host.closing()||host.stopped()||host.backgrounded()||s.released||s.contentProbeUnavailable||SystemClock.uptimeMillis()<s.nextContentProbe)return;
 if(s.renderBounds.width()<2||s.renderBounds.height()<2)return;
 int token=s.drawEvidence.beginProbe();if(token<0)return;
 final SurfaceControl captured;
 try{captured=(SurfaceControl)de.robv.android.xposed.XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckContentProbe");}
 catch(Throwable e){s.drawEvidence.completeProbe(token,false);s.contentProbeUnavailable=true;Log.w(TAG,"handoff_presented_copy_failed",e);return;}

 final int task=s.taskId,width=s.renderBounds.width(),height=s.renderBounds.height();
 s.nextContentProbe=SystemClock.uptimeMillis()+80;
 pump.worker().post(()->{
  boolean shown=false;Throwable failure=null;
  try{shown=LiveTaskContent.presented(captured,width,height);}catch(Throwable e){failure=e;}finally{captured.release();}
  final boolean ready=shown;final Throwable error=failure;
  handler.post(()->{
   if(host.closing()||host.stopped()||host.backgrounded()||s.released||s.taskId!=task)return;
   try{
    taskDrawState(s);
    if(!s.drawEvidence.completeProbe(token,ready))return;
    if(error!=null){s.contentProbeUnavailable=true;Log.w(TAG,"handoff_presented_unavailable slot="+s.id+" task="+task,error);}
    else if(ready){Log.i(TAG,"handoff_frame_presented task="+task);host.maybeAnimateAddedCard();}
   }catch(Throwable e){s.drawEvidence.invalidate();Log.w(TAG,"handoff_presented_stale slot="+s.id,e);}
  });
 });
}
void commitCoveredFrame(int generation,String key){
  if(generation!=coverWaitGeneration||entranceCover==null)return;
  if(!key.equals(entranceSurfaceKey())){awaitCoveredSurfaces(generation);return;}
  host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   if(generation!=coverWaitGeneration||entranceCover==null)return;
   // Two animation frames on top of the commit, the same margin releaseSlot() uses before it
   // uncovers resumed tasks. A commit is a submission; the compositor presents it a frame later,
   // and closing on the commit itself could expose one frame of the still-hidden launcher.
   host.stage().postOnAnimation(()->host.stage().postOnAnimation(()->{
    if(generation!=coverWaitGeneration||entranceCover==null)return;
    if(!key.equals(entranceSurfaceKey())){awaitCoveredSurfaces(generation);return;}
    closeEntranceCover("frame_committed");unlockEntranceInput();
   }));
  }));
  host.stage().invalidate();
  // HandoffCover owns the absolute watchdog; retries never extend its deadline.
}
Bitmap snapshotWorkbench(){
  try{
   int width=host.stage()==null?0:host.stage().getWidth(),height=host.stage()==null?0:host.stage().getHeight();
   if(width<2||height<2){Log.w(TAG,"entrance_cover_snapshot_skipped size="+width+"x"+height);return null;}
   Rect display=host.windowManager().getMaximumWindowMetrics().getBounds();
   int displayW=Math.min(display.width(),display.height()),displayH=Math.max(display.width(),display.height());
   int[] origin=new int[2];host.stage().getLocationOnScreen(origin);
   if(!DisplayGeometry.fitsContent(width,height,origin[0],origin[1],displayW,displayH)){
    Log.w(TAG,"entrance_cover_snapshot_skipped reason=unstable_stage");return null;
   }
   Bitmap out=Bitmap.createBitmap(displayW,displayH,Bitmap.Config.ARGB_8888);
   Canvas bitmapCanvas=new Canvas(out);bitmapCanvas.drawColor(Ui.CHROME);
   Bitmap wallpaper=host.backdrop()==null?null:host.backdrop().currentBitmap();
   if(wallpaper!=null&&!wallpaper.isRecycled()){
    Rect dst=new Rect(0,0,displayW,displayH);
    bitmapCanvas.drawBitmap(wallpaper,new Rect(0,0,wallpaper.getWidth(),wallpaper.getHeight()),dst,null);
   }
   bitmapCanvas.translate(origin[0],origin[1]);
   for(WorkbenchSlot s:session.slots){
    if(s.card==null||s.released)continue;
    int left=Math.round(s.card.getLeft()+s.card.getTranslationX());
    int top=Math.round(s.card.getTop()+s.card.getTranslationY());
    int w=Math.round(s.card.getWidth()*s.card.getScaleX()),h=Math.round(s.card.getHeight()*s.card.getScaleY());
    if(w<=0||h<=0)continue;
    Rect dst=new Rect(left,top,left+w,top+h);
    Bitmap face=s.surface==null?null:host.hangFace(s);
    if(face!=null&&!face.isRecycled())bitmapCanvas.drawBitmap(face,new Rect(0,0,face.getWidth(),face.getHeight()),dst,null);
    else bitmapCanvas.drawRoundRect(new RectF(dst),Ui.dp(host.context(),Ui.MAIN_RADIUS),Ui.dp(host.context(),Ui.MAIN_RADIUS),cardFill());
   }
   Log.i(TAG,"entrance_cover_snapshot slots="+session.slots.size()+" size="+displayW+"x"+displayH+" stage="+width+"x"+height+" origin="+Arrays.toString(origin));
   return out;
  }catch(Throwable e){Log.w(TAG,"entrance_cover_snapshot_failed",e);return null;}
 }
android.graphics.Paint cardFill(){
  android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
  paint.setColor(Ui.CARD);return paint;
}
}
