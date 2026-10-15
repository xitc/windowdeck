package io.github.xitc.windowdeck;

import android.os.Handler;
import android.os.HandlerThread;

/** One background thread for live-task pixel probes. Quit on destroy. */
final class LiveContentPump {
 private HandlerThread thread;
 private Handler worker;
 Handler worker(){
  if(thread==null){
   thread=new HandlerThread("WindowDeckLiveContent");
   thread.start();
   worker=new Handler(thread.getLooper());
  }
  return worker;
 }
 HandlerThread thread(){return thread;}
 void quit(){
  if(thread!=null){thread.quitSafely();thread=null;worker=null;}
 }
}
