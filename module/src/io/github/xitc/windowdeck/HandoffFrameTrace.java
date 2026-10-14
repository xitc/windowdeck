package io.github.xitc.windowdeck;

import android.util.Log;
import android.view.Choreographer;

/** Portrait travel only. Collect on UI thread, log once after travel/cancellation. */
final class HandoffFrameTrace implements Choreographer.FrameCallback {
 private final Choreographer choreographer=Choreographer.getInstance();
 private final MotionFrameSamples samples=new MotionFrameSamples();
 private final long started=System.nanoTime();
 private boolean stopped;
 void start(){choreographer.postFrameCallback(this);}
 @Override public void doFrame(long frameTimeNanos){
  if(stopped)return;
  samples.add(frameTimeNanos,System.nanoTime());
  choreographer.postFrameCallback(this);
 }
 void stop(String reason){
  if(stopped)return;stopped=true;choreographer.removeFrameCallback(this);
  Log.i("WindowDeck","handoff_frame_trace owner=choreographer result="+reason+" start_ns="+started+" end_ns="+System.nanoTime()+" "+samples.trace()+" presentation=UNVERIFIED");
 }
}
