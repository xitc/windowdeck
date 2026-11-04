package io.github.xitc.windowdeck;

import android.animation.*;
import android.graphics.*;
import android.os.IBinder;
import android.util.Log;
import android.view.*;

/** The corner panel stays above the embedded task's input hole, like the caption. */
final class CornerResizeController {
 final WorkbenchActivity host;
 Handle handle;
 boolean attached,tracking,moving,ready,paused=true;
 int pointer,task;
 float downX,downY;
 WorkbenchSlot main;
 CardMotion.Pose source,shown;
 CardSpace space;
 ValueAnimator rebound;
 ViewTreeObserver.OnPreDrawListener frame;
 int failures;
 final java.util.LinkedHashMap<ViewGroup,boolean[]> clips=new java.util.LinkedHashMap<>();
 CornerResizeController(WorkbenchActivity host){this.host=host;}
 boolean active(){return main!=null;}
 boolean available(){return !paused&&host.initialized&&!host.closing&&!host.stopped&&!host.backgrounded&&host.back.backReady()
   &&!host.sideSort.active()&&!host.hang.hanging&&!host.arrival.screenArrival&&!host.portrait.deferPortrait
   &&!host.handoff.handoffPresentation&&host.pendingEntrance()==null&&host.layout.addAnimation==null
   &&host.layout.layoutPreparation==null&&!host.layout.switchPreparation.pending()&&host.replacements.current()==null
   &&host.entranceCover()==null&&!host.layoutPosted&&host.stage!=null&&host.stage.isShown()&&host.stage.isHardwareAccelerated();}
 void layout(){
  if(active())return;
  if(!available()||host.layout.layoutSwitchVisuals!=null){detach();return;}
  WorkbenchSlot slot=host.session.slots.get(host.session.primary);
  if(slot.released||slot.taskId<0||!slot.embedded||slot.card==null){detach();return;}
  CardMotion.Pose pose=host.surfaces.currentPose(slot);if(pose==null){detach();return;}
  if(handle==null)handle=new Handle();
  float d=host.getResources().getDisplayMetrics().density;
  int size=Math.round(48*d);int[] origin=new int[2];host.stage.getLocationInWindow(origin);
  Rect windowBounds=host.windowManager().getCurrentWindowMetrics().getBounds();
  CornerResize.HandlePlacement placement=new CornerResize.HandlePlacement(origin[0]+pose.box[0]+pose.box[2],
    origin[1]+pose.box[1]+pose.box[3],d,windowBounds.width(),windowBounds.height());
  int x=placement.x,y=placement.y;
  if(handle.glyphX!=placement.glyphX||handle.glyphY!=placement.glyphY){
   handle.glyphX=placement.glyphX;handle.glyphY=placement.glyphY;handle.invalidate();
  }
  try{
   if(!attached){
    IBinder token=host.getWindow().getDecorView().getWindowToken();if(token==null)return;
    WindowManager.LayoutParams lp=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
    lp.token=token;lp.gravity=Gravity.TOP|Gravity.LEFT;lp.setTitle("WindowDeckCornerResize");lp.x=x;lp.y=y;
    host.windowManager().addView(handle,lp);attached=true;
   }else{
    WindowManager.LayoutParams lp=(WindowManager.LayoutParams)handle.getLayoutParams();
    if(lp.x!=x||lp.y!=y||lp.width!=size){lp.x=x;lp.y=y;lp.width=lp.height=size;host.windowManager().updateViewLayout(handle,lp);}
   }
  }catch(Throwable e){Log.w("WindowDeck","corner_resize_panel_failed",e);detach();}
 }
 void detach(){if(attached){try{host.windowManager().removeViewImmediate(handle);}catch(Throwable ignored){}attached=false;}}
 boolean valid(){return active()&&!host.closing&&!host.stopped&&!host.backgrounded&&host.identity.taskRotationRun==null
   &&host.session.slots.contains(main)&&host.session.slots.get(host.session.primary)==main&&!main.released&&main.taskId==task
   &&space.same(host.stage.cardSpace())&&main.motionPose==shown&&!host.layout.switching()&&!host.menu.recovering
   &&!host.sideSort.active()&&host.menu.modalWindows==0&&!host.arrival.screenArrival&&!host.handoff.handoffPresentation
   &&host.pendingEntrance()==null&&host.replacements.current()==null&&!host.layout.switchPreparation.pending();}
 boolean begin(MotionEvent e){
  if(active()||!available())return false;
  for(WorkbenchSlot slot:host.session.slots)if(slot.released||slot.card==null||slot.surface==null||!slot.embedded||slot.taskId<0)return false;
  main=host.session.slots.get(host.session.primary);source=host.surfaces.currentPose(main);
  if(source==null){main=null;return false;}
  space=host.stage.cardSpace();task=main.taskId;shown=source;pointer=e.getPointerId(0);downX=e.getRawX();downY=e.getRawY();
  tracking=true;moving=ready=false;failures=0;main.motionPose=source;
  for(WorkbenchSlot slot:host.session.slots){slot.card.preview=true;slot.card.gesturesEnabled=false;try{host.rom.interceptInput.setBoolean(slot.surface,true);}catch(Throwable error){Log.w("WindowDeck","corner_resize_input_failed",error);finish(false);return false;}}
  for(ViewParent p=host.stage;p instanceof ViewGroup;p=p.getParent()){ViewGroup group=(ViewGroup)p;clips.put(group,new boolean[]{group.getClipChildren(),group.getClipToPadding()});group.setClipChildren(false);group.setClipToPadding(false);}
  host.addCard.setEnabled(false);host.more.setVisibility(View.INVISIBLE);
  frame=()->{drawFrame();return true;};host.stage.getViewTreeObserver().addOnPreDrawListener(frame);
  return true;
 }
 void drawFrame(){
  if(!active())return;
  if(!valid()){abort();return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   if(!host.surfaces.writeLeashFit(main,fit)){if(++failures>=8)abort();else host.stage.invalidate();return;}
   failures=0;host.surfaces.submitMotionFit(fit);
  }catch(Throwable e){Log.w("WindowDeck","corner_resize_fit_failed",e);abort();}
 }
 void render(CardMotion.Pose pose){shown=pose;host.surfaces.poseCard(main,pose,source);host.stage.invalidate();}
 void release(boolean cancel){
  if(!active()||!tracking)return;tracking=false;
  if(!valid()){abort();return;}
  if(moving&&ready&&!cancel){finish(true);host.exitToFullscreen();return;}
  if(!moving){finish(false);return;}
  CardMotion.Pose from=shown;
  rebound=ValueAnimator.ofFloat(0,1);rebound.setDuration(260);rebound.setInterpolator(t->1-(float)Math.pow(1-t,3));
  rebound.addUpdateListener(a->{if(valid())render(CardMotion.between(from,source,(Float)a.getAnimatedValue()));else abort();});
  rebound.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){if(rebound==a){rebound=null;finish(false);}}});
  rebound.start();
 }
 void abort(){if(!active())return;finish(false);}
 void finish(boolean preserve){
  WorkbenchSlot slot=main;if(slot==null)return;
  main=null;tracking=moving=ready=false;
  if(rebound!=null){ValueAnimator old=rebound;rebound=null;old.cancel();}
  if(frame!=null){host.stage.getViewTreeObserver().removeOnPreDrawListener(frame);frame=null;}
  // Do not erase a new controller's pose after a task/layout change.
  if(!preserve&&slot.motionPose==shown){slot.motionPose=null;host.surfaces.resetCardTransform(slot);}
  for(int i=0;i<host.session.slots.size();i++){WorkbenchSlot s=host.session.slots.get(i);if(s.card!=null)s.card.gesturesEnabled=true;if(!s.released&&s.surface!=null)host.inputRole(s,i==host.session.primary);}
  for(java.util.Map.Entry<ViewGroup,boolean[]> entry:clips.entrySet()){entry.getKey().setClipChildren(entry.getValue()[0]);entry.getKey().setClipToPadding(entry.getValue()[1]);}clips.clear();
  host.addCard.setEnabled(true);host.more.setVisibility(View.VISIBLE);host.stage.invalidate();
  if(!preserve&&!host.closing)host.handler.post(()->{layout();host.focusPrimary("corner_resize_end");});
  if(handle!=null)handle.invalidate();
 }
 final class Handle extends View {
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  final Path arc=new Path();
  float glyphX,glyphY;
  Handle(){super(host);setClickable(true);setFocusable(true);setForceDarkAllowed(false);setContentDescription("拖动放大主窗口，松手全屏");}
  @Override protected void onDraw(Canvas c){
   float d=getResources().getDisplayMetrics().density;int save=c.save();c.translate(glyphX,glyphY);c.scale(d,d);
   // Same rounded outer-corner silhouette as vivo's 26dp phone asset.
   arc.reset();arc.moveTo(0,23.606f);arc.cubicTo(0,24.755f,.98f,25.674f,2.125f,25.574f);
   arc.cubicTo(5.454f,25.287f,8.837f,24.678f,11.804f,23.166f);arc.cubicTo(19.331f,19.331f,23.166f,11.804f,23.166f,11.804f);
   arc.cubicTo(24.678f,8.837f,25.287f,5.454f,25.574f,2.125f);arc.cubicTo(25.674f,.98f,24.755f,0,23.606f,0);
   arc.cubicTo(22.544f,0,21.674f,.81f,21.584f,1.867f);arc.cubicTo(21.344f,4.656f,20.863f,7.513f,19.602f,9.988f);
   arc.cubicTo(16.357f,16.357f,9.988f,19.602f,9.988f,19.602f);arc.cubicTo(7.513f,20.863f,4.656f,21.344f,1.867f,21.584f);
   arc.cubicTo(.81f,21.674f,0,22.544f,0,23.606f);arc.close();
   paint.setStyle(Paint.Style.FILL);paint.setColor(ready?0xffffffff:0x4dd4d4d4);if(!moving)c.drawPath(arc,paint);c.restoreToCount(save);
  }
  @Override public boolean performClick(){super.performClick();if(available()&&!active())host.exitToFullscreen();return true;}
  @Override public boolean onTouchEvent(MotionEvent e){
   int action=e.getActionMasked();
   if(action==MotionEvent.ACTION_DOWN)return begin(e);
   if(!active())return true;
   if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL){release(true);return true;}
   if(!tracking)return true;
   if(e.getPointerId(0)!=pointer||!valid()){abort();return true;}
   if(action==MotionEvent.ACTION_MOVE||action==MotionEvent.ACTION_UP){
    float dx=e.getRawX()-downX,dy=e.getRawY()-downY,d=getResources().getDisplayMetrics().density;
    if(!moving&&Math.hypot(dx,dy)>2*d){if(!CornerResize.starts(dx,dy,d)){release(true);return true;}moving=true;}
    if(moving){float scale=CornerResize.scale(source.box[2],source.box[3],dx,dy,d);boolean next=CornerResize.ready(source.box[2],source.box[3],scale,d);
     if(next&&!ready)performHapticFeedback(HapticFeedbackConstants.CONFIRM);ready=next;render(CornerResize.pose(source,scale));invalidate();}
    if(action==MotionEvent.ACTION_UP)release(false);
   }
   return true;
  }
 }
}
