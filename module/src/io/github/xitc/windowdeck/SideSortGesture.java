package io.github.xitc.windowdeck;

/** Separates the latest candidate delta from the first nonzero DRAW baseline. */
final class SideSortGesture {
 private final float slop;
 private float raw,baseline=Float.NaN;
 private boolean pending,sorting;
 SideSortGesture(float slop){if(!Float.isFinite(slop)||slop<0)throw new IllegalArgumentException();this.slop=slop;}
 void update(float delta){if(!Float.isFinite(delta))throw new IllegalArgumentException();raw=delta;pending=true;}
 boolean pending(){return pending;}
 float candidateDelta(){return raw;}
 float draw(){
  if(Float.isNaN(baseline)&&Math.abs(raw)>=1e-6f)baseline=raw;
  pending=false;float delta=Float.isNaN(baseline)?0:raw-baseline;
  if(Math.abs(delta)>slop)sorting=true;
  return delta;
 }
 boolean sorting(){return sorting;}
 /** A queued MOVE crossing a known baseline must beat the menu's delayed callback. */
 boolean wouldSort(){return sorting||(!Float.isNaN(baseline)&&Math.abs(raw-baseline)>slop);}
}
