package io.github.xitc.windowdeck;

import java.util.Arrays;

/** One task's type 20 right-bottom Y spring in an ordinary top/bottom exchange. */
final class TopSwapSpring {
 static final long WATCHDOG_MS=1500;
 private static final float FREQUENCY=(float)(2*Math.PI/Math.log(2))/.5f;
 private static final float STOP_DISTANCE=1.5f,STOP_VELOCITY=STOP_DISTANCE*62.5f;
 final CardMotion.Pose from,to;
 final float start,goal;
 final boolean physical;
 private final CardMotion.Pose continuation;
 private Float previousGoal;
 float position,velocity;
 boolean done;

 TopSwapSpring(CardMotion.Pose from,CardMotion.Pose to,TopSwapSpring previous){
  this.from=from;this.to=to;
  start=from.quad[5];
  float end=to.quad[5];
  physical=Math.abs(end-start)>.5f;
  // An unchanged Y cannot parameterize a changing quad. Use the maximum
  // corner displacement as the spring's distance for this degenerate segment.
  goal=physical?end:start+span(from,to);
  position=start;
  done=!physical&&Arrays.equals(from.quad,to.quad)&&Arrays.equals(from.box,to.box)
    &&Arrays.equals(from.crop,to.crop)&&from.radius==to.radius;
  if(physical&&previous!=null&&previous.physical&&!previous.done){
   velocity=previous.velocity;
   previousGoal=previous.goal;
   continuation=previous.continuationFrom(start,goal);
  }else continuation=null;
 }

 private static float span(CardMotion.Pose from,CardMotion.Pose to){
  float span=1;
  for(int i=0;i<8;i++)span=Math.max(span,Math.abs(to.quad[i]-from.quad[i]));
  return span;
 }

 private CardMotion.Pose continuationFrom(float source,float destination){
  float away=Math.signum(source-destination);
  CardMotion.Pose anchor=null;
  float far=0;
  for(CardMotion.Pose p:new CardMotion.Pose[]{from,to,continuation}){
   if(p==null)continue;
   float distance=(p.quad[5]-source)*away;
   if(distance>far){far=distance;anchor=p;}
  }
  return anchor;
 }

 private void advance(long millis,float target){
  double t=millis/1000d,offset=(double)position-target,linear=velocity+FREQUENCY*offset;
  double delta=offset+linear*t,decay=Math.exp(-FREQUENCY*t);
  position=(float)(target+delta*decay);
  velocity=(float)((linear-FREQUENCY*delta)*decay);
 }

 CardMotion.Pose step(long millis,boolean forceEnd,int originX,int originY){
  if(!done&&!forceEnd&&millis>0){
   if(previousGoal!=null){
    advance(millis/2,previousGoal);
    advance(millis/2,goal);
    previousGoal=null;
   }else advance(millis,goal);
   done=Math.abs(position-goal)<STOP_DISTANCE&&Math.abs(velocity)<STOP_VELOCITY;
  }
  if(forceEnd||done){position=goal;velocity=0;done=true;return to;}
  float progress=(position-start)/(goal-start);
  if(progress<0&&continuation!=null){
   float back=(position-start)/(continuation.quad[5]-start);
   return MainSideMotion.betweenY(from,continuation,back,originX,originY);
  }
  return MainSideMotion.betweenY(from,to,progress,originX,originY);
 }
}
