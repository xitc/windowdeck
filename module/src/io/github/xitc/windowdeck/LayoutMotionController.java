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

/** Owns layout state for the workbench. The activity is only the nested Host. */
final class LayoutMotionController {
 interface Host {
  Button addCard();
  void apply(WorkbenchSlot s,int[] r);
  boolean backgrounded();
  void cancelForLayout(boolean preservePose);
  void cancelHandoffPresentation();
  float[] cardBox(WorkbenchSlot s);
  CardLayout.Result cardGeometry();
  void closeEntranceCover(String why);
  boolean closing();
  Context context();
  boolean coverReleasePending();
  CardMotion.Pose currentPose(WorkbenchSlot s);
  void detachCaption();
  Display display();
  WorkbenchSlot dragging();
  int effectiveMode();
  HandoffCover entranceCover();
  String entranceSurfaceKey();
  String entranceWaitReason();
  void finishTaskRotation(TaskIdentityController.RotationRun run,String result);
  boolean finishing();
  void fitRecoveryCover(WorkbenchSlot s,int w,int h);
  boolean focusPrimary(String reason);
  boolean handoffPresentation();
  boolean initialized();
  void inputRole(WorkbenchSlot s,boolean isPrimary);
  void layoutAddCard(int[] box);
  void layoutCaption();
  boolean layoutPosted();
  ViewTreeObserver.OnPreDrawListener motionFitListener();
  WorkbenchSlot pendingEntrance();
  void poseCard(WorkbenchSlot s,CardMotion.Pose pose,CardMotion.Pose target);
  int[] presentationSize(WorkbenchSlot s);
  void queueTaskDirections();
  void raiseMain();
  boolean recovering();
  void releaseEntrance();
  void removeMotionFitListener();
  ReplacementController<WorkbenchSlot> replacements();
  int requestedOrientation();
  void resetCardTransform(WorkbenchSlot s);
  CardMotion.Pose rolePose(WorkbenchSlot s,float[] box,boolean preview);
  boolean rotatePresentation(WorkbenchSlot s);
  void runOnUiThread(Runnable action);
  boolean screenArrival();
  void setMotionFitListener(ViewTreeObserver.OnPreDrawListener value);
  CanvasStage stage();
  TextView status();
  boolean stopped();
  void submitMotionFit(SurfaceControl.Transaction fit);
  int[] surfacePlate(WorkbenchSlot s);
  int taskId();
  TaskIdentityController.RotationRun taskRotationRun();
  boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 LayoutMotionController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 int layoutMode=PaneLayout.LEFT_RIGHT;
 int mainWindowPercent=CardLayout.MAIN_DEFAULT_PERCENT;
 LayoutSwitchVisuals layoutSwitchVisuals;
 boolean layoutSwitchRail;
 boolean settlingInput;
 int switchGeneration;
 final SwitchPreparation switchPreparation=new SwitchPreparation();
 int switchPrepareToken;
 WorkbenchSlot switchPrepareTarget;
 ArrayList<WorkbenchSlot> switchPrepareSlots;
 int[] switchPrepareTasks;
 int switchPreparePrimary;
 long switchPrepareSession;
 int switchOriginPrepareToken;
 long switchStarted,lastAnimationFrame,maxFrameGap;
 int animationFrames,motionFitFailures;
 final ResizeStats resizeStats=new ResizeStats();
 ValueAnimator animation,addAnimation;
 int layoutEvidenceGeneration;


