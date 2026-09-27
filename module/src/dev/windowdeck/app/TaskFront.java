package dev.windowdeck.app;

/** Root-only entry point to raise an existing task without starting an Activity. */
public final class TaskFront {
 public static void main(String[] args) throws Exception {
  if(args.length!=1&&args.length!=2)throw new IllegalArgumentException("taskId required");
  int taskId=Integer.parseInt(args[0]);
  TaskFrontBinder.moveToFront(taskId);
  System.out.println("WindowDeck task_front task="+taskId);
  if(args.length==2){
   int source=Integer.parseInt(args[1]);if(source<0)throw new IllegalArgumentException("invalid source");
   Process reveal=new ProcessBuilder("am","broadcast","--user","0","--receiver-foreground","-a","dev.windowdeck.app.REVEAL_ADDED_CARD","-p","com.oplus.pscanvas","--ei","windowdeck_add_task_id",Integer.toString(source),"--ei","windowdeck_container_task_id",Integer.toString(taskId)).redirectErrorStream(true).start();
   if(!reveal.waitFor(8,java.util.concurrent.TimeUnit.SECONDS)){reveal.destroy();throw new IllegalStateException("reveal timed out");}
   if(reveal.exitValue()!=0)throw new IllegalStateException("reveal failed");
  }
 }
}
