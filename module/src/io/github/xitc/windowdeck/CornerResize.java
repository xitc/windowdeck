package io.github.xitc.windowdeck;

/** Main-window corner gesture geometry, independent of Android event dispatch. */
final class CornerResize {
 static boolean starts(float dx,float dy,float density){
  if(!Float.isFinite(dx)||!Float.isFinite(dy)||Math.hypot(dx,dy)<=2*density)return false;
  int angle=(int)Math.toDegrees(Math.atan2(dy,dx));
  return Math.abs(angle)>=30&&Math.abs(angle)<=150||angle>=-30&&angle<=90;
 }
 static float scale(float width,float height,float dx,float dy,float density){
  if(width<=0||height<=0||density<=0||!Float.isFinite(dx)||!Float.isFinite(dy))return 1;
  float delta=Math.abs(dx)>=Math.abs(dy)?dx:dy;
  float extent=Math.abs(dx)>=Math.abs(dy)?width:height;
  // Preserve the centre and ratio; progressively resist past the native 12dp edge threshold.
  float edge=delta<=0?delta/(1+Math.abs(delta)/(extent*.075f)):Math.min(delta,12*density)+Math.max(0,delta-12*density)*.2f;
  return Math.max(.85f,1+2*edge/extent);
 }
 static boolean ready(float width,float height,float scale,float density){
  return (width*scale+2*density>=width+24*density)||(height*scale+2*density>=height+24*density);
 }
 static CardMotion.Pose pose(CardMotion.Pose source,float scale){
  float[] b=source.box;float w=b[2]*scale,h=b[3]*scale;
  return CardMotion.rebox(source,new float[]{b[0]+(b[2]-w)/2,b[1]+(b[3]-h)/2,w,h});
 }
 static final class HandlePlacement {
  final int x,y,size;
  final float glyphX,glyphY;
  HandlePlacement(float right,float bottom,float density,int windowWidth,int windowHeight){
   size=Math.round(48*density);
   x=Math.max(0,Math.min(Math.round(right-27*density),windowWidth-size));
   y=Math.max(0,Math.min(Math.round(bottom-27*density),windowHeight-size));
   // Clamp only the touch panel. The asset remains at the native corner anchor.
   glyphX=right-16*density-x;glyphY=bottom-16*density-y;
  }
 }
 private CornerResize(){}
}
