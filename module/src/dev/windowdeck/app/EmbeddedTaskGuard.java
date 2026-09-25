package dev.windowdeck.app;

import android.app.ActivityManager;
import android.os.Bundle;
import android.view.SurfaceControl;
import de.robv.android.xposed.*;
import java.util.WeakHashMap;

/** Observe ownership before the ROM reuses a leash for an external fullscreen task. */
final class EmbeddedTaskGuard {
 interface Owner { void changed(boolean embedded); }
 private static final class Entry {
  final int container;final Owner owner;
  Entry(int container,Owner owner){this.container=container;this.owner=owner;}
 }
 private static final WeakHashMap<Object,Entry> entries=new WeakHashMap<>();
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
   installed=true;
  }
  entries.put(view,new Entry(container,owner));
 }
 static synchronized void forget(Object view){entries.remove(view);}
 private static void update(Object view,ActivityManager.RunningTaskInfo task,boolean vanished){
  Entry entry;synchronized(EmbeddedTaskGuard.class){entry=entries.get(view);}if(entry==null)return;
  try{
   Bundle extra=(Bundle)XposedHelpers.getObjectField(task,"mOplusExtraBundle");
   boolean embedded=!vanished&&extra!=null&&extra.getInt("androidx.activity.LaunchScenario",-1)==2&&extra.getInt("androidx.activity.LaunchContainerTaskId",-1)==entry.container;
   entry.owner.changed(embedded);
  }catch(Throwable e){XposedBridge.log(e);entry.owner.changed(false);}
 }
}
