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

/** Owns handoff state for the workbench. The activity is only the nested Host. */
final class HandoffController {
 interface Host {
  ValueAnimator addAnimation();
  void animateAddedCard(WorkbenchSlot slot);
  ValueAnimator animation();
  WorkbenchBackdrop backdrop();
  boolean backgrounded();
  void beginHangEntrance();
  void cancelAnimation();
  void abortSideSort(String reason);
  void cancelAnimation(boolean preservePose);
  CardLayout.Result cardGeometry();
  int cardRadius(boolean isPrimary);
  void clearHeldPose();
  void closeEntranceCover(String why);
  void dropHangEntranceDue();
  boolean closing();
  Context context();
  void createWindow(WorkbenchSlot slot);
  boolean deferPortrait();
  Display display();
  boolean focusPrimary(String reason);
  boolean hangQuiet();
  boolean initialized();
  void inputRole(WorkbenchSlot s,boolean isPrimary);
  void layoutCaption();
  void layoutCards(boolean animate);
  void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source);
  boolean layoutPosted();
  WorkbenchSlot pendingPickerLaunch();
  boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus);
  boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus,long waitMs);
  void prepareReplacement(int index,WorkbenchSlot old,WorkbenchSlot fresh);
  int[] presentationSize(WorkbenchSlot s);
  void probeLandscapePresented(WorkbenchSlot s,SurfaceControl leash);
  void publishState(int count);
  void publishState(int count,int generation,int attempt);
  boolean readOriginalPlus();
  boolean recovering();
  void refreshStatus();
  void releaseEntrance();
  void releaseSlot(WorkbenchSlot s);
  void releaseSlot(WorkbenchSlot s,boolean toFront);
  void releaseSlot(WorkbenchSlot s,boolean toFront,boolean retainPlate);
  ReplacementController<WorkbenchSlot> replacements();
  void requestCoverPose(WorkbenchSlot slot);
  Resources resources();
  boolean rotatePresentation(WorkbenchSlot s);
  void scheduleLayoutEvidence(String reason);
  boolean screenArrival();
  Rect screenRect(View view);
  void setAddAnimation(ValueAnimator value);
  void setDeferPortrait(boolean value);
  void setRequestedOrientation(int orientation);
  SourceRelease sourceRelease();
  CanvasStage stage();
  void supersedeHangEntrance();
  boolean stopped();
  int[] surfacePlate(WorkbenchSlot s);
  CardMotion.Pose surfacePose(WorkbenchSlot s,int w,int h);
  void syncSurface(WorkbenchSlot s);
  int taskDrawState(WorkbenchSlot s) throws Exception;
  int taskId();
  void unlockEntranceInput();
  void updateNaturalBounds();
  WorkbenchSlot validateSourceTask(int id,int user) throws Exception;
  void queueExistingSwitch(WorkbenchSlot selected,long selectionSession);
  boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 HandoffController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 final EntranceIntent<WorkbenchSlot> entrance=new EntranceIntent<WorkbenchSlot>();
 boolean gestureHandoff;
 android.os.ResultReceiver handoffReady;
 long handoffDeadline;
 long portraitHandoffLimit;
 boolean handoffPresentation;
 int presentationGeneration;
 android.os.ResultReceiver presentationReply;
 String presentationLayoutKey,presentationLiveKey;
 Bundle presentationGeometry;
 boolean presentationLiveReady;
 View entranceFlying;
 WorkbenchSlot entranceSlot;
 WorkbenchSlot existingSwipeSlot;
 int existingSwipeTask=-1;


 /** 0 is a new app, 4 is a validated reuse, 3 is a rejected reuse request. */
 int restoreExistingTask(Intent request){
  int id=request.getIntExtra("windowdeck_add_task_id",-1),user=request.getIntExtra("windowdeck_add_user_id",-1);
  if(id<0||user!=android.os.Process.myUid()/100000||request.getIntExtra("windowdeck_container_task_id",-1)!=host.taskId()
    ||host.closing()||host.recovering()||!host.initialized()||host.pendingPickerLaunch()!=null)return 3;
  try{
   ActivityManager.RunningTaskInfo source=null;
   java.util.List<?> tasks=rom.canvas.tasks();
   for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;if(task.taskId==id)source=task;}
   if(source==null||source.baseActivity==null||source.topActivity==null
     ||source.getClass().getField("userId").getInt(source)!=user
     ||((Number)source.getClass().getMethod("getWindowingMode").invoke(source)).intValue()!=1
     ||!source.baseActivity.getPackageName().equals(source.topActivity.getPackageName()))return 3;
   String pkg=source.baseActivity.getPackageName();
   for(WorkbenchSlot slot:session.slots){
    if(slot.released||!slot.component.getPackageName().equals(pkg))continue;
    if(entrance.pending()!=null||host.addAnimation()!=null)return 3;
    ActivityManager.RunningTaskInfo held=null;
    for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;if(task.taskId==slot.taskId)held=task;}
    if(held==null||held.baseActivity==null||!pkg.equals(held.baseActivity.getPackageName())
      ||held.getClass().getField("userId").getInt(held)!=user)return 3;
    // onResume restores the original task with the existing embedding options.
    // Do not create a second Slot or send this through the add entrance watchdog.
    host.abortSideSort("existing_restore");
    if(slot.taskId==id||!slot.embedded)slot.pendingTask=slot.taskId;
    existingSwipeSlot=slot;existingSwipeTask=slot.taskId;
    if(!host.stopped()&&!host.backgrounded())resumeExistingSwipe();
    Log.i(TAG,"swipe_existing_restored source="+id+" task="+slot.taskId+" slot="+slot.id+" container="+host.taskId()+" count="+session.slots.size());
    return 4;
   }
   return 0;
  }catch(Throwable e){Log.w(TAG,"swipe_existing_restore_failed task="+id,e);return 3;}
 }
 boolean addExistingTask(Intent request){
  int id=request.getIntExtra("windowdeck_add_task_id",-1),user=request.getIntExtra("windowdeck_add_user_id",-1);
  int expectedContainer=request.getIntExtra("windowdeck_container_task_id",-1);
  if((entrance.pending()!=null&&entrance.pending().taskId>=0)||host.addAnimation()!=null){Log.i(TAG,"add_entrance_preempted slot="+(entrance.pending()==null?-1:entrance.pending().id));if(host.addAnimation()!=null){ValueAnimator old=host.addAnimation();host.setAddAnimation(null);old.cancel();}host.releaseEntrance();}
  boolean replace=request.getBooleanExtra("windowdeck_hang_replace",false);
  if(id<0||user<0||expectedContainer!=host.taskId()||host.closing()||host.recovering()||!host.initialized()||host.pendingPickerLaunch()!=null||entrance.pending()!=null||host.addAnimation()!=null||(!replace&&session.slots.size()>=Caps.MAX_TASKS)){Log.w(TAG,"add_task_rejected id="+id+" user="+user+" container="+expectedContainer+" closing="+host.closing()+" recovering="+host.recovering()+" initialized="+host.initialized()+" entrance="+(entrance.pending()!=null)+" anim="+(host.addAnimation()!=null)+" count="+session.slots.size()+" replace="+replace);return false;}
  try{
   WorkbenchSlot fresh=host.validateSourceTask(id,user);
   boolean originalPlus=attachTaskEntrance(fresh,replace,request.getBooleanExtra("windowdeck_hang_place",false),request);
   Log.i(TAG,"add_existing_requested slot="+fresh.id+" task="+id+" user="+user+" container="+host.taskId()+" replace="+replace+" original_plus="+originalPlus);
   return true;
  }catch(Throwable e){Log.w(TAG,"add_existing_failed task="+id,e);if(!host.hangQuiet())Toast.makeText(host.context(),"未能加入："+(e.getMessage()==null?"原应用保持不变":e.getMessage()),Toast.LENGTH_LONG).show();return false;}
 }
 boolean attachTaskEntrance(WorkbenchSlot fresh,boolean replace,boolean hang,Intent request) throws Exception {
   host.abortSideSort("task_entrance");
   if(replace){
    WorkbenchSlot old=session.slots.get(session.primary);if(old.pinned)throw new IllegalArgumentException("请先取消固定");
    host.prepareReplacement(session.slots.indexOf(old),old,fresh);
    if(host.replacements().current()!=null&&host.replacements().current().fresh==fresh)host.replacements().current().mainEntrance=true;
    return false;
   }
   host.cancelAnimation();
   WorkbenchSlot replaced=null;
   final boolean originalPlus;
   if(replace){
    replaced=session.slots.get(session.primary);if(replaced.pinned)throw new IllegalArgumentException("请先取消固定");
    originalPlus=false;session.primary=HangPlace.place(session.slots,session.primary,fresh,true,false,false);
   }else if(hang&&!session.slots.isEmpty()){
    originalPlus=host.readOriginalPlus();session.primary=HangPlace.place(session.slots,session.primary,fresh,false,true,originalPlus);
   }else{originalPlus=false;session.primary=HangPlace.place(session.slots,session.primary,fresh,false,false,false);}
   adoptPendingEntrance(fresh,!hang&&!replace);
   handoffReady=gestureHandoff?request.getParcelableExtra("windowdeck_handoff_ready",android.os.ResultReceiver.class):null;
   portraitHandoffLimit=0;
   handoffDeadline=SystemClock.uptimeMillis()+2000;
   if(gestureHandoff)watchHandoffDeadline(fresh);
   host.publishState(session.slots.size());host.updateNaturalBounds();host.createWindow(fresh);host.refreshStatus();
   if(replaced!=null){replaced.quietDetach=true;host.releaseSlot(replaced);}
   if(fresh.card!=null)fresh.card.setAlpha(1f);
   boolean edge=(hang||replace)&&session.slots.size()>1,hungMain=hang&&!replace;
   // Launcher already owns the gesture's moving task. Do not restart it from fullscreen
   // inside the host. Shelf add/replace retains its separate, user-triggered transition.
   if(gestureHandoff)host.layoutCards(false);
   else if(!host.poseScreenArrival(fresh,edge,hungMain,originalPlus)){
    WorkbenchSlot arriving=fresh;host.stage().post(()->{
     if(host.closing()||arriving.released||entrance.pending()!=arriving)return;
     if(!host.poseScreenArrival(arriving,edge,hungMain,originalPlus)){Log.w(TAG,"arrive_pose_unavailable slot="+arriving.id);abandonEntrance(arriving);}
    });
   }
   return originalPlus;
 }
 void revealAddedCard(Intent request){
  WorkbenchSlot slot=entrance.pending();
  if(slot==null||request.getIntExtra("windowdeck_container_task_id",-1)!=host.taskId()||request.getIntExtra("windowdeck_add_task_id",-1)!=slot.sourceTaskId)return;
  entrance.markFrontReady();Log.i(TAG,"add_card_front_ready slot="+slot.id+" task="+slot.sourceTaskId);
  requestEntranceFrame();
 }
