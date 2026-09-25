package dev.windowdeck.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Only the real launcher may request the existing task handoff. */
public final class LauncherIngressReceiver extends BroadcastReceiver {
 private static final java.util.concurrent.atomic.AtomicBoolean BUSY=new java.util.concurrent.atomic.AtomicBoolean();
 static final String ACTION="dev.windowdeck.app.ADD_EXISTING_TASK";
 static final String STATE="dev.windowdeck.app.WORKBENCH_STATE";
 @Override public void onReceive(Context context,Intent intent){
  if(intent==null)return;
  int uid=getSentFromUid();boolean launcher=false,host=false;
  String[] packages=context.getPackageManager().getPackagesForUid(uid);
  if(packages!=null)for(String pkg:packages){if("com.android.launcher".equals(pkg))launcher=true;if("com.oplus.pscanvas".equals(pkg))host=true;}
  if(STATE.equals(intent.getAction())){
   if(!host){Log.w("WindowDeck","state_sender_denied uid="+uid);return;}
   int container=intent.getIntExtra("containerTaskId",-1),count=intent.getIntExtra("count",-1);long token=intent.getLongExtra("instanceToken",0);
   if(container<0||count<0||count>3)return;
   android.content.SharedPreferences prefs=context.getSharedPreferences("workbench_state",0);
   if(count==0&&(prefs.getInt("container",-1)!=container||prefs.getLong("token",0)!=token))return;
   prefs.edit().putInt("container",container).putInt("count",count).putLong("token",token).apply();
   Log.i("WindowDeck","state_broadcast_received container="+container+" count="+count);
   return;
  }
  if(!ACTION.equals(intent.getAction()))return;
  if(!launcher){Log.w("WindowDeck","launcher_ingress_denied uid="+uid);return;}
  int task=intent.getIntExtra("taskId",-1),user=intent.getIntExtra("userId",-1),container=intent.getIntExtra("containerTaskId",-1);
  if(task<0||user!=0||container < -1||task==container)return;
  final PendingResult pending=goAsync();startTask(context,task,user,container,pending::finish);
 }
 private static void createWorkbench(Context context,int task,int user) throws Exception {
  String apk=context.getPackageCodePath();
  if(!apk.matches("/data/app/[A-Za-z0-9_~=/+.-]+/base\\.apk"))throw new IOException("unexpected apk path");
  Process process=new ProcessBuilder("su","-c","CLASSPATH="+apk+" app_process /system/bin dev.windowdeck.app.TaskIngress "+task+" "+user).redirectErrorStream(true).start();
  if(!process.waitFor(15,TimeUnit.SECONDS)&&process.isAlive()){process.destroy();throw new IOException("create workbench timed out");}
  if(process.exitValue()!=0)throw new IOException("create workbench exit="+process.exitValue());
  Log.i("WindowDeck","launcher_ingress_created source="+task);
 }
 static void startTask(Context context,int task,int user,int container,Runnable done){
  if(!BUSY.compareAndSet(false,true)){if(done!=null)done.run();return;}
  new Thread(()->{
   try{
    if(container<0){createWorkbench(context,task,user);return;}
    // Deliver to the live container. Starting ContainerActivity from outside can
    // create a second task, whose empty instance then replaces the real one.
    String command="am broadcast --user 0 --receiver-foreground -a dev.windowdeck.app.ADD_TO_LIVE_WORKBENCH -p com.oplus.pscanvas --ei windowdeck_add_task_id "+task+" --ei windowdeck_add_user_id "+user+" --ei windowdeck_container_task_id "+container;
    Process process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
    if(!process.waitFor(12,TimeUnit.SECONDS)&&process.isAlive()){process.destroy();throw new IOException("am start timed out");}
    if(process.exitValue()!=0)throw new IOException("am broadcast exit="+process.exitValue());
    String apk=context.getPackageCodePath();
    if(!apk.matches("/data/app/[A-Za-z0-9_~=/+.-]+/base\\.apk"))throw new IOException("unexpected apk path");
    Process front=new ProcessBuilder("su","-c","CLASSPATH="+apk+" app_process /system/bin dev.windowdeck.app.TaskFront "+container+" "+task).redirectErrorStream(true).start();
    if(!front.waitFor(12,TimeUnit.SECONDS)&&front.isAlive()){front.destroy();throw new IOException("task front timed out");}
    if(front.exitValue()!=0)throw new IOException("task front exit="+front.exitValue());
    Log.i("WindowDeck","launcher_ingress_sent task="+task+" container="+container+" front=true");
   }catch(Exception e){Log.e("WindowDeck","launcher_ingress_failed task="+task,e);}
   finally{BUSY.set(false);if(done!=null)done.run();}
  },"WindowDeckIngress").start();
 }
}
