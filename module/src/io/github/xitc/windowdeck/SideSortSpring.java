package io.github.xitc.windowdeck;

/** Zero-velocity response using the original duration/bounce conversion; no phase switching. */
final class SideSortSpring {
 static final SideSortSpring PRESS=new SideSortSpring(.5f,0);
 static final SideSortSpring RECOVER=new SideSortSpring(.5f,.3f);
 final double damping,frequency;
 SideSortSpring(float duration,float bounce){
  if(!(duration>0)||bounce<0||!Float.isFinite(duration)||!Float.isFinite(bounce))throw new IllegalArgumentException();
  float ratio=1f/(bounce+1f);damping=ratio;
  frequency=ratio==1?((float)(2*Math.PI/Math.log(2)))/duration:
    (float)(Math.log((double)ratio/((float)Math.sqrt(1-ratio*ratio)*.001))/(ratio*duration));
 }
 double value(double seconds){
  double t=Math.max(0,seconds),decay=Math.exp(-damping*frequency*t);
  if(damping==1)return 1-decay*(1+frequency*t);
  double w=frequency*Math.sqrt(1-damping*damping);
  return 1-decay*(Math.cos(w*t)+damping*frequency/w*Math.sin(w*t));
 }
 double velocity(double seconds){
  double t=Math.max(0,seconds),decay=Math.exp(-damping*frequency*t);
  if(damping==1)return decay*frequency*frequency*t;
  double w=frequency*Math.sqrt(1-damping*damping);
  return decay*frequency*frequency/w*Math.sin(w*t);
 }
 boolean atRest(double seconds,double distance,double minimumChange){
  double threshold=Math.abs(minimumChange)*.75;
  return Math.abs(distance*(1-value(seconds)))<threshold&&Math.abs(distance*velocity(seconds))<threshold*62.5;
 }
 /** A threshold-derived end, not a fixed duration copied from the duration parameter. */
 long settleMillis(double distance,double minimumChange){
  for(int ms=1;ms<=1200;ms++)if(atRest(ms/1000.0,distance,minimumChange))return ms;
  throw new IllegalArgumentException("Spring exceeds recovery budget");
 }
}
