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

/** Owns portrait state for the workbench. The activity is only the nested Host. */
final class PortraitPoseController {
 interface Host {
  void applyCardRadius(WorkbenchSlot s,boolean isPrimary);
  WorkbenchBackdrop backdrop();
  boolean backgrounded();
  void clipSide(View card,int w,int h,boolean side);
  boolean closing();
  Context context();
  void createWindow(WorkbenchSlot slot);
  void failHandoff(WorkbenchSlot expected,String reason);
  long handoffDeadline();
  String handoffLayoutKey();
  String handoffLayoutKey(boolean requireLive);
  android.os.ResultReceiver handoffReady();
  boolean initialized();
  void layoutCards(boolean animate);
  void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source);
  boolean layoutPosted();
  void maybeAnimateAddedCard();
  void motionOutline(WorkbenchSlot s);
  WorkbenchSlot pendingEntrance();
  void probeLandscapePresented(WorkbenchSlot s,SurfaceControl leash);
  Resources resources();
  void markFrameReady();
  void scheduleLayout();
  CanvasStage stage();
  boolean stopped();
  void submitMotionFit(SurfaceControl.Transaction fit);
  Rect taskBuffer(WorkbenchSlot slot);
  int taskDrawState(WorkbenchSlot s) throws Exception;
  Window window();
  boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 PortraitPoseController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 boolean deferPortrait,poseHeld,poseQuerySent,portraitAfterPose;
 int deferSourceRotation=-1;
 float[] heldCorners,heldCrop;
 int[] heldStage;
 float heldRadius;
 int heldLayer=-1;
 boolean poseSettling,poseReported;
 SourceRelease sourceRelease;
 boolean sourceBootstrapFramePending;
 ViewTreeObserver.OnPreDrawListener poseFitListener;
 ViewTreeObserver.OnPreDrawListener poseBootstrapListener;
 RecoveryCover poseBridge;
 Bitmap poseBridgeBitmap;
 float[] poseBridgeSource;
 boolean poseBridgeFitted;
 WorkbenchSlot poseSlot;
 android.os.ResultReceiver poseReply;