void abandonEntrance(WorkbenchSlot slot){
  if(entrance.pending()!=slot)return;
  if(gestureHandoff){failHandoff(slot,"task_unavailable");return;}
  entrance.abandon(slot);host.closeEntranceCover("entrance_abandoned");
  if(slot.card!=null)slot.card.setAlpha(1f);
  if(!host.closing())host.layoutCards(false);
}
void watchHandoffDeadline(WorkbenchSlot expected){
 final long deadline=handoffDeadline;
 handler.postDelayed(()->{
  if(entrance.pending()==expected&&gestureHandoff&&handoffDeadline==deadline
    &&!(portraitHandoffLimit>0&&presentationLiveReady))failHandoff(expected,"ready_timeout");
 },Math.max(0L,deadline-SystemClock.uptimeMillis()));
}
void failHandoff(WorkbenchSlot expected,String reason){
 if(host.sourceRelease()!=null)host.sourceRelease().cancel();
 if(host.deferPortrait()){host.clearHeldPose();host.setDeferPortrait(false);host.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);}
 if(entrance.pending()!=expected||!gestureHandoff)return;
 Log.w(TAG,"handoff_result result="+reason+" task="+expected.sourceTaskId);
 android.os.ResultReceiver reply=handoffReady;handoffReady=null;gestureHandoff=false;
 abandonEntrance(expected);cancelHandoffPresentation();if(reply!=null)reply.send(HandoffProtocol.FAILED,Bundle.EMPTY);
}
void requestEntranceFrame(){
  if(host.deferPortrait())return; // The live-pose path owns layout until rotation/final fit commit.
  sendHandoffBackground();
  if(entrance.pending()==null||!entrance.frontReady()||host.stopped()||host.backgrounded()||host.closing()||host.stage().getWidth()==0||entrance.framePending())return;
  // A measured whole-group target can start the opaque snapshot before live
  // content and wallpaper finish. The launcher only accepts a hole-free expansion.
  if(gestureHandoff&&HandoffPreparation.measuredLayout(session.slots.size(),entrance.pending().taskId,entrance.pending().sourceTaskId,
    handoffReady!=null&&handoffGeometryKey()!=null))beginHandoffPresentation(entrance.pending(),false);
  // Only the window having *something* to draw is required. The wallpaper underlay is loaded on
  // a background thread, and waiting for it held the whole entrance behind an async task
  // (TODO A1-4) — the gap the entrance cover exists to hide is exactly that wait.
  if(gestureHandoff&&(entrance.pending().taskId<0||!entrance.pending().windowDrawn||(host.backdrop()!=null&&!host.backdrop().ready))){
   // The independent source cover stays visible while the target backdrop settles.
   // A placeholder is safe for normal host startup, but must match the final wallpaper handoff.
   host.stage().postOnAnimation(this::requestEntranceFrame);return;
  }
  WorkbenchSlot expected=entrance.pending();entrance.markFramePending();
  if(gestureHandoff){commitHandoffLayout(expected);return;}
  host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   if(entrance.pending()!=expected)return;
   if(gestureHandoff){commitHandoffLayout(expected);return;}
   entrance.clearFramePending();
   if(entrance.pending()!=expected||host.stopped()||host.backgrounded()||host.closing())return;
   entrance.markFrameReady();host.unlockEntranceInput();maybeAnimateAddedCard();
  }));host.stage().invalidate();
}
 String handoffGeometryKey(){return handoffLayoutKey(false);}
 String handoffLayoutKey(){return handoffLayoutKey(true);}
 String handoffLayoutKey(boolean requireLive){
  if(host.layoutPosted()||host.stage().isLayoutRequested()||host.animation()!=null||host.addAnimation()!=null||host.stage().getWidth()>=host.stage().getHeight()||host.resources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return null;
  int[][] target=host.cardGeometry().cards;
  if(target.length!=session.slots.size())return null;
  int[] origin=new int[2];host.stage().getLocationOnScreen(origin);
  StringBuilder key=new StringBuilder().append(Arrays.toString(origin)).append(':').append(host.display().getRotation()).append(':').append(host.cardRadius(true)).append(':').append(session.primary).append(':').append(host.stage().getWidth()).append('x').append(host.stage().getHeight())
   .append(':').append(requireLive&&host.backdrop()!=null?host.backdrop().visualGeneration():0);
  for(int i=0;i<session.slots.size();i++){
   WorkbenchSlot s=session.slots.get(i);int[] r=target[i];
   if(s.released||s.taskId<0||s.card==null||s.surface==null||s.card.isLayoutRequested()||s.surface.isLayoutRequested()
    ||s.card.getLeft()!=r[0]||s.card.getTop()!=r[1]||s.card.getWidth()!=r[2]||s.card.getHeight()!=r[3])return null;
   int[] plate=host.surfacePlate(s);
   if(s.surface.getWidth()!=plate[0]||s.surface.getHeight()!=plate[1])return null;
   key.append('|').append(s.id).append(':').append(s.taskId).append(':').append(Arrays.toString(r))
    .append(':').append(s.orientationAxis).append(':').append(s.renderBounds).append(':').append(Arrays.toString(plate));
   if(!requireLive)continue;
   try{
    SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface),surface=((SurfaceView)s.surface).getSurfaceControl();
    int state=host.taskDrawState(s);
    if(!TaskSurfaceEvidence.ready(state)||leash==null||!leash.isValid()||surface==null||!surface.isValid()
      ||!((SurfaceView)s.surface).getHolder().getSurface().isValid())return null;
    key.append(':').append(LiveTaskContent.layerId(leash)).append(':').append(LiveTaskContent.layerId(surface));
   }catch(Throwable e){return null;}
  }
  return key.toString();
 }
 void retryHandoffLayout(WorkbenchSlot expected){
  if(entrance.pending()!=expected)return;
  entrance.clearFrameProgress();
  if(SystemClock.uptimeMillis()>=handoffDeadline){
   failHandoff(expected,"layout_timeout");return;
  }
  host.stage().postOnAnimation(this::requestEntranceFrame);
 }
 void commitHandoffLayout(WorkbenchSlot expected){
  if(host.stopped()||host.backgrounded()||host.closing()){entrance.clearFramePending();return;}
  String key=handoffLayoutKey();
  if(key==null){retryHandoffLayout(expected);return;}
  // L1: measured geometry can travel under the opaque snapshot while the final fit/frame
  // barrier completes. Every live fit and stable host frame remains required.
  if(!host.deferPortrait()&&HandoffPreparation.measuredLayout(session.slots.size(),expected.taskId,expected.sourceTaskId,handoffReady!=null))
   beginHandoffPresentation(expected,false);
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(WorkbenchSlot s:session.slots)if(!host.writeLeashFit(s,fit))throw new IllegalStateException("Unfitted slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(entrance.pending()!=expected)return;
    if(host.stopped()||host.backgrounded()||host.closing()){entrance.clearFramePending();return;}
    host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(entrance.pending()!=expected)return;
     if(host.stopped()||host.backgrounded()||host.closing()){entrance.clearFramePending();return;}
     if(!key.equals(handoffLayoutKey())||(host.backdrop()!=null&&!host.backdrop().ready)){retryHandoffLayout(expected);return;}
     // Submission is earlier than presentation. Keep the launcher's independent cover until
     // the same layout and surface identities survive two additional display frames.
     host.stage().postOnAnimation(()->host.stage().postOnAnimation(()->{
      if(entrance.pending()!=expected)return;
      if(host.stopped()||host.backgrounded()||host.closing()){entrance.clearFramePending();return;}
      if(!key.equals(handoffLayoutKey())){retryHandoffLayout(expected);return;}
      // Keep the already-committed input role until the independent motion finishes.
      // Unlocking here requests layout, making beginHandoffPresentation capture a null key.
      entrance.markFrameReady();
      Log.i(TAG,"handoff_layout_committed task="+expected.taskId+" cards="+session.slots.size()+" axis="+expected.orientationAxis);
      maybeAnimateAddedCard();
     }));
    }));host.stage().invalidate();
   });fit.apply();
  }catch(Throwable e){Log.w(TAG,"handoff_layout_commit_failed",e);retryHandoffLayout(expected);}
 }
 void maybeAnimateAddedCard(){
  WorkbenchSlot slot=entrance.pending();
  if(host.deferPortrait()){if(slot!=null)host.requestCoverPose(slot);return;}
  if(host.screenArrival()&&slot!=null&&entrance.frontReady()&&!host.stopped()&&!host.backgrounded()&&!host.closing()){handOffPendingToHang();host.beginHangEntrance();return;}
  if(slot==null||!entrance.frontReady()||host.stopped()||host.backgrounded()||host.closing()||slot.released||slot.taskId<0)return;
  if(gestureHandoff&&!slot.windowDrawn){pollHandoffDraw(slot);return;}
  if(!entrance.frameReady()){requestEntranceFrame();return;}
  if(!slot.windowDrawn&&!slot.entranceDrawTimedOut){
   if(!slot.entranceWaitScheduled){slot.entranceWaitScheduled=true;handler.postDelayed(()->{if(entrance.pending()==slot){slot.entranceDrawTimedOut=true;maybeAnimateAddedCard();}},120);}
   return;
  }
  consumePendingForMotion();
  if(gestureHandoff){
   gestureHandoff=false;entrance.clearGestureReveal();
   if(slot.card!=null)slot.card.setAlpha(1f);
   // layoutCards already established the z-order before commitHandoffLayout. Repeating
   // bringToFront here requests a new ViewGroup layout even when the order is unchanged,
   // invalidating the stable geometry immediately before the presentation handshake.
   host.layoutCaption();
   for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);
   host.focusPrimary("gesture_handoff");
   Log.i(TAG,"gesture_handoff_ready task="+slot.taskId+" host_animation=false");
   if(handoffReady!=null||handoffPresentation)beginHandoffPresentation(slot,true);
   else{host.unlockEntranceInput();host.focusPrimary("gesture_without_presentation");}
  }else host.animateAddedCard(slot);
 }
 void pollHandoffDraw(WorkbenchSlot slot){
  if(slot.handoffPolling)return;
  slot.handoffPolling=true;
  final long deadline=handoffDeadline;
  Runnable check=new Runnable(){public void run(){
   if(host.closing()||slot.released||entrance.pending()!=slot||!gestureHandoff){slot.handoffPolling=false;return;}
   if(slot.windowDrawn){slot.handoffPolling=false;return;}
   // Attachment/layout can settle after the first requestEntranceFrame attempt.
   // Retry geometry while pixels are pending, rather than gating PREPARED on draw.
   if(!host.deferPortrait()&&entrance.frontReady()&&!host.stopped()&&!host.backgrounded()
     &&HandoffPreparation.measuredLayout(session.slots.size(),slot.taskId,slot.sourceTaskId,handoffReady!=null&&handoffGeometryKey()!=null))
    beginHandoffPresentation(slot,false);
   try{
    int state=host.taskDrawState(slot);
    SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(slot.surface);
    SurfaceControl plate=((SurfaceView)slot.surface).getSurfaceControl();
    boolean surface=leash!=null&&leash.isValid()&&plate!=null&&plate.isValid();
    // Landscape pixels decide. drawState 0 is a cold embed, not a presented frame.
    if(slot.orientationAxis==2){
     if(!surface){
      if(SystemClock.uptimeMillis()<deadline){host.stage().postOnAnimation(this);return;}
      slot.handoffPolling=false;failHandoff(slot,"draw_state_timeout");return;
     }
     if(!slot.drawEvidence.hasContent()){
      host.probeLandscapePresented(slot,leash);
      if(SystemClock.uptimeMillis()<deadline){host.stage().postOnAnimation(this);return;}
      Log.i(TAG,"handoff_black_held task="+slot.taskId);
      slot.handoffPolling=false;failHandoff(slot,"presented_timeout");return;
     }
    }else if(!TaskSurfaceEvidence.ready(state)||!surface){
     if(SystemClock.uptimeMillis()<deadline){host.stage().postOnAnimation(this);return;}
     slot.handoffPolling=false;failHandoff(slot,"draw_state_timeout");return;
    }
    host.syncSurface(slot);
    try(SurfaceControl.Transaction committed=new SurfaceControl.Transaction()){
     host.writeLeashFit(slot,committed);
     committed.addTransactionCommittedListener((Executor)handler::post,()->{
      if(host.closing()||entrance.pending()!=slot||slot.released)return;
      if(slot.orientationAxis==2&&!slot.drawEvidence.hasContent()){slot.handoffPolling=false;host.stage().postOnAnimation(()->pollHandoffDraw(slot));return;}
      slot.windowDrawn=true;slot.handoffPolling=false;
      Log.i(TAG,"handoff_task_surface_committed task="+slot.taskId+" drawState="+state);
      requestEntranceFrame();
     });committed.apply();
    }
   }catch(Throwable e){Log.w(TAG,"handoff_draw_state_unavailable",e);slot.handoffPolling=false;failHandoff(slot,"draw_state_unavailable");}
  }};
  host.stage().postOnAnimation(check);
 }
