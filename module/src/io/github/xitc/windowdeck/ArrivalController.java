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

/** Owns arrival state for the workbench. The activity is only the nested Host. */
final class ArrivalController {
 interface Host {
  int[][] allocation();
  ValueAnimator animation();
  void apply(WorkbenchSlot s,int[] r);
  boolean backgrounded();
  CardLayout.Result cardGeometry();
  void clearHangReturnGeometry();
  void clearHangSourceFrame();
  void closeEntranceCover(String why);
  boolean closing();
  Context context();
  int effectiveMode();
  HandoffCover entranceCover();
  String entranceSurfaceKey();
  String entranceWaitReason();
  boolean focusPrimary(String reason);
  boolean hangReplace();
  CardMotion.Pose hangReturnPose(WorkbenchSlot s);
  IdentityHashMap<WorkbenchSlot,Rect> hangReturnRects();
  void inputRole(WorkbenchSlot s,boolean isPrimary);
  void layoutAddCard(int[] box);
  void layoutCaption();
  void layoutCards(boolean animate);
  void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source);
  ViewTreeObserver.OnPreDrawListener motionFitListener();
  WorkbenchSlot pendingEntrance();
  void poseCard(WorkbenchSlot s,CardMotion.Pose pose,CardMotion.Pose target);
  void raiseMain();
  void releaseEntranceCoverWhenDrawn();
  void removeMotionFitListener();
  void resetCardTransform(WorkbenchSlot s);
  Resources resources();
  CardMotion.Pose rolePose(WorkbenchSlot s,float[] box,boolean preview);
  void blockInput();
  void clearInputBlock();
  void resetPendingEntrance();
  void scheduleLayoutEvidence(String reason);
  void setAnimation(ValueAnimator value);
  void setMotionFitListener(ViewTreeObserver.OnPreDrawListener value);
  void setSettlingInput(boolean value);
  boolean settlingInput();
  CanvasStage stage();
  boolean stopped();
  void submitMotionFit(SurfaceControl.Transaction fit);
  int taskDrawState(WorkbenchSlot s) throws Exception;
  void unlockEntranceInput();
  WindowManager windowManager();
  boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 ArrivalController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 boolean hangEntranceDue,screenArrival;
 WorkbenchSlot arrivalSlot;
 int[][] arrivalFrom;
 CardMotion.Pose[] arrivalSources,arrivalTargets;
 String arrivalGeometry;
 boolean arrivalFromEdge,arrivalHungMain,arrivalOriginalPlus;
 MotionSpec.Scene arrivalScene=MotionSpec.Scene.ADD;
 final EntranceRun arrivalRun=new EntranceRun();
 final ArrivalCancellation arrivalCancellation=new ArrivalCancellation();
 int arrivalToken;
 final Runnable arrivalUnlock=this::checkArrivalDeadline;
 long arrivalLastFrame,arrivalFrameGap;
 int arrivalFrames,arrivalFitFailures;


