package io.github.xitc.windowdeck;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The accepted swipe's handoff transaction.
 *
 * <p>Runs before the ROM's own gesture-end logic. C17's bridge is static, so the handler is
 * {@code args[0]}. Bail reasons, gesture-owner claim, source identity checks and the
 * finish-callback order stay in this method, in the order the hook used to run them.
 */
final class SwipeReleaseHandoff {
 private static final String TAG="WindowDeck";

 /**
  * Runs before the ROM's own gesture-end logic.
  *
  * C17's bridge is {@code static void (handler, float, PointF, PointF, PointF, boolean, boolean, int)},
  * so the receiver is {@code args[0]} and the {@code PointF}s sit one slot further along than they
  * did when this was an instance method. The trailing {@code int} is the Kotlin default-argument
  * mask; the two booleans are read raw, which is the conservative reading.
  */
 static void release(XC_MethodHook.MethodHookParam p){
  Object handler=p.args[0];
  if(handler==null){log("release bail=no_handler");return;}
  View panel=(View)XposedHelpers.getObjectField(handler,RomSymbols.HANDLER_TRIGGER_PANEL);
  LauncherSwipeHook.State s=LauncherSwipeHook.stateOf(panel);
  // Every bail below used to be silent, which made "no log" indistinguishable from "the hook never
  // ran". One line on entry plus one per reason is what tells those two apart.
  log("release zone="+(s==null||s.zone()==null?"none":s.zone().getVisibility())+" progress="+(s==null?"-":s.progress)+" trigger="+(s==null?"-":s.trigger)+" selected="+(s!=null&&s.motion.selected()));
  if(s==null||s.zone()==null){log("release bail=no_state");return;}
  if(s.zone().getVisibility()!=View.VISIBLE){log("release bail=zone_hidden");return;}
  if(!SwipePanelPolicy.selectable(s.progress,s.trigger)){log("release bail=below_trigger");return;}
  if(Boolean.TRUE.equals(p.args[RomSymbols.BRIDGE_ARG_BOOLEAN])||Boolean.TRUE.equals(p.args[RomSymbols.BRIDGE_ARG_BOOLEAN+1])){log("release bail=cancelled");return;}
  PointF up=(PointF)p.args[RomSymbols.BRIDGE_ARG_UP_POS];if(up==null||!Float.isFinite(up.x)||!Float.isFinite(up.y)){log("release bail=invalid_up");return;}
  int rotation=rotation(panel),axis=axisCode(panel),offset=centerOffset(panel);
  if(!LauncherSwipeHook.midActive()||!SwipePanelPolicy.releaseSelected(s.progress,s.trigger,offset,dp(panel.getContext(),64),axis)){
   log("release bail=outside_entry axis="+axis+" offset="+offset+" progress="+s.progress+" trigger="+s.trigger);return;
  }
  SwipeCandidate.Match candidate=CandidateProbe.match(handler);
  if(candidate==null||candidate.task!=s.gestureTask||(s.candidate!=null&&(candidate.task!=s.candidate.task||candidate.container!=s.candidate.container))){
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
  RectF sourceRect=null;float sourceRadius=0;float[] sourceCrop=null,sourceMatrix=null;
  try{
   Object[] handles=(Object[])XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES);
   // A fullscreen single task only. Split/multi-target geometry is deliberately not guessed.
   if(handles!=null&&handles.length==1){
    Object simulator=XposedHelpers.callMethod(handles[0],RomSymbols.SOURCE_SIMULATOR);
    android.graphics.Matrix matrix=new android.graphics.Matrix((android.graphics.Matrix)XposedHelpers.getObjectField(simulator,RomSymbols.SOURCE_MATRIX));
    RectF rect=new RectF((RectF)XposedHelpers.callMethod(simulator,RomSymbols.SOURCE_CROP));
    sourceCrop=new float[]{rect.left,rect.top,rect.right,rect.bottom};sourceMatrix=new float[9];matrix.getValues(sourceMatrix);matrix.mapRect(rect);
    sourceRect=rect;sourceRadius=matrix.mapRadius(((Number)XposedHelpers.callMethod(simulator,RomSymbols.SOURCE_RADIUS)).floatValue());
   }
  }catch(Throwable e){Log.w(TAG,"swipe_source_geometry_unavailable",e);}
  final RectF releaseRect=sourceRect;final float releaseRadius=sourceRadius;
  final float[] releaseCrop=sourceCrop,releaseMatrix=sourceMatrix;
  final float[] releaseVelocity=s.source.motion().velocity(candidate.task,released);
  Log.i(TAG,"swipe_source_velocity task="+candidate.task+" measured="+(releaseVelocity!=null)+" edges_px_ms="+java.util.Arrays.toString(releaseVelocity));
  final boolean landscape=SwipePanelPolicy.landscapeStack(rotation);
  final boolean deferPortrait=landscape&&candidate.container<0;
  final SourceRelease sourceRelease=deferPortrait?new SourceRelease():null;
  final Runnable[] releaseSource={null};
  final android.os.ResultReceiver[] sourceReleaseReply={null};
  android.app.Activity sourceActivity=deferPortrait?SwipeSourceFrame.activity(panel):null;
  // A first portrait task uses the same release-layer capture as the rotation bridge,
  // but retains its own task crop. Existing containers and rotated sources keep their path.
  final boolean portraitRequested=!landscape&&candidate.container<0&&releaseRect!=null;
  SwipeSourceFrame.Capture capturedSource=(deferPortrait||portraitRequested)?SwipeSourceFrame.capture(handler,candidate.task):null;
  if(portraitRequested&&capturedSource==null)Log.i(TAG,"swipe_portrait_content skipped=source_layer_unavailable");
  if(!deferPortrait&&capturedSource!=null&&(capturedSource.left!=0||capturedSource.top!=0||!PortraitContentGeometry.sourceValid(capturedSource.width,capturedSource.height,releaseCrop,
    new float[]{releaseRect.left,releaseRect.top,releaseRect.right,releaseRect.bottom},releaseMatrix))){
   capturedSource.leash.release();capturedSource.closeVisibility();capturedSource=null;
   Log.i(TAG,"swipe_portrait_content skipped=unsupported_source_geometry");
  }
  final SwipeSourceFrame.Capture capture=capturedSource;
  final boolean portraitPixels=capture!=null&&!deferPortrait;
  final PortraitHandoffDeadline portraitWait=portraitPixels?new PortraitHandoffDeadline(candidate.task,released,MotionSpec.COVER_TIMEOUT_MS):null;
  if(deferPortrait&&(sourceActivity==null||capture==null||releaseRect==null)){
   if(capture!=null){capture.leash.release();capture.closeVisibility();}
   Log.w(TAG,"swipe_add_degraded reason=release_source_unavailable native_release=true");return;
  }
  final HandoffCover[] sourceCover={null};
  // Run in parallel with captureDisplay. The reply cannot run on the main
  // thread until this release hook has attached and armed its source cover.
  if(capture!=null)SwipeSourceFrame.capturePixels(capture,manager,gestureController,main,sourceCover);
  final Bitmap taskFrame=s.source.takeFrame(candidate.task);
  final HandoffCover cover;
  try{
   if(deferPortrait){HandoffCover.recycle(taskFrame);cover=HandoffCover.createSource(sourceActivity);}
   else cover=HandoffCover.create(panel.getContext(),landscape,taskFrame,false);
   if(portraitPixels&&!cover.preparePortraitSource(candidate.task,capture.width,capture.height,releaseCrop,releaseRect,releaseMatrix,released))
    cover.close("portrait_source_geometry_unavailable");
  }catch(Throwable e){if(capture!=null)capture.closeVisibility();Log.w(TAG,"swipe_add_degraded reason=source_window_unavailable native_release=true",e);return;}
  sourceCover[0]=cover;
  if(portraitWait!=null)cover.portraitDeadline(portraitWait);
  if(!LauncherSwipeHook.claimHandoff(handler,!cover.isClosed())){
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
   cover.concealUnderlay(()->SwipeLauncherVeil.conceal(handler,underlayRoot,underlayStop,!deferPortrait,capture));
   cover.hideUnderlay();
  }
  if(landscape&&releaseRect!=null)
   SwipeSourceFrame.planLandscape(panel,cover,releaseRect,releaseRadius,releaseVelocity,released);
  java.util.concurrent.atomic.AtomicBoolean recentsEnded=new java.util.concurrent.atomic.AtomicBoolean();
  Runnable endRecents=()->{
   if(!recentsEnded.compareAndSet(false,true))return;
   try{
    if(gestureController!=null&&XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER)==gestureController)
     XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_FINISH_RECENTS,false);
   }catch(Throwable e){Log.w(TAG,"swipe_handoff_finish_failed",e);}
  };
  java.util.concurrent.atomic.AtomicBoolean finished=new java.util.concurrent.atomic.AtomicBoolean();
  final HandoffPreparation<Bundle> preparation=new HandoffPreparation<>(SwipeReleaseHandoff::sameHandoffGeometry);
  java.util.concurrent.atomic.AtomicBoolean motionTransferred=new java.util.concurrent.atomic.AtomicBoolean();
  final android.os.ResultReceiver[] completion={null};
  final int[] outcome={HandoffProtocol.FAILED};
  Runnable finish=()->{
   if(!finished.compareAndSet(false,true))return;
   if(portraitWait!=null)portraitWait.cancel();
   if(sourceRelease!=null)sourceRelease.cancel();
   android.os.ResultReceiver ack=completion[0];completion[0]=null;
   underlayStop.set(true);SwipeLauncherVeil.restore(underlayRoot);
   if(capture!=null)capture.closeVisibility();
   endRecents.run();cover.close("handoff_finished");
   if(ack!=null)ack.send(outcome[0],Bundle.EMPTY);
   Log.i(TAG,"swipe_handoff_result result="+outcome[0]+" task="+candidate.task);
   Log.i(TAG,"swipe_handoff_finish ms="+(SystemClock.uptimeMillis()-released));
  };
  cover.onClosed(why->{
   if(!"handoff_finished".equals(why)){
    underlayStop.set(true);
    SwipeLauncherVeil.restore(underlayRoot);
    Log.w(TAG,"swipe_cover_failed reason="+why);finish.run();
   }
  });
  android.os.ResultReceiver localReady=new android.os.ResultReceiver(main){
   @Override protected void onReceiveResult(int code,Bundle data){
    if(code==HandoffProtocol.EXISTING_ACCEPTED){
     outcome[0]=HandoffProtocol.READY;
     Log.i(TAG,"swipe_existing_handoff accepted=true task="+candidate.task+" container="+candidate.container);
     finish.run();return;
    }
    if(code==HandoffProtocol.HOST_STARTED){
     if(finished.get()||portraitWait==null)return;
     if(data==null||!portraitWait.hostStarted(data.getInt(HandoffProtocol.TASK,-1),data.getInt(HandoffProtocol.CONTAINER,-1),
       data.getLong(HandoffProtocol.READY_DEADLINE,0),SystemClock.uptimeMillis())){
      Log.w(TAG,"swipe_portrait_host_rejected");finish.run();return;
     }
     cover.portraitDeadline(portraitWait);
     Log.i(TAG,"swipe_portrait_host_started task="+candidate.task+" ready_deadline="+portraitWait.deadline()+" limit="+portraitWait.limit());return;
    }
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
      ||(portraitWait!=null&&!portraitWait.matches(data.getInt(HandoffProtocol.TASK,-1),data.getInt(HandoffProtocol.CONTAINER,-1)))
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
    if(!cover.alignPortraitTarget(data.getInt(HandoffProtocol.ROTATION),data.getIntArray(HandoffProtocol.CONTENT_SIZE),
      data.getFloatArray(HandoffProtocol.CONTENT_CROP),data.getFloatArray(HandoffProtocol.CONTENT_QUAD),proposedRect)){
     if(completion[0]==null)completion[0]=data.getParcelable(HandoffProtocol.COMPLETE,android.os.ResultReceiver.class);
     Log.w(TAG,"swipe_content_geometry_failed task="+candidate.task);finish.run();return;
    }
    HandoffPreparation.Event event=code==HandoffProtocol.READY?HandoffPreparation.Event.READY:
      code==HandoffProtocol.BACKGROUND?HandoffPreparation.Event.BACKGROUND:HandoffPreparation.Event.PREPARED;
    Bitmap background=data.getParcelable(HandoffProtocol.BACKDROP,android.graphics.Bitmap.class);
    boolean hasBackdrop=background!=null&&!background.isRecycled()&&background.getWidth()>0&&background.getHeight()>0;
    boolean safe=code==HandoffProtocol.READY||cover.ownsTravel()||cover.canPrepare(releaseRect,proposedRect,data.getInt(HandoffProtocol.ROTATION),data.getFloat(HandoffProtocol.RADIUS),hasBackdrop);
    HandoffPreparation.Action action=preparation.receive(event,new Bundle(data),safe);
    if(action==HandoffPreparation.Action.REJECT){Log.w(TAG,"swipe_host_geometry_changed");finish.run();return;}
    if(action==HandoffPreparation.Action.IGNORE)return;
    if(code==HandoffProtocol.READY&&portraitWait!=null){
     if(!portraitWait.liveReady(candidate.task,data.getInt(HandoffProtocol.CONTAINER,-1),SystemClock.uptimeMillis())){
      if(completion[0]==null)completion[0]=data.getParcelable(HandoffProtocol.COMPLETE,android.os.ResultReceiver.class);
      Log.w(TAG,"swipe_portrait_live_timeout");finish.run();return;
     }
     cover.portraitDeadline(portraitWait);
    }
    if(action==HandoffPreparation.Action.WAIT){
     Log.i(TAG,"swipe_prepared_wait reason=background_exposed");return;
    }
    if(action==HandoffPreparation.Action.BACKGROUND){
     cover.prepareBackdrop(data.getParcelable(HandoffProtocol.BACKDROP,android.graphics.Bitmap.class));return;
    }
    if(action==HandoffPreparation.Action.LIVE){
     cover.allowReveal(data.getParcelable(HandoffProtocol.BACKDROP,android.graphics.Bitmap.class));return;
    }
    if(code==HandoffProtocol.BACKGROUND)Log.i(TAG,"swipe_prepared_resumed reason=background_ready");
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
  if(portraitWait==null)main.postDelayed(()->{if(!finished.get()){Log.w(TAG,"swipe_host_ready_timeout");finish.run();}},hostWait);
  else{
   Runnable timeout=new Runnable(){public void run(){
    if(finished.get())return;
    long now=SystemClock.uptimeMillis();
    if(portraitWait.expired(now)){Log.w(TAG,"swipe_portrait_timeout phase="+portraitWait.phase());finish.run();return;}
    main.postDelayed(this,portraitWait.deadline()-now);
   }};main.postDelayed(timeout,Math.max(0L,portraitWait.deadline()-SystemClock.uptimeMillis()));
  }
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
    if(deferPortrait)SwipeLauncherVeil.logLeashes(handler);
    if(deferPortrait){
     if(sourceReleaseReply[0]==null||!sourceRelease.completeRelease()){finish.run();return;}
     // FlexibleTaskView may now use the same task surface. Stop source alpha
     // writes before handing it over and release only our retained handle.
     underlayStop.set(true);SwipeLauncherVeil.restore(underlayRoot);
     if(capture!=null)capture.closeVisibility();
     Bundle done=new Bundle();done.putInt(HandoffProtocol.TASK,candidate.task);
     sourceReleaseReply[0].send(HandoffProtocol.SOURCE_RELEASED,done);return;
    }
    Runnable launch=()->{if(!finished.get())dispatchHandoff(context,candidate,released,ready,finish,deferPortrait,rotation,portraitWait);};
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
    dispatchHandoff(context,candidate,released,ready,finish,true,rotation,portraitWait);
   }else releaseSource[0].run();
  });
  Log.i(TAG,"swipe_add_requested task="+candidate.task+" container="+candidate.container);
  s.motion.resetExpansion();s.zone().setVisibility(View.GONE);s.candidate=null;LauncherSwipeHook.deactivateMid();p.setResult(null);
 }

 private static boolean sameHandoffGeometry(Bundle first,Bundle next){
  return first.getInt(HandoffProtocol.TASK)==next.getInt(HandoffProtocol.TASK)
   &&first.getInt(HandoffProtocol.CONTAINER)==next.getInt(HandoffProtocol.CONTAINER)
   &&first.getInt(HandoffProtocol.ROTATION)==next.getInt(HandoffProtocol.ROTATION)
   &&Float.compare(first.getFloat(HandoffProtocol.RADIUS),next.getFloat(HandoffProtocol.RADIUS))==0
   &&java.util.Arrays.equals(first.getIntArray(HandoffProtocol.TARGET),next.getIntArray(HandoffProtocol.TARGET))
   &&java.util.Arrays.equals(first.getIntArray(HandoffProtocol.CONTENT_SIZE),next.getIntArray(HandoffProtocol.CONTENT_SIZE))
   &&java.util.Arrays.equals(first.getFloatArray(HandoffProtocol.CONTENT_CROP),next.getFloatArray(HandoffProtocol.CONTENT_CROP))
   &&java.util.Arrays.equals(first.getFloatArray(HandoffProtocol.CONTENT_QUAD),next.getFloatArray(HandoffProtocol.CONTENT_QUAD));
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

 private static void dispatchHandoff(Context context,SwipeCandidate.Match candidate,long released,android.os.ResultReceiver ready,Runnable finish,boolean deferPortrait,int sourceRotation,PortraitHandoffDeadline portraitWait){
  Log.i(TAG,"swipe_handoff_begin ms="+(SystemClock.uptimeMillis()-released));
  if(candidate.container<0){
   try{
    Intent create=new Intent().setComponent(new ComponentName("com.oplus.pscanvas","com.oplus.pscanvas.canvasmode.canvas.ContainerActivity"));
    create.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION);
    create.putExtra("windowdeck_workbench_v1",true);
    create.putExtra("windowdeck_create_source_task",candidate.task);
    create.putExtra("windowdeck_create_source_user",0);
    create.putExtra("windowdeck_handoff_ready",ready);
    if(portraitWait!=null)create.putExtra(HandoffProtocol.PORTRAIT_LIMIT,portraitWait.limit());
    if(deferPortrait){create.putExtra("windowdeck_defer_portrait",true);create.putExtra("windowdeck_defer_rotation",sourceRotation);create.putExtra("windowdeck_wait_source_release",true);}
    context.startActivity(create,TaskFrontBinder.handoffOptions(context));
    Log.i(TAG,"swipe_create_direct task="+candidate.task+" ms="+(SystemClock.uptimeMillis()-released));
    return;
   }catch(Throwable e){Log.w(TAG,"swipe_create_direct_failed",e);}
  }else try{
   Intent add=new Intent((BuildIdentity.APPLICATION_ID+".LAUNCHER_ADD_TO_WORKBENCH")).setPackage("com.oplus.pscanvas");
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
     else if(result==4){
      try{TaskFrontBinder.moveToFront(context,candidate.container);ready.send(HandoffProtocol.EXISTING_ACCEPTED,Bundle.EMPTY);}
      catch(Throwable e){Log.w(TAG,"swipe_existing_front_failed",e);finish.run();}
     }
     else if(result==3)finish.run();
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
  try{if(context.getContentResolver().call(Uri.parse(BuildIdentity.STATE_URI),"add_task",null,message)!=null)return;}
  catch(Throwable e){Log.w(TAG,"swipe_provider_fallback",e);}
  try{
   Intent request=new Intent().setComponent(new ComponentName(BuildIdentity.APPLICATION_ID,"io.github.xitc.windowdeck.IngressActivity"));
   request.putExtras(message);request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
   context.startActivity(request,ActivityOptions.makeBasic().setShareIdentityEnabled(true).toBundle());
  }catch(Throwable e){Log.e(TAG,"swipe_add_dispatch_failed",e);}
 }

 private static int rotation(View panel){try{return XposedHelpers.getIntField(panel,RomSymbols.PANEL_ROTATION);}catch(Throwable ignored){return 0;}}
 private static int centerOffset(View panel){return XposedHelpers.getIntField(panel,RomSymbols.PANEL_CENTER_OFFSET);}
 private static int axisCode(View panel){
  try{return XposedHelpers.getIntField(panel,RomSymbols.PANEL_AXIS_CODE);}
  catch(Throwable e){Log.w(TAG,"swipe_axis_read_failed",e);return 0;}
 }
 private static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
 private static void log(String message){
  Log.i(TAG,message);
  try{XposedBridge.log("WindowDeck "+message);}catch(Throwable ignored){}
 }
}
