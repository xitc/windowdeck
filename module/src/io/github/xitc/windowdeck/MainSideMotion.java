package io.github.xitc.windowdeck;

/** Vivo type 8/11 ordinary exchange. Other scenes keep their own timing and crops. */
final class MainSideMotion {
 static final long DURATION_MS=500;
 static final float RETIRE_HEIGHT_RATIO=1.05f;
 static float retirementRatio(CardMotion.Pose pose,CardMotion.Pose target){
  return (pose.quad[7]-pose.quad[1])/(target.quad[7]-target.quad[1]);
 }
 static float retirementAlpha(float ratio){
  return Math.max(0,Math.min(1,(RETIRE_HEIGHT_RATIO-ratio)/(RETIRE_HEIGHT_RATIO-1f)));
 }
 /** One forward run of SpringInterpolator(0,1,.5,0,TYPE_DURATION).
  * A cancelled exchange creates a new run from the displayed source pose. */
 static final class Curve {
  private static final float FREQUENCY=(float)(2*Math.PI/Math.log(2))/.5f;
  private static final float ESTIMATED_MS=9.232803f/FREQUENCY*1000f;
  private static final float THRESHOLD=1e-4f*.75f;
  private float last,value,velocity;
  private boolean initialized;
  float sample(float fraction){
   if(fraction<=0||fraction>=1){initialized=false;last=0;value=fraction<=0?0:1;velocity=0;return value;}
   if(!initialized){initialized=true;last=fraction;value=velocity=0;}
   long millis=(long)(Math.abs(fraction-last)*ESTIMATED_MS);last=fraction;
   if(Math.abs(velocity)<THRESHOLD*62.5&&Math.abs(value-1)<THRESHOLD){value=1;velocity=0;return value;}
   double t=millis/1000d,x=(double)value-1,b=(double)velocity+FREQUENCY*x;
   double delta=x+b*t,decay=Math.pow(Math.E,-FREQUENCY*t);
   value=(float)(delta*decay+1);velocity=(float)(delta*decay*(-FREQUENCY)+b*decay);
   return value;
  }
 }
 static float[] point(float x,float y,float endX,float endY,float f){
  if(x==endX)return new float[]{x,(int)(y+.5f+(endY-y)*f)};
  float nextX=x+(endX-x)*f;
  float nextY=y+((endY-y)/(endX-x))*(nextX-x);
  return new float[]{(int)(nextX+.5f),(int)(nextY+.5f)};
 }
 static CardMotion.Pose between(CardMotion.Pose from,CardMotion.Pose to,float fraction){
  return between(from,to,fraction,0,0);
 }
 static CardMotion.Pose between(CardMotion.Pose from,CardMotion.Pose to,float fraction,int originX,int originY){
  return between(from,to,fraction,originX,originY,false);
 }
 static CardMotion.Pose betweenY(CardMotion.Pose from,CardMotion.Pose to,float fraction,int originX,int originY){
  return between(from,to,fraction,originX,originY,true);
 }
 private static CardMotion.Pose between(CardMotion.Pose from,CardMotion.Pose to,float fraction,int originX,int originY,boolean type20){
  CardMotion.Pose mixed=CardMotion.between(from,to,fraction);
  if(fraction<=0||fraction>=1)return mixed;
  float[] corners=new float[8];
  // Vivo's PointInfo is in display coordinates. Adding .5 then truncating is
  // not invariant under translation across zero, so round before returning local.
  for(int i=0;i<8;i+=2){
   float x=from.quad[i]+originX,y=from.quad[i+1]+originY,endX=to.quad[i]+originX,endY=to.quad[i+1]+originY;
   if(type20&&x==endX){
    corners[i]=x-originX;corners[i+1]=(int)(y+(endY-y)*fraction)-originY;
   }else{
    float[] p=point(x,y,endX,endY,fraction);
    corners[i]=p[0]-originX;corners[i+1]=p[1]-originY;
   }
  }
  // The host also animates card scale. Interpolating task-space radius and
  // multiplying by that changing scale makes a shrinking corner bulge first.
  // Keep the display radius bounded; the renderer converts it once for both
  // the task leash and host aperture, including an interrupted reversal.
  return new CardMotion.Pose(mixed.box,corners,mixed.crop,mixed.radius);
 }
 private MainSideMotion(){}
}
