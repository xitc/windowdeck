package dev.windowdeck.app;

import android.app.ActivityManager;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Root helper: recheck live tasks before creating a container for a gesture. */
public final class TaskIngress {
 public static void main(String[] args) throws Exception {
  if(android.os.Process.myUid()!=0||args.length!=2)throw new SecurityException("root task/user required");
  int source=Integer.parseInt(args[0]),user=Integer.parseInt(args[1]);
  if(source<0||user!=0)throw new IllegalArgumentException("invalid source");
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,true,0);
  ActivityManager.RunningTaskInfo sourceInfo=null;
  for(Object value:tasks){
   ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;
   // Never replace a container that appeared between gesture release and dispatch.
   if(task.baseActivity!=null&&"com.oplus.pscanvas".equals(task.baseActivity.getPackageName()))throw new IllegalStateException("container already exists");
   if(task.taskId==source)sourceInfo=task;
  }
  if(sourceInfo==null||sourceInfo.baseActivity==null||sourceInfo.topActivity==null||!sourceInfo.baseActivity.getPackageName().equals(sourceInfo.topActivity.getPackageName())||sourceInfo.getClass().getField("userId").getInt(sourceInfo)!=user||((Number)sourceInfo.getClass().getMethod("getWindowingMode").invoke(sourceInfo)).intValue()!=1)throw new IllegalStateException("source changed");
  Process process=new ProcessBuilder("am","start","-W","--user","0","-f","0x10000000","-n","com.oplus.pscanvas/.canvasmode.canvas.ContainerActivity","--ez","windowdeck_workbench_v1","true","--ei","windowdeck_create_source_task",Integer.toString(source),"--ei","windowdeck_create_source_user",Integer.toString(user)).redirectErrorStream(true).start();
  if(!process.waitFor(12,TimeUnit.SECONDS)){process.destroy();throw new IllegalStateException("start timed out");}
  ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] bytes=new byte[1024];int n;
  while((n=process.getInputStream().read(bytes))!=-1)output.write(bytes,0,n);
  String result=output.toString("UTF-8");
  if(process.exitValue()!=0||result.contains("Error:")||!result.contains("Status: ok"))throw new IllegalStateException("start failed: "+result);
  System.out.println("WindowDeck created from task="+source);
 }
}