 void beginHangEntrance(){
  if(!hangEntranceDue||host.closing()||!screenArrival||host.stopped()||host.backgrounded()||host.animation()!=null)return;
  hangEntranceDue=false;playScreenArrival();
 }
 boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus){
  return poseScreenArrival(fresh,fromEdge,hungMain,originalPlus,MotionSpec.ARRIVAL_UNLOCK_MS);
 }
 boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus,long waitMs){
  return poseScreenArrivalUntil(fresh,fromEdge,hungMain,originalPlus,SystemClock.uptimeMillis()+waitMs);
 }
 boolean poseScreenArrivalUntil(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus,long deadline){
  if(fresh==null||fresh.card==null||host.stage().getWidth()==0||session.slots.isEmpty())return false;
  fresh.card.setAlpha(1f);
  int[] loc=new int[2];host.stage().getLocationOnScreen(loc);
  Rect screen=host.windowManager().getCurrentWindowMetrics().getBounds();
  int[][] target=host.cardGeometry().cards;if(target.length!=session.slots.size())return false;
  boolean sideAdd=SideEntranceMotion.applies(session.slots.indexOf(fresh),session.primary,session.slots.size(),hungMain,originalPlus);
  int mode=host.effectiveMode(),railExtent=sideAdd?host.allocation()[session.primary][mode==PaneLayout.TOP_BOTTOM?1:0]:0;
  java.util.HashMap<WorkbenchSlot,Rect> edge=fromEdge?edgeRects(fresh,target,loc,screen,hungMain,originalPlus):new java.util.HashMap<>();
  int[][] from=new int[session.slots.size()][4];
  CardMotion.Pose[] sources=new CardMotion.Pose[session.slots.size()],targets=new CardMotion.Pose[session.slots.size()];
  int shelf=HangPlace.shelfMain(session.slots.size(),session.primary,hungMain,originalPlus);
  for(int i=0;i<session.slots.size();i++){
   WorkbenchSlot s=session.slots.get(i);Rect placed=edge.get(s);
   if(s==fresh)from[i]=sideAdd?SideEntranceMotion.start(target[i],mode,railExtent):new int[]{-loc[0],-loc[1],Math.max(1,screen.width()),Math.max(1,screen.height())};
   else if(placed!=null)from[i]=new int[]{placed.left-loc[0],placed.top-loc[1],Math.max(1,placed.width()),Math.max(1,placed.height())};
   else from[i]=new int[]{target[i][0],target[i][1],target[i][2],target[i][3]};
   boolean mainRadius=shelf>=0?i==shelf:i==session.primary;
   s.motionFromRadius=s==fresh?(sideAdd?Ui.dp(host.context(),Ui.SIDE_RADIUS):0f):Ui.dp(host.context(),mainRadius?Ui.MAIN_RADIUS:Ui.SIDE_RADIUS);
   int[] r=target[i];targets[i]=host.rolePose(s,new float[]{r[0],r[1],r[2],r[3]},i!=session.primary);
   CardMotion.Pose shape=s==fresh&&sideAdd?targets[i]:hungMain&&s!=fresh?host.hangReturnPose(s):null;
   if(shape==null)shape=host.rolePose(s,new float[]{from[i][0],from[i][1],from[i][2],from[i][3]},s!=fresh&&!mainRadius);
   sources[i]=ArrivalMotion.atBox(shape,from[i],s.motionFromRadius);
  }
  // Own the whole source frame before any resize/reparent can inspect a new role.
  for(int i=0;i<session.slots.size();i++)session.slots.get(i).motionPose=sources[i];
  for(int i=0;i<session.slots.size();i++){WorkbenchSlot s=session.slots.get(i);host.apply(s,target[i]);host.poseCard(s,sources[i],targets[i]);}
  host.layoutAddCard(host.cardGeometry().add);
  host.raiseMain();arrivalSlot=fresh;arrivalFrom=from;arrivalSources=sources;arrivalTargets=targets;screenArrival=true;hangEntranceDue=true;host.blockInput();
  arrivalGeometry=arrivalGeometryKey();arrivalFromEdge=fromEdge;arrivalHungMain=hungMain;arrivalOriginalPlus=originalPlus;
  arrivalScene=sideAdd?MotionSpec.Scene.SIDE_ADD:host.hangReplace()?MotionSpec.Scene.REPLACE:MotionSpec.Scene.ADD;
  long prepared=SystemClock.uptimeMillis(),waitMs=Math.max(0L,deadline-prepared);
  arrivalToken=arrivalRun.begin(prepared,waitMs);
  if(host.entranceCover()!=null)host.entranceCover().holdForArrival(arrivalRun.waitingDeadline(arrivalToken));
  handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,waitMs);
  if(!host.stopped()&&!host.backgrounded())handler.post(this::beginHangEntrance);
  Log.i(TAG,"arrive_posed token="+arrivalToken+" task="+fresh.sourceTaskId+" edge="+fromEdge+" original_plus="+originalPlus+" scene="+arrivalScene+" mode="+mode+" rail_extent="+railExtent+" source="+Arrays.toString(from[session.slots.indexOf(fresh)])+" screen="+screen.width()+"x"+screen.height());
  return true;
}
 String arrivalGeometryKey(){
  StringBuilder key=new StringBuilder().append(host.stage().getWidth()).append('x').append(host.stage().getHeight()).append(':').append(host.effectiveMode());
  for(WorkbenchSlot s:session.slots)key.append('|').append(s.id).append(':').append(s.orientationAxis).append(':').append(s.renderBounds);
  return key.toString();
 }
 void rebaseArrivalLayout(){
  if(!screenArrival||Objects.equals(arrivalGeometry,arrivalGeometryKey()))return;
  if(arrivalRun.phase()!=EntranceRun.Phase.WAITING){finishArrival("cancelled_geometry_changed");host.layoutCards(false);return;}
  long remaining=arrivalRun.remaining(SystemClock.uptimeMillis());
  if(remaining==0){checkArrivalDeadline();host.layoutCards(false);return;}
  long deadline=arrivalRun.waitingDeadline(arrivalToken);
  WorkbenchSlot fresh=arrivalSlot;boolean edge=arrivalFromEdge,hung=arrivalHungMain,plus=arrivalOriginalPlus;
  if(host.animation()!=null){ValueAnimator old=host.animation();host.setAnimation(null);old.cancel();}
  arrivalRun.cancel();
  Log.i(TAG,"arrive_layout_rebased task="+(fresh==null?-1:fresh.taskId)+" old_token="+arrivalToken+" remaining_ms="+remaining);
  if(!poseScreenArrivalUntil(fresh,edge,hung,plus,deadline)){finishArrival("cancelled_geometry_unavailable");host.layoutCards(false);}
 }
 java.util.HashMap<WorkbenchSlot,Rect> edgeRects(WorkbenchSlot fresh,int[][] target,int[] loc,Rect screen,boolean hungMain,boolean originalPlus){
  java.util.HashMap<WorkbenchSlot,Rect> map=new java.util.HashMap<>();
  if(session.slots.size()<2)return map;
  if(hungMain&&!host.hangReturnRects().isEmpty()){
   for(WorkbenchSlot s:session.slots)if(s!=fresh&&host.hangReturnRects().containsKey(s))map.put(s,new Rect(host.hangReturnRects().get(s)));
   if(map.size()==session.slots.size()-1)return map;
   map.clear();Log.w(TAG,"arrival_shelf_geometry_missing fallback=true");
  }
  ArrayList<WorkbenchSlot> order=new ArrayList<>();ArrayList<HangShelf.Card> cards=new ArrayList<>();
  WorkbenchSlot previous=null;
  if(hungMain){
   int shelf=HangPlace.shelfMain(session.slots.size(),session.primary,true,originalPlus);
   previous=shelf<0?null:session.slots.get(shelf);
   if(!originalPlus&&previous==fresh&&session.slots.size()>2)previous=session.slots.get(session.slots.size()-2);
   if(previous!=null&&previous!=fresh){order.add(previous);cards.add(new HangShelf.Card(null,previous.component.getPackageName(),placed(previous,target,loc),true));}
  }
  for(WorkbenchSlot s:session.slots)if(s!=fresh&&s!=previous){order.add(s);cards.add(new HangShelf.Card(null,s.component.getPackageName(),placed(s,target,loc),false));}
  if(cards.isEmpty())return map;
  Rect[] ends=HangShelf.ends(cards,screen,host.effectiveMode()==PaneLayout.TOP_BOTTOM,screen.height()>screen.width(),Ui.dp(host.context(),20),Ui.dp(host.context(),48));
  for(int i=0;i<order.size()&&i<ends.length;i++)map.put(order.get(i),ends[i]);
  return map;
 }
 Rect placed(WorkbenchSlot s,int[][] target,int[] loc){int i=session.slots.indexOf(s);int[] r=target[Math.max(0,i)];return new Rect(r[0]+loc[0],r[1]+loc[1],r[0]+loc[0]+r[2],r[1]+loc[1]+r[3]);}
 void playScreenArrival(){
  if(!screenArrival||arrivalSources==null||arrivalTargets==null||host.animation()!=null||host.closing()||arrivalRun.phase()!=EntranceRun.Phase.WAITING)return;
  // A fullscreen landscape launch can still be returning the display to our portrait
  // container. Wait for its actual canvas instead of animating against the old display.
  if(host.stage().getWidth()>=host.stage().getHeight()||host.resources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT){handler.postDelayed(this::playScreenArrival,MotionSpec.SURFACE_RETRY_MS);return;}
  // The entrance flag swallows every card touch. Drop it before the spring so the end state can receive input.
  host.resetPendingEntrance();
  final CardMotion.Pose[] from=arrivalSources,to=arrivalTargets;
  final ArrayList<WorkbenchSlot> current=new ArrayList<>(session.slots);
  final int token=arrivalToken;
  // Side-list add has its own 150 ms delay and 350/300 ms travel on a 500 ms group clock.
  // Desktop add, replacement and multi-window retain their independent scene timing.
  // Apply the curve after local time conversion, never re-time an already-curved value.
  final MotionSpec.Scene scene=arrivalScene;
  final long total=MotionSpec.duration(scene);
  final int arrivalPrimary=session.primary;
  final int arrivalMode=host.effectiveMode();final WorkbenchSlot arriving=arrivalSlot;
  final boolean[] moving=new boolean[current.size()];
  host.setAnimation(ValueAnimator.ofFloat(0f,1f));host.animation().setDuration(total);host.animation().setInterpolator(null);
  final ValueAnimator expected=host.animation();
  arrivalFitFailures=0;
  host.removeMotionFitListener();
  host.setMotionFitListener(()->{
   if(screenArrival&&arrivalToken==token&&!host.closing()&&!host.stopped()&&!host.backgrounded()){
    try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
     boolean ready=true;for(WorkbenchSlot s:current)ready&=host.writeLeashFit(s,fit);
     if(ready)host.submitMotionFit(fit);else if(!arrivalRun.waiting(token))arrivalFitFailures++;
    }catch(Throwable e){if(!arrivalRun.waiting(token))arrivalFitFailures++;Log.w(TAG,"arrival_frame_fit_failed token="+token,e);}
   }
   return true;
  });
  host.stage().getViewTreeObserver().addOnPreDrawListener(host.motionFitListener());
  for(int i=0;i<current.size();i++)host.inputRole(current.get(i),i==session.primary);
  host.animation().addUpdateListener(a->{float raw=(Float)a.getAnimatedValue();
   if(host.animation()!=a||!arrivalRun.running(token))return;
   long now=SystemClock.uptimeMillis();if(arrivalLastFrame>0)arrivalFrameGap=Math.max(arrivalFrameGap,now-arrivalLastFrame);arrivalLastFrame=now;arrivalFrames++;
   for(int i=0;i<current.size()&&i<from.length&&i<to.length;i++)if(current.get(i).card!=null){
    int position=MotionSpec.entrancePosition(i,arrivalPrimary);
    boolean freshSide=scene==MotionSpec.Scene.SIDE_ADD&&current.get(i)==arriving;
    float local=ArrivalMotion.localTime(raw,scene,position,freshSide,arrivalMode);
    if(local>0f&&!moving[i]){moving[i]=true;Log.i(TAG,"arrive_card_start slot="+current.get(i).id+" position="+position+" primary="+(i==arrivalPrimary)+" delay_ms="+(freshSide?MotionSpec.SIDE_ADD_DELAY_MS:MotionSpec.delayFor(scene,position))+" elapsed_ms="+Math.round(raw*total));}
    host.poseCard(current.get(i),ArrivalMotion.frame(from[i],to[i],local,scene),to[i]);
   }host.stage().invalidate();});
  host.animation().addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){
   Log.i(TAG,"arrive_animation_end token="+token+" current="+(host.animation()==a)+" phase="+arrivalRun.phase());
   if(host.animation()!=a)return;
   if(!arrivalRun.settle(token,SystemClock.uptimeMillis(),MotionSpec.ARRIVAL_UNLOCK_MS)){checkArrivalDeadline();return;}
   host.setAnimation(null);host.blockInput();host.stage().watch("arrival_"+token);
   for(int i=0;i<current.size();i++){host.poseCard(current.get(i),to[i],to[i]);host.inputRole(current.get(i),i==session.primary);}
   handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,arrivalRun.remaining(SystemClock.uptimeMillis()));
   Log.i(TAG,"arrive_settling token="+token+" task="+(arrivalSlot==null?-1:arrivalSlot.taskId));
   settleArrivalFrame(token,true);
  }});
  final boolean[] started={false};
  final String[] sourceKey={null};
  final Runnable[] preparation={null};
  final int[] waits={0};
  Runnable start=new Runnable(){public void run(){
   if(started[0]||host.animation()!=expected||!screenArrival||host.closing()||host.stopped()||host.backgrounded()||!arrivalRun.waiting(token))return;
   if(arrivalRun.remaining(SystemClock.uptimeMillis())==0){checkArrivalDeadline();return;}
   if(!Objects.equals(sourceKey[0],host.entranceSurfaceKey())||sourceKey[0]==null){sourceKey[0]=null;handler.post(preparation[0]);return;}
   // Remove the static cover while every task still holds the committed source.
   // Its stale bitmap must never cover the first 60-129 ms of moving live cards.
   if(host.entranceCover()!=null){host.releaseEntranceCoverWhenDrawn();handler.postDelayed(this,MotionSpec.SURFACE_RETRY_MS);return;}
   if(!arrivalRun.start(token,SystemClock.uptimeMillis(),total+MotionSpec.FRAME_TIMEOUT_MS)){checkArrivalDeadline();return;}
   handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,arrivalRun.remaining(SystemClock.uptimeMillis()));
   started[0]=true;arrivalLastFrame=arrivalFrameGap=0;arrivalFrames=0;host.unlockEntranceInput();expected.start();
   Log.i(TAG,"arrive_animation_start token="+token+" slots="+current.size()+" scene="+scene+" duration_ms="+total+" side_travel_ms="+(scene==MotionSpec.Scene.SIDE_ADD?SideEntranceMotion.travelMs(arrivalMode):0)+" curve="+(scene==MotionSpec.Scene.SIDE_ADD?"vivo_side_critical":scene==MotionSpec.Scene.MULTI_WINDOW?"coui_geometry":"c17_rapid_path"));
  }};
  Runnable prepare=new Runnable(){public void run(){
   if(started[0]||sourceKey[0]!=null||host.animation()!=expected||!screenArrival||host.closing()||!arrivalRun.waiting(token))return;
   if(arrivalRun.remaining(SystemClock.uptimeMillis())==0){checkArrivalDeadline();return;}
   String key=host.entranceSurfaceKey();
   if(key==null){
    if(++waits[0]==30)for(WorkbenchSlot s:current)try{Log.w(TAG,"arrival_surface_wait slot="+s.id+" task="+s.taskId+" draw="+host.taskDrawState(s)+" "+s.drawDetail+" stage_layout="+host.stage().isLayoutRequested()+" card_layout="+s.card.isLayoutRequested()+" surface_layout="+s.surface.isLayoutRequested()+" holder_valid="+((SurfaceView)s.surface).getHolder().getSurface().isValid());}catch(Throwable e){Log.w(TAG,"arrival_surface_wait_failed",e);}
    handler.postDelayed(this,MotionSpec.SURFACE_RETRY_MS);return;
   }
   try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
    for(WorkbenchSlot s:current)if(!host.writeLeashFit(s,fit))throw new IllegalStateException("Unfitted arrival slot "+s.id);
    fit.addTransactionCommittedListener((Executor)handler::post,()->{
     if(host.animation()!=expected||started[0])return;
     host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
      if(host.animation()!=expected||!arrivalRun.waiting(token))return;
      if(key.equals(host.entranceSurfaceKey())){sourceKey[0]=key;start.run();}
      else handler.post(this);
     }));host.stage().invalidate();
    });host.submitMotionFit(fit);host.stage().invalidate();
   }catch(Throwable e){Log.w(TAG,"arrival_surface_commit_failed",e);handler.postDelayed(this,MotionSpec.SURFACE_RETRY_MS);}
  }};
  preparation[0]=prepare;
  handler.post(prepare);
  Log.i(TAG,"arrive_waiting_surfaces slots="+current.size());
}
 void checkArrivalDeadline(){
  EntranceRun.Phase phase=arrivalRun.phase();
  if(phase!=EntranceRun.Phase.WAITING&&phase!=EntranceRun.Phase.RUNNING&&phase!=EntranceRun.Phase.SETTLING)return;
  long remaining=arrivalRun.remaining(SystemClock.uptimeMillis());
  if(remaining>0){handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,remaining+1);return;}
  if(arrivalRun.timeout(arrivalToken,SystemClock.uptimeMillis())){
   Log.w(TAG,"arrive_timeout phase="+phase+" token="+arrivalToken+" waiting_for="+host.entranceWaitReason()+" "+host.stage().trace());
   finishArrival("timeout_"+phase);
  }
 }
 void finishArrival(String result){
  if(!"completed".equals(result))arrivalRun.cancel();
  Log.i(TAG,"arrive_result result="+result+" token="+arrivalToken+" task="+(arrivalSlot==null?-1:arrivalSlot.taskId));
  Log.i(TAG,"arrive_motion_frames callbacks="+arrivalFrames+" max_gap_ms="+arrivalFrameGap+" fit_failures="+arrivalFitFailures+" owner=ui_animator");
  host.stage().stopWatching();
  host.removeMotionFitListener();
  handler.removeCallbacks(arrivalUnlock);
  if(host.animation()!=null){ValueAnimator old=host.animation();host.setAnimation(null);old.cancel();}
  boolean locked=screenArrival||host.pendingEntrance()!=null||host.settlingInput();
  screenArrival=false;hangEntranceDue=false;arrivalSlot=null;arrivalFrom=null;arrivalSources=null;arrivalTargets=null;
  host.resetPendingEntrance();host.setSettlingInput(false);
  host.clearInputBlock();host.closeEntranceCover("arrival_"+result);
  for(WorkbenchSlot s:session.slots)if(s.card!=null){s.motionPose=null;host.resetCardTransform(s);}
  for(int i=0;i<session.slots.size();i++)if(session.slots.get(i).card!=null)host.inputRole(session.slots.get(i),i==session.primary);
  try{Method method=rom.canvas.viewMethod("setNoNeedStartEmbedded");for(WorkbenchSlot s:session.slots)if(s.surface!=null)method.invoke(s.surface,false);}catch(Throwable ignored){}
  host.layoutCaption();
  host.clearHangSourceFrame();
  host.clearHangReturnGeometry();
  host.scheduleLayoutEvidence("arrival_"+result);
  if(locked)Log.i(TAG,"arrive_unlocked");
 }
 void settleArrivalFrame(int token,boolean reveal){
  if(!arrivalRun.settling(token)||!screenArrival||host.closing()||host.stopped()||host.backgrounded())return;
  if(arrivalRun.remaining(SystemClock.uptimeMillis())==0){checkArrivalDeadline();return;}
  String key=host.entranceSurfaceKey();
  if(key==null){handler.postDelayed(()->settleArrivalFrame(token,reveal),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(WorkbenchSlot s:session.slots)if(!host.writeLeashFit(s,fit))throw new IllegalStateException("Unfitted final arrival slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!arrivalRun.settling(token)||!screenArrival)return;
    host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(!arrivalRun.settling(token)||!screenArrival)return;
     if(!key.equals(host.entranceSurfaceKey())){settleArrivalFrame(token,reveal);return;}
     if(reveal){
      host.removeMotionFitListener();
      for(WorkbenchSlot s:session.slots){s.motionPose=null;host.resetCardTransform(s);}
      host.clearInputBlock();
      for(int i=0;i<session.slots.size();i++)host.inputRole(session.slots.get(i),i==session.primary);
      handler.post(()->settleArrivalFrame(token,false));
     }else if(arrivalRun.complete(token,SystemClock.uptimeMillis())){
      finishArrival("completed");handler.post(()->host.focusPrimary("arrive"));
     }else checkArrivalDeadline();
    }));host.stage().invalidate();
   });host.submitMotionFit(fit);host.stage().invalidate();
  }catch(Throwable e){Log.w(TAG,"arrival_final_commit_failed",e);handler.postDelayed(()->settleArrivalFrame(token,reveal),MotionSpec.SURFACE_RETRY_MS);}
 }
 void dropHangEntranceDue(){hangEntranceDue=false;}
 void cancelForLayout(boolean preservePose){
  arrivalCancellation.cancel(new ArrivalCancellation.Effects(){
   public boolean active(){return screenArrival;}
   public void cancelRun(){arrivalRun.cancel();}
   public void stopWatching(){host.stage().stopWatching();}
   public void logCancelled(){Log.i(TAG,"arrive_result result=cancelled token="+arrivalToken);}
   public void removeUnlock(){handler.removeCallbacks(arrivalUnlock);}
   public void clearInput(){host.clearInputBlock();}
   public void closeCover(){host.closeEntranceCover("arrival_cancelled");}
   public void clearHangFrame(){host.clearHangSourceFrame();}
   public void clearHangGeometry(){host.clearHangReturnGeometry();}
   public void clearFields(){screenArrival=false;hangEntranceDue=false;arrivalSlot=null;arrivalFrom=null;arrivalSources=null;arrivalTargets=null;}
   public void resetPoses(){for(WorkbenchSlot s:session.slots)if(s.card!=null)host.resetCardTransform(s);}
  },preservePose);
 }
}
