package dev.windowdeck.app;
import android.app.Instrumentation;
import android.content.Intent;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
 public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
  if ("com.android.launcher".equals(p.packageName) || "com.oplus.pscanvas".equals(p.packageName))
   XposedBridge.log("WindowDeck load_package package="+p.packageName+" process="+p.processName+" first="+p.isFirstApplication);
  if("com.android.launcher".equals(p.packageName)){
   android.util.Log.i("WindowDeck","launcher_load_package process="+p.processName);
   try{LauncherSwipeHook.install(p.classLoader);XposedBridge.log("WindowDeck launcher_hook_ready process="+p.processName);}catch(Throwable e){android.util.Log.e("WindowDeck","launcher_hook_failed",e);XposedBridge.log(e);}return;
  }
  if (!"com.oplus.pscanvas".equals(p.packageName)) return;
  XposedHelpers.findAndHookMethod(Instrumentation.class,"newActivity",ClassLoader.class,String.class,Intent.class,new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam param) {
    Intent intent=(Intent)param.args[2];
    if (!"com.oplus.pscanvas.canvasmode.canvas.ContainerActivity".equals(param.args[1]) || intent==null || !intent.getBooleanExtra("windowdeck_workbench_v1",false)) return;
    param.setResult(new WorkbenchActivity());
    android.util.Log.i("WindowDeck","custom_activity_created");
   }
  });
  try{LeashTransactions.install();}catch(Throwable e){android.util.Log.e("WindowDeck","leash_atomic_hook_failed",e);}
  android.util.Log.i("WindowDeck","hook_ready version=0.4.7-beta.21");
 }
}
