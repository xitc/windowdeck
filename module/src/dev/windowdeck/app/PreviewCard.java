package dev.windowdeck.app;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;
/** Owns each gesture through UP/CANCEL; previews never forward input to a task. */
final class PreviewCard extends FrameLayout {
 boolean preview=true, gesturesEnabled=true, vertical;
 BooleanSupplier beginDrag=()->false;
 Runnable cancelDrag=()->{}, dismiss=()->{};
 private float downX,downY;
 private boolean dragging,suppressed;
 private VelocityTracker velocity;
 private ValueAnimator settle;
 PreviewCard(Context context){super(context);setClickable(true);}
 @Override public boolean onInterceptTouchEvent(MotionEvent event){return preview||super.onInterceptTouchEvent(event);}
 @Override public boolean onTouchEvent(MotionEvent e){
  if(!preview)return super.onTouchEvent(e);
  int action=e.getActionMasked();
  if(action==MotionEvent.ACTION_DOWN){
   if(dragging)cancelDrag.run();resetGesture();downX=e.getRawX();downY=e.getRawY();suppressed=!gesturesEnabled;
   velocity=VelocityTracker.obtain();track(e);
   if(!suppressed)super.onTouchEvent(e);return true;
  }
  if(velocity!=null)track(e);
  if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL){
   cancelLongPress();MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();
   boolean held=dragging;resetGesture();suppressed=true;if(held)cancelDrag.run();return true;
  }
  if(suppressed){if(action==MotionEvent.ACTION_UP)recycleVelocity();return true;}
  float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
  float distance=vertical?-dy:-dx,cross=vertical?dx:dy;
  if(action==MotionEvent.ACTION_MOVE){
   if(!dragging&&Math.hypot(dx,dy)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){
    cancelLongPress();MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);super.onTouchEvent(cancel);cancel.recycle();
    if(distance<=Math.abs(cross)||!beginDrag.getAsBoolean()){suppressed=true;return true;}
    dragging=true;getParent().requestDisallowInterceptTouchEvent(true);
   }
   if(dragging){float shift=-Math.max(0,Math.min(distance,extent()*.85f));if(vertical)setTranslationY(shift);else setTranslationX(shift);setAlpha(1-Math.min(.45f,Math.max(0,distance)/Math.max(1,extent())*.45f));return true;}
  }
  if(action==MotionEvent.ACTION_UP&&dragging){
   velocity.computeCurrentVelocity(1000);float speed=vertical?-velocity.getYVelocity():-velocity.getXVelocity();
   boolean commit=GesturePolicy.dismiss(distance,cross,speed,extent(),getResources().getDisplayMetrics().density);
   recycleVelocity();settle(commit);return true;
  }
  boolean result=super.onTouchEvent(e);
  if(action==MotionEvent.ACTION_UP)recycleVelocity();
  return result;
 }
 private void track(MotionEvent e){
  // View translation changes local coordinates; velocity must use screen coordinates.
  MotionEvent raw=MotionEvent.obtain(e);raw.offsetLocation(e.getRawX()-e.getX(),e.getRawY()-e.getY());velocity.addMovement(raw);raw.recycle();
 }
 private float extent(){return vertical?getHeight():getWidth();}
 private void recycleVelocity(){if(velocity!=null){velocity.recycle();velocity=null;}}
 private void settle(boolean commit){
  float start=vertical?getTranslationY():getTranslationX(),end=commit?-extent():0,alpha=getAlpha();
  settle=ValueAnimator.ofFloat(0,1);settle.setDuration(commit?140:240);ValueAnimator current=settle;
  current.setInterpolator(input->input);
  current.addUpdateListener(a->{float f=GesturePolicy.spring((Float)a.getAnimatedValue());float value=start+(end-start)*f;if(vertical)setTranslationY(value);else setTranslationX(value);setAlpha(alpha+((commit?0:1)-alpha)*f);});
  current.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){if(settle!=a)return;settle=null;dragging=false;if(commit)dismiss.run();else{setTranslationX(0);setTranslationY(0);setAlpha(1);cancelDrag.run();}}});current.start();
 }
 void resetGesture(){
  if(settle!=null){ValueAnimator old=settle;settle=null;old.cancel();}
  recycleVelocity();dragging=false;suppressed=true;
  setTranslationX(0);setTranslationY(0);setAlpha(1);cancelLongPress();setPressed(false);
  MotionEvent cancel=MotionEvent.obtain(0,android.os.SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,0,0,0);super.onTouchEvent(cancel);cancel.recycle();
 }
}
