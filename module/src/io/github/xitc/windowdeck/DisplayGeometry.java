package io.github.xitc.windowdeck;

/** Convert a captured display into the host display's coordinates, independent of task axes. */
final class DisplayGeometry {
 static int turns(int sourceRotation,int targetRotation){return (sourceRotation-targetRotation+4)%4;}
 static float[] point(float x,float y,int width,int height,int turns){
  switch(turns&3){case 1:return new float[]{height-y,x};case 2:return new float[]{width-x,height-y};case 3:return new float[]{y,width-x};default:return new float[]{x,y};}
 }
 static float[] rect(float[] rect,int width,int height,int turns){
  float[] a=point(rect[0],rect[1],width,height,turns),b=point(rect[2],rect[3],width,height,turns);
  return new float[]{Math.min(a[0],b[0]),Math.min(a[1],b[1]),Math.max(a[0],b[0]),Math.max(a[1],b[1])};
 }
 static boolean matches(int width,int height,int targetWidth,int targetHeight,int turns){
  return (turns&1)==0?width==targetWidth&&height==targetHeight:width==targetHeight&&height==targetWidth;
 }
 /** The overlay's first buffer may still be landscape while Launcher reports compact portrait.
  * Choose only a reported rotation whose dimensions fit this actual viewport. */
 static int viewportTurns(int width,int height,int viewportWidth,int viewportHeight,int sourceRotation,int wmRotation,int contextRotation){
  int wm=turns(sourceRotation,wmRotation),context=turns(sourceRotation,contextRotation);
  if(matches(width,height,viewportWidth,viewportHeight,wm))return wm;
  if(matches(width,height,viewportWidth,viewportHeight,context))return context;
  return -1;
 }
 /** Edge speeds are derivatives of rect(), so offsets disappear and edge order may swap. */
 static float[] velocity(float[] v,int turns){
  if(v==null||v.length!=4)return null;
  for(float value:v)if(!Float.isFinite(value))return null;
  switch(turns&3){
   case 1:return new float[]{-v[3],v[0],-v[1],v[2]};
   case 2:return new float[]{-v[2],-v[3],-v[0],-v[1]};
   case 3:return new float[]{v[1],-v[2],v[3],-v[0]};
   default:return v.clone();
  }
 }
 /** A content-sized workbench snapshot must be padded, never stretched over system insets. */
 static boolean fitsContent(int w,int h,int x,int y,int displayW,int displayH){
  return w>0&&h>w&&x>=0&&y>=0&&displayW>0&&displayH>displayW
   &&(long)x+w<=displayW&&(long)y+h<=displayH;
 }
 /** Same task buffer may be downsampled by WM; its axes and aspect must still match the crop. */
 static boolean sameAspect(int w,int h,float cropW,float cropH){
  return w>0&&h>0&&Float.isFinite(cropW)&&Float.isFinite(cropH)&&cropW>0&&cropH>0
   &&Math.abs(w*cropH-h*cropW)<=Math.max(w,h)*3f;
 }
 /** Until wallpaper arrives, the moving card must cover every old task pixel. */
 static boolean coversSource(float[] source,float[] target){
  if(source==null||target==null||source.length!=4||target.length!=4)return false;
  for(int i=0;i<4;i++)if(!Float.isFinite(source[i])||!Float.isFinite(target[i]))return false;
  return source[2]>source[0]&&source[3]>source[1]&&target[0]<=source[0]&&target[1]<=source[1]
   &&target[2]>=source[2]&&target[3]>=source[3];
 }
 /** Inverse of {@link #point} for a view that still has the capture's axes.
  * {@code viewW} and {@code viewH} are that view. Turns 0 is identity. */
 static float[] unpoint(float x,float y,int viewW,int viewH,int turns){
  switch(turns&3){
   case 1:return new float[]{y,viewH-x};
   case 2:return new float[]{viewW-x,viewH-y};
   case 3:return new float[]{viewW-y,x};
   default:return new float[]{x,y};
  }
 }
 /** Portrait stage, or the capture-sized swap a quarter-turn stage was planned from. */
 static boolean sameStage(int viewW,int viewH,int stageW,int stageH,int turns){
  if(viewW<1||viewH<1||stageW<1||stageH<1)return false;
  if(viewW==stageW&&viewH==stageH)return true;
  return (turns&1)==1&&viewW==stageH&&viewH==stageW;
 }
 /** Row-major 3×3 mapping stage pixels into the current view. Identity when the view
  *  is already the stage. A quarter-turn whose view is still the swapped capture gets
  *  the inverse of {@link #point}. Any other size is refused so the caller does not stretch. */
 static boolean stageMatrix(float[] out,int turns,int viewW,int viewH,int stageW,int stageH){
  if(out==null||out.length<9||!sameStage(viewW,viewH,stageW,stageH,turns))return false;
  out[0]=1;out[1]=0;out[2]=0;out[3]=0;out[4]=1;out[5]=0;out[6]=0;out[7]=0;out[8]=1;
  if(viewW==stageW&&viewH==stageH)return true;
  if((turns&3)==1){out[0]=0;out[1]=1;out[2]=0;out[3]=-1;out[4]=0;out[5]=viewH;}
  else{out[0]=0;out[1]=-1;out[2]=viewW;out[3]=1;out[4]=0;out[5]=0;}
  return true;
 }
 /** A held card stays in the portrait display's coordinates. Convert on every host draw;
  *  both rotation and the SurfaceView's inset/location can change during the handoff. */
 static float[] localQuad(float[] quad,int stageW,int stageH,int turns,int viewW,int viewH,int x,int y){
  if(quad==null||quad.length!=8)return null;
  float[] map=new float[9];
  if(!stageMatrix(map,turns,viewW,viewH,stageW,stageH))return null;
  float[] out=new float[8];
  for(int i=0;i<8;i+=2){
   if(!Float.isFinite(quad[i])||!Float.isFinite(quad[i+1]))return null;
   out[i]=map[0]*quad[i]+map[1]*quad[i+1]+map[2]-x;
   out[i+1]=map[3]*quad[i]+map[4]*quad[i+1]+map[5]-y;
  }
  return out;
 }
 /** Pixel crop of the shared release snapshot. It must never be used as task buffer bounds. */
 static float[] snapshotQuad(float[] crop,int width,int height){
  if(crop==null||crop.length!=4||width<2||height<2)return null;
  for(float value:crop)if(!Float.isFinite(value))return null;
  if(crop[0]<0||crop[1]<0||crop[2]>width||crop[3]>height||crop[2]-crop[0]<1||crop[3]-crop[1]<1)return null;
  return new float[]{crop[0],crop[1],crop[2],crop[1],crop[2],crop[3],crop[0],crop[3]};
 }
 /** View-space quad LT, RT, RB, LB. Progress 0 is the release crop mapped by {@link #point},
  * the card already under the finger. Progress 1 cycles the target the same way, so the
  * bitmap keeps that rotation and only moves into the workbench card.
  * Pass the stage size when drawing in stage space; {@link #stageMatrix} then places that
  * drawing into a view that has not rotated yet. This method itself stays in view space. */
 static float[] sweep(float[] captureRect,float[] target,int captureW,int captureH,int viewW,int viewH,int turns,float progress){
  if(captureRect==null||target==null||captureRect.length!=4||target.length!=4||captureW<1||captureH<1||viewW<1||viewH<1)return null;
  for(int i=0;i<4;i++)if(!Float.isFinite(captureRect[i])||!Float.isFinite(target[i]))return null;
  if(captureRect[2]-captureRect[0]<1||captureRect[3]-captureRect[1]<1)return null;
  int rotW=(turns&1)==0?captureW:captureH,rotH=(turns&1)==0?captureH:captureW;
  float sx=viewW/(float)rotW,sy=viewH/(float)rotH;
  float[] xs={captureRect[0],captureRect[2],captureRect[2],captureRect[0]};
  float[] ys={captureRect[1],captureRect[1],captureRect[3],captureRect[3]};
  float[] start=new float[8];
  for(int i=0;i<4;i++){
   float[] p=point(xs[i],ys[i],captureW,captureH,turns);
   start[i*2]=p[0]*sx;start[i*2+1]=p[1]*sy;
  }
  float[] end=cycle(new float[]{target[0],target[1],target[2],target[1],target[2],target[3],target[0],target[3]},turns);
  float t=Math.max(0,Math.min(1,progress));
  float[] out=new float[8];
  for(int i=0;i<8;i++)out[i]=start[i]+(end[i]-start[i])*t;
  return out;
 }
 /** One quarter-turn moves capture top-left onto the next view corner, matching {@link #point}. */
 static float[] cycle(float[] quad,int turns){
  int shift=(turns&3)*2;
  if(shift==0)return quad.clone();
  float[] out=new float[8];
  for(int i=0;i<8;i++)out[i]=quad[(i+shift)%8];
  return out;
 }
 static float[] quadBounds(float[] quad){
  float left=quad[0],top=quad[1],right=left,bottom=top;
  for(int i=2;i<8;i+=2){left=Math.min(left,quad[i]);top=Math.min(top,quad[i+1]);right=Math.max(right,quad[i]);bottom=Math.max(bottom,quad[i+1]);}
  return new float[]{left,top,right,bottom};
 }
 static boolean roundedTargetCovers(float[] source,float[] target,float radius){
  if(!coversSource(source,target)||!Float.isFinite(radius)||radius<0)return false;
  float r=Math.min(radius,Math.min(target[2]-target[0],target[3]-target[1])/2);
  for(float x:new float[]{source[0],source[2]})for(float y:new float[]{source[1],source[3]}){
   float cx=Math.max(target[0]+r,Math.min(target[2]-r,x));
   float cy=Math.max(target[1]+r,Math.min(target[3]-r,y));
   if((x-cx)*(x-cx)+(y-cy)*(y-cy)>r*r)return false;
  }
  return true;
 }
 private DisplayGeometry(){}
}
