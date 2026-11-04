package io.github.xitc.windowdeck;

/** Pure gesture/geometry policy. See docs/specs/右下角斜滑入口.md and the dex audit. */
final class CornerGesture {
 static final long ENTER_MS=500;
 final int width,height,limit;
 final float density,downX,downY,slop;
 int left,top,right,bottom;
 boolean claimed,rejected,cancelled;
 private float lastY,minimizeY=-1,compensation;
 CornerGesture(int width,int height,float density,float x,float y,float slop){
  this.width=width;this.height=height;this.density=density;this.limit=Math.round(65*density);
  this.downX=(int)x;this.downY=(int)y;this.lastY=(int)y;this.slop=slop;right=width;bottom=height;
 }
 static boolean hot(int width,int height,float density,boolean navigationBar,float x,float y){
  if(width<=0||height<=0||!(density>0)||!Float.isFinite(x)||!Float.isFinite(y))return false;
  int size=Math.round(48*density),inset=navigationBar?Math.round(12*density):0;
  return x>=width-size+inset&&x<width&&y>=height-size&&y<height+1;
 }
 static boolean angle(float dx,float dy){
  if(!Float.isFinite(dx)||!Float.isFinite(dy)||dx>=0||dy>=0)return false;
  int angle=(int)Math.abs(Math.toDegrees(Math.atan2(dy,dx)));
  return angle>110&&angle<160;
 }
 void move(float x,float y){
  if(cancelled||rejected)return;
  if(!Float.isFinite(x)||!Float.isFinite(y)){cancel();return;}
  x=(int)x;y=(int)y;
  if(!claimed){
   if(Math.hypot(x-downX,y-downY)<slop)return;
   if(!angle(x-downX,y-downY)){rejected=true;return;}
   claimed=true;
  }
  float delta=damp(y-lastY,y);lastY=y;
  int rounded=(int)(delta>0?delta+.5f:delta-.5f);
  if(bottom-top+2f*rounded<=0){cancel();return;}
  int half=((int)(((bottom-top+2f*rounded)*((float)width/height))+.5f))/2;
  int center=(left+right)/2;left=center-half;right=center+half;top-=rounded;bottom+=rounded;
 }
 boolean ready(){return claimed&&!cancelled&&!rejected&&bottom-top<=height-2*limit;}
 void cancel(){cancelled=true;}
 float scale(){return (float)(bottom-top)/height;}
 float radius(){return 18*density*Math.max(.2f,Math.min(1f,((float)(height-bottom+top)/limit)*2));}
 private float damp(float delta,float y){
  int boundary=height-limit,range=(int)(boundary-height*.8f);
  if(delta<0){
   if(bottom<=boundary){
    float value=dampValue(delta,bottom-boundary,range,false);
    if(Math.abs(bottom-boundary)>=Math.abs(range)&&minimizeY<0)minimizeY=y;
    return value;
   }
   if(bottom>height&&y>height)return 0;
  }else if(delta>0){
   if(bottom>=height)return 0;
   if(bottom<boundary&&y<boundary){
    if(minimizeY>0&&Math.abs(bottom-boundary)<Math.abs(range))minimizeY=-1;
    return dampValue(delta,bottom-boundary,range,minimizeY>0&&y>minimizeY);
   }
  }
  return delta;
 }
 private float dampValue(float delta,float offset,float range,boolean force){
  if(Math.abs(range)<1e-6)return delta;
  float ratio=Math.abs(offset/range);
  float value=ratio<1||force?delta/(float)(Math.pow(ratio,1)*2.5+(float)Math.pow(ratio+1,1.2000000476837158)):0;
  if(Math.abs(value)>.5f){compensation=0;return value;}
  compensation+=value;
  if(Math.abs(compensation)<=.5f)return value;
  value=Math.min(compensation,1);compensation=0;return value;
 }
 void rebound(int startBottom,long elapsedMs){
  float value=startBottom+(height-startBottom)*reboundFraction(elapsedMs);
  // Native update first truncates the spring value, then dampRebound rounds (value + .5).
  int raw=(int)value;bottom=Math.round(raw+.5f);top=height-bottom;
  int w=(int)(((bottom-top)*((float)width/height))+.5f),center=(left+right)/2;
  left=center-w/2;right=left+w;
  if(raw>=height||reboundDone(startBottom,elapsedMs)){left=top=0;right=width;bottom=height;}
 }
 static float reboundFraction(long elapsedMs){
  double omega=(float)(2*Math.PI/Math.log(2))/.4f,t=Math.max(0,elapsedMs)/1000d;
  return (float)(1-(1+omega*t)*Math.exp(-omega*t));
 }
 boolean reboundDone(int startBottom,long elapsedMs){
  double omega=(float)(2*Math.PI/Math.log(2))/.4f,t=Math.max(0,elapsedMs)/1000d,distance=height-startBottom;
  return Math.abs(distance*(1+omega*t)*Math.exp(-omega*t))<.75&&Math.abs(distance*omega*omega*t*Math.exp(-omega*t))<46.875;
 }
 static float enterFraction(float fraction){
  if(fraction<=0)return 0;if(fraction>=1)return 1;
  // SpringInterpolator estimates duration as 9.232803 / naturalFrequency.
  double t=9.232803f*fraction;
  return (float)(1-(1+t)*Math.exp(-t));
 }
}