 void toggleLayout(){
  if(host.closing()||!host.initialized()||host.recovering()||host.dragging()!=null||switching()||host.stopped()||host.backgrounded()
    ||host.taskRotationRun()!=null||host.pendingEntrance()!=null||host.screenArrival()||addAnimation!=null||host.handoffPresentation()
    ||switchPreparation.pending()||host.replacements().current()!=null||host.layoutPosted()||host.stage().isLayoutRequested()||session.slots.isEmpty())return;
  SwitchMotion<WorkbenchSlot> source=SwitchMotion.capture(session.slots,s->s.card==null?null:host.currentPose(s));
  if(source==null){Log.w(TAG,"layout_switch_rejected reason=source_not_ready");return;}
  CardMotion.Pose[] poses=new CardMotion.Pose[session.slots.size()];
  for(int i=0;i<session.slots.size();i++)poses[i]=source.source(session.slots.get(i));
  int oldMode=layoutMode;float extent=LayoutSwitchMotion.extent(poses,session.primary,oldMode,host.cardGeometry().add);
  cancelAnimation(true);
  LayoutSwitchVisuals visuals=new LayoutSwitchVisuals(host.stage(),oldMode,extent);
  layoutSwitchRail=true;
  try{
   Method snapshot=rom.viewApi.getDeclaredMethod("getSnapBitMap",boolean.class);snapshot.setAccessible(true);
   for(int i=0;i<session.slots.size();i++)if(i!=session.primary){
    WorkbenchSlot slot=session.slots.get(i);CardMotion.Pose pose=poses[i];
    visuals.side((Bitmap)snapshot.invoke(slot.surface,false),pose,slot.renderBounds.width(),slot.renderBounds.height(),host.presentationSize(slot)[0],host.rotatePresentation(slot));
    visuals.chrome(slot.icon,slot.card.getLeft()+slot.icon.getLeft(),slot.card.getTop()+slot.icon.getTop());
    visuals.chrome(slot.pin,slot.card.getLeft()+slot.pin.getLeft(),slot.card.getTop()+slot.pin.getTop());
   }
   visuals.chrome(host.addCard(),host.addCard().getLeft(),host.addCard().getTop());visuals.attach();
  }catch(Throwable e){
   visuals.close();visuals=new LayoutSwitchVisuals(host.stage(),oldMode,extent);layoutSwitchRail=false;
   Log.w(TAG,"layout_switch_rail_degraded reason=snapshot_unavailable",e);
  }
  layoutSwitchVisuals=visuals;host.addCard().setEnabled(false);host.detachCaption();
  for(WorkbenchSlot slot:session.slots)slot.motionPose=source.source(slot);
  layoutMode=oldMode==PaneLayout.LEFT_RIGHT?PaneLayout.TOP_BOTTOM:PaneLayout.LEFT_RIGHT;
  layoutCards(true,source);host.queueTaskDirections();
  Log.i(TAG,"layout_mode="+layoutMode+" tasks="+taskIds()+" animation=layout_flip old_mode="+oldMode+" rail_snapshots="+layoutSwitchRail);
 }
 void clearLayoutSwitch(){
  if(layoutSwitchVisuals==null)return;
  layoutSwitchVisuals.close();layoutSwitchVisuals=null;layoutSwitchRail=false;
  host.addCard().setTranslationX(0);host.addCard().setTranslationY(0);host.addCard().setEnabled(true);
 }
 void layoutCards(boolean animate){layoutCards(animate,null);}
 void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source){
  if(session.slots.isEmpty()||host.closing())return;
  CardLayout.Result geometry=host.cardGeometry();host.layoutAddCard(geometry.add);int[][] target=geometry.cards;
  final ArrayList<WorkbenchSlot> current=new ArrayList<>(session.slots);
  if(!animate){clearLayoutSwitch();for(int i=0;i<current.size();i++){WorkbenchSlot s=current.get(i);if(s.card==null)return;s.motionPose=null;host.resetCardTransform(s);host.inputRole(s,i==session.primary);host.apply(s,target[i]);}host.raiseMain();host.layoutCaption();scheduleLayoutEvidence("settled");return;}
  final CardMotion.Pose[] from=new CardMotion.Pose[current.size()],to=new CardMotion.Pose[current.size()];
  for(int i=0;i<current.size();i++){
   WorkbenchSlot s=current.get(i);if(s.card==null)return;
   from[i]=source==null?host.currentPose(s):source.source(s);
   if(from[i]==null){Log.w(TAG,"switch_degraded reason=missing_source_pose slot="+s.id+" task="+s.taskId);layoutCards(false);return;}
   int[] r=target[i];to[i]=host.rolePose(s,new float[]{r[0],r[1],r[2],r[3]},i!=session.primary);
  }
  final boolean layoutFlip=layoutSwitchVisuals!=null,rail=layoutFlip&&layoutSwitchRail;
  final int mode=host.effectiveMode();
  final float railExtent=LayoutSwitchMotion.extent(to,session.primary,mode,geometry.add);
  if(rail)for(int i=0;i<current.size();i++)if(i!=session.primary)from[i]=LayoutSwitchMotion.incoming(to[i],mode,railExtent,0);
  final long duration=layoutFlip?LayoutSwitchMotion.DURATION_MS:MotionSpec.SWITCH_MS;
  final int generation=++switchGeneration;
  switchStarted=SystemClock.uptimeMillis();lastAnimationFrame=0;maxFrameGap=0;animationFrames=0;resizeStats.reset();motionFitFailures=0;
  animation=ValueAnimator.ofFloat(0,1);animation.setDuration(duration);animation.setInterpolator(layoutFlip?t->t:MotionSpec::geometry);
  // All slots already own the captured source frame before target layout begins.
  // Keep render bounds fixed and interpolate crop, corners and screen box together.
  for(int i=0;i<current.size();i++){
   WorkbenchSlot s=current.get(i);s.motionPose=from[i];host.inputRole(s,i==session.primary);host.apply(s,target[i]);host.poseCard(s,from[i],to[i]);
  }
  host.raiseMain();
  if(rail){host.addCard().setTranslationX(mode==PaneLayout.LEFT_RIGHT?-railExtent:0);host.addCard().setTranslationY(mode==PaneLayout.TOP_BOTTOM?-railExtent:0);}
  host.setMotionFitListener(()->{
   if(generation==switchGeneration&&!host.closing()&&!host.stopped()&&!host.backgrounded()){
    try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
     boolean ready=true;for(WorkbenchSlot s:current){host.fitRecoveryCover(s,s.surface.getWidth(),s.surface.getHeight());ready&=host.writeLeashFit(s,fit);}
     if(ready)host.submitMotionFit(fit);else motionFitFailures++;
    }catch(Throwable e){motionFitFailures++;Log.w(TAG,"switch_frame_fit_failed generation="+generation,e);}
   }
   return true;
  });
  host.stage().getViewTreeObserver().addOnPreDrawListener(host.motionFitListener());
  animation.addUpdateListener(a->{
   long now=SystemClock.uptimeMillis();if(lastAnimationFrame!=0)maxFrameGap=Math.max(maxFrameGap,now-lastAnimationFrame);lastAnimationFrame=now;animationFrames++;
   float time=(Float)a.getAnimatedValue(),f=layoutFlip?LayoutSwitchMotion.progress(time):time;
   for(int i=0;i<current.size();i++)host.poseCard(current.get(i),CardMotion.between(from[i],to[i],f),to[i]);
   if(layoutFlip&&layoutSwitchVisuals!=null){
    layoutSwitchVisuals.frame(time);
    if(rail){float offset=LayoutSwitchMotion.offset(railExtent,f,true);host.addCard().setTranslationX(mode==PaneLayout.LEFT_RIGHT?offset:0);host.addCard().setTranslationY(mode==PaneLayout.TOP_BOTTOM?offset:0);}
   }
   host.stage().invalidate();
  });
  animation.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){if(animation!=a)return;
   animation=null;settlingInput=true;host.removeMotionFitListener();
   settleSwitch(generation,SystemClock.uptimeMillis()+MotionSpec.ARRIVAL_UNLOCK_MS);
  }});Log.i(TAG,"switch_animation_start generation="+generation+" prepare_token="+switchOriginPrepareToken+" tasks="+taskIds()+" duration_ms="+duration+" layout_flip="+layoutFlip+" geometry=quad_crop_radius");animation.start();
 }
 String switchSurfaceKey(){
  String key=host.entranceSurfaceKey();if(key==null)return null;
  StringBuilder out=new StringBuilder(key);
  for(WorkbenchSlot s:session.slots){
   if(s.motionPose==null)return null;
   out.append(':').append(Arrays.toString(host.cardBox(s))).append(':').append(Arrays.toString(s.motionPose.quad)).append(':').append(Arrays.toString(s.motionPose.crop));
  }
  return out.toString();
 }
 void settleSwitch(int generation,long deadline){
  if(generation!=switchGeneration||!settlingInput||host.closing()||host.stopped()||host.backgrounded())return;
  if(SystemClock.uptimeMillis()>=deadline){
   Log.w(TAG,"switch_settle_timeout generation="+generation+" waiting_for="+host.entranceWaitReason());
   logSwitch("failed_settle_timeout");cancelAnimation();layoutCards(false);return;
  }
  String key=switchSurfaceKey();
  if(key==null){handler.postDelayed(()->settleSwitch(generation,deadline),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(WorkbenchSlot s:session.slots)if(!host.writeLeashFit(s,fit))throw new IllegalStateException("Unfitted switch slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(generation!=switchGeneration||!settlingInput)return;
    host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(generation!=switchGeneration||!settlingInput||host.closing()||host.stopped()||host.backgrounded())return;
     if(SystemClock.uptimeMillis()>=deadline){settleSwitch(generation,deadline);return;}
     if(!key.equals(switchSurfaceKey())){settleSwitch(generation,deadline);return;}
     settlingInput=false;clearLayoutSwitch();
     for(int i=0;i<session.slots.size();i++){WorkbenchSlot s=session.slots.get(i);s.motionPose=null;host.resetCardTransform(s);host.inputRole(s,i==session.primary);int[] plate=host.surfacePlate(s);host.fitRecoveryCover(s,plate[0],plate[1]);}
     host.layoutCaption();logSwitch(motionFitFailures==0?"settled":"failed_motion_fit");scheduleLayoutEvidence("switch");handler.post(()->host.focusPrimary("switch"));
    }));host.stage().invalidate();
   });host.submitMotionFit(fit);host.stage().invalidate();
   // A missing compositor or host-frame callback is a failure, too.
   handler.postDelayed(()->{if(generation==switchGeneration&&settlingInput)settleSwitch(generation,deadline);},Math.max(1,deadline-SystemClock.uptimeMillis()));
  }catch(Throwable e){Log.w(TAG,"switch_final_fit_failed generation="+generation,e);handler.postDelayed(()->settleSwitch(generation,deadline),MotionSpec.SURFACE_RETRY_MS);}
 }
 void scheduleLayoutEvidence(String reason){
  final int generation=++layoutEvidenceGeneration;
  final long deadline=SystemClock.uptimeMillis()+1500;
  Runnable check=new Runnable(){public void run(){
   if(generation!=layoutEvidenceGeneration||host.closing()||host.stopped()||host.backgrounded()||host.stage()==null)return;
   boolean ready=host.taskRotationRun()==null&&!host.layoutPosted()&&!host.stage().isLayoutRequested()&&!host.screenArrival()&&!host.handoffPresentation()&&animation==null&&addAnimation==null;
   for(WorkbenchSlot s:session.slots)ready&=s.card!=null&&s.surface!=null&&!s.card.isLayoutRequested()&&!s.surface.isLayoutRequested()&&s.taskId>=0;
   if(!ready){if(SystemClock.uptimeMillis()<deadline)host.stage().postOnAnimation(this);else{
    StringBuilder detail=new StringBuilder().append("host_layout=").append(host.stage().isLayoutRequested()).append(" posted=").append(host.layoutPosted()).append(" arrival=").append(host.screenArrival()).append(" handoff=").append(host.handoffPresentation()).append(" rotation=").append(host.taskRotationRun()!=null).append(" animation=").append(animation!=null).append(" add=").append(addAnimation!=null);
    for(WorkbenchSlot s:session.slots)detail.append(" slot=").append(s.id).append(" task=").append(s.taskId).append(" card_layout=").append(s.card!=null&&s.card.isLayoutRequested()).append(" surface_layout=").append(s.surface!=null&&s.surface.isLayoutRequested());
    Log.w(TAG,"layout_evidence_timeout "+detail);
   }return;}
   try{
    org.json.JSONObject data=new org.json.JSONObject();int[] loc=new int[2];host.stage().getLocationOnScreen(loc);
    data.put("container",host.taskId()).put("reason",reason).put("width",host.stage().getWidth()).put("height",host.stage().getHeight())
     .put("x",loc[0]).put("y",loc[1]).put("mode",host.effectiveMode()).put("primary",session.primary).put("count",session.slots.size())
     .put("rotation",host.display().getRotation()).put("requested_orientation",host.requestedOrientation()).put("main_window_percent",mainWindowPercent);
    org.json.JSONArray cards=new org.json.JSONArray();
    for(WorkbenchSlot s:session.slots){org.json.JSONObject card=new org.json.JSONObject();card.put("slot",s.id).put("task",s.taskId).put("pkg",s.component.getPackageName())
     .put("axis",s.orientationAxis).put("task_rotation",s.taskOrientation.rotation()).put("previous_task_rotation",s.taskOrientation.previousRotation())
     .put("requested_visible_types",s.lastRequestedVisibleTypes).put("direction_eligibility",s.taskOrientation.eligibility()).put("effective_direction_rotation",s.taskOrientation.effectiveRotation())
     .put("rotation_class",s.taskOrientation.rotationClass()).put("rotation_unavailable",s.taskOrientation.unavailable()==null?"":s.taskOrientation.unavailable())
     .put("top_activity",s.taskOrientation.topActivity()).put("orig_activity",s.taskOrientation.origActivity()).put("real_activity",s.taskOrientation.realActivity()).put("top_activity_info",s.taskOrientation.topActivityInfo())
     .put("requested_task_orientation",s.taskOrientation.requested()).put("orientation_source",s.taskOrientation.source()).put("orientation_bounds",new org.json.JSONArray(s.taskOrientation.bounds()))
     .put("render",new org.json.JSONArray(new int[]{s.renderBounds.width(),s.renderBounds.height()}))
     .put("box",new org.json.JSONArray(new int[]{s.card.getLeft(),s.card.getTop(),s.card.getWidth(),s.card.getHeight()}))
     .put("rotate",host.rotatePresentation(s)).put("perspective",s.perspectiveApplied);cards.put(card);}
    data.put("cards",cards);if(host.addCard().getVisibility()==View.VISIBLE)data.put("add",new org.json.JSONArray(new int[]{host.addCard().getLeft(),host.addCard().getTop(),host.addCard().getWidth(),host.addCard().getHeight()}));
    Log.i(TAG,"layout_evidence "+data);
   }catch(Throwable e){Log.w(TAG,"layout_evidence_failed",e);}
  }};host.stage().postOnAnimation(check);
 }
 boolean switching(){return animation!=null||settlingInput;}
 void logSwitch(String result){Log.i(TAG,"switch_metrics result="+result+" generation="+switchGeneration+" primary="+session.primary+" elapsed_ms="+(SystemClock.uptimeMillis()-switchStarted)+" frames="+animationFrames+" max_frame_gap_ms="+maxFrameGap+" resize_calls="+resizeStats.calls()+" resize_wall_us="+(resizeStats.nanos()/1000)+" fit_failures="+motionFitFailures);}
 void cancelAnimation(){
  cancelAnimation(false);
 }
 void cancelAnimation(boolean preservePose){
  clearLayoutSwitch();
  if(host.taskRotationRun()!=null)host.finishTaskRotation(host.taskRotationRun(),"cancelled");
  host.cancelHandoffPresentation();
  if(addAnimation!=null){ValueAnimator old=addAnimation;addAnimation=null;old.cancel();host.releaseEntrance();}
  if(switching())logSwitch("cancelled");switchGeneration++;host.removeMotionFitListener();
  settlingInput=false;
  if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}
  if(!preservePose)for(WorkbenchSlot s:session.slots)s.motionPose=null;
  host.cancelForLayout(preservePose);
 }
 void recordResize(long elapsed){resizeStats.record(elapsed);}
 void queueExistingSwitch(WorkbenchSlot selected,long selectionSession){
  cancelSwitchPreparation("superseded");
  switchPrepareTarget=selected;switchPrepareSession=selectionSession;switchPreparePrimary=session.primary;
  switchPrepareSlots=new ArrayList<>(session.slots);switchPrepareTasks=new int[session.slots.size()];
  for(int i=0;i<session.slots.size();i++){WorkbenchSlot slot=session.slots.get(i);switchPrepareTasks[i]=slot.taskId;}
  final int token=switchPrepareToken=switchPreparation.begin(SystemClock.uptimeMillis(),MotionSpec.ARRIVAL_UNLOCK_MS);
  // Do not read a destroyed background Surface or reorder Slots before restoration.
  for(int i=0;i<session.slots.size();i++)host.inputRole(session.slots.get(i),i==session.primary);
  Log.i(TAG,"switch_prepare_wait token="+token+" task="+selected.taskId+" session="+selectionSession+" container="+host.taskId()+" tasks="+taskIds());
  handler.post(()->prepareExistingSwitch(token));
  handler.postDelayed(()->{if(switchPreparation.current(token))failSwitchPreparation(token,"timeout");},MotionSpec.ARRIVAL_UNLOCK_MS);
 }
 boolean sameSwitchParticipants(){
  if(switchPrepareSlots==null||switchPrepareSlots.size()!=session.slots.size()||session.primary!=switchPreparePrimary)return false;
  for(int i=0;i<session.slots.size();i++){
   WorkbenchSlot s=session.slots.get(i);if(s!=switchPrepareSlots.get(i)||s.released||switchPrepareTasks[i]<0)return false;
   // A rebind can temporarily publish -1; readiness will wait. A different real
   // task never inherits this request, even if its package name is the same.
   if(s.taskId>=0&&s.taskId!=switchPrepareTasks[i])return false;
  }
  return switchPrepareTarget!=null&&session.slots.contains(switchPrepareTarget);
 }
 void prepareExistingSwitch(int token){
  if(!switchPreparation.waiting(token))return;
  if(host.closing()||host.finishing()||!sameSwitchParticipants()){failSwitchPreparation(token,"identity_changed");return;}
  if(switchPreparation.remaining(SystemClock.uptimeMillis())==0){failSwitchPreparation(token,"timeout");return;}
  if(host.stopped()||host.backgrounded()||host.recovering()||host.dragging()!=null||host.screenArrival()||host.handoffPresentation()||animation!=null||settlingInput||host.entranceCover()!=null||host.coverReleasePending()){
   handler.postDelayed(()->prepareExistingSwitch(token),MotionSpec.SURFACE_RETRY_MS);return;
  }
  String key=host.entranceSurfaceKey();
  if(key==null){handler.postDelayed(()->prepareExistingSwitch(token),MotionSpec.SURFACE_RETRY_MS);return;}
  final int ticket=switchPreparation.commit(token,SystemClock.uptimeMillis());
  if(ticket<0){failSwitchPreparation(token,"timeout");return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(WorkbenchSlot s:session.slots)if(!host.writeLeashFit(s,fit))throw new IllegalStateException("Unfitted existing-switch slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!switchPreparation.committing(token,ticket))return;
    host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(!switchPreparation.committing(token,ticket))return;
     if(host.closing()||host.finishing()||!sameSwitchParticipants()){failSwitchPreparation(token,"identity_changed");return;}
     boolean ready=!host.stopped()&&!host.backgrounded()&&!host.recovering()&&host.dragging()==null&&!host.screenArrival()&&!host.handoffPresentation()&&animation==null&&!settlingInput&&host.entranceCover()==null&&!host.coverReleasePending()&&key.equals(host.entranceSurfaceKey());
     for(WorkbenchSlot s:session.slots)ready&=s.taskId>=0&&host.currentPose(s)!=null;
     if(!ready){retrySwitchPreparation(token,ticket);return;}
     WorkbenchSlot selected=switchPrepareTarget;long selectionSession=switchPrepareSession;int task=selected.taskId,index=session.slots.indexOf(selected);
     if(!switchPreparation.ready(token,ticket,SystemClock.uptimeMillis())){failSwitchPreparation(token,"timeout");return;}
     clearSwitchPreparation();
     Log.i(TAG,"switch_prepare_result result=ready token="+token+" task="+task+" session="+selectionSession+" container="+host.taskId());
     // promote samples the restored, committed pose immediately before changing roles.
     switchOriginPrepareToken=token;
     try{promote(index);}finally{switchOriginPrepareToken=0;}
    }));host.stage().invalidate();
   });host.submitMotionFit(fit);host.stage().invalidate();
  }catch(Throwable e){Log.w(TAG,"switch_prepare_fit_failed token="+token+" ticket="+ticket,e);retrySwitchPreparation(token,ticket);}
 }
 void retrySwitchPreparation(int token,int ticket){
  if(switchPreparation.retry(token,ticket,SystemClock.uptimeMillis()))handler.postDelayed(()->prepareExistingSwitch(token),MotionSpec.SURFACE_RETRY_MS);
  else if(switchPreparation.current(token))failSwitchPreparation(token,"timeout");
 }
 void clearSwitchPreparation(){switchPrepareTarget=null;switchPrepareSlots=null;switchPrepareTasks=null;switchPrepareSession=0;}
 void cancelSwitchPreparation(String reason){
  if(!switchPreparation.pending())return;
  int task=switchPrepareTarget==null?-1:switchPrepareTarget.taskId;
  switchPreparation.cancel();clearSwitchPreparation();
  Log.i(TAG,"switch_prepare_result result=cancelled token="+switchPrepareToken+" task="+task+" reason="+reason);
  if(!host.closing())for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);
 }
 void failSwitchPreparation(int token,String reason){
  if(!switchPreparation.current(token))return;
  int task=switchPrepareTarget==null?-1:switchPrepareTarget.taskId;
  if(!switchPreparation.timeout(token,SystemClock.uptimeMillis()))switchPreparation.cancel();
  clearSwitchPreparation();
  Log.w(TAG,"switch_prepare_result result=failed token="+token+" task="+task+" reason="+reason+" waiting_for="+host.entranceWaitReason());
  host.closeEntranceCover("switch_prepare_"+reason);
  if(!host.closing()){for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);Toast.makeText(host.context(),"窗口尚未恢复，请再试一次",Toast.LENGTH_SHORT).show();}
 }
 void promote(int index){
  if(layoutSwitchVisuals!=null||host.closing()||host.recovering()||host.dragging()!=null||index<0||index>=session.slots.size()||index==session.primary)return;
  SwitchMotion<WorkbenchSlot> source=SwitchMotion.capture(session.slots,s->{
   CardMotion.Pose pose=s.taskId<0||s.card==null?null:host.currentPose(s);
   if(pose==null)Log.w(TAG,"switch_rejected reason=source_not_ready slot="+s.id+" task="+s.taskId);
   return pose;
  });
  if(source==null){Toast.makeText(host.context(),"请等待应用窗口就绪",Toast.LENGTH_SHORT).show();return;}
  cancelSwitchPreparation("direct_switch");cancelAnimation(true);
  // Claim the whole source frame before a role change can trigger a native fit.
  for(WorkbenchSlot s:session.slots)s.motionPose=source.source(s);
  CardOrder.promote(session.slots,session.primary,index);layoutCards(true,source);
  refreshStatus();Log.i(TAG,"switch primary="+session.primary+" tasks="+taskIds());
 }
 String taskIds(){StringBuilder b=new StringBuilder();for(WorkbenchSlot s:session.slots){if(b.length()>0)b.append(',');b.append(s.taskId);}return b.toString();}
 void refreshStatus(){host.runOnUiThread(()->{if(host.closing()||session.slots.isEmpty())return;boolean ready=true,failed=false;for(WorkbenchSlot s:session.slots){ready&=s.taskId>=0;failed|=s.failed;}host.status().setVisibility(ready&&!failed?View.GONE:View.VISIBLE);host.status().setText(failed?"部分窗口未就绪，可长按卡片替换或移出":ready?session.slots.size()+" 个实时窗口 · 主应用："+session.slots.get(session.primary).label:"正在等待应用窗口…");if(host.status().getVisibility()==View.VISIBLE)host.status().bringToFront();});}
}
