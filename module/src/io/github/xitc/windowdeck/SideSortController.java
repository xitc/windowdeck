package io.github.xitc.windowdeck;

import android.animation.*;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Owns side-only preview poses, including native fits, until the recovery frame commits. */
final class SideSortController {
 private static final long ICON_FADE_OUT_MS=150,ICON_FADE_IN_MS=300;
 enum Phase { IDLE,PRESSED,SORTING,SETTLING }
 private final WorkbenchActivity host;
 private SideSortOrder<WorkbenchSlot> order;
 private WorkbenchSlot selected;
 private Phase phase=Phase.IDLE;
 private int generation,mode,fitFailures;
 private boolean awaitingFrame,submitted,previewLayout,restoringIcons;
 private float delta,scale=1;
 private SideSortGesture gesture;
 private CardSpace space;
 private final Map<WorkbenchSlot,CardMotion.Pose> bases=new HashMap<>(),shown=new HashMap<>(),starts=new HashMap<>();
 private final Map<WorkbenchSlot,Integer> tasks=new HashMap<>();
 private final Map<WorkbenchSlot,Rect> bounds=new HashMap<>();
 private final Map<WorkbenchSlot,Long> moveStarts=new HashMap<>();
 private final Map<WorkbenchSlot,SideSortMoveCurve> moveCurves=new HashMap<>();
 private ValueAnimator press,settle,chrome;
 private ViewTreeObserver.OnPreDrawListener frame;
 private Runnable menuDelay,timeout;
 private PopupWindow popup;
 private AnimatorSet menuAnimation;
 private final SideSortMenuPolicy menuPolicy=new SideSortMenuPolicy();
 private int menuDownX,menuDownY;
 SideSortController(WorkbenchActivity host){this.host=host;}
 boolean active(){return phase!=Phase.IDLE;}
 WorkbenchSlot selected(){return selected;}
 boolean lifted(WorkbenchSlot s){return active()&&!awaitingFrame&&selected==s;}
 boolean begin(WorkbenchSlot s){
  dismissMenu();
  if(active()||host.closing||host.stopped||host.backgrounded||!host.initialized||host.layoutPosted||host.menu.dragging!=null
    ||host.menu.recovering||host.menu.modalWindows>0||host.layout.switching()||host.layout.addAnimation!=null||host.layout.layoutPreparation!=null
    ||host.layout.switchPreparation.pending()||host.arrival.screenArrival||host.portrait.deferPortrait||host.handoff.handoffPresentation
    ||host.pendingEntrance()!=null||host.coverReleasePending()||host.entranceCover()!=null||host.identity.taskRotationRun!=null||host.replacements.current()!=null
    ||host.fullscreen.fullscreenRun!=null||host.hang.hanging||!host.session.slots.contains(s)||s.released||s.taskId<0||host.session.slots.indexOf(s)==host.session.primary
    ||!host.stage.isHardwareAccelerated()||!host.stage.isAttachedToWindow())return false;
  Set<Integer> taskIds=new HashSet<>();
  for(WorkbenchSlot slot:host.session.slots){
   if(!taskIds.add(slot.taskId)||slot.released||slot.taskId<0||slot.card==null||!slot.embedded||slot.surface==null)return false;
   if(slot!=host.session.slots.get(host.session.primary)&&host.surfaces.currentPose(slot)==null)return false;
  }
  order=new SideSortOrder<>(host.session.slots,host.session.primary);selected=s;phase=Phase.PRESSED;generation++;
  menuDownX=Math.round(s.card.downRawX());menuDownY=Math.round(s.card.downRawY());
  mode=host.effectiveMode();space=host.stage.cardSpace();delta=0;gesture=new SideSortGesture(ViewConfiguration.get(host).getScaledTouchSlop());scale=1;fitFailures=0;submitted=awaitingFrame=previewLayout=restoringIcons=false;
  for(WorkbenchSlot slot:host.session.slots){
   tasks.put(slot,slot.taskId);bounds.put(slot,new Rect(slot.renderBounds));
   if(slot==host.session.slots.get(host.session.primary))continue;
   CardMotion.Pose pose=host.surfaces.currentPose(slot);bases.put(slot,pose);shown.put(slot,pose);
  }
  // Declare every owned pose before touching any RenderNode or allowing native fit callbacks.
  for(WorkbenchSlot slot:bases.keySet()){slot.motionPose=shown.get(slot);slot.card.gesturesEnabled=false;}
  s.card.bringToFront();host.status.bringToFront();
  frame=()->{drawFrame();return true;};host.stage.getViewTreeObserver().addOnPreDrawListener(frame);
  final int token=generation;
  final long pressDuration=SideSortSpring.PRESS.settleMillis(.15,.001);
  press=ValueAnimator.ofFloat(0,1);press.setDuration(pressDuration);press.setInterpolator(t->t);
  press.addUpdateListener(a->{if(token!=generation||(phase!=Phase.PRESSED&&phase!=Phase.SORTING))return;scale=a.getAnimatedFraction()>=1?1.15f:1+.15f*(float)SideSortSpring.PRESS.value(a.getAnimatedFraction()*pressDuration/1000.0);render(SystemClock.uptimeMillis());});press.start();
  menuDelay=()->{if(token==generation&&phase==Phase.PRESSED&&valid()&&!gesture.wouldSort())showMenu(s,tasks.get(s));};host.handler.postDelayed(menuDelay,300);
  s.card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
  Log.i("WindowDeck","side_sort_pressed slot="+s.id+" generation="+generation);return true;
 }
 void move(float dx,float dy){
  if(phase!=Phase.PRESSED&&phase!=Phase.SORTING)return;
  if(!valid()){abort("invalid_move");return;}
  if(host.session.slots.size()<=2)return;
  float raw=mode==PaneLayout.LEFT_RIGHT?dy:dx;
  CardMotion.Pose origin=bases.get(selected);int axis=mode==PaneLayout.LEFT_RIGHT?1:0;
  float min=host.stage.getPaddingTop(),max=host.stage.getHeight()-host.stage.getPaddingBottom();
  if(axis==0){min=host.stage.getPaddingLeft();max=host.stage.getWidth()-host.stage.getPaddingRight();}
  raw=Math.max(min-origin.box[axis],Math.min(raw,max-origin.box[axis]-origin.box[axis+2]));
  gesture.update(raw);host.stage.invalidate();
 }
 private boolean applyGestureFrame(){
  if((phase!=Phase.PRESSED&&phase!=Phase.SORTING)||!gesture.pending())return false;
  delta=gesture.draw();
  if(phase==Phase.PRESSED&&gesture.sorting()){
   phase=Phase.SORTING;if(menuDelay!=null){host.handler.removeCallbacks(menuDelay);menuDelay=null;}dismissMenu();
   fadeDecorations(false,null);
   Log.i("WindowDeck","side_sort_drag_started slot="+selected.id+" generation="+generation);
  }
  if(phase==Phase.SORTING)updatePreviewOrder(gesture.candidateDelta());
  render(SystemClock.uptimeMillis());return true;
 }
 private void updatePreviewOrder(float candidateDelta){
  CardMotion.Pose origin=bases.get(selected);int axis=mode==PaneLayout.LEFT_RIGHT?1:0;
  float location=origin.box[axis]+candidateDelta;
  // Successive neighbours only. Candidate tests use the unscaled card rectangle, not the finger.
  int direction=location>target(selected).box[axis]?1:-1;
  for(int guard=0;guard<host.session.slots.size();guard++){
   CardMotion.Pose current=target(selected);int next=order.neighbor(selected,direction);if(next<0)break;
   WorkbenchSlot other=order.preview().get(next);CardMotion.Pose candidate=target(other);
   if(!SideSortOrder.crosses(location,origin.box[axis+2],current.box[axis],candidate.box[axis],candidate.box[axis+2],direction))break;
   starts.put(other,shown.get(other));moveStarts.put(other,SystemClock.uptimeMillis());moveCurves.put(other,new SideSortMoveCurve());order.step(selected,direction);
   Log.i("WindowDeck","side_sort_swap slot="+selected.id+" other="+other.id+" generation="+generation);
  }
 }
 void release(){
  if(phase!=Phase.PRESSED&&phase!=Phase.SORTING)return;
  if(!valid()){abort("invalid_release");return;}
  applyGestureFrame();
  if(!active())return;
  cancelPress();phase=Phase.SETTLING;
  final int token=generation;final Map<WorkbenchSlot,CardMotion.Pose> from=new HashMap<>(shown);
  long recoveryDuration=1;int axis=mode==PaneLayout.LEFT_RIGHT?1:0;
  for(WorkbenchSlot slot:bases.keySet()){
   CardMotion.Pose start=from.get(slot),end=target(slot);
   float distance=end.box[axis]-start.box[axis];
   float scaleDistance=1-start.box[2]/end.box[2];
   recoveryDuration=Math.max(recoveryDuration,SideSortSpring.RECOVER.settleMillis(distance,1));
   recoveryDuration=Math.max(recoveryDuration,SideSortSpring.RECOVER.settleMillis(scaleDistance,.001));
  }
  final long recoveryMillis=recoveryDuration;
  settle=ValueAnimator.ofFloat(0,1);settle.setDuration(recoveryMillis);settle.setInterpolator(t->t);
  settle.addUpdateListener(a->{
   if(token!=generation||phase!=Phase.SETTLING)return;
   if(!valid()){abort("invalid_settle");return;}
   float f=a.getAnimatedFraction()>=1?1:(float)SideSortSpring.RECOVER.value(a.getAnimatedFraction()*recoveryMillis/1000.0);
   Map<WorkbenchSlot,CardMotion.Pose> poses=new HashMap<>();
   for(WorkbenchSlot slot:bases.keySet()){
    CardMotion.Pose start=from.get(slot),end=target(slot);float[] box=new float[4];
    for(int i=0;i<4;i++)box[i]=start.box[i]+(end.box[i]-start.box[i])*f;
    poses.put(slot,dynamic(new CardMotion.Pose(box,start.quad,start.crop,start.radius+(end.radius-start.radius)*f)));
   }
   publish(poses);
  });
  settle.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(token!=generation||phase!=Phase.SETTLING)return;settle=null;awaitingFrame=true;stagePreviewLayout();host.stage.invalidate();
  }});
  timeout=()->{if(token==generation&&active())abort("recovery_frame_timeout");};host.handler.postDelayed(timeout,1600+ICON_FADE_IN_MS);settle.start();
 }
 private boolean valid(){
  if(!active()||host.closing||host.backgrounded||host.stopped||!order.valid(host.session.slots,host.session.primary)
    ||mode!=host.effectiveMode()||!space.same(host.stage.cardSpace())||host.layoutPosted
    ||host.layout.switching()||host.layout.addAnimation!=null||host.layout.layoutPreparation!=null||host.layout.switchPreparation.pending()
    ||host.identity.taskRotationRun!=null||host.replacements.current()!=null||host.arrival.screenArrival||host.handoff.handoffPresentation)return false;
  for(WorkbenchSlot slot:host.session.slots)if(slot.released||!slot.embedded||slot.taskId!=tasks.get(slot)||!slot.renderBounds.equals(bounds.get(slot)))return false;
  return true;
 }
 private CardMotion.Pose target(WorkbenchSlot slot){
  CardMotion.Pose own=bases.get(slot),destination=bases.get(host.session.slots.get(order.index(slot)));
  return dynamic(new CardMotion.Pose(destination.box,own.quad,own.crop,own.radius));
 }
 private CardMotion.Pose dynamic(CardMotion.Pose pose){
  float[] b=pose.box;return CardMotion.at(b,host.surfaces.roleQuad(Math.round(b[2]),Math.round(b[3]),b[0],b[1],true),pose.crop,pose.radius);
 }
 private void render(long now){
  if(!active()||phase==Phase.SETTLING)return;
  Map<WorkbenchSlot,CardMotion.Pose> poses=new HashMap<>();
  for(WorkbenchSlot slot:bases.keySet()){
   CardMotion.Pose base=bases.get(slot),pose=target(slot);
   if(slot==selected){
    float[] box=base.box.clone();box[0]-=box[2]*(scale-1)/2;box[1]-=box[3]*(scale-1)/2;
    box[mode==PaneLayout.LEFT_RIGHT?1:0]+=delta;box[2]*=scale;box[3]*=scale;
    pose=new CardMotion.Pose(box,base.quad,base.crop,base.radius*scale);
   }else if(moveStarts.containsKey(slot)){
    float f=Math.min(1,(now-moveStarts.get(slot))/(float)SideSortMoveCurve.DURATION_MS);float eased=moveCurves.get(slot).sample(f);
    pose=CardMotion.between(starts.get(slot),pose,eased);if(f==1){moveStarts.remove(slot);moveCurves.remove(slot);starts.remove(slot);}
   }
   poses.put(slot,dynamic(pose));
  }
  publish(poses);
 }
 private void publish(Map<WorkbenchSlot,CardMotion.Pose> poses){
  for(WorkbenchSlot slot:poses.keySet())slot.motionPose=poses.get(slot);
  for(WorkbenchSlot slot:poses.keySet())host.surfaces.poseCard(slot,poses.get(slot),bases.get(slot));
  shown.clear();shown.putAll(poses);host.stage.invalidate();
 }
 /** Lay out the preview without changing the live task list or buffer sizes. */
 private void stagePreviewLayout(){
  previewLayout=true;
  for(WorkbenchSlot slot:bases.keySet())setLayoutBox(slot,target(slot).box);
 }
 private void setLayoutBox(WorkbenchSlot slot,float[] box){
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)slot.card.getLayoutParams();
  lp.leftMargin=Math.round(box[0]);lp.topMargin=Math.round(box[1]);lp.width=Math.round(box[2]);lp.height=Math.round(box[3]);
  slot.card.setLayoutParams(lp);
 }
 private boolean retainRecoveryPose(){
  for(WorkbenchSlot slot:bases.keySet()){
   float[] box=target(slot).box;
   if(slot.card.isLayoutRequested()||slot.card.getLeft()!=Math.round(box[0])||slot.card.getTop()!=Math.round(box[1])
     ||slot.card.getWidth()!=Math.round(box[2])||slot.card.getHeight()!=Math.round(box[3]))return false;
  }
  // Traversal changed each layout origin. Cancel that change in the View transform
  // while keeping the same stage pose for the final task/plate transaction.
  for(WorkbenchSlot slot:bases.keySet()){
   float[] actual={slot.card.getLeft(),slot.card.getTop(),slot.card.getWidth(),slot.card.getHeight()};
   CardMotion.Pose base=bases.get(slot);
   host.surfaces.poseCard(slot,shown.get(slot),new CardMotion.Pose(actual,base.quad,base.crop,base.radius));
  }
  return true;
 }
 private void drawFrame(){
  if(!active())return;
  if(!valid()){abort("invalid_frame");return;}
  boolean gestureFrame=applyGestureFrame();
  if(!active())return;
  if(awaitingFrame&&!retainRecoveryPose()){host.stage.invalidate();return;}
  if(!gestureFrame&&!moveStarts.isEmpty()&&phase!=Phase.SETTLING)render(SystemClock.uptimeMillis());
  final int token=generation;
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   boolean ready=true;for(WorkbenchSlot slot:bases.keySet())ready&=host.surfaces.writeLeashFit(slot,fit);
   if(!ready){if(++fitFailures>=8)abort("surface_fit_unavailable");else host.stage.invalidate();return;}
   fitFailures=0;
   if(awaitingFrame&&!submitted){
    submitted=true;
    // Commit the order only after the final fit transaction and its host draw both complete.
    fit.addTransactionCommittedListener(host.handler::post,()->{
     if(token!=generation||!active())return;
     host.stage.getViewTreeObserver().registerFrameCommitCallback(()->host.handler.post(()->complete(token)));
     host.stage.invalidate();
    });
   }
   host.surfaces.submitMotionFit(fit);
  }catch(Throwable error){Log.w("WindowDeck","side_sort_fit_failed",error);abort("surface_fit_failed");}
 }
 private void complete(int token){
  if(token!=generation||phase!=Phase.SETTLING||restoringIcons)return;
  if(!valid()){abort("invalid_restore");return;}
  restoringIcons=true;
  // The fitted geometry and ordinary layer must commit before chrome returns.
  // Keep the live order and input ownership until the fade has finished as well.
  fadeDecorations(true,()->commitOrder(token));
 }
 private void commitOrder(int token){
  if(token!=generation||phase!=Phase.SETTLING||!restoringIcons)return;
  if(!valid()){abort("invalid_commit");return;}
  boolean committed=order.commit(host.session.slots,host.session.primary);
  Log.i("WindowDeck","side_sort_end generation="+generation+" committed="+committed+" tasks="+host.layout.taskIds());
  cleanup(false);host.layout.layoutCards(false);host.publisher.publishState(host.session.slots.size());
 }
 private void cancelChrome(){
  if(chrome!=null){ValueAnimator old=chrome;chrome=null;old.cancel();}
 }
 private void fadeDecorations(boolean showing,Runnable finished){
  cancelChrome();
  final WorkbenchSlot slot=selected;final int token=generation;
  final float iconFrom=slot.icon.getAlpha(),pinFrom=slot.pin.getAlpha(),end=showing?1:0;
  final boolean pinned=slot.pinned;
  if(showing&&iconFrom==1&&(!pinned||pinFrom==1)){if(finished!=null)finished.run();return;}
  ValueAnimator run=ValueAnimator.ofFloat(0,1);chrome=run;
  run.setDuration(showing?ICON_FADE_IN_MS:ICON_FADE_OUT_MS);
  run.setInterpolator(new android.view.animation.PathInterpolator(.33f,0,.67f,1));
  run.addUpdateListener(a->{
   if(chrome!=run||token!=generation||!active())return;
   if(!valid()){abort("invalid_chrome");return;}
   float f=(Float)a.getAnimatedValue();
   slot.icon.setAlpha(iconFrom+(end-iconFrom)*f);
   if(pinned)slot.pin.setAlpha(pinFrom+(end-pinFrom)*f);
   host.stage.invalidate();
  });
  run.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(chrome!=run||token!=generation||!active())return;
   chrome=null;
   if(!valid()){abort("invalid_chrome_end");return;}
   if(finished!=null)finished.run();
  }});
  run.start();
 }
 void abort(String reason){
  dismissMenu();if(!active())return;
  Log.i("WindowDeck","side_sort_abort generation="+generation+" reason="+reason);order.abort();cleanup(true);
 }
 private void cancelPress(){
  if(menuDelay!=null){host.handler.removeCallbacks(menuDelay);menuDelay=null;}
  if(press!=null){ValueAnimator old=press;press=null;old.cancel();}
 }
 private void cleanup(boolean abort){
  generation++;cancelPress();cancelChrome();
  if(abort&&previewLayout)for(WorkbenchSlot slot:bases.keySet())if(!slot.released&&slot.card!=null)setLayoutBox(slot,bases.get(slot).box);if(timeout!=null){host.handler.removeCallbacks(timeout);timeout=null;}
  if(settle!=null){ValueAnimator old=settle;settle=null;old.cancel();}
  if(frame!=null&&host.stage.getViewTreeObserver().isAlive())host.stage.getViewTreeObserver().removeOnPreDrawListener(frame);frame=null;
  for(WorkbenchSlot slot:bases.keySet()){
   slot.motionPose=null;if(slot.card!=null){host.surfaces.resetCardTransform(slot);slot.card.resetGesture();}
   if(slot.icon!=null)slot.icon.setAlpha(1);if(slot.pin!=null)slot.pin.setAlpha(1);
  }
  selected=null;phase=Phase.IDLE;gesture=null;
  if(!host.closing)for(int i=0;i<host.session.slots.size();i++){
   WorkbenchSlot slot=host.session.slots.get(i);if(!slot.released&&slot.card!=null&&slot.surface!=null)host.surfaces.inputRole(slot,i==host.session.primary);
  }
  order=null;bases.clear();shown.clear();tasks.clear();bounds.clear();starts.clear();moveStarts.clear();moveCurves.clear();host.surfaces.raiseMain();
  // Abort restores fits at the original layout; callers changing geometry may lay out again.
  if(abort&&!host.closing&&!host.stopped&&!host.backgrounded)for(WorkbenchSlot slot:host.session.slots)
   if(!slot.released&&slot.surface!=null)host.surfaces.scheduleLeashFit(slot,slot.surface.getWidth(),slot.surface.getHeight());
 }
 private void cancelMenuAnimation(){
  if(menuAnimation!=null){AnimatorSet old=menuAnimation;menuAnimation=null;old.cancel();}
 }
 void dismissMenu(){
  menuPolicy.dismiss();cancelMenuAnimation();
  if(popup!=null){PopupWindow old=popup;popup=null;old.dismiss();}
 }
 private void showMenu(WorkbenchSlot s,int task){
  dismissMenu();
  LinearLayout content=new LinearLayout(host);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(12,12,12,12);content.setBackground(Ui.bg(0xff252833,Ui.dp(host,14)));
  String[] labels={"切换为主应用",s.pinned?"取消固定":"固定此应用","替换应用","从工作台移出"};
  final int width=Ui.dp(host,200);PopupWindow window=new PopupWindow(content,width,-2,false);popup=window;
  final long token=menuPolicy.open();
  // C17 keeps the held pointer in the card window; visibility and actions have separate gates.
  window.setAnimationStyle(0);window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
  window.setOutsideTouchable(true);window.setFocusable(false);window.setTouchable(true);window.setTouchModal(false);window.setElevation(Ui.dp(host,12));
  window.setIsLaidOutInScreen(true);window.setIsClippedToScreen(true);
  window.setOnDismissListener(()->{if(popup==window){popup=null;menuPolicy.dismiss();cancelMenuAnimation();}});
  for(int i=0;i<labels.length;i++){
   final int action=i;Button button=new Button(host);button.setText(labels[i]);content.addView(button);
   button.setOnClickListener(v->{
    if(popup!=window||host.closing||host.backgrounded||s.released||s.taskId!=task||!host.session.slots.contains(s))return;
    boolean busy=active()||host.menu.recovering||host.switching()||host.layout.addAnimation!=null||host.identity.taskRotationRun!=null||host.replacements.current()!=null;
    if(!menuPolicy.takeAction(token,busy)){Log.i("WindowDeck","side_sort_menu result=ignored slot="+s.id+" busy="+busy);return;}
    // A menu item must never cancel a held sort or its recovery to make itself executable.
    dismissMenu();
    if(action==0)host.promote(host.session.slots.indexOf(s));else if(action==1)host.menu.togglePin(s);else if(action==2)host.chooseApp(s);else host.menu.removeSlot(s);
   });
  }
  int index=host.session.slots.indexOf(s),sidePosition=index<host.session.primary?index+1:index;
  boolean leftOpening=mode==PaneLayout.TOP_BOTTOM&&sidePosition==4;
  CardSpace current=host.stage.cardSpace();
  int[] position=SideSortMenuPolicy.position(current.x+s.card.getLeft(),current.y+s.card.getTop(),current.x+s.card.getRight(),
    s.card.getPaddingLeft(),s.card.getPaddingTop(),menuDownX,menuDownY,width,Ui.dp(host,20),leftOpening);
  content.setPivotX(leftOpening?width:0);content.setPivotY(0);content.setScaleX(.3f);content.setScaleY(.3f);content.setAlpha(0);
  window.showAtLocation(host.stage,Gravity.TOP|Gravity.LEFT,position[0],position[1]);
  Log.i("WindowDeck","side_sort_menu result=opening slot="+s.id+" task="+task);
  ObjectAnimator growX=ObjectAnimator.ofFloat(content,"scaleX",.3f,1.02f),growY=ObjectAnimator.ofFloat(content,"scaleY",.3f,1.02f);
  android.view.animation.PathInterpolator growCurve=new android.view.animation.PathInterpolator(.37f,.75f,.46f,1);
  growX.setInterpolator(growCurve);growY.setInterpolator(growCurve);
  ObjectAnimator alpha=ObjectAnimator.ofFloat(content,"alpha",0,1);alpha.setInterpolator(new android.view.animation.PathInterpolator(.33f,0,.67f,1));
  AnimatorSet grow=new AnimatorSet();grow.playTogether(growX,growY,alpha);grow.setDuration(250);
  ObjectAnimator restX=ObjectAnimator.ofFloat(content,"scaleX",1.02f,1),restY=ObjectAnimator.ofFloat(content,"scaleY",1.02f,1);
  android.view.animation.PathInterpolator restCurve=new android.view.animation.PathInterpolator(.3f,0,.5f,1);
  restX.setInterpolator(restCurve);restY.setInterpolator(restCurve);
  AnimatorSet rest=new AnimatorSet();rest.playTogether(restX,restY);rest.setDuration(250);
  AnimatorSet run=new AnimatorSet();menuAnimation=run;run.playSequentially(grow,rest);
  run.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(menuAnimation!=run||popup!=window)return;menuAnimation=null;menuPolicy.ready(token);Log.i("WindowDeck","side_sort_menu result=ready slot="+s.id+" task="+task);
  }});run.start();
 }
}
