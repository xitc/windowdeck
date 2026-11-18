package io.github.xitc.windowdeck;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.util.Log;
import de.robv.android.xposed.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** Route picker clicks before the launcher sends a fullscreen app start. */
final class LauncherExistingAppHook {
 private static final String ICON_RETRY="windowdeck_picker_icon_retry";
 private static final class IconRetry {
  final PickerLaunchPolicy.Retry selection;
  final String launchId;
  IconRetry(PickerLaunchPolicy.Retry selection,String launchId){
   this.selection=selection;this.launchId=launchId;
  }
 }
 static boolean launcherIcon(Intent intent){
  return intent!=null&&intent.getComponent()!=null&&Intent.ACTION_MAIN.equals(intent.getAction())
    &&intent.hasCategory(Intent.CATEGORY_LAUNCHER)&&intent.getData()==null&&intent.getSelector()==null
    &&intent.getClipData()==null&&!intent.hasExtra(Intent.EXTRA_USER);
 }
 static void installPickerRetry(Class<?> handler){
  XC_MethodHook.Unhook registration=null;
  try{
   if(!"h84".equals(RomSymbols.field(handler,"t").getType().getName()))return;
   ClassLoader loader=handler.getClassLoader();
   Class<?> manager=loader.loadClass("com.android.launcher3.QuickstepTransitionManager");
   Class<?> reason=loader.loadClass("com.oplus.animation.core.anim.SpringAnimConstants$ReasonEndTransition");
   Class<?> callback=loader.loadClass("jf5");
   Field target=callback.getDeclaredField("r");
   Method end=manager.getDeclaredMethod("endAppTransition",reason,boolean.class,Consumer.class);
   Method accept=callback.getDeclaredMethod("accept",Object.class);
   if(!reason.isEnum()||!Consumer.class.isAssignableFrom(callback)||target.getType()!=Intent.class
     ||end.getReturnType()!=void.class||accept.getReturnType()!=void.class)throw new NoSuchMethodException("picker retry contract");
   target.setAccessible(true);
   registration=XposedBridge.hookMethod(end,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
     if(!(p.args[0] instanceof Enum<?>)||!"START_NEW_APP".equals(((Enum<?>)p.args[0]).name())
       ||Boolean.TRUE.equals(p.args[1])||!callback.isInstance(p.args[2]))return;
     Intent intent=(Intent)target.get(p.args[2]);
     if(!launcherIcon(intent))return;
     int user=android.os.Process.myUid()/100000;
     PickerLaunchPolicy.Retry selection=LiveWorkbenchState.pickerRetry(intent.getComponent(),user);
     String launchId=LauncherStartDiagnostics.currentId(intent);
     XposedHelpers.setAdditionalInstanceField(p.args[2],ICON_RETRY,new IconRetry(selection,launchId));
     HandoffDiagnostics.timeline(HandoffDiagnostics.Event.APP_DEFERRED,
       selection==null?"icon_transition_unowned":"icon_transition_picker",-1,launchId);
    }
   });
   XposedBridge.hookMethod(accept,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
     Object recorded=XposedHelpers.getAdditionalInstanceField(p.thisObject,ICON_RETRY);
     if(!(recorded instanceof IconRetry))return;
     IconRetry retry=(IconRetry)recorded;
     Intent intent=(Intent)target.get(p.thisObject);
     boolean sameTarget=retry.selection!=null&&launcherIcon(intent)
       &&retry.selection.component.equals(intent.getComponent().flattenToString());
     int task=sameTarget?LiveWorkbenchState.consumedPickerTask(retry.selection):-1;
     if(task<0){
      HandoffDiagnostics.timeline(HandoffDiagnostics.Event.APP_CALLBACK,"icon_retry_native",-1,retry.launchId);
      return;
     }
     p.setResult(null);
     HandoffDiagnostics.timeline(HandoffDiagnostics.Event.APP_CALLBACK,"icon_retry_already_embedded",task,retry.launchId);
     Log.i("WindowDeck","launcher_picker_retry_consumed task="+task);
    }
   });
   Log.i("WindowDeck","launcher_picker_retry_ready");
  }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
   if(registration!=null)registration.unhook();
   Log.w("WindowDeck","launcher_picker_retry_unavailable",e);
  }
 }
 private static void diagnosticRoute(String outcome,int task){
  diagnosticRoute(outcome,task,null);
 }
 private static void diagnosticRoute(String outcome,int task,String launchId){
  HandoffDiagnostics.timeline(HandoffDiagnostics.Event.APP_ROUTE,outcome,task,launchId);
 }
 static final String SELECT=(BuildIdentity.APPLICATION_ID+".SELECT_EXISTING_CARD");
 static final String LAUNCH=(BuildIdentity.APPLICATION_ID+".LAUNCH_PICKER_APP");
 static int userOf(android.os.UserHandle handle){try{return (Integer)android.os.UserHandle.class.getMethod("getIdentifier").invoke(handle);}catch(Throwable e){return 0;}}
 static void install(){
  XposedHelpers.findAndHookMethod(Instrumentation.class,"execStartActivity",Context.class,IBinder.class,IBinder.class,Activity.class,Intent.class,int.class,Bundle.class,new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam p){
    Context context=(Context)p.args[0];Intent intent=(Intent)p.args[4];
    if(context==null||!"com.android.launcher".equals(context.getPackageName())||(Integer)p.args[5]>=0||!launcherIcon(intent))return;
    int user=android.os.Process.myUid()/100000;String pkg=intent.getComponent().getPackageName();
    int task=LiveWorkbenchState.diagnosticTask(pkg,user);
    String launchId=LauncherStartDiagnostics.currentId(intent);
    HandoffDiagnostics.timeline(HandoffDiagnostics.Event.APP_START,"instrumentation",task,launchId);
    Bundle existing=LiveWorkbenchState.existing(pkg,user);
    final boolean select=existing!=null;
    final Bundle selection=select?existing:LiveWorkbenchState.launch(pkg,user);
    if(selection==null){diagnosticRoute("native_passthrough",task,launchId);return;}
    if(!select)selection.putParcelable("selectedIntent",new Intent(intent));
    Object receiver=p.thisObject;java.lang.reflect.Member method=p.method;Object[] args=p.args.clone();args[4]=new Intent(intent);if(args[6]!=null)args[6]=new Bundle((Bundle)args[6]);
    try{
     BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
     context.sendOrderedBroadcast(new Intent(select?SELECT:LAUNCH).setPackage("com.oplus.pscanvas").addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(selection),null,options.toBundle(),new BroadcastReceiver(){
      public void onReceive(Context c,Intent request){
       if(getResultCode()==1){diagnosticRoute(select?"existing_card":"picker_embed",task,launchId);Log.i("WindowDeck",(select?"existing_launch_routed task="+selection.getInt("selectedTask"):"picker_launch_routed package="+pkg)+" session="+selection.getLong("selectionSession"));return;}
       // A stale host/session must not swallow an ordinary app launch. The
       // original method bypasses this hook, so fallback happens exactly once.
       try{XposedBridge.invokeOriginalMethod(method,receiver,args);diagnosticRoute("native_fallback",task,launchId);Log.i("WindowDeck","existing_launch_fallback result="+getResultCode());}
       catch(Throwable e){diagnosticRoute("native_fallback_failed",task,launchId);Log.e("WindowDeck","existing_launch_fallback_failed",e);}
      }
     },new Handler(Looper.getMainLooper()),0,null,null);
     p.setResult(null);
    }catch(Throwable e){diagnosticRoute("dispatch_failed_passthrough",task,launchId);Log.w("WindowDeck","existing_launch_dispatch_failed",e);}
   }
  });
  Log.i("WindowDeck","existing_launch_hook_ready");
  installCloneStart();
 }
 /** Cloned apps keep the owner's ComponentName, so the only place their user survives is
  * LauncherApps.startMainActivity: the launcher calls it with an explicit UserHandle and it
  * never reaches Instrumentation. Hook that path too, but only for other-user starts, so an
  * ordinary launch keeps behaving exactly as before. */
 private static void installCloneStart(){
  try{
   XposedHelpers.findAndHookMethod(android.content.pm.LauncherApps.class,"startMainActivity",
    ComponentName.class,android.os.UserHandle.class,android.graphics.Rect.class,Bundle.class,new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam p){
     ComponentName component=(ComponentName)p.args[0];
     android.os.UserHandle handle=(android.os.UserHandle)p.args[1];
     if(component==null||handle==null)return;
     int self=android.os.Process.myUid()/100000,user=userOf(handle);String pkg=component.getPackageName();
     Log.i("WindowDeck","launcher_apps_start package="+pkg+" user="+user+" self="+self);
     Context context=null;
     try{context=(Context)XposedHelpers.getObjectField(p.thisObject,"mContext");}catch(Throwable ignored){}
     if(context==null||!"com.android.launcher".equals(context.getPackageName())){
      if(user>0&&user!=self)Log.i("WindowDeck","launcher_apps_skip package="+pkg+" reason=no_launcher_context");
      return;
     }
     int task=LiveWorkbenchState.diagnosticTask(pkg,user);
     HandoffDiagnostics.timeline(HandoffDiagnostics.Event.APP_START,"launcher_apps",task);
     if(user<=0||user==self){diagnosticRoute("native_passthrough",task);return;}
     Bundle selection=LiveWorkbenchState.launch(pkg,user);
     if(selection==null){diagnosticRoute("native_passthrough",task);Log.i("WindowDeck","launcher_apps_skip package="+pkg+" reason=no_session");return;}
     selection.putParcelable("selectedIntent",new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component));
     BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
     Object receiver=p.thisObject;java.lang.reflect.Member method=p.method;Object[] args=p.args.clone();
     try{
      context.sendOrderedBroadcast(new Intent(LAUNCH).setPackage("com.oplus.pscanvas").addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(selection),null,options.toBundle(),new BroadcastReceiver(){
       public void onReceive(Context c,Intent request){
        if(getResultCode()==1){diagnosticRoute("clone_embed",task);Log.i("WindowDeck","clone_launch_routed package="+pkg+" user="+user+" session="+selection.getLong("selectionSession"));return;}
        Log.i("WindowDeck","clone_launch_rejected package="+pkg+" user="+user);
        try{XposedBridge.invokeOriginalMethod(method,receiver,args);diagnosticRoute("native_fallback",task);Log.i("WindowDeck","clone_launch_fallback package="+pkg+" user="+user);}
        catch(Throwable e){diagnosticRoute("native_fallback_failed",task);Log.e("WindowDeck","clone_launch_fallback_failed",e);}
       }
      },new Handler(Looper.getMainLooper()),0,null,null);
      // The host owns the start now; leaving the ROM call in place would also open it fullscreen.
      p.setResult(null);
     }catch(Throwable e){diagnosticRoute("dispatch_failed_passthrough",task);Log.w("WindowDeck","clone_launch_dispatch_failed",e);}
    }
   });
   Log.i("WindowDeck","clone_launch_hook_ready");
  }catch(Throwable e){Log.w("WindowDeck","clone_launch_hook_failed",e);}
 }
}
