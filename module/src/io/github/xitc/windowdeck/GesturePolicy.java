package io.github.xitc.windowdeck;
/** Positive distance/velocity means towards the dismissal edge. */
final class GesturePolicy {
 static boolean dismiss(float distance,float cross,float velocity,float extent,float density){
  if(extent<=0||distance<=0||distance<=Math.abs(cross))return false;
  return distance>=Math.max(32*density,extent*.35f)
      ||(distance>=24*density&&velocity>=900*density);
 }
 // Normalized COUI spring. The retired curve was a self-made critically damped spring with
 // ωn=8, unrelated to any vivo/ROM parameter; it is kept as legacySpring() for reference.
 /** @deprecated superseded by {@link MotionSpec#geometry(float)} / {@link SpringCurve}. */
 @Deprecated
 static float spring(float t){
  return MotionSpec.geometry(t);
 }
 /** The retired ωn=8 critically damped spring, preserved for reference only. */
 @Deprecated
 static float legacySpring(float t){
  return SpringCurve.legacySwitch(t);
 }
}