void beginHandoffPresentation(WorkbenchSlot slot,boolean liveReady){
 if(handoffPresentation){
  if(!liveReady)return;
  if(presentationGeometry==null||presentationGeometry.getInt(HandoffProtocol.TASK,-1)!=slot.sourceTaskId
    ||!Objects.equals(presentationLayoutKey,handoffGeometryKey())||handoffLayoutKey()==null||host.stopped()||host.backgrounded()||host.closing()
    ||!sameHandoffContentGeometry(slot,presentationGeometry)){
   cancelHandoffPresentation();return;
  }
  presentationLiveKey=handoffLayoutKey();
  presentationLiveReady=true;
  if(!putHandoffBackdrop(presentationGeometry)){cancelHandoffPresentation();return;}
  presentationReply.send(HandoffProtocol.READY,presentationGeometry);
  Log.i(TAG,"handoff_live_ready task="+slot.taskId+" overlapped=true");return;
 }
 final int generation=++presentationGeneration;
 final String key=handoffGeometryKey();
 if(key==null||(liveReady&&handoffLayoutKey()==null))return;
 presentationLayoutKey=key;presentationLiveReady=liveReady;presentationLiveKey=liveReady?handoffLayoutKey():null;
 handoffPresentation=true;presentationReply=handoffReady;handoffReady=null;
 android.os.ResultReceiver done=new android.os.ResultReceiver(handler){
  @Override protected void onReceiveResult(int code,Bundle data){
   if(!handoffPresentation||generation!=presentationGeneration)return;
   String finalKey=handoffLayoutKey();
   boolean valid=code==HandoffProtocol.READY&&presentationLiveReady&&!slot.released&&!host.closing()&&!host.stopped()&&!host.backgrounded()&&key.equals(handoffGeometryKey())&&Objects.equals(presentationLiveKey,finalKey)&&finalKey!=null&&sameHandoffContentGeometry(slot,presentationGeometry);
   handoffPresentation=false;presentationReply=null;presentationGeometry=null;presentationLayoutKey=null;presentationLiveKey=null;presentationLiveReady=false;
   if(entrance.pending()==slot&&gestureHandoff){gestureHandoff=false;abandonEntrance(slot);}
   entrance.clearInputBlock();
   Log.i(TAG,"handoff_presentation_result result="+(valid?"completed":"degraded")+" code="+code+" task="+slot.taskId+" stable_start="+(key!=null)+" stable_end="+(finalKey!=null)+" stable_same="+valid);
   for(int i=0;i<session.slots.size();i++)host.inputRole(session.slots.get(i),i==session.primary);
   if(!host.closing()&&!host.stopped()&&!host.backgrounded())host.focusPrimary("handoff_presentation");
   host.scheduleLayoutEvidence("handoff");
  }
 };
 Rect target=host.screenRect(slot.card);
 Bundle data=new Bundle();data.putInt(HandoffProtocol.TASK,slot.sourceTaskId);data.putInt(HandoffProtocol.CONTAINER,host.taskId());
 data.putInt(HandoffProtocol.ROTATION,host.display().getRotation());
 data.putIntArray(HandoffProtocol.TARGET,new int[]{target.left,target.top,target.right,target.bottom});
 data.putFloat(HandoffProtocol.RADIUS,host.cardRadius(true));data.putParcelable(HandoffProtocol.COMPLETE,HandoffProtocol.transport(done));
 if(!putHandoffContentGeometry(slot,data)){cancelHandoffPresentation();return;}
 if(!putHandoffBackdrop(data)){cancelHandoffPresentation();return;}
 presentationGeometry=data;
 presentationReply.send(liveReady?HandoffProtocol.READY:HandoffProtocol.PREPARED,data);
 Log.i(TAG,"handoff_geometry_prepared task="+slot.taskId+" live_ready="+liveReady+" backdrop_ready="+(host.backdrop()==null||host.backdrop().ready));
 if(data.containsKey(HandoffProtocol.CONTENT_SIZE))Log.i(TAG,"handoff_content_host task="+slot.taskId+" buffer="+Arrays.toString(data.getIntArray(HandoffProtocol.CONTENT_SIZE))
   +" crop="+Arrays.toString(data.getFloatArray(HandoffProtocol.CONTENT_CROP))+" quad="+Arrays.toString(data.getFloatArray(HandoffProtocol.CONTENT_QUAD)));
 handler.postDelayed(()->{if(handoffPresentation&&generation==presentationGeneration){
  Log.w(TAG,"handoff_presentation_timeout task="+slot.taskId);cancelHandoffPresentation();
 }},portraitHandoffLimit>0?Math.max(0L,portraitHandoffLimit-SystemClock.uptimeMillis()):handoffHoldMs());
}
boolean putHandoffContentGeometry(WorkbenchSlot slot,Bundle data){
 if(slot.released||slot.surface==null||slot.renderBounds.isEmpty())return false;
 if(host.rotatePresentation(slot)||session.slots.indexOf(slot)!=session.primary)return true;
 int w=slot.surface.getWidth(),h=slot.surface.getHeight();if(w<1||h<1)return false;
 CardMotion.Pose pose=host.surfacePose(slot,w,h);
 float[] pixels=CardMotion.sourceQuad(pose,host.presentationSize(slot)[0],false),quad=pose.quad.clone();
 int[] origin=new int[2];host.stage().getLocationOnScreen(origin);
 for(int i=0;i<8;i+=2){quad[i]+=origin[0];quad[i+1]+=origin[1];}
 data.putIntArray(HandoffProtocol.CONTENT_SIZE,new int[]{slot.renderBounds.width(),slot.renderBounds.height()});
 data.putFloatArray(HandoffProtocol.CONTENT_CROP,new float[]{pixels[0],pixels[1],pixels[4],pixels[5]});
 data.putFloatArray(HandoffProtocol.CONTENT_QUAD,quad);return true;
}
boolean sameHandoffContentGeometry(WorkbenchSlot slot,Bundle data){
 if(data==null)return false;
 Bundle current=new Bundle();
 return putHandoffContentGeometry(slot,current)
   &&Arrays.equals(current.getIntArray(HandoffProtocol.CONTENT_SIZE),data.getIntArray(HandoffProtocol.CONTENT_SIZE))
   &&Arrays.equals(current.getFloatArray(HandoffProtocol.CONTENT_CROP),data.getFloatArray(HandoffProtocol.CONTENT_CROP))
   &&Arrays.equals(current.getFloatArray(HandoffProtocol.CONTENT_QUAD),data.getFloatArray(HandoffProtocol.CONTENT_QUAD));
}
long handoffHoldMs(){
 if(handoffDeadline<=0)return MotionSpec.COVER_TIMEOUT_MS;
 return Math.max(MotionSpec.ADD_CARD_MS,handoffDeadline-SystemClock.uptimeMillis());
}
boolean putHandoffBackdrop(Bundle data){
 try{if(host.backdrop()!=null&&host.backdrop().ready&&host.backdrop().currentBitmap()!=null){
  Bitmap background=host.backdrop().currentBitmap().copy(Bitmap.Config.HARDWARE,false);
  if(background!=null){data.putParcelable(HandoffProtocol.BACKDROP,background);handler.postDelayed(background::recycle,handoffHoldMs());}
 }return true;}catch(Throwable e){Log.w(TAG,"handoff_backdrop_transport_failed",e);return false;}
}
void sendHandoffBackground(){
 if(!handoffPresentation||presentationReply==null||presentationGeometry==null||host.backdrop()==null||!host.backdrop().ready
   ||presentationGeometry.containsKey(HandoffProtocol.BACKDROP)||presentationGeometry.getBoolean("background_sent")||host.stopped()||host.backgrounded()||host.closing())return;
 if(!Objects.equals(presentationLayoutKey,handoffGeometryKey()))return;
 if(!putHandoffBackdrop(presentationGeometry)){cancelHandoffPresentation();return;}
 // A settled fallback has no bitmap; mark it too so polling does not resend it.
 presentationGeometry.putBoolean("background_sent",true);
 presentationReply.send(HandoffProtocol.BACKGROUND,new Bundle(presentationGeometry));
 Log.i(TAG,"handoff_background_prepared task="+presentationGeometry.getInt(HandoffProtocol.TASK));
}
void cancelHandoffPresentation(){
 if(!handoffPresentation)return;
 handoffPresentation=false;presentationGeneration++;entrance.clearInputBlock();
 android.os.ResultReceiver reply=presentationReply;presentationReply=null;
 presentationGeometry=null;presentationLayoutKey=null;presentationLiveKey=null;presentationLiveReady=false;
 if(reply!=null)reply.send(HandoffProtocol.CANCELLED,Bundle.EMPTY);
 for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);
 Log.i(TAG,"handoff_presentation_result result=cancelled");
}
 void acceptGestureSource(WorkbenchSlot fresh){entrance.acceptGestureSource(fresh);gestureHandoff=true;}
 void noteGestureFront(){entrance.noteGestureFront();resumeExistingSwipe();}
 void resumeExistingSwipe(){
  if(existingSwipeSlot==null)return;
  WorkbenchSlot selected=existingSwipeSlot;int task=existingSwipeTask;
  // Start restoration readiness at foreground resume, not while the ordered
  // broadcast is still raising a stopped host. A rebind may briefly publish -1.
  handler.post(()->awaitExistingSwipe(selected,task,SystemClock.uptimeMillis()+MotionSpec.ARRIVAL_UNLOCK_MS));
 }
 void awaitExistingSwipe(WorkbenchSlot selected,int task,long deadline){
  if(existingSwipeSlot!=selected||existingSwipeTask!=task)return;
  if(host.closing()||selected.released||!session.slots.contains(selected)||(selected.taskId>=0&&selected.taskId!=task)){
   existingSwipeSlot=null;existingSwipeTask=-1;Log.w(TAG,"swipe_existing_failed reason=identity_changed task="+task);return;
  }
  if(host.stopped()||host.backgrounded())return;
  boolean bound=selected.taskId==task&&selected.pendingTask<0;
  for(WorkbenchSlot slot:session.slots)bound&=slot.taskId>=0;
  if(bound){
   existingSwipeSlot=null;existingSwipeTask=-1;
   if(session.slots.indexOf(selected)==session.primary){
    // Already-main restoration has no role exchange. Do not enter a preparation
    // barrier whose promote(primary) would return without restoring input roles.
    for(int i=0;i<session.slots.size();i++)host.inputRole(session.slots.get(i),i==session.primary);
    host.focusPrimary("swipe_existing_main");
    Log.i(TAG,"swipe_existing_ready role=primary task="+task+" count="+session.slots.size());
   }else host.queueExistingSwitch(selected,SystemClock.uptimeMillis());
   return;
  }
  if(SystemClock.uptimeMillis()>=deadline){existingSwipeSlot=null;existingSwipeTask=-1;Log.w(TAG,"swipe_existing_failed reason=rebind_timeout task="+task);return;}
  handler.postDelayed(()->awaitExistingSwipe(selected,task,deadline),MotionSpec.SURFACE_RETRY_MS);
 }
 void adoptPendingEntrance(WorkbenchSlot fresh,boolean gesture){entrance.adoptPending(fresh);gestureHandoff=gesture;}
 void resetPendingEntrance(){entrance.resetPending();}
 void blockInput(){entrance.blockInput();}
 void clearInputBlock(){entrance.clearInputBlock();}
 boolean consumeInputBlock(){return entrance.consumeInputBlock();}
 void releasePendingEntrance(WorkbenchSlot slot){entrance.releasePending(slot);}
 void markFrameReady(){entrance.markFrameReady();}
 WorkbenchSlot releaseEntrancePresentation(){
  return entrance.release(new EntranceIntent.ReleaseEffects<WorkbenchSlot>(){
   public WorkbenchSlot shown(){return entranceSlot;}
   public void supersedeHang(){host.supersedeHangEntrance();}
   public void dropHangDue(){host.dropHangEntranceDue();}
   public void closeCover(){host.closeEntranceCover("entrance_released");}
   public void clearFlying(){entranceSlot=null;clearEntranceFlying();}
  });
 }
 void clearEntranceFlying(){if(entranceFlying!=null){if(entranceFlying.getParent()==host.stage())host.stage().removeView(entranceFlying);entranceFlying=null;}}
 void handOffPendingToHang(){entrance.handOffToHang();}
 void consumePendingForMotion(){entrance.consumePending();}
}
