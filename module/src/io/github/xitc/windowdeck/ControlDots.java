package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Filled dots only. The phone pill stays hidden, and the window size is the scaled hit box. */
final class ControlDots extends View {
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 private boolean lightContent=true;
 private final Drawable darkDots,lightDots;
 ControlDots(Context c){
  super(c);
  darkDots=MenuChrome.drawable(c,"canvas_control_bar_icon_plus");
  lightDots=MenuChrome.drawable(c,"canvas_control_bar_icon_plus_light");
  setForceDarkAllowed(false);
  setBackgroundColor(0);
  setClickable(true);setFocusable(true);
  setContentDescription("主应用菜单");
 }
 void setLightContent(boolean lightContent){
  if(this.lightContent==lightContent)return;
  this.lightContent=lightContent;invalidate();
 }
 @Override protected void onDraw(Canvas canvas){
  int w=getWidth(),h=getHeight();
  if(w<=0||h<=0)return;
  Drawable icon=lightContent?darkDots:lightDots;
  if(icon!=null){icon.setBounds(0,0,w,h);icon.draw(canvas);return;}
  paint.setColor(lightContent?ControlBarMetrics.DOT_ON_LIGHT:ControlBarMetrics.DOT_ON_DARK);
  float[] dots=ControlBarMetrics.dots(w,h);
  for(int i=0;i<3;i++)canvas.drawCircle(dots[i*3],dots[i*3+1],dots[i*3+2],paint);
 }
}
