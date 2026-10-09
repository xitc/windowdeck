package io.github.xitc.windowdeck;

import android.app.BroadcastOptions;
import android.content.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import de.robv.android.xposed.*;

/** Current host -> launcher state. Does not require starting the module APK. */
final class LiveWorkbenchState {
 static final String UPDATE="io.github.xitc.windowdeck.LIVE_WORKBENCH_STATE";
 static final String QUERY="io.github.xitc.windowdeck.QUERY_LIVE_WORKBENCH";
 private static boolean registered;
 private static volatile Bundle latest;
 static void installLauncher(){
  XposedHelpers.findAndHookMethod(android.app.Application.class,"attach",Context.class,new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){register((Context)p.args[0]);}
  });
 }
 private static void accept(Bundle state){
  if(state==null)return;
  int container=state.getInt("containerTaskId",-1),count=state.getInt("count",-1);
  if(container<0||count<0||count>Caps.MAX_TASKS)return;
  if(count==0&&(latest==null||latest.getInt("containerTaskId",-1)!=container||latest.getLong("instanceToken")!=state.getLong("instanceToken")))return;
  latest=new Bundle(state);
  Log.i("WindowDeck","live_state_received container="+container+" count="+count);
 }
 /** Container and count already pushed by the host, or null when no broadcast has arrived. */
 static int[] published(){
  Bundle state=latest;if(state==null)return null;
  return new int[]{state.getInt("containerTaskId",-1),state.getInt("count",0)};
 }
 static void register(Context context){
  if(context!=null)CandidateProbe.warm(context);
  if(registered)return;
  Context app=context.getApplicationContext();if(app==null)app=context;
  Handler main=new Handler(Looper.getMainLooper());
  app.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
   String[] packages=c.getPackageManager().getPackagesForUid(getSentFromUid());
   if(packages!=null)for(String pkg:packages)if("com.oplus.pscanvas".equals(pkg)){accept(intent.getExtras());return;}
   Log.w("WindowDeck","live_state_sender_denied uid="+getSentFromUid());
  }},new IntentFilter(UPDATE),null,main,Context.RECEIVER_EXPORTED);
  registered=true;
  BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
  app.sendOrderedBroadcast(new Intent(QUERY).setPackage("com.oplus.pscanvas").addFlags(Intent.FLAG_RECEIVER_FOREGROUND),null,options.toBundle(),new BroadcastReceiver(){
   public void onReceive(Context c,Intent intent){if(getResultCode()==1)accept(getResultExtras(false));}
  },main,0,null,null);
 }
 static Bundle state(int container){
  if(latest==null||latest.getInt("containerTaskId",-1)!=container)return null;
  Bundle state=new Bundle();state.putInt("container",container);state.putInt("count",latest.getInt("count",0));return state;
 }
 static Bundle existing(String pkg,int user){
  Bundle state=latest;if(state==null)return null;
  int task=ExistingAppPolicy.task(state.getBoolean("hanging"),state.getInt("userId",-1),user,pkg,state.getStringArray("packages"),state.getIntArray("tasks"));
  if(task<0)return null;
  Bundle result=new Bundle(state);result.putInt("selectedTask",task);result.putString("selectedPackage",pkg);return result;
 }
 static void publish(Context context,Bundle state){
  BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
  context.sendBroadcast(new Intent(UPDATE).setPackage("com.android.launcher").addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(state),null,options.toBundle());
  for(String pkg:new String[]{"tv.acfundanmaku.video","com.google.android.youtube"})
   context.sendBroadcast(new Intent(UPDATE).setPackage(pkg).addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(state),null,options.toBundle());
 }
}
