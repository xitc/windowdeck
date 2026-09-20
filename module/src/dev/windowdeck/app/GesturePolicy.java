package dev.windowdeck.app;
/** Positive distance/velocity means towards the dismissal edge. */
final class GesturePolicy {
 static boolean dismiss(float distance,float cross,float velocity,float extent,float density){
  if(extent<=0||distance<=0||distance<=Math.abs(cross))return false;
  return distance>=Math.max(32*density,extent*.35f)
      ||(distance>=24*density&&velocity>=900*density);
 }
 // Normalized critically damped spring: no overshoot beyond the safe viewport.
 static float spring(float t){
  if(t<=0)return 0;if(t>=1)return 1;
  return (float)((1-(1+8*t)*Math.exp(-8*t))/(1-9*Math.exp(-8)));
 }
}
