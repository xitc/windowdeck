package io.github.xitc.windowdeck;

/** C17 entrance pacing, with a bounded Hermite branch for a fresh measured start speed. */
final class EntranceMotion {
 static float fraction(float time,float speed){
  float t=Math.max(0,Math.min(1,time)),v=Math.max(0,Math.min(2,Float.isFinite(speed)?speed:0));
  return v==0?c17Fraction(t):t*t*(3-2*t)+v*t*(1-t)*(1-t);
 }
 /** C17 rapid-reaction PathInterpolator(0.3,0,0.1,1), inverted in time space. */
 static float c17Fraction(float time){
  if(time<=0)return 0;if(time>=1)return 1;
  double lo=0,hi=1,u=0;
  for(int i=0;i<24;i++){
   u=(lo+hi)/2;double one=1-u;
   double x=3*.3*u*one*one+3*.1*u*u*one+u*u*u;
   if(x<time)lo=u;else hi=u;
  }
  return (float)(u*u*(3-2*u));
 }
 static String curve(float speed){return speed>0?"entrance_velocity_hermite":"c17_rapid_path";}
 /** Wallpaper uses elapsed time, so the rapid card travel does not abruptly clear its blur. */
 static float backdropFraction(float time){
  float t=Math.max(0,Math.min(1,time));return t*t*(3-2*t);
 }
 /** Project measured screen-space rectangle velocity onto the requested trajectory.
  * Reject stale/opposing motion rather than transferring finger velocity across coordinate spaces. */
 static float startSpeed(float[] source,float[] target,float[] velocity,long ageMs,long durationMs){
  if(velocity==null||ageMs<0||ageMs>=120||durationMs<=0)return 0;
  double dot=0,length=0;
  for(int i=0;i<4;i++){
   if(!Float.isFinite(source[i])||!Float.isFinite(target[i])||!Float.isFinite(velocity[i]))return 0;
   double delta=target[i]-source[i];dot+=delta*velocity[i];length+=delta*delta;
  }
  if(length<1||dot<=0)return 0;
  return (float)Math.min(2,dot/length*durationMs*Math.exp(-ageMs/80.0));
 }
 private EntranceMotion(){}
}
