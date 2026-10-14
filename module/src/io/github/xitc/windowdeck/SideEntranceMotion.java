package io.github.xitc.windowdeck;

/** vivo RecyclerView TYPE_ADD_TO_CHILD_TASK: rail-sized translation, never fullscreen. */
final class SideEntranceMotion {
 /** SpringInterpolator.estimateDuration() for bounce=0 cancels the natural frequency. */
 private static final double DAMPED_TIME = 9.232803;

 static boolean applies(int fresh,int primary,int count,boolean hungMain,boolean originalPlus){
  return hungMain&&originalPlus&&count>1&&count<=Caps.MAX_TASKS
   &&fresh>=0&&fresh<count&&primary>=0&&primary<count&&fresh!=primary;
 }

 /** The rail includes its outer inset and its separation from the allocated main box. */
 static int[] start(int[] target,int mode,int railExtent){
  int[] from=target.clone();
  from[mode==PaneLayout.TOP_BOTTOM?1:0]-=Math.max(1,railExtent);
  return from;
 }

 static long travelMs(int mode){
  return mode==PaneLayout.TOP_BOTTOM?MotionSpec.SIDE_ADD_TOP_BOTTOM_MS:MotionSpec.SIDE_ADD_MS;
 }

 /** Convert the group's 500 ms clock before applying the side card's spring. */
 static float localTime(float fraction,int mode){
  float elapsed=Math.max(0f,Math.min(1f,fraction))*MotionSpec.SIDE_ADD_TOTAL_MS;
  return Math.max(0f,Math.min(1f,(elapsed-MotionSpec.SIDE_ADD_DELAY_MS)/travelMs(mode)));
 }

 /** Continuous critical response from originui SpringForce, with the ROM's exact endpoints.
  * The ROM rounds each delta to milliseconds; no frame-rate-dependent rounding is copied. */
 static float spring(float time){
  if(time<=0f)return 0f;if(time>=1f)return 1f;
  double t=DAMPED_TIME*time;
  return (float)(1.0-(1.0+t)*Math.exp(-t));
 }

 private SideEntranceMotion(){}
}
