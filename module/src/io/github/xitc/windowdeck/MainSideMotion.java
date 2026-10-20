package io.github.xitc.windowdeck;

/** Vivo type 8/11 ordinary exchange. Other scenes keep their own timing and crops. */
final class MainSideMotion {
 static final long DURATION_MS=500;
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
  CardMotion.Pose mixed=CardMotion.between(from,to,fraction);
  if(fraction<=0||fraction>=1)return mixed;
  float[] corners=new float[8];
  // Vivo's PointInfo is in display coordinates. Adding .5 then truncating is
  // not invariant under translation across zero, so round before returning local.
  for(int i=0;i<8;i+=2){
   float[] p=point(from.quad[i]+originX,from.quad[i+1]+originY,to.quad[i]+originX,to.quad[i+1]+originY,fraction);
   corners[i]=p[0]-originX;corners[i+1]=p[1]-originY;
  }
  float sourceRadius=CardMotion.sourceRadius(from)+(CardMotion.sourceRadius(to)-CardMotion.sourceRadius(from))*fraction;
  float scale=Math.min(mixed.box[2]/mixed.crop[2],mixed.box[3]/mixed.crop[3]);
  return new CardMotion.Pose(mixed.box,corners,mixed.crop,sourceRadius*scale);
 }
 private MainSideMotion(){}
}
