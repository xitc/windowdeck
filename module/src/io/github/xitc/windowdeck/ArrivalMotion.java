package io.github.xitc.windowdeck;

/** Click-add frames share one box, content crop, outline and task-leash pose. */
final class ArrivalMotion {
 static final long REPLACE_RETIRE_MS=150;
 static final float REPLACE_RETIRE_SCALE=.7f;
 static int[] replacementStart(int[] target){
  int width=Math.max(1,Math.round(target[2]*.7f)),height=Math.max(1,Math.round(target[3]*.7f));
  return new int[]{target[0]+(target[2]-width)/2,target[1]+(target[3]-height)/2,width,height};
 }
 static float replacementRetirement(float raw){
  return EntranceMotion.fraction(Math.max(0f,Math.min(1f,raw*MotionSpec.REPLACE_MS/REPLACE_RETIRE_MS)),0);
 }
 static boolean sameCardBox(float[] source,int[] target){
  for(int i=0;i<4;i++)if(!(Math.abs(source[i]-target[i])<=.5f))return false;
  return true;
 }
 /** Reposition a captured source without assigning its destination role/crop.
  * Side additions pass the target here, preserving its shape during translation. */
 static CardMotion.Pose atBox(CardMotion.Pose source,int[] box,float radius){
  return CardMotion.at(new float[]{box[0],box[1],box[2],box[3]},
   CardMotion.localQuad(source,box[2],box[3]),source.crop,radius);
 }
 static float localTime(float raw,MotionSpec.Scene scene,int position,boolean freshSide,int mode){
  return freshSide?SideEntranceMotion.localTime(raw,mode):MotionSpec.entranceFraction(raw,scene,position);
 }
 static CardMotion.Pose frame(CardMotion.Pose from,CardMotion.Pose to,float local,MotionSpec.Scene scene){
  float fraction=scene==MotionSpec.Scene.SIDE_ADD?SideEntranceMotion.spring(local):
   scene==MotionSpec.Scene.MULTI_WINDOW?MotionSpec.geometry(local):EntranceMotion.fraction(local,0);
  return CardMotion.between(from,to,fraction);
 }
 private ArrivalMotion(){}
}
