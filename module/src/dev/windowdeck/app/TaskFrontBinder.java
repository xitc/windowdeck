package dev.windowdeck.app;

import java.lang.reflect.Method;

/**
 * Raises an existing task with one binder call and no process of its own.
 *
 * The launcher must supply its own package and application thread for caller attribution.
 * Root uses the same C17 signature with a null application thread. A successful binder return
 * does not prove that a frame has been displayed.
 */
final class TaskFrontBinder {
 private TaskFrontBinder(){}
 /** @throws Exception if the service, the method or the permission check is unavailable. */
 static void moveToFront(int taskId) throws Exception {
  moveToFront(taskId,"android",null,android.app.ActivityOptions.makeBasic().toBundle());
 }
 static void moveToFront(android.content.Context context,int taskId) throws Exception {
  Object thread=Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
  if(thread==null)throw new IllegalStateException("no application thread");
  Object caller=thread.getClass().getMethod("getApplicationThread").invoke(thread);
  moveToFront(taskId,context.getPackageName(),caller,handoffOptions(context));
 }
 static android.os.Bundle handoffOptions(android.content.Context context) throws Exception {
  android.app.ActivityOptions options=android.app.ActivityOptions.makeCustomAnimation(context,0,0);
  // The independent handoff cover already supplies a preview. A starting-window
  // snapshot of the container contains the OLD main and fades over the new layout.
  android.app.ActivityOptions.class.getMethod("setDisableStartingWindow",boolean.class).invoke(options,true);
  return options.toBundle();
 }
 private static void moveToFront(int taskId,String packageName,Object caller,android.os.Bundle options) throws Exception {
  if(taskId<0)throw new IllegalArgumentException("invalid taskId");
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  if(service==null)throw new IllegalStateException("no ActivityTaskManager");
  Method target=Class.forName("android.app.IActivityTaskManager").getMethod("moveTaskToFront",
    Class.forName("android.app.IApplicationThread"),String.class,int.class,int.class,android.os.Bundle.class);
  target.invoke(service,caller,packageName,taskId,0,options);
 }
}
