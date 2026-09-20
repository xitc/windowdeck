package dev.windowdeck.app;
import android.app.Instrumentation;
import android.content.Intent;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
 public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
  if (!"com.oplus.pscanvas".equals(p.packageName)) return;
  XposedHelpers.findAndHookMethod(Instrumentation.class,"newActivity",ClassLoader.class,String.class,Intent.class,new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam param) {
    Intent intent=(Intent)param.args[2];
    if (!"com.oplus.pscanvas.canvasmode.canvas.ContainerActivity".equals(param.args[1]) || intent==null || !intent.getBooleanExtra("windowdeck_workbench_v1",false)) return;
    param.setResult(new WorkbenchActivity());
    android.util.Log.i("WindowDeck","custom_activity_created");
   }
  });
  android.util.Log.i("WindowDeck","hook_ready version=0.4.3-beta.1");
 }
}
