package io.github.xitc.windowdeck;

import android.content.*;
import android.os.*;
import android.util.Log;
import android.view.SurfaceControl;
import java.lang.reflect.*;
import de.robv.android.xposed.*;

/** One expiring Shell registration for the exiting canvas task's display change.
 * Runs in the already scoped launcher; never replaces the global transition player. */
final class FullscreenRotationCut {
 private static final String TAG="WindowDeck",ACTION=BuildIdentity.APPLICATION_ID+".FULLSCREEN_ROTATION_CUT";
 private static final String REMOTE="android.window.IRemoteTransition",FINISH="android.window.IRemoteTransitionFinishedCallback";
 private static final String SHELL="com.android.wm.shell.shared.IShellTransitions";
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static ClassLoader launcherLoader;
 private static IBinder shellBinder;
 private static Lease active;
 private static boolean registered;
 interface Ready {void ready(IBinder lease);}
 static void installLauncher(ClassLoader loader) throws Exception {
  launcherLoader=loader;
  Class<?> service=loader.loadClass("com.android.quickstep.TouchInteractionService");boolean found=false;
  for(Class<?> nested:service.getDeclaredClasses()){
   if(nested.getSuperclass()==null||!nested.getSuperclass().getName().equals("com.oplus.quickstep.proxy.OplusAbsOverviewProxyImpl"))continue;
   Method initialize=nested.getDeclaredMethod("onInitialize",Bundle.class);
   XposedBridge.hookMethod(initialize,new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam p){
    IBinder binder=((Bundle)p.args[0]).getBinder(SHELL);MAIN.post(()->shellBinder=binder);
   }});found=true;
  }
  if(!found)throw new NoSuchMethodException("Shell initialization owner");
  XposedHelpers.findAndHookMethod(android.app.Application.class,"attach",Context.class,new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){register((Context)p.args[0]);}
  });
 }
 private static void register(Context context){
  if(registered)return;registered=true;
  context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
   try{
    int uid=c.getPackageManager().getApplicationInfo("com.oplus.pscanvas",0).uid;
    if(getSentFromUid()!=uid||uid/100000!=android.os.Process.myUid()/100000||intent.getIntExtra("version",-1)!=Version.CODE)return;
    int task=intent.getIntExtra("container",-1);if(task<0||shellBinder==null||!shellBinder.isBinderAlive())return;
    if(active!=null)active.close();
    Lease lease=new Lease(c,uid,task);lease.arm();active=lease;
    Bundle result=new Bundle();result.putBinder("lease",lease);setResultExtras(result);setResultCode(1);
   }catch(Throwable e){Log.w(TAG,"fullscreen_rotation_cut_unavailable",e);}
  }},new IntentFilter(ACTION),null,MAIN,Context.RECEIVER_EXPORTED);
 }
 static void arm(Context context,int task,Ready ready){
  Intent intent=new Intent(ACTION).setPackage("com.android.launcher").putExtra("container",task).putExtra("version",Version.CODE);
  context.sendOrderedBroadcast(intent,null,new BroadcastReceiver(){public void onReceive(Context c,Intent i){
   Bundle result=getResultExtras(false);ready.ready(getResultCode()==1&&result!=null?result.getBinder("lease"):null);
  }},MAIN,0,null,null);
 }
 static void release(IBinder lease){
  if(lease==null)return;
  Parcel data=Parcel.obtain();try{data.writeInterfaceToken(ACTION);lease.transact(IBinder.FIRST_CALL_TRANSACTION,data,null,IBinder.FLAG_ONEWAY);}
  catch(Throwable e){Log.w(TAG,"fullscreen_rotation_cut_release_failed",e);}finally{data.recycle();}
 }
 private static final class Lease extends Binder {
  final Context context;final int uid,task;final Runnable expiry=this::close;
  Object shell,remote;boolean closed;
  Lease(Context context,int uid,int task){this.context=context;this.uid=uid;this.task=task;}
  void arm() throws Exception {
   IBinder cookie=taskCookie();
   Class<?> api=launcherLoader.loadClass(SHELL),remoteType=Class.forName("android.window.RemoteTransition");
   shell=launcherLoader.loadClass(SHELL+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,shellBinder);
   Object runner=Class.forName(REMOTE+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,new Runner(this));
   remote=remoteType.getConstructor(Class.forName(REMOTE),String.class).newInstance(runner,"WindowDeckFullscreenRotationCut:"+task);
   Class<?> requirement=Class.forName("android.window.TransitionFilter$Requirement");
   Object filter=RotationCutFilter.create(Class.forName("android.window.TransitionFilter"),requirement,
     new ComponentName("com.oplus.pscanvas","com.oplus.pscanvas.canvasmode.canvas.ContainerActivity"),task,cookie);
   verifyParcel(filter,cookie);
   remoteType.getMethod("setFilter",filter.getClass()).invoke(remote,filter);
   api.getMethod("registerRemote",remoteType).invoke(shell,remote);
   MAIN.postDelayed(expiry,MotionSpec.COVER_TIMEOUT_MS);
   Log.i(TAG,"fullscreen_rotation_cut_armed container="+task+" version="+Version.NAME);
  }
  android.app.ActivityManager.RunningTaskInfo taskInfo(){
   android.app.ActivityManager manager=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
   for(android.app.ActivityManager.RunningTaskInfo info:manager.getRunningTasks(100))if(info.taskId==task&&info.topActivity!=null
     &&info.topActivity.equals(new ComponentName("com.oplus.pscanvas","com.oplus.pscanvas.canvasmode.canvas.ContainerActivity")))return info;
   return null;
  }
  IBinder taskCookie() throws Exception {
   if(taskInfo()==null)throw new IllegalStateException("canvas task changed");
   // A new cookie is written to this existing activity by the normal Recents API.
   // Verify it in the task report before using it as the Shell filter identity.
   IBinder cookie=new Binder();android.app.ActivityOptions options=android.app.ActivityOptions.makeCustomAnimation(context,0,0);
   options.getClass().getMethod("setLaunchCookie",IBinder.class).invoke(options,cookie);
   Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
   Object result=Class.forName("android.app.IActivityTaskManager").getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(service,task,options.toBundle());
   if(!(result instanceof Integer)||!TransitionPolicy.startAccepted((Integer)result))throw new IllegalStateException("canvas cookie request rejected");
   android.app.ActivityManager.RunningTaskInfo info=taskInfo();if(info==null)throw new IllegalStateException("canvas task disappeared");
   Object cookies=info.getClass().getField("launchCookies").get(info);
   if(!(cookies instanceof java.util.List)||!((java.util.List<?>)cookies).contains(cookie))throw new IllegalStateException("canvas cookie not applied");
   return cookie;
  }
  void verifyParcel(Object filter,IBinder cookie) throws Exception {
   Parcel parcel=Parcel.obtain();try{
    ((Parcelable)filter).writeToParcel(parcel,0);parcel.setDataPosition(0);
    Parcelable.Creator<?> creator=(Parcelable.Creator<?>)filter.getClass().getField("CREATOR").get(null);
    Object copy=creator.createFromParcel(parcel),requirements=copy.getClass().getField("mRequirements").get(copy);
    Object host=Array.get(requirements,0);
    if(!cookie.equals(host.getClass().getField("mLaunchCookie").get(host)))throw new IllegalStateException("exact task filter lost in parcel");
   }finally{parcel.recycle();}
  }
  void close(){
   if(closed)return;closed=true;MAIN.removeCallbacks(expiry);if(active==this)active=null;
   if(remote!=null&&shell!=null)try{launcherLoader.loadClass(SHELL).getMethod("unregisterRemote",remote.getClass()).invoke(shell,remote);}
   catch(Throwable e){Log.w(TAG,"fullscreen_rotation_cut_unregister_failed",e);}
  }
  @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
   if(code!=IBinder.FIRST_CALL_TRANSACTION)return super.onTransact(code,data,reply,flags);
   data.enforceInterface(ACTION);data.enforceNoDataAvail();if(Binder.getCallingUid()!=uid)throw new SecurityException("rotation cut owner");MAIN.post(this::close);return true;
  }
 }
 private static final class Runner extends Binder {
  final Lease lease;
  Runner(Lease lease){this.lease=lease;}
  @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
   if(code==IBinder.INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(REMOTE);return true;}
   data.enforceInterface(REMOTE);
   if(code==4){data.readStrongBinder();data.readBoolean();data.enforceNoDataAvail();MAIN.post(lease::close);return true;}
   if(code==5){data.enforceNoDataAvail();MAIN.post(lease::close);return true;}
   if(code!=1&&code!=2)return super.onTransact(code,data,reply,flags);
   Object info=null;SurfaceControl.Transaction transaction=null;IBinder callback=null;
   try{
    data.readStrongBinder();
    Parcelable.Creator<?> creator=(Parcelable.Creator<?>)Class.forName("android.window.TransitionInfo").getField("CREATOR").get(null);
    info=data.readTypedObject(creator);transaction=data.readTypedObject(SurfaceControl.Transaction.CREATOR);
    if(code==2)data.readStrongBinder();callback=data.readStrongBinder();data.enforceNoDataAvail();
    // The framework owns final bounds/reparent cleanup. Apply its start state,
    // then immediately finish: no ScreenRotationAnimation is constructed here.
    if(transaction!=null)transaction.apply();
    Log.i(TAG,"fullscreen_rotation_cut_applied container="+lease.task+" merged="+(code==2));
   }catch(Throwable e){Log.w(TAG,"fullscreen_rotation_cut_failed container="+lease.task,e);}
   finally{
    if(callback!=null){Parcel finished=Parcel.obtain();try{
     finished.writeInterfaceToken(FINISH);finished.writeTypedObject(null,0);finished.writeTypedObject(null,0);
     callback.transact(IBinder.FIRST_CALL_TRANSACTION,finished,null,IBinder.FLAG_ONEWAY);
    }catch(Throwable e){Log.w(TAG,"fullscreen_rotation_cut_finish_failed",e);}finally{finished.recycle();}}
    if(transaction!=null)transaction.close();
    if(info!=null)try{info.getClass().getMethod("releaseAllSurfaces").invoke(info);}catch(Throwable ignored){}
    MAIN.post(lease::close);
   }
   return true;
  }
 }
 private FullscreenRotationCut(){}
}
