package io.github.xitc.windowdeck;

/** Forward-only subset of Vivo's move SpringInterpolator, including per-sample float state. */
final class SideSortMoveCurve {
 static final long DURATION_MS=350;
 private final double frequency;
 private final float estimatedMillis;
 private float value,velocity,last;
 private boolean initialized;
 SideSortMoveCurve(){
  // The original animateMove passes the millisecond duration straight to TYPE_DURATION.
  float natural=((float)(2*Math.PI/Math.log(2)))/(float)DURATION_MS;
  frequency=natural;estimatedMillis=(9.232803f/natural)*1000f;
 }
 float sample(float fraction){
  if(!Float.isFinite(fraction))throw new IllegalArgumentException("Invalid fraction");
  if(!initialized){value=velocity=0;last=fraction;initialized=true;}
  if(fraction<=0||fraction>=1){initialized=false;last=0;return fraction<=0?0:1;}
  if(fraction<last)throw new IllegalArgumentException("Retarget with a fresh move curve");
  long millis=(long)(Math.abs(fraction-last)*estimatedMillis);last=fraction;
  double threshold=.001f*.75f;
  if(Math.abs(velocity)<threshold*62.5&&Math.abs(value-1)<threshold){value=1;velocity=0;return value;}
  double t=millis/1000.0,offset=value-1.0,linear=velocity+frequency*offset;
  double position=offset+linear*t,decay=Math.pow(Math.E,-frequency*t);
  value=(float)(decay*position+1);
  velocity=(float)(-frequency*position*decay+linear*decay);
  return value;
 }
}
