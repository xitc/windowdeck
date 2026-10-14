package io.github.xitc.windowdeck;

/** Click-add frames share one box, content crop, outline and task-leash pose. */
final class ArrivalMotion {
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
