package io.github.xitc.windowdeck;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** 52×28 dp 轨道、20 dp 滑块的设置开关，尺寸取 C17 的 sud_switch_* 资源。 */
final class CouiSwitch extends View {
 private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final Paint thumb=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final RectF rect=new RectF();
 private boolean checked;
 private float progress;
 private ValueAnimator animator;
 CouiSwitch(Context c){
  super(c);
  thumb.setColor(0xffffffff);thumb.setStyle(Paint.Style.FILL);
  setForceDarkAllowed(false);setClickable(false);setFocusable(false);
 }
 void setChecked(boolean value,boolean animate){
  if(checked==value&&progress==(value?1f:0f))return;
  checked=value;
  if(animator!=null){animator.cancel();animator=null;}
  if(!animate){progress=value?1f:0f;invalidate();return;}
  animator=ValueAnimator.ofFloat(progress,value?1f:0f);
  animator.setDuration(120);
  animator.addUpdateListener(a->{progress=(float)a.getAnimatedValue();invalidate();});
  animator.start();
 }
 boolean isChecked(){return checked;}
 @Override protected void onMeasure(int widthSpec,int heightSpec){
  setMeasuredDimension(Ui.dp(getContext(),52),Ui.dp(getContext(),28));
 }
 @Override protected void onDraw(Canvas canvas){
  int w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
  float radius=h/2f;
  track.setColor(blend(SettingsTheme.trackOff(getContext()),SettingsTheme.accent(getContext()),progress));
  rect.set(0,0,w,h);canvas.drawRoundRect(rect,radius,radius,track);
  float thumbRadius=Ui.dp(getContext(),10);
  float inset=h/2f-thumbRadius;
  float cx=inset+thumbRadius+(w-2*inset-2*thumbRadius)*progress;
  canvas.drawCircle(cx,h/2f,thumbRadius,thumb);
 }
 private static int blend(int from,int to,float t){
  int a=(int)(((from>>>24)&0xff)+(((to>>>24)&0xff)-((from>>>24)&0xff))*t);
  int r=(int)(((from>>16)&0xff)+(((to>>16)&0xff)-((from>>16)&0xff))*t);
  int g=(int)(((from>>8)&0xff)+(((to>>8)&0xff)-((from>>8)&0xff))*t);
  int b=(int)((from&0xff)+((to&0xff)-(from&0xff))*t);
  return (a<<24)|(r<<16)|(g<<8)|b;
 }
}
