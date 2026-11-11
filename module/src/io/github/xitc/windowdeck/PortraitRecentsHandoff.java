package io.github.xitc.windowdeck;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.ParcelableColorSpace;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Owns the matching Recents leash until the portrait host has committed its bootstrap frame. */
final class PortraitRecentsHandoff {
 private static final String TAG="WindowDeck";
 private static volatile PortraitRecentsHandoff poseOwner;

 private static final class SourcePose {
  final Matrix transform=new Matrix();
  final Rect crop;
  final float radius;
  SourcePose(Rect crop,float sx,float sy,float x,float y,float radius){
   transform.setScale(sx,sy);transform.postTranslate(x,y);
   this.crop=crop;this.radius=radius;
  }
 }

 /** Native simulator callbacks can still emit the last finger pose during the release. */
 static boolean retainPose(Object simulator,Object builder,Object target,CornerGestureContract contract) throws Exception {
  PortraitRecentsHandoff run=poseOwner;
  if(run==null||simulator!=run.simulator||target==null||!run.ownsSource())return false;
  SourcePose pose=run.ownedPose;
  if(pose==null||XposedHelpers.getIntField(target,RomSymbols.SOURCE_TASK)!=run.candidate.task)return false;
  SurfaceControl leash=(SurfaceControl)XposedHelpers.getObjectField(target,RomSymbols.SOURCE_LEASH);
  if(LiveTaskContent.layerId(leash)!=run.source.layer)return false;
  // Builder methods may retain their inputs until submission.
  contract.matrix.invoke(builder,new Matrix(pose.transform));
  contract.clip.invoke(builder,new Rect(pose.crop));
  contract.corners.invoke(builder,pose.radius,2.7f);
  if(!run.nativePoseLogged){
   run.nativePoseLogged=true;
   Log.i(TAG,"portrait_recents_native_pose_retained task="+run.candidate.task+" ms="+run.elapsed());
  }
  return true;
 }

 private final Handler main=new Handler(Looper.getMainLooper());
 private final SourceRelease release=new SourceRelease();
 private final Runnable watchdog=this::watchDeadline;
 private final Context context;
 private final Display display;
 private final Object manager,controller,simulator;
 private volatile SourcePose ownedPose;
 private boolean nativePoseLogged;
 private final SwipeCandidate.Match candidate;
 private final SwipeSourceFrame.Capture source;
 private final RectF from;
 private final Rect fromCrop;
 private final int[] target,addBox;
 private final float[] addQuad;
 private float progress;
 private final float fromRadius,toRadius;
 private final Method matrix,radius;
 private final long released;
 private final PortraitHandoffDeadline deadline;
 private ValueAnimator motion;
 private Bitmap snapshot;
 private HandoffCover terminalCover,addPreview;
 private ResultReceiver hostReply;
 private int container=-1;
 private boolean hostPoseCommitted;
 private boolean hostLaunchPosted,terminalCommitted,terminalCaptureStarted,terminalCoverReady,poseSent,finishRequested,closed;

