package io.github.xitc.windowdeck;

import android.app.ActivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import de.robv.android.xposed.*;
import java.util.HashMap;

/** Observe ownership before the ROM reuses a leash for an external fullscreen task. */
final class EmbeddedTaskGuard {
 private static final String RETIRED="windowdeck.retiredTaskView";
 interface Owner { void changed(boolean embedded); }
 private static final class Entry {
  final int container;final Owner owner;
  Entry(int container,Owner owner){this.container=container;this.owner=owner;}
 }
 // Strong map: the owner lambda holds the view, so a weak key would never clear on its own.
 private static final HashMap<Object,Entry> entries=new HashMap<>();
 private static Handler main;
 private static boolean installed;
 static synchronized void watch(Object view,int container,Owner owner){
  if(!installed){
   Class<?> type=view.getClass();
   for(String name:new String[]{"onTaskAppeared","onTaskInfoChanged"})
    XposedHelpers.findAndHookMethod(type,name,ActivityManager.RunningTaskInfo.class,SurfaceControl.class,new XC_MethodHook(){
     @Override protected void beforeHookedMethod(MethodHookParam p){update(p.thisObject,(ActivityManager.RunningTaskInfo)p.args[0],false);}
    });
   XposedHelpers.findAndHookMethod(type,"onTaskVanished",ActivityManager.RunningTaskInfo.class,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){update(p.thisObject,(ActivityManager.RunningTaskInfo)p.args[0],true);}
   });
   XposedHelpers.findAndHookMethod(type,"updateTaskVisibilityWhenSurfaceDestroyed",new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){
     boolean retired,owned;
     synchronized(EmbeddedTaskGuard.class){
      retired=Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(p.thisObject,RETIRED));
      owned=retired||entries.containsKey(p.thisObject);
     }
     if(!owned)return;
     // C17 calls release() again from onDetachedFromWindow(). It resets mSuperLocked,
     // so that field cannot protect a queued callback after the task token is cleared.
     if(retired||XposedHelpers.getObjectField(p.thisObject,"mTaskToken")==null){
      p.setResult(null);
      XposedBridge.log("WindowDeck surface_visibility_skipped reason="+(retired?"released":"no_task_token"));
     }
    }
   });
   installed=true;
  }
  entries.put(view,new Entry(container,owner));
 }
 static synchronized void forget(Object view){
  if(entries.remove(view)!=null)XposedHelpers.setAdditionalInstanceField(view,RETIRED,Boolean.TRUE);
 }
 private static void update(Object view,ActivityManager.RunningTaskInfo task,boolean vanished){
  Entry entry;synchronized(EmbeddedTaskGuard.class){entry=entries.get(view);}if(entry==null||task==null)return;
  boolean embedded;
  try{
   Bundle extra=(Bundle)XposedHelpers.getObjectField(task,"mOplusExtraBundle");
   embedded=!vanished&&extra!=null&&extra.getInt("androidx.activity.LaunchScenario",-1)==2&&extra.getInt("androidx.activity.LaunchContainerTaskId",-1)==entry.container;
  }catch(Throwable e){XposedBridge.log(e);embedded=false;}
  post(view,entry,embedded);
 }
 private static void post(final Object view,final Entry entry,final boolean embedded){
  Handler handler;
  synchronized(EmbeddedTaskGuard.class){
   if(main==null)main=new Handler(Looper.getMainLooper());
   handler=main;
  }
  handler.post(new Runnable(){public void run(){
   synchronized(EmbeddedTaskGuard.class){if(entries.get(view)!=entry)return;}
   try{entry.owner.changed(embedded);}catch(Throwable e){XposedBridge.log(e);}
  }});
 }
}
