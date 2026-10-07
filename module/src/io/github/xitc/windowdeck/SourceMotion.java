package io.github.xitc.windowdeck;

/** Recent actual simulator rectangles, tied to one task; units are screen pixels/millisecond. */
final class SourceMotion {
 private int task=-1;
 private long previousTime,lastTime;
 private float[] previous,last;
 void reset(){task=-1;previous=last=null;previousTime=lastTime=0;}
 void sample(int task,long time,float[] rect){
  if(rect==null||rect.length!=4)return;
  for(float v:rect)if(!Float.isFinite(v))return;
  if(this.task!=task||time<lastTime){reset();this.task=task;}
  if(last!=null&&time-lastTime<4)return;
  previous=last;previousTime=lastTime;last=rect.clone();lastTime=time;
 }
 float[] velocity(int task,long now){
  long dt=lastTime-previousTime;
  if(task!=this.task||previous==null||dt<4||dt>80||now<lastTime||now-lastTime>32)return null;
  float[] v=new float[4];for(int i=0;i<4;i++)v[i]=(last[i]-previous[i])/dt;return v;
 }
}