 static boolean start(View panel,Object handler,Object manager,Object controller,SwipeCandidate.Match candidate,
   SwipeSourceFrame.Capture source,RectF from,float[] crop,float radius,long released){
  Display display=panel.getDisplay();
  if(!CornerSwipeHook.available()||poseOwner!=null||controller==null||!Float.isFinite(radius)||radius<0||display==null||display.getRotation()!=0)return false;
  try{
   WindowManager wm=panel.getContext().getSystemService(WindowManager.class);
   WindowInsets insets=panel.getRootWindowInsets();
   if(wm==null||insets==null||insets.isVisible(WindowInsets.Type.ime()))return false;
   Rect bounds=wm.getMaximumWindowMetrics().getBounds();
   if(!bounds.equals(new Rect(0,0,source.width,source.height))||!source.visibility.isValid()
     ||from.left<0||from.top<0||from.right>source.width||from.bottom>source.height)return false;
   Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
   int top=insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout()).top;
   Context context=panel.getContext();
   int gap=Math.max(1,Ui.dp(context,1));
   if(gap*48!=Ui.dp(context,48))return false;
   int stageW=source.width-bars.left-bars.right,stageH=source.height-top-bars.bottom;
   Bundle settings=MainWindowSettings.readLayout(context);
   CardLayout.Result layout=LandscapeEntrance.singleLayout(stageW,stageH,gap,source.width,source.height,
     settings.getInt(MainWindowSettings.PREF,CardLayout.MAIN_DEFAULT_PERCENT));
   if(layout==null)return false;
   int[] target=LandscapeEntrance.screenBox(layout.cards[0],bars.left,top);
   if(target[0]<0||target[1]<0||target[2]>source.width||target[3]>source.height
     ||new RectF(target[0],target[1],target[2],target[3]).equals(from))return false;
   Method matrix=SurfaceControl.Transaction.class.getDeclaredMethod("setMatrix",SurfaceControl.class,float.class,float.class,float.class,float.class);
   Method round=SurfaceControl.Transaction.class.getDeclaredMethod("setCornerRadius",SurfaceControl.class,float.class);
   matrix.setAccessible(true);round.setAccessible(true);
   Object[] handles=(Object[])XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES);
   if(handles==null||handles.length!=1)return false;
   Object simulator=XposedHelpers.callMethod(handles[0],RomSymbols.SOURCE_SIMULATOR);
   if(simulator==null)return false;
   int[] add=LandscapeEntrance.screenBox(layout.add,bars.left,top);
   // A transparent preview is above Recents; leave any overlapping chrome to the host.
   float[] addQuad=null;
   if(from.left>=add[2]&&target[0]>=add[2]){
    int w=add[2]-add[0],h=add[3]-add[1];
    addQuad=settings.getBoolean(SideWindowSettings.PREF,AtomicPresentation.DEFAULT)&&supportsPerspective()?
      CardPerspective.railTargetQuad(w,h,PaneLayout.LEFT_RIGHT,context.getResources().getDisplayMetrics().density,
        true,add[0],add[1],source.width,source.height,PaneLayout.leftRightSideW(stageW,gap)):
      new float[]{0,0,w,0,w,h,0,h};
   }
   PortraitRecentsHandoff run=new PortraitRecentsHandoff(context,display,manager,controller,simulator,candidate,source,from,crop,radius,target,add,addQuad,matrix,round,released);
   if(!LauncherSwipeHook.claimHandoff(handler,true))return false;
   try{run.begin();}
   catch(Throwable e){Log.w(TAG,"portrait_recents_begin_failed",e);run.finish(HandoffProtocol.FAILED,"source_start");}
   return true;
  }catch(Exception e){Log.w(TAG,"portrait_recents_unavailable",e);return false;}
 }

 private static boolean supportsPerspective(){
  try{
   SurfaceControl.Transaction.class.getDeclaredMethod("setTransformationMatrix4x4",SurfaceControl.class,float[].class).setAccessible(true);
   return true;
  }catch(Exception e){return false;}
 }

 private PortraitRecentsHandoff(Context context,Display display,Object manager,Object controller,Object simulator,SwipeCandidate.Match candidate,
   SwipeSourceFrame.Capture source,RectF from,float[] crop,float fromRadius,int[] target,int[] addBox,float[] addQuad,Method matrix,Method radius,long released){
  this.context=context;this.display=display;this.manager=manager;this.controller=controller;this.simulator=simulator;
  this.candidate=candidate;this.source=source;
  this.from=new RectF(from);fromCrop=new Rect(Math.round(crop[0]),Math.round(crop[1]),Math.round(crop[2]),Math.round(crop[3]));
  this.fromRadius=fromRadius;this.target=target;this.addBox=addBox;this.addQuad=addQuad;toRadius=Ui.dp(context,Ui.MAIN_RADIUS);
  this.matrix=matrix;this.radius=radius;this.released=released;
  deadline=new PortraitHandoffDeadline(candidate.task,released,MotionSpec.COVER_TIMEOUT_MS);
 }

 private void begin(){
  // Source pixels are captured in task coordinates, independent of the moving parent leash.
  Thread captureThread=new Thread(()->{
   Bitmap pixels=null;
   try{
    pixels=LiveTaskContent.captureFrame(source.leash,source.width,source.height,source.height);
    Bitmap buffer=HandoffCover.taskFrame(pixels)?pixels.copy(Bitmap.Config.HARDWARE,false):null;
    main.post(()->{
     if(closed){HandoffCover.recycle(buffer);return;}
     if(buffer==null||buffer.getWidth()!=source.width||buffer.getHeight()!=source.height){
      HandoffCover.recycle(buffer);finish(HandoffProtocol.FAILED,"source_pixels");return;
     }
     snapshot=buffer;sendPose();
    });
   }catch(Throwable e){main.post(()->finish(HandoffProtocol.FAILED,"source_capture"));}
   finally{HandoffCover.recycle(pixels);source.leash.release();}
  },"windowdeck-recents-pixels");
  try{captureThread.start();}
  catch(Throwable e){source.leash.release();throw e;}
  if(!SwipeReleaseHandoff.keepSourceVisible(controller)){finish(HandoffProtocol.FAILED,"source_visibility");return;}
  ownedPose=sourcePose(0);
  poseOwner=this;
  ValueAnimator run=ValueAnimator.ofFloat(0,1);
  motion=run;run.setDuration(MotionSpec.PORTRAIT_HANDOFF_MS);run.setInterpolator(null);
  run.addUpdateListener(a->{
   if(closed||motion!=a)return;
   try{
    if(!ownsSource()){finish(HandoffProtocol.FAILED,"source_changed");return;}
    float time=(float)a.getAnimatedValue();
    if(time>0&&time<1){
     writeFrame(EntranceMotion.c17ZoomFraction(time),false);
     launchAfterFirstFrame();
    }
   }catch(Exception e){Log.w(TAG,"portrait_recents_frame_failed",e);finish(HandoffProtocol.FAILED,"source_frame");}
  });
  run.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(closed||motion!=a)return;
   motion=null;
   try{
    if(!ownsSource()){finish(HandoffProtocol.FAILED,"source_changed");return;}
    writeFrame(1,true);
    launchAfterFirstFrame();
   }catch(Exception e){Log.w(TAG,"portrait_recents_final_frame_failed",e);finish(HandoffProtocol.FAILED,"source_final_frame");}
  }});
  Log.i(TAG,"swipe_add_requested task="+candidate.task+" container=-1");
  Log.i(TAG,"portrait_recents_motion task="+candidate.task+" ms="+elapsed()+" duration_ms="+MotionSpec.PORTRAIT_HANDOFF_MS
    +" source="+from+" target="+Arrays.toString(target));
  run.start();
  main.postAtTime(watchdog,deadline.deadline());
 }

 private void launchAfterFirstFrame(){
  if(hostLaunchPosted)return;
  hostLaunchPosted=true;
  Log.i(TAG,"portrait_recents_first_frame_submitted task="+candidate.task+" ms="+elapsed());
  main.post(()->{
   if(closed)return;
   if(addQuad!=null){
    addPreview=HandoffCover.createAddPreview(context,addBox,addQuad,progress);
    if(!addPreview.isClosed())Log.i(TAG,"portrait_recents_add_preview task="+candidate.task+" ms="+elapsed());
   }
   try{
    ResultReceiver receiver=new ResultReceiver(main){
     @Override protected void onReceiveResult(int code,Bundle data){receive(code,data);}
    };
    SwipeReleaseHandoff.launchContainer(context.getApplicationContext(),candidate,released,HandoffProtocol.transport(receiver),true,0,deadline,true);
   }catch(Throwable e){Log.w(TAG,"portrait_recents_launch_failed",e);finish(HandoffProtocol.FAILED,"host_launch");}
  });
 }

 private SourcePose sourcePose(float f){
  Rect crop=new Rect(Math.round(blend(fromCrop.left,0,f)),Math.round(blend(fromCrop.top,0,f)),
    Math.round(blend(fromCrop.right,source.width,f)),Math.round(blend(fromCrop.bottom,source.height,f)));
  float left=blend(from.left,target[0],f),top=blend(from.top,target[1],f);
  float sx=(blend(from.right,target[2],f)-left)/crop.width(),sy=(blend(from.bottom,target[3],f)-top)/crop.height();
  return new SourcePose(crop,sx,sy,left-crop.left*sx,top-crop.top*sy,blend(fromRadius,toRadius,f)/Math.max(sx,sy));
 }

 private void writeFrame(float f,boolean commit) throws Exception {
  SourcePose pose=sourcePose(f);
  ownedPose=pose;
  float[] m=new float[9];pose.transform.getValues(m);
  try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
   matrix.invoke(tx,source.visibility,m[0],m[3],m[1],m[4]);
   tx.setPosition(source.visibility,m[2],m[5]);
   tx.setCrop(source.visibility,pose.crop);
   radius.invoke(tx,source.visibility,pose.radius);
   if(commit)tx.addTransactionCommittedListener(main::post,()->{
    if(closed)return;
    terminalCommitted=true;
    Log.i(TAG,"portrait_recents_motion_committed task="+candidate.task+" ms="+elapsed());
    sendPose();
   });
   tx.apply();
  }
  progress=f;
  if(addPreview!=null)addPreview.updateAddPreview(f);
 }

 private void receive(int code,Bundle data){
  ResultReceiver reply=data==null?null:data.getParcelable(HandoffProtocol.POSE_REPLY,ResultReceiver.class);
  if(closed){if(code==HandoffProtocol.POSE_QUERY&&reply!=null)reply.send(HandoffProtocol.FAILED,Bundle.EMPTY);return;}
  if(code==HandoffProtocol.FAILED||code==HandoffProtocol.CANCELLED){finish(code,"host_rejected");return;}
  int task=data==null?-1:data.getInt(HandoffProtocol.TASK,-1),host=data==null?-1:data.getInt(HandoffProtocol.CONTAINER,-1);
  if(code==HandoffProtocol.HOST_STARTED){
   if(!deadline.hostStarted(task,host,data==null?0:data.getLong(HandoffProtocol.READY_DEADLINE),SystemClock.uptimeMillis())){
    finish(HandoffProtocol.FAILED,"host_identity");return;
   }
   container=host;
   main.removeCallbacks(watchdog);main.postAtTime(watchdog,deadline.deadline());
   return;
  }
  if(task!=candidate.task||container<0||host!=container){finish(HandoffProtocol.FAILED,"pose_identity");return;}
  switch(code){
   case HandoffProtocol.POSE_QUERY:
    if(hostReply!=null||reply==null){finish(HandoffProtocol.FAILED,"pose_reply");return;}
    hostReply=reply;sendPose();return;
   case HandoffProtocol.BOOTSTRAP_COMMITTED:
    if(!poseSent||!release.commitBootstrap()||!release.requestRelease())return;
    releaseSource();return;
   case HandoffProtocol.POSE_COMMITTED:
    if(!release.mayEmbed()){finish(HandoffProtocol.FAILED,"early_live");return;}
    if(hostPoseCommitted)return;
    hostPoseCommitted=true;
    closeAddPreview();
    if(terminalCover!=null)terminalCover.fadeTerminal();
    else hostReply.send(HandoffProtocol.OWNERSHIP,Bundle.EMPTY);
    return;
   case HandoffProtocol.READY:
    ResultReceiver complete=data.getParcelable(HandoffProtocol.COMPLETE,ResultReceiver.class);
    if(!release.mayEmbed()||complete==null||!deadline.liveReady(task,host,SystemClock.uptimeMillis())){
     if(complete!=null)complete.send(HandoffProtocol.FAILED,Bundle.EMPTY);
     finish(HandoffProtocol.FAILED,"live_identity");return;
    }
    complete.send(HandoffProtocol.READY,Bundle.EMPTY);
    finish(HandoffProtocol.READY,"completed");return;
   default:
    finish(HandoffProtocol.FAILED,"unexpected_reply");
  }
 }

 private void sendPose(){
  if(closed||poseSent||!terminalCommitted||snapshot==null||hostReply==null)return;
  if(!ownsSource()){finish(HandoffProtocol.FAILED,"source_changed");return;}
  if(!terminalCoverReady){prepareTerminalCover();return;}
  try(HardwareBuffer buffer=snapshot.getHardwareBuffer()){
   Bundle pose=new Bundle();
   float l=target[0],t=target[1],r=target[2],b=target[3];
   float[] crop={0,0,source.width,source.height};
   pose.putInt(HandoffProtocol.TASK,candidate.task);
   pose.putFloatArray(HandoffProtocol.CORNERS,new float[]{l,t,r,t,r,b,l,b});
   pose.putFloatArray(HandoffProtocol.CROP,new float[]{l,t,r,b});
   pose.putFloatArray(HandoffProtocol.SNAPSHOT_CROP,crop);
   pose.putIntArray(HandoffProtocol.POSE_STAGE,new int[]{source.width,source.height,0});
   pose.putFloat(HandoffProtocol.RADIUS,toRadius);
   pose.putParcelable(HandoffProtocol.SNAPSHOT,buffer);
   pose.putParcelable(HandoffProtocol.SNAPSHOT_COLOR_SPACE,new ParcelableColorSpace(snapshot.getColorSpace()));
   hostReply.send(HandoffProtocol.POSE_QUERY,pose);poseSent=true;
  }catch(Exception e){Log.w(TAG,"portrait_recents_pose_failed",e);finish(HandoffProtocol.FAILED,"pose_send");}
  finally{HandoffCover.recycle(snapshot);snapshot=null;}
 }

 private void prepareTerminalCover(){
  if(terminalCaptureStarted)return;
  terminalCaptureStarted=true;
  // SF transaction commit is earlier than display presentation. Capture after
  // the terminal leash has had a chance to become the displayed frame.
  Choreographer choreographer=Choreographer.getInstance();
  choreographer.postFrameCallback(first->choreographer.postFrameCallback(second->{
   if(closed)return;
   if(!ownsSource()){finish(HandoffProtocol.FAILED,"source_changed");return;}
   int displayId=display.getDisplayId();
   SurfaceControl previewLayer=addPreview==null?null:addPreview.previewLayer();
   new Thread(()->{
    Bitmap withoutPreview=HandoffCover.captureNow(displayId,previewLayer);
    if(withoutPreview!=null){
     main.post(()->attachTerminalCover(withoutPreview,previewLayer!=null));return;
    }
    Bitmap fallback=previewLayer==null?null:HandoffCover.captureNow(displayId);
    main.post(()->attachTerminalCover(fallback,false));
   },"windowdeck-recents-terminal").start();
  }));
 }

 private void attachTerminalCover(Bitmap frame,boolean redrawAdd){
  if(closed||!ownsSource()){
   HandoffCover.recycle(frame);
   if(!closed)finish(HandoffProtocol.FAILED,"source_changed");
   return;
  }
  if(frame==null){terminalCoverUnavailable("capture");return;}
  HandoffCover cover=HandoffCover.createTerminal(context,frame);
  if(redrawAdd)cover.drawAddPreview(context,addBox,addQuad,1);
  terminalCover=cover;
  cover.onClosed(reason->{
   if(terminalCover!=cover)return;
   terminalCover=null;
   if(hostPoseCommitted)hostReply.send(HandoffProtocol.OWNERSHIP,Bundle.EMPTY);
   else if(!poseSent)terminalCoverUnavailable(reason);
  });
  if(cover.isClosed())return;
  cover.portraitDeadline(deadline);
  cover.dispatchWhenDrawn(()->{
   if(closed||terminalCover!=cover)return;
   // The terminal scene carries the same settled chrome before the preview is removed.
   closeAddPreview();
   terminalCoverReady=true;
   Log.i(TAG,"portrait_recents_terminal_cover_committed task="+candidate.task+" ms="+elapsed());
   sendPose();
  });
 }

 private void terminalCoverUnavailable(String reason){
  Log.w(TAG,"portrait_recents_terminal_cover_degraded reason="+reason+" task="+candidate.task);
  terminalCoverReady=true;
  sendPose();
 }

 private void closeAddPreview(){
  if(addPreview==null)return;
  addPreview.close("recents_add_preview_handoff");addPreview=null;
 }

 private void closeTerminalCover(String reason){
  HandoffCover cover=terminalCover;terminalCover=null;
  if(cover!=null)cover.close(reason);
 }

 private void releaseSource(){
  if(!ownsSource()||!SwipeReleaseHandoff.commitDestination(controller)){finish(HandoffProtocol.FAILED,"source_commit");return;}
  clearPoseOwner();
  try{
   XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_FINISH_RECENTS,false);finishRequested=true;
   XposedHelpers.callMethod(controller,RomSymbols.RECENTS_FINISH_CALLBACK,false,(Runnable)()->main.post(()->{
    if(closed)return;
    try{
     Object current=XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER);
     if((current!=null&&current!=controller)||!release.completeRelease()){
      finish(HandoffProtocol.FAILED,"source_release_changed");return;
     }
     source.closeVisibility();
     Bundle data=new Bundle();data.putInt(HandoffProtocol.TASK,candidate.task);
     Log.i(TAG,"swipe_source_released task="+candidate.task+" ms="+elapsed());
     hostReply.send(HandoffProtocol.SOURCE_RELEASED,data);
    }catch(Throwable e){Log.w(TAG,"portrait_recents_release_reply_failed",e);finish(HandoffProtocol.FAILED,"source_release_reply");}
   }),false,null,true);
  }catch(Throwable e){Log.w(TAG,"portrait_recents_release_failed",e);finish(HandoffProtocol.FAILED,"source_release");}
 }

 private boolean ownsSource(){
  return sameController()&&source.visibility.isValid()&&display.isValid()&&display.getRotation()==0;
 }
 private boolean sameController(){
  try{return XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER)==controller;}
  catch(Throwable e){return false;}
 }

 private void watchDeadline(){
  if(!closed&&deadline.expired(SystemClock.uptimeMillis()))finish(HandoffProtocol.FAILED,deadline.phase()+"_timeout");
 }

 private void clearPoseOwner(){
  if(poseOwner==this)poseOwner=null;
 }

 private void finish(int result,String reason){
  if(closed)return;
  closed=true;clearPoseOwner();main.removeCallbacks(watchdog);release.cancel();deadline.cancel();
  if(motion!=null){motion.cancel();motion=null;}
  if(!finishRequested&&sameController()){
   try{if(ownsSource())writeFrame(0,false);}
   catch(Exception e){Log.w(TAG,"portrait_recents_restore_failed",e);}
   try{SwipeReleaseHandoff.keepSourceVisible(controller);XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_FINISH_RECENTS,false);}
   catch(Throwable e){Log.w(TAG,"portrait_recents_finish_failed",e);}
  }
  source.closeVisibility();
  closeAddPreview();
  closeTerminalCover(reason);
  HandoffCover.recycle(snapshot);snapshot=null;
  if(result!=HandoffProtocol.READY&&hostReply!=null)hostReply.send(HandoffProtocol.FAILED,Bundle.EMPTY);
  Log.i(TAG,"portrait_recents_result task="+candidate.task+" result="+result+" reason="+reason+" ms="+elapsed());
  Log.i(TAG,"swipe_handoff_result result="+result+" task="+candidate.task);
  Log.i(TAG,"swipe_handoff_finish ms="+elapsed());
 }

 private long elapsed(){return SystemClock.uptimeMillis()-released;}
 private static float blend(float from,float to,float f){return from+(to-from)*f;}
}
