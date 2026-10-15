package io.github.xitc.windowdeck;

/** vivo layout flip: outgoing old rail and incoming new rail share a 500 ms clock. */
final class LayoutSwitchMotion {
 static final long DURATION_MS=500, BLUR_MS=400;
 static final int BLUR_RADIUS=100;
 static float progress(float time){return SideEntranceMotion.spring(time);}
 static float offset(float extent,float progress,boolean entering){
  return -Math.max(1,extent)*(entering?1-progress:progress);
 }
 static CardMotion.Pose incoming(CardMotion.Pose target,int mode,float extent,float progress){
  float[] box=target.box.clone();box[mode==PaneLayout.TOP_BOTTOM?1:0]+=offset(extent,progress,true);
  return CardMotion.rebox(target,box);
 }
 /** Include projected corners, not just the View rectangle, when hiding an old rail. */
 static float extent(CardMotion.Pose[] poses,int main,int mode,int[] add){
  int axis=mode==PaneLayout.TOP_BOTTOM?1:0;float extent=1;
  for(int i=0;i<poses.length;i++)if(i!=main){
   for(int j=axis;j<8;j+=2)extent=Math.max(extent,poses[i].quad[j]);
   extent=Math.max(extent,poses[i].box[axis]+poses[i].box[axis+2]);
  }
  if(add!=null)extent=Math.max(extent,add[axis]+add[axis+2]);
  return extent+1;
 }
 /** ValueAnimator's default accelerate/decelerate curve, sampled on elapsed time. */
 static int blurRadius(float time){
  float t=Math.max(0,Math.min(1,time*DURATION_MS/BLUR_MS));
  return Math.round(BLUR_RADIUS*(float)(0.5+0.5*Math.cos(Math.PI*t)));
 }
 private LayoutSwitchMotion(){}
}
