package io.github.xitc.windowdeck;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.util.Log;
import de.robv.android.xposed.*;

/** Route picker clicks before the launcher sends a fullscreen app start. */
final class LauncherExistingAppHook {
 static final String SELECT=(BuildIdentity.APPLICATION_ID+".SELECT_EXISTING_CARD");
 static final String LAUNCH=(BuildIdentity.APPLICATION_ID+".LAUNCH_PICKER_APP");
 static int userOf(android.os.UserHandle handle){try{return (Integer)android.os.UserHandle.class.getMethod("getIdentifier").invoke(handle);}catch(Throwable e){return 0;}}
 static void install(){
  XposedHelpers.findAndHookMethod(Instrumentation.class,"execStartActivity",Context.class,IBinder.class,IBinder.class,Activity.class,Intent.class,int.class,Bundle.class,new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam p){
    Context context=(Context)p.args[0];Intent intent=(Intent)p.args[4];
    if(context==null||!"com.android.launcher".equals(context.getPackageName())||intent==null||intent.getComponent()==null||(Integer)p.args[5]>=0||!Intent.ACTION_MAIN.equals(intent.getAction())||!intent.hasCategory(Intent.CATEGORY_LAUNCHER)||intent.getData()!=null||intent.getSelector()!=null||intent.getClipData()!=null||intent.hasExtra(Intent.EXTRA_USER))return;
    int user=android.os.Process.myUid()/100000;String pkg=intent.getComponent().getPackageName();
    Bundle existing=LiveWorkbenchState.existing(pkg,user);
    final boolean select=existing!=null;
    final Bundle selection=select?existing:LiveWorkbenchState.launch(pkg,user);
    if(selection==null)return;
    if(!select)selection.putParcelable("selectedIntent",new Intent(intent));
    Object receiver=p.thisObject;java.lang.reflect.Member method=p.method;Object[] args=p.args.clone();args[4]=new Intent(intent);if(args[6]!=null)args[6]=new Bundle((Bundle)args[6]);
    try{
     BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
     context.sendOrderedBroadcast(new Intent(select?SELECT:LAUNCH).setPackage("com.oplus.pscanvas").addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(selection),null,options.toBundle(),new BroadcastReceiver(){
      public void onReceive(Context c,Intent request){
       if(getResultCode()==1){Log.i("WindowDeck",(select?"existing_launch_routed task="+selection.getInt("selectedTask"):"picker_launch_routed package="+pkg)+" session="+selection.getLong("selectionSession"));return;}
       // A stale host/session must not swallow an ordinary app launch. The
       // original method bypasses this hook, so fallback happens exactly once.
       try{XposedBridge.invokeOriginalMethod(method,receiver,args);Log.i("WindowDeck","existing_launch_fallback result="+getResultCode());}
       catch(Throwable e){Log.e("WindowDeck","existing_launch_fallback_failed",e);}
      }
     },new Handler(Looper.getMainLooper()),0,null,null);
     p.setResult(null);
    }catch(Throwable e){Log.w("WindowDeck","existing_launch_dispatch_failed",e);}
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
     if(user<=0||user==self)return;
     Context context=null;
     try{context=(Context)XposedHelpers.getObjectField(p.thisObject,"mContext");}catch(Throwable ignored){}
     if(context==null||!"com.android.launcher".equals(context.getPackageName())){Log.i("WindowDeck","launcher_apps_skip package="+pkg+" reason=no_launcher_context");return;}
     Bundle selection=LiveWorkbenchState.launch(pkg,user);
     if(selection==null){Log.i("WindowDeck","launcher_apps_skip package="+pkg+" reason=no_session");return;}
     selection.putParcelable("selectedIntent",new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component));
     BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
     Object receiver=p.thisObject;java.lang.reflect.Member method=p.method;Object[] args=p.args.clone();
     try{
      context.sendOrderedBroadcast(new Intent(LAUNCH).setPackage("com.oplus.pscanvas").addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtras(selection),null,options.toBundle(),new BroadcastReceiver(){
       public void onReceive(Context c,Intent request){
        if(getResultCode()==1){Log.i("WindowDeck","clone_launch_routed package="+pkg+" user="+user+" session="+selection.getLong("selectionSession"));return;}
        Log.i("WindowDeck","clone_launch_rejected package="+pkg+" user="+user);
        try{XposedBridge.invokeOriginalMethod(method,receiver,args);Log.i("WindowDeck","clone_launch_fallback package="+pkg+" user="+user);}
        catch(Throwable e){Log.e("WindowDeck","clone_launch_fallback_failed",e);}
       }
      },new Handler(Looper.getMainLooper()),0,null,null);
      // The host owns the start now; leaving the ROM call in place would also open it fullscreen.
      p.setResult(null);
     }catch(Throwable e){Log.w("WindowDeck","clone_launch_dispatch_failed",e);}
    }
   });
   Log.i("WindowDeck","clone_launch_hook_ready");
  }catch(Throwable e){Log.w("WindowDeck","clone_launch_hook_failed",e);}
 }
}