 void requestCoverPose(WorkbenchSlot slot){
  if(!deferPortrait||poseQuerySent||portraitAfterPose||host.handoffReady()==null||slot==null||slot.released||slot.sourceTaskId<0)return;
  // Multiple task callbacks may ask; reserve this wait before posting it.
  poseQuerySent=true;
  Runnable wait=new Runnable(){public void run(){
   if(!deferPortrait||portraitAfterPose||host.closing()||slot.released)return;
   if(SystemClock.uptimeMillis()>=host.handoffDeadline()){host.failHandoff(slot,"pose_content_timeout");return;}
   poseSlot=slot;
   Log.i(TAG,"defer_snapshot_pose_query task="+slot.sourceTaskId+" before_live=true");
   poseReply=new android.os.ResultReceiver(handler){
    @Override protected void onReceiveResult(int code,Bundle data){
     if(!deferPortrait||host.closing()||slot.released)return;
     if(code==HandoffProtocol.POSE_QUERY){if(!poseHeld&&!portraitAfterPose)applyCoverPose(slot,data);}
     else if(code==HandoffProtocol.SOURCE_RELEASED){
      if(data==null||data.getInt(HandoffProtocol.TASK,-1)!=slot.sourceTaskId||sourceRelease==null||!sourceRelease.completeRelease())return;
      Log.i(TAG,"defer_source_released task="+slot.sourceTaskId+" bootstrap_committed=true");
      if(!host.initialized()){host.failHandoff(slot,"source_release_before_layout");return;}
      for(WorkbenchSlot s:new ArrayList<>(session.slots))if(s.card==null)host.createWindow(s);
      expandPosePlate(slot);if(slot.card!=null)host.motionOutline(slot);host.scheduleLayout();awaitCoverPoseCommit(slot);
     }
     else if(code==HandoffProtocol.OWNERSHIP)requestPortraitAfterPose("ownership");
     else host.failHandoff(slot,"pose_rejected_"+code);
    }
   };
   Bundle ask=new Bundle();ask.putInt(HandoffProtocol.TASK,slot.sourceTaskId);
   ask.putParcelable(HandoffProtocol.POSE_REPLY,HandoffProtocol.transport(poseReply));
   try{host.handoffReady().send(HandoffProtocol.POSE_QUERY,ask);}
   catch(Throwable e){Log.w(TAG,"defer_pose_query_failed",e);host.failHandoff(slot,"pose_query_failed");}
  }};
  handler.post(wait);
 }
 void applyCoverPose(WorkbenchSlot slot,Bundle data){
  // The first frame is a snapshot only. Constructing FlexibleTaskView here would
  // let it steal the task while Launcher still owns the recents leash.
  if(host.stage()==null||host.window().getDecorView().getWidth()<2||host.window().getDecorView().getHeight()<2){
   if(SystemClock.uptimeMillis()>=host.handoffDeadline()){host.failHandoff(slot,"pose_bootstrap_timeout");return;}
   handler.postDelayed(()->{if(deferPortrait&&!host.closing()&&!slot.released&&!poseHeld)applyCoverPose(slot,data);},MotionSpec.SURFACE_RETRY_MS);return;
  }
  float[] corners=data==null?null:data.getFloatArray(HandoffProtocol.CORNERS);
  float[] crop=data==null?null:data.getFloatArray(HandoffProtocol.CROP);
  int[] space=data==null?null:data.getIntArray(HandoffProtocol.POSE_STAGE);
  float radius=data==null?Float.NaN:data.getFloat(HandoffProtocol.RADIUS,Float.NaN);
  if(data==null||data.getInt(HandoffProtocol.TASK,-1)!=slot.sourceTaskId||corners==null||corners.length!=8
    ||crop==null||crop.length!=4||space==null||space.length!=3||space[0]<2||space[1]<=space[0]
    ||space[2]!=deferSourceRotation||!Float.isFinite(radius)||radius<0){host.failHandoff(slot,"pose_malformed");return;}
  for(float point:corners)if(!Float.isFinite(point)){host.failHandoff(slot,"pose_nonfinite");return;}
  Bitmap snapshot=null;
  android.hardware.HardwareBuffer shared=data.getParcelable(HandoffProtocol.SNAPSHOT,android.hardware.HardwareBuffer.class);
  try{
   if(shared!=null&&(shared.getUsage()&android.hardware.HardwareBuffer.USAGE_PROTECTED_CONTENT)==0){
    android.graphics.ParcelableColorSpace color=data.getParcelable(HandoffProtocol.SNAPSHOT_COLOR_SPACE,android.graphics.ParcelableColorSpace.class);
    snapshot=Bitmap.wrapHardwareBuffer(shared,color==null?android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB):color.getColorSpace());
   }
  }catch(Throwable e){Log.w(TAG,"defer_snapshot_buffer_failed",e);}
  finally{if(shared!=null)shared.close();}
  float[] pixels=data.getFloatArray(HandoffProtocol.SNAPSHOT_CROP);
  float[] snapshotSource=snapshot==null||snapshot.isRecycled()?null:DisplayGeometry.snapshotQuad(pixels,snapshot.getWidth(),snapshot.getHeight());
  if(snapshotSource==null){if(snapshot!=null&&!snapshot.isRecycled())snapshot.recycle();host.failHandoff(slot,"pose_snapshot_invalid");return;}
  heldCorners=corners.clone();heldCrop=crop.clone();heldStage=space.clone();heldRadius=radius;
  poseHeld=true;poseSlot=slot;
  poseBridgeBitmap=snapshot;poseBridgeSource=snapshotSource;
  poseBridge=new RecoveryCover(host.context());poseBridge.setBitmap(snapshot);
  ((ViewGroup)host.window().getDecorView()).getOverlay().add(poseBridge);
  fitPoseBridge();
  Log.i(TAG,"defer_snapshot_bridge task="+slot.sourceTaskId+" source=launcher_release live=false size="+snapshot.getWidth()+"x"+snapshot.getHeight());
  expandPosePlate(slot);if(slot.card!=null)host.motionOutline(slot);
  poseFitListener=()->{
   if(poseHeld&&!host.closing()&&!host.stopped()&&!host.backgrounded()){
    fitPoseBridge();
    if(slot.surface!=null)try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
     if(writeHeldPose(slot,fit))host.submitMotionFit(fit);
    }catch(Throwable e){Log.w(TAG,"defer_pose_frame_fit_failed",e);}
   }
   return true;
  };
  host.stage().getViewTreeObserver().addOnPreDrawListener(poseFitListener);
  Log.i(TAG,"defer_pose_received stage="+Arrays.toString(heldStage)+" quad="+Arrays.toString(heldCorners)
    +" capture_crop="+Arrays.toString(heldCrop)+" task_buffer="+host.taskBuffer(slot));
  if(waitingForSourceRelease())awaitSourceBootstrap(slot);else awaitCoverPoseCommit(slot);
 }
 boolean waitingForSourceRelease(){return deferPortrait&&sourceRelease!=null&&!sourceRelease.mayEmbed();}
 void awaitSourceBootstrap(WorkbenchSlot slot){
  if(!waitingForSourceRelease()||!poseHeld||host.closing()||host.stopped()||slot.released||sourceBootstrapFramePending)return;
  if(SystemClock.uptimeMillis()>=host.handoffDeadline()){host.failHandoff(slot,"source_bootstrap_timeout");return;}
  fitPoseBridge();
  if(!poseBridgeFitted||host.backdrop()==null||!host.backdrop().ready||host.stage().isLayoutRequested()){
   handler.postDelayed(()->awaitSourceBootstrap(slot),MotionSpec.SURFACE_RETRY_MS);return;
  }
  sourceBootstrapFramePending=true;
  host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   sourceBootstrapFramePending=false;
   if(!waitingForSourceRelease()||host.closing()||host.stopped()||host.backgrounded()||slot.released)return;
   if(!poseBridgeFitted||poseBridge==null||!host.backdrop().ready||host.stage().isLayoutRequested()){
    awaitSourceBootstrap(slot);return;
   }
   if(!sourceRelease.commitBootstrap()||!sourceRelease.requestRelease())return;
   Bundle done=new Bundle();done.putInt(HandoffProtocol.TASK,slot.sourceTaskId);
   done.putParcelable(HandoffProtocol.POSE_REPLY,HandoffProtocol.transport(poseReply));
   Log.i(TAG,"defer_bootstrap_committed task="+slot.sourceTaskId+" live=false embed_started=false");
   if(host.handoffReady()==null||poseReply==null){host.failHandoff(slot,"source_release_reply_missing");return;}
   host.handoffReady().send(HandoffProtocol.BOOTSTRAP_COMMITTED,done);
  }));host.window().getDecorView().invalidate();host.stage().invalidate();
 }
 void fitPoseBridge(){
  poseBridgeFitted=false;
  if(poseBridge==null||poseBridgeBitmap==null||heldStage==null)return;
  View decor=host.window().getDecorView();int[] loc=new int[2];decor.getLocationOnScreen(loc);
  float[] local=DisplayGeometry.localQuad(heldCorners,heldStage[0],heldStage[1],heldStage[2],decor.getWidth(),decor.getHeight(),loc[0],loc[1]);
  if(local==null)return;
  poseBridge.layout(0,0,decor.getWidth(),decor.getHeight());
  // SurfaceView's native background may exist beyond the fitted task during
  // its first resize. Keep the whole bootstrap scene above that background.
  poseBridge.setBackdrop(host.backdrop()==null?null:host.backdrop().currentBitmap());
  Matrix map=new Matrix();if(!map.setPolyToPoly(poseBridgeSource,0,local,0,4))return;
  float[] m=new float[9];map.getValues(m);
  float scale=(float)Math.max(0.01d,Math.max(Math.hypot(m[0],m[3]),Math.hypot(m[1],m[4])));
  poseBridge.fit(poseBridgeSource,local,poseBridgeBitmap.getWidth(),poseBridgeBitmap.getHeight(),heldRadius/scale);
  poseBridgeFitted=true;
 }
 void clearPoseBridge(String reason){
  poseBridgeFitted=false;
  if(poseBootstrapListener!=null&&host.stage()!=null){host.stage().getViewTreeObserver().removeOnPreDrawListener(poseBootstrapListener);poseBootstrapListener=null;}
  if(poseBridge==null)return;
  RecoveryCover cover=poseBridge;poseBridge=null;
  ((ViewGroup)host.window().getDecorView()).getOverlay().remove(cover);cover.setBitmap(null);
  Bitmap bitmap=poseBridgeBitmap;poseBridgeBitmap=null;poseBridgeSource=null;
  if(bitmap!=null)handler.postDelayed(()->{if(!bitmap.isRecycled())bitmap.recycle();},1000);
  Log.i(TAG,"defer_snapshot_reveal result="+reason+" task="+(poseSlot==null?-1:poseSlot.taskId));
 }
 void expandPosePlate(WorkbenchSlot slot){
  if(slot==null||host.stage()==null||slot.card==null||slot.surface==null)return;
  View decor=host.window().getDecorView();int w=decor.getWidth(),h=decor.getHeight();
  if(w<2||h<2)return;
  int[] stageLocation=new int[2],windowLocation=new int[2];
  host.stage().getLocationOnScreen(stageLocation);decor.getLocationOnScreen(windowLocation);
  host.stage().setClipChildren(false);host.stage().setClipToPadding(false);
  if(host.stage().getParent() instanceof ViewGroup){ViewGroup parent=(ViewGroup)host.stage().getParent();parent.setClipChildren(false);parent.setClipToPadding(false);}
  FrameLayout.LayoutParams card=(FrameLayout.LayoutParams)slot.card.getLayoutParams();
  if(card==null)card=new FrameLayout.LayoutParams(w,h);
  int x=windowLocation[0]-stageLocation[0],y=windowLocation[1]-stageLocation[1];
  if(card.width!=w||card.height!=h||card.leftMargin!=x||card.topMargin!=y){
   card.width=w;card.height=h;card.leftMargin=x;card.topMargin=y;slot.card.setLayoutParams(card);
  }
  FrameLayout.LayoutParams plate=(FrameLayout.LayoutParams)slot.surface.getLayoutParams();
  if(plate==null)plate=new FrameLayout.LayoutParams(w,h);
  if(plate.width!=w||plate.height!=h){plate.width=w;plate.height=h;slot.surface.setLayoutParams(plate);}
  slot.card.setClipToOutline(false);slot.card.setClipChildren(false);
 }
 String poseSurfaceKey(WorkbenchSlot slot){
  if(host.closing()||host.stopped()||host.backgrounded()||slot==null||slot.released||slot.taskId!=slot.sourceTaskId
    ||slot.surface==null||slot.card==null||slot.surface.isLayoutRequested()||slot.card.isLayoutRequested()
    ||host.stage()==null||host.stage().isLayoutRequested()||host.layoutPosted()||(host.backdrop()!=null&&!host.backdrop().ready))return null;
  Rect buffer=host.taskBuffer(slot);
  if(buffer==null||buffer.width()!=slot.renderBounds.width()||buffer.height()!=slot.renderBounds.height())return null;
  try{
   SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(slot.surface),plate=((SurfaceView)slot.surface).getSurfaceControl();
   if(leash==null||!leash.isValid()||plate==null||!plate.isValid())return null;
   host.taskDrawState(slot);
   if(!slot.drawEvidence.hasContent()){host.probeLandscapePresented(slot,leash);return null;}
   View decor=host.window().getDecorView();int[] loc=new int[2];slot.surface.getLocationOnScreen(loc);
   return slot.taskId+":"+LiveTaskContent.layerId(leash)+":"+LiveTaskContent.layerId(plate)+":"+buffer
     +":"+slot.surface.getWidth()+"x"+slot.surface.getHeight()+":"+Arrays.toString(loc)
     +":"+decor.getWidth()+"x"+decor.getHeight()+":"+(host.backdrop()==null?0:host.backdrop().visualGeneration());
  }catch(Throwable e){Log.w(TAG,"defer_live_pose_unavailable",e);return null;}
 }
 void awaitCoverPoseCommit(WorkbenchSlot slot){
  if(!deferPortrait||!poseHeld||poseReported||host.closing()||slot.released)return;
  if(SystemClock.uptimeMillis()>=host.handoffDeadline()){host.failHandoff(slot,"pose_frame_timeout");return;}
  View decor=host.window().getDecorView();
  String key=poseSurfaceKey(slot);
  if(key==null||slot.surface.getWidth()!=decor.getWidth()||slot.surface.getHeight()!=decor.getHeight()){
   handler.postDelayed(()->awaitCoverPoseCommit(slot),MotionSpec.SURFACE_RETRY_MS);return;
  }
  try(SurfaceControl.Transaction committed=new SurfaceControl.Transaction()){
   if(!writeHeldPose(slot,committed))throw new IllegalStateException("No live pose fit");
   committed.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!deferPortrait||!poseHeld||poseReported||host.closing()||slot.released)return;
    host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->
     host.stage().postOnAnimation(()->host.stage().postOnAnimation(()->{
      if(!deferPortrait||!poseHeld||poseReported||host.closing()||slot.released)return;
      if(!key.equals(poseSurfaceKey(slot))){awaitCoverPoseCommit(slot);return;}
      poseReported=true;logHeldLayer("defer_live_pose_committed");
      Bundle done=new Bundle();done.putInt(HandoffProtocol.TASK,slot.sourceTaskId);
      done.putParcelable(HandoffProtocol.POSE_REPLY,HandoffProtocol.transport(poseReply));
      if(host.handoffReady()==null||poseReply==null){host.failHandoff(slot,"pose_reply_missing");return;}
      host.handoffReady().send(HandoffProtocol.POSE_COMMITTED,done);
     }))));host.stage().invalidate();
   });host.submitMotionFit(committed);host.stage().invalidate();
  }catch(Throwable e){Log.w(TAG,"defer_pose_apply_failed",e);host.failHandoff(slot,"pose_apply_failed");}
 }
 void requestPortraitAfterPose(String reason){
  if(portraitAfterPose||!deferPortrait)return;
  if(!poseHeld||!poseReported||poseSlot==null){host.failHandoff(host.pendingEntrance(),"pose_ownership_not_ready");return;}
  portraitAfterPose=true;
  Log.i(TAG,"canvas_orientation retained=portrait after="+reason+" layer="+heldLayer);
  awaitPortraitPose(poseSlot);
 }
 void awaitPortraitPose(WorkbenchSlot slot){
  if(!deferPortrait||!poseHeld||host.closing()||slot.released)return;
  if(SystemClock.uptimeMillis()>=host.handoffDeadline()){host.failHandoff(slot,"pose_rotation_timeout");return;}
  View decor=host.window().getDecorView();
  boolean portrait=host.resources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT
    &&decor.getWidth()==heldStage[0]&&decor.getHeight()==heldStage[1]
    &&host.stage().getWidth()>0&&host.stage().getWidth()<host.stage().getHeight();
  if(!portrait){handler.postDelayed(()->awaitPortraitPose(slot),MotionSpec.SURFACE_RETRY_MS);return;}
  if(!poseSettling){
   poseSettling=true;host.layoutCards(false);
   Log.i(TAG,"defer_portrait_layout task="+slot.taskId+" quad="+Arrays.toString(heldCorners));
  }
  String key=host.handoffLayoutKey();
  if(key==null||poseSurfaceKey(slot)==null){handler.postDelayed(()->awaitPortraitPose(slot),MotionSpec.SURFACE_RETRY_MS);return;}
  // The normal final card now has the same display-space quad as the held pose.
  // Exchange the writer and fit with this host draw, without cancelling the handoff.
  poseHeld=false;removePoseFitListener();
  commitPortraitPose(slot);
 }
 void commitPortraitPose(WorkbenchSlot slot){
  if(!deferPortrait||host.closing()||host.stopped()||slot.released)return;
  if(SystemClock.uptimeMillis()>=host.handoffDeadline()){host.failHandoff(slot,"pose_final_frame_timeout");return;}
  String key=host.handoffLayoutKey();
  if(key==null||poseSurfaceKey(slot)==null){handler.postDelayed(()->commitPortraitPose(slot),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   if(!host.writeLeashFit(slot,fit))throw new IllegalStateException("No final portrait fit");
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!deferPortrait||host.closing()||slot.released)return;
    host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->
     host.stage().postOnAnimation(()->host.stage().postOnAnimation(()->{
      if(!deferPortrait||host.closing()||slot.released)return;
      if(!key.equals(host.handoffLayoutKey())||poseSurfaceKey(slot)==null){commitPortraitPose(slot);return;}
      revealPortraitPose(slot,key);
     }))));host.stage().invalidate();
   });host.submitMotionFit(fit);host.stage().invalidate();
  }catch(Throwable e){Log.w(TAG,"defer_portrait_fit_failed",e);host.failHandoff(slot,"pose_final_fit_failed");}
 }
 void revealPortraitPose(WorkbenchSlot slot,String key){
  if(poseBridge==null){host.failHandoff(slot,"pose_bridge_missing");return;}
  poseBridge.setVisibility(View.INVISIBLE);
  host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   if(!deferPortrait||host.closing()||host.stopped()||slot.released)return;
   if(!key.equals(host.handoffLayoutKey())||poseSurfaceKey(slot)==null){
    if(poseBridge!=null)poseBridge.setVisibility(View.VISIBLE);
    commitPortraitPose(slot);return;
   }
   clearPoseBridge("live_frame_committed");deferPortrait=false;restorePoseClipping();logHeldLayer("defer_portrait_live_committed");
   slot.windowDrawn=true;host.markFrameReady();
   Log.i(TAG,"handoff_layout_committed task="+slot.taskId+" cards="+session.slots.size()+" axis="+slot.orientationAxis+" pose_rotation=true");
   host.maybeAnimateAddedCard();
  }));host.window().getDecorView().invalidate();host.stage().invalidate();
 }
 void removePoseFitListener(){
  if(poseFitListener!=null&&host.stage()!=null){host.stage().getViewTreeObserver().removeOnPreDrawListener(poseFitListener);poseFitListener=null;}
 }
 void restorePoseClipping(){
  if(host.stage()!=null){host.stage().setClipChildren(true);host.stage().setClipToPadding(true);
   if(host.stage().getParent() instanceof ViewGroup){ViewGroup parent=(ViewGroup)host.stage().getParent();parent.setClipChildren(true);parent.setClipToPadding(true);}}
  if(poseSlot!=null&&poseSlot.card!=null){host.applyCardRadius(poseSlot,session.slots.indexOf(poseSlot)==session.primary);host.clipSide(poseSlot.card,poseSlot.card.getWidth(),poseSlot.card.getHeight(),session.slots.indexOf(poseSlot)!=session.primary);}
 }
 void clearHeldPose(){if(sourceRelease!=null)sourceRelease.cancel();sourceBootstrapFramePending=false;poseHeld=false;removePoseFitListener();clearPoseBridge("cancelled");restorePoseClipping();}
 boolean writeHeldPose(WorkbenchSlot s,SurfaceControl.Transaction t){
  try{
   SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface),plate=((SurfaceView)s.surface).getSurfaceControl();
   Rect buffer=host.taskBuffer(s);View decor=host.window().getDecorView();
   if(leash==null||!leash.isValid()||plate==null||!plate.isValid()||buffer==null||heldStage==null
     ||buffer.width()!=s.renderBounds.width()||buffer.height()!=s.renderBounds.height())return false;
   int[] loc=new int[2];s.surface.getLocationOnScreen(loc);
   float[] local=DisplayGeometry.localQuad(heldCorners,heldStage[0],heldStage[1],heldStage[2],decor.getWidth(),decor.getHeight(),loc[0],loc[1]);
   if(local==null)return false;
   float[] src={0,0,buffer.width(),0,buffer.width(),buffer.height(),0,buffer.height()};
   Matrix map=new Matrix();if(!map.setPolyToPoly(src,0,local,0,4))return false;
   float[] m=new float[9];map.getValues(m);
   try{Field bg=SurfaceView.class.getDeclaredField("mBackgroundControl");bg.setAccessible(true);SurfaceControl background=(SurfaceControl)bg.get(s.surface);if(background!=null&&background.isValid())t.setVisibility(background,false);}catch(Throwable ignored){}
   if(rom.leashRadius!=null)rom.leashRadius.invoke(t,plate,0f);
   t.setCrop(plate,new Rect(0,0,Math.max(1,s.surface.getWidth()),Math.max(1,s.surface.getHeight())));
   t.setCrop(leash,buffer);
   if(rom.leashMatrix==null)return false;
   rom.leashMatrix.invoke(t,leash,m[0],m[3],m[1],m[4]);t.setPosition(leash,m[2],m[5]);
   float scale=(float)Math.max(0.01d,Math.max(Math.hypot(m[0],m[3]),Math.hypot(m[1],m[4])));
   if(rom.leashRadius!=null)rom.leashRadius.invoke(t,leash,heldRadius/scale);
   t.setAlpha(leash,1f);t.setVisibility(leash,true);t.setAlpha(plate,1f);t.setVisibility(plate,true);
   heldLayer=LiveTaskContent.layerId(leash);s.fittedLayer=heldLayer;s.fittedTask=s.taskId;
   return true;
  }catch(Throwable e){Log.w(TAG,"defer_pose_fit_failed slot="+s.id,e);return false;}
 }
 void logHeldLayer(String event){
  if(poseSlot==null||poseSlot.surface==null||rom.taskLeash==null){Log.i(TAG,event+" leash=missing");return;}
  try{
   SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(poseSlot.surface);
   SurfaceControl plate=((SurfaceView)poseSlot.surface).getSurfaceControl();
   logLayer(event,poseSlot,leash,plate);
  }catch(Throwable e){Log.w(TAG,event+"_failed",e);}
 }
 void logLayer(String event,WorkbenchSlot slot,SurfaceControl leash,SurfaceControl plate){
  int leashId=LiveTaskContent.layerId(leash),plateId=LiveTaskContent.layerId(plate);
  if(leashId>=0)heldLayer=leashId;
  Log.i(TAG,event+" task="+(slot==null?-1:slot.taskId)+" leash="+leashId+" name="+leash+" plate="+plateId+" plate_name="+plate+" leash_valid="+(leash!=null&&leash.isValid())+" plate_valid="+(plate!=null&&plate.isValid()));
 }
}
