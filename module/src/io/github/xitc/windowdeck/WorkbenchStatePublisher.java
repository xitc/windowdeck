package io.github.xitc.windowdeck;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.window.OnBackAnimationCallback;
import android.window.BackEvent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.window.OnBackInvokedDispatcher;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Owns publisher state for the workbench. The activity is only the nested Host. */
final class WorkbenchStatePublisher {
 interface Host {
  ContentResolver contentResolver();
  Context context();
  boolean hangReplace();
  long hangStarted();
  boolean hanging();
  boolean readOriginalPlus();
  void sendBroadcast(Intent intent,String receiverPermission,Bundle options);
  int taskId();
 }

 final WorkbenchSession session;
 final Handler handler;
 final Host host;
 WorkbenchStatePublisher(Host host,WorkbenchSession session,Handler handler){
  this.host=host;this.session=session;this.handler=handler;
 }

 private static final String TAG="WindowDeck";

 final long stateToken=SystemClock.elapsedRealtimeNanos();
 final android.os.IBinder videoCompatHost=new android.os.Binder();
 int statePublishGeneration;


 void publishState(int count){publishState(count,++statePublishGeneration,0);}
 void publishState(int count,int generation,int attempt){
  if(generation!=statePublishGeneration)return;
  Bundle state=liveState(count);
  if(attempt==0)try{LiveWorkbenchState.publish(host.context(),state);}catch(Throwable e){Log.w(TAG,"live_state_publish_failed",e);}
  try{if(host.contentResolver().call(android.net.Uri.parse("content://io.github.xitc.windowdeck.state"),"publish",null,state)!=null)return;}
  catch(Throwable e){Log.w(TAG,"publish_provider_failed attempt="+attempt,e);}
  if(attempt==0){
   Intent message=new Intent(LauncherIngressReceiver.STATE).setComponent(new ComponentName("io.github.xitc.windowdeck","io.github.xitc.windowdeck.LauncherIngressReceiver"));
   message.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES|Intent.FLAG_RECEIVER_FOREGROUND);message.putExtras(state);
   // Receiver checks getSentFromUid(). Without identity sharing a fallback can
   // be rejected even though the originating host is trusted.
   BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
   try{host.sendBroadcast(message,null,options.toBundle());Log.i(TAG,"publish_broadcast_sent container="+host.taskId()+" count="+count);}
   catch(Throwable e){Log.w(TAG,"publish_state_failed",e);}
  }
  // Package replacement can temporarily make the provider unavailable. Retry
  // the current publication only; an older count must never overwrite an add/exit.
  if(attempt<3)handler.postDelayed(()->publishState(count,generation,attempt+1),250L*(attempt+1));
 }
 Bundle liveState(int count){
  Bundle state=new Bundle();state.putInt("containerTaskId",host.taskId());state.putInt("count",count);state.putLong("instanceToken",stateToken);
  state.putBinder(VideoWindowCompat.HOST_BINDER,videoCompatHost);
  state.putBoolean("hanging",host.hanging()&&count>0);state.putBoolean("replacing",host.hanging()&&host.hangReplace());state.putLong("selectionSession",host.hangStarted());state.putInt("userId",android.os.Process.myUid()/100000);
  state.putBoolean(PickerLaunchPolicy.EMBED_BEFORE_LAUNCH,host.hanging()&&count>0&&PickerLaunchPolicy.embedBeforeLaunch(host.hangReplace(),host.readOriginalPlus()));
  String[] packages=new String[session.slots.size()];int[] tasks=new int[session.slots.size()];
  for(int i=0;i<session.slots.size();i++){packages[i]=session.slots.get(i).component.getPackageName();tasks[i]=session.slots.get(i).taskId;}
  state.putStringArray("packages",packages);state.putIntArray("tasks",tasks);return state;
 }
}
