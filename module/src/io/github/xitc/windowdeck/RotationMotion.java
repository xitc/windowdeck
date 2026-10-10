package io.github.xitc.windowdeck;

/** Card-local rotation geometry. The ROM curve is sampled separately when present.
  *  Official screen rotation does not shrink the entering frame. The exit layer
  *  rotates the other way, scales to 1.25, and fades over the first 200/450 of
  *  the resource timeline. Alpha is a function of that fraction, not wall time. */
final class RotationMotion {
 static final int DURATION_MS=450, ALPHA_MS=200, TIMEOUT_MS=1800;
 static final float EXIT_SCALE_END=1.25f;
 static float enterDegrees(int turn,float fraction){
  if(turn!=1&&turn!=-1)throw new IllegalArgumentException("quarter turn required");
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("finite progress required");
  return 90*turn*(Math.max(0,Math.min(1,fraction))-1);
 }
 static float exitDegrees(int turn,float fraction){
  if(turn!=1&&turn!=-1)throw new IllegalArgumentException("quarter turn required");
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("finite progress required");
  return 90*turn*Math.max(0,Math.min(1,fraction));
 }
 /** AccelerateDecelerate over the full 450ms, matching a scale child with no interpolator. */
 static float exitScale(float fraction){
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("finite progress required");
  float f=Math.max(0,Math.min(1,fraction));
  float eased=(float)(Math.cos((f+1)*Math.PI)/2.0)+0.5f;
  return 1f+(EXIT_SCALE_END-1f)*eased;
 }
 /** Path(0.33,0,0.67,1) over the first 200/450. At and after that fraction the exit is gone. */
 static float exitAlpha(float fraction){
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("finite progress required");
  float f=Math.max(0,Math.min(1,fraction));
  float window=ALPHA_MS/(float)DURATION_MS;
  if(f>=window)return 0f;
  return 1f-bezier(solveT(f/window,.33f,.67f),0f,1f);
 }
 /** Path(0.33,0,0.1,1), the official rotate interpolator. */
 static float rotateProgress(float fraction){
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("finite progress required");
  float f=Math.max(0,Math.min(1,fraction));
  return bezier(solveT(f,.33f,.1f),0f,1f);
 }
 static float[] rotate(float[] quad,float cx,float cy,float degrees){
  double a=Math.toRadians(degrees);float c=(float)Math.cos(a),s=(float)Math.sin(a);
  float[] out=quad.clone();
  for(int i=0;i<out.length;i+=2){float x=quad[i]-cx,y=quad[i+1]-cy;out[i]=cx+x*c-y*s;out[i+1]=cy+x*s+y*c;}
  return out;
 }
 static float[] scaleAbout(float[] quad,float cx,float cy,float scale){
  float[] out=new float[quad.length];
  for(int i=0;i<quad.length;i+=2){out[i]=cx+(quad[i]-cx)*scale;out[i+1]=cy+(quad[i+1]-cy)*scale;}
  return out;
 }
 /** Fallback enter: rotate in buffer space, then the same quad map as the presentation fit. */
 static float[] enterFrame(float[] source,float pivotX,float pivotY,int turn,float fraction,float[] destination){
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("finite progress required");
  float f=Math.max(0,Math.min(1,fraction));
  return mapThrough(source,destination,rotate(source,pivotX,pivotY,enterDegrees(turn,rotateProgress(f))));
 }
 /** Fallback exit: rotate and scale the frozen presentation about the plate center. Fraction 0 is that quad. */
 static float[] exitFrame(float[] presentation,float pivotX,float pivotY,int turn,float fraction){
  if(presentation==null||presentation.length!=8)throw new IllegalArgumentException("quad required");
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("finite progress required");
  float f=Math.max(0,Math.min(1,fraction));
  return scaleAbout(rotate(presentation,pivotX,pivotY,exitDegrees(turn,rotateProgress(f))),pivotX,pivotY,exitScale(f));
 }
 /** Scale about the plate center so this frame's corners stay inside that plate.
  *  Official screen rotation does not call this. It remains for the geometry check. */
 static float[] contain(float[] quad,float plateW,float plateH){
  if(quad==null||quad.length!=8)throw new IllegalArgumentException("quad required");
  if(!(plateW>0)||!(plateH>0)||!Float.isFinite(plateW)||!Float.isFinite(plateH))throw new IllegalArgumentException("plate required");
  float cx=plateW/2f,cy=plateH/2f,scale=1f;
  for(int i=0;i<8;i+=2){
   float x=quad[i],y=quad[i+1];
   if(!Float.isFinite(x)||!Float.isFinite(y))throw new IllegalArgumentException("finite quad required");
   float dx=Math.abs(x-cx),dy=Math.abs(y-cy);
   if(dx>0)scale=Math.min(scale,(plateW/2f)/dx);
   if(dy>0)scale=Math.min(scale,(plateH/2f)/dy);
  }
  if(!(scale>0)||!Float.isFinite(scale))throw new IllegalArgumentException("invalid contain scale");
  float[] out=new float[8];
  for(int i=0;i<8;i+=2){out[i]=cx+(quad[i]-cx)*scale;out[i+1]=cy+(quad[i+1]-cy)*scale;}
  return out;
 }
 private static float[] mapThrough(float[] source,float[] destination,float[] points){
  float[] h=homography(source,destination),out=new float[points.length];
  for(int i=0;i<points.length;i+=2){
   float x=points[i],y=points[i+1],w=h[6]*x+h[7]*y+h[8];
   if(w==0||!Float.isFinite(w))throw new IllegalArgumentException("degenerate quad");
   out[i]=(h[0]*x+h[1]*y+h[2])/w;out[i+1]=(h[3]*x+h[4]*y+h[5])/w;
  }
  return out;
 }
 static float[] homography(float[] source,float[] destination){
  if(source==null||destination==null||source.length!=8||destination.length!=8)throw new IllegalArgumentException("quad required");
  double[][] a=new double[8][9];
  for(int i=0;i<4;i++){
   double x=source[2*i],y=source[2*i+1],u=destination[2*i],v=destination[2*i+1];
   if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(u)||!Double.isFinite(v))throw new IllegalArgumentException("finite quad required");
   int r=2*i;
   a[r][0]=x;a[r][1]=y;a[r][2]=1;a[r][6]=-u*x;a[r][7]=-u*y;a[r][8]=u;
   a[r+1][3]=x;a[r+1][4]=y;a[r+1][5]=1;a[r+1][6]=-v*x;a[r+1][7]=-v*y;a[r+1][8]=v;
  }
  for(int col=0;col<8;col++){
   int pivot=col;
   for(int r=col+1;r<8;r++)if(Math.abs(a[r][col])>Math.abs(a[pivot][col]))pivot=r;
   if(Math.abs(a[pivot][col])<1e-8)throw new IllegalArgumentException("degenerate quad");
   double[] swap=a[col];a[col]=a[pivot];a[pivot]=swap;
   double div=a[col][col];
   for(int c=col;c<9;c++)a[col][c]/=div;
   for(int r=0;r<8;r++)if(r!=col){double f=a[r][col];if(f!=0)for(int c=col;c<9;c++)a[r][c]-=f*a[col][c];}
  }
  float[] h=new float[9];
  for(int i=0;i<8;i++)h[i]=(float)a[i][8];
  h[8]=1f;
  return h;
 }
 private static float solveT(float x,float x1,float x2){
  float lo=0,hi=1;
  for(int i=0;i<24;i++){float mid=(lo+hi)/2f;if(bezier(mid,x1,x2)<x)lo=mid;else hi=mid;}
  return (lo+hi)/2f;
 }
 private static float bezier(float t,float c1,float c2){
  float u=1f-t;return 3*u*u*t*c1+3*u*t*t*c2+t*t*t;
 }
 private RotationMotion(){}
}
