package io.github.xitc.windowdeck;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Refreshes the task list off the launcher main thread, before a swipe reads it. */
final class CandidateProbe {
 private static final long REFRESH_MS=800;
 private static final String TAG="WindowDeck";
 private static final Object LOCK=new Object();
 private static volatile Context app;
 private static volatile Snapshot snapshot;
 private static volatile boolean started,loggedFailure,loggedReady;
 private static final class Snapshot {
  final SwipeCandidate.Task[] tasks;
  final boolean providerKnown;
  final int providerContainer,providerCount;
  Snapshot(SwipeCandidate.Task[] tasks,boolean providerKnown,int providerContainer,int providerCount){
   this.tasks=tasks;this.providerKnown=providerKnown;this.providerContainer=providerContainer;this.providerCount=providerCount;
  }
 }
 private CandidateProbe(){}
 /** Starts the background refresh once. Later calls only keep the application context. */
 static void warm(Context context){
  if(context==null)return;
  Context next=context.getApplicationContext();
  app=next==null?context:next;
  synchronized(LOCK){
   if(started)return;
   started=true;
   Thread thread=new Thread(CandidateProbe::loop,"windowdeck-candidate");
   thread.setDaemon(true);
   thread.start();
  }
 }
 /** Wakes the refresher without waiting. Used after a gesture, not during one. */
 static void kick(){synchronized(LOCK){LOCK.notifyAll();}}
 static boolean ready(){return snapshot!=null;}
 static SwipeCandidate.Match match(Object handler){
  Snapshot shot=snapshot;
  if(shot==null||handler==null)return null;
  int source=runningTask(handler);
  int[] published=LiveWorkbenchState.published();
  boolean known;int container,count;
  if(published!=null){known=true;container=published[0];count=published[1];}
  else if(shot.providerKnown){known=true;container=shot.providerContainer;count=shot.providerCount;}
  else{known=false;container=-1;count=0;}
  return SwipeCandidate.match(source,shot.tasks,known,container,count);
 }
 private static void loop(){
  while(true){
   Context current=app;
   if(current!=null)try{
    snapshot=load(current);
    if(!loggedReady){loggedReady=true;Log.i(TAG,"candidate_cache_ready tasks="+snapshot.tasks.length);}
   }catch(Throwable e){
    if(!loggedFailure){loggedFailure=true;Log.w(TAG,"candidate_cache_failed",e);}
   }
   synchronized(LOCK){try{LOCK.wait(REFRESH_MS);}catch(InterruptedException e){return;}}
  }
 }
 static int runningTask(Object handler){
  try{
   Object gesture=de.robv.android.xposed.XposedHelpers.getObjectField(handler,RomSymbols.HANDLER_GESTURE_STATE);
   return ((Number)de.robv.android.xposed.XposedHelpers.callMethod(gesture,RomSymbols.GESTURE_RUNNING_TASK_ID)).intValue();
  }catch(Throwable e){return -1;}
 }
 private static Snapshot load(Context context) throws Exception {
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  List<?> raw=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,true,0);
  ArrayList<SwipeCandidate.Task> tasks=new ArrayList<>();
  if(raw!=null)for(Object value:raw){
   ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)value;
   String base=info.baseActivity==null?null:info.baseActivity.getPackageName();
   String top=info.topActivity==null?null:info.topActivity.getPackageName();
   boolean workbench=info.baseIntent!=null&&info.baseIntent.getBooleanExtra("windowdeck_workbench_v1",false);
   int userId=info.getClass().getField("userId").getInt(info);
   int mode=((Number)info.getClass().getMethod("getWindowingMode").invoke(info)).intValue();
   tasks.add(new SwipeCandidate.Task(info.taskId,base,top,userId,mode,workbench));
  }
  boolean providerKnown=false;int providerContainer=-1,providerCount=0;
  if(LiveWorkbenchState.published()==null)try{
   Bundle state=context.getContentResolver().call(Uri.parse("content://io.github.xitc.windowdeck.state"),"state",null,null);
   if(state!=null){providerKnown=true;providerContainer=state.getInt("container",-1);providerCount=state.getInt("count",0);}
  }catch(Throwable ignored){}
  return new Snapshot(tasks.toArray(new SwipeCandidate.Task[0]),providerKnown,providerContainer,providerCount);
 }
}
