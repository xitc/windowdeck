package io.github.xitc.windowdeck;

/** C17 TRANSIT_FULL (202): interpolate position and uniform scale, opening the buffer crop.
 * Timing is 400 ms with Path(0.3,0,0.1,1), independently of the 420 ms entrance. */
final class FullscreenMotion {
 final CardMotion.Pose from,to;
 private final float startScale,endScale,sourceRadius;
 FullscreenMotion(CardMotion.Pose from,float[] viewport,int bufferWidth,int bufferHeight){
  if(from==null||viewport==null||viewport.length!=4||bufferWidth<1||bufferHeight<1)
   throw new IllegalArgumentException("fullscreen source and viewport required");
  for(float value:viewport)if(!Float.isFinite(value))throw new IllegalArgumentException("finite viewport required");
  if(viewport[2]<=0||viewport[3]<=0||from.crop[2]<=0||from.crop[3]<=0)
   throw new IllegalArgumentException("positive fullscreen geometry required");
  this.from=from;startScale=from.box[2]/from.crop[2];endScale=viewport[2]/bufferWidth;
  sourceRadius=CardMotion.sourceRadius(from);
  float height=viewport[3]/endScale;
  if(height>bufferHeight+1)throw new IllegalArgumentException("fullscreen buffer is too short");
  to=CardMotion.at(viewport,new float[]{0,0,viewport[2],0,viewport[2],viewport[3],0,viewport[3]},
   new float[]{0,0,bufferWidth,height},sourceRadius*endScale);
 }
 CardMotion.Pose frame(float fraction){
  float f=Math.max(0,Math.min(1,fraction));
  if(f==0)return from;if(f==1)return to;
  float scale=MotionSpec.lerp(startScale,endScale,f);
  float[] crop=new float[4];for(int i=0;i<4;i++)crop[i]=MotionSpec.lerp(from.crop[i],to.crop[i],f);
  float[] box={MotionSpec.lerp(from.box[0],to.box[0],f),MotionSpec.lerp(from.box[1],to.box[1],f),crop[2]*scale,crop[3]*scale};
  // A click interrupting a switch retains the actual starting outline, too.
  float[] quad=CardMotion.localQuad(from,1,1),rect={0,0,1,0,1,1,0,1};
  for(int i=0;i<8;i+=2){quad[i]=MotionSpec.lerp(quad[i],rect[i],f)*box[2];quad[i+1]=MotionSpec.lerp(quad[i+1],rect[i+1],f)*box[3];}
  // C17 keeps the task-space corner radius throughout TRANSIT_FULL. Shrinking
  // the screen radius to zero exposes square corners before the screen is filled.
  // WM restores the fullscreen outline when it takes over the retained task.
  return CardMotion.at(box,quad,crop,sourceRadius*scale);
 }
}
