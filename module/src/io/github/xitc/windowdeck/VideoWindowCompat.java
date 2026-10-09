package io.github.xitc.windowdeck;

import android.app.Activity;
import android.app.Application;
import android.app.BroadcastOptions;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.*;
import de.robv.android.xposed.*;

/** App-side multiwindow compatibility, authenticated by the live WindowDeck host. */
final class VideoWindowCompat {
 static final String QUERY="io.github.xitc.windowdeck.QUERY_VIDEO_WINDOW_COMPAT";
 static final String HOST_BINDER="videoCompatHost";
 private static volatile Bundle state;
 private static Handler main;
 private static boolean registered;
 private static IBinder linkedHost;
 private static IBinder.DeathRecipient hostDeath;
 private static final Set<Method> hooked=new HashSet<>();
 private static final Map<Activity,Boolean> activities=Collections.synchronizedMap(new WeakHashMap<>());
 private static final XC_MethodHook queryHook=new XC_MethodHook(){
  protected void beforeHookedMethod(MethodHookParam p){Activity a=(Activity)p.thisObject;if(applies(a)){mark(a);p.setResult(false);}}
 };
 private static final XC_MethodHook callbackHook=new XC_MethodHook(){
  protected void beforeHookedMethod(MethodHookParam p){Activity a=(Activity)p.thisObject;if(applies(a)){mark(a);p.args[0]=false;}}
 };
 static void install(){
  hookClass(Activity.class);
  XposedBridge.hookAllMethods(Activity.class,"attach",new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){
    Activity a=(Activity)p.thisObject;
    for(Class<?> c=a.getClass();c!=null&&c!=Activity.class;c=c.getSuperclass())hookClass(c);
    activities.put(a,false);register(a);
   }
  });
  XposedHelpers.findAndHookMethod(Application.class,"attach",Context.class,new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){register((Context)p.args[0]);}
  });
  XposedBridge.hookAllMethods(Activity.class,"onDestroy",new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){activities.remove((Activity)p.thisObject);}
  });
  Log.i("WindowDeck","video_window_compat_ready version="+Version.NAME+" process="+android.os.Process.myPid());
 }
 private static synchronized void hookClass(Class<?> type){
  for(Method m:type.getDeclaredMethods()){
   Class<?>[] args=m.getParameterTypes();
   boolean query=m.getName().equals("isInMultiWindowMode")&&args.length==0&&m.getReturnType()==boolean.class;
   boolean callback=m.getName().equals("onMultiWindowModeChanged")&&(args.length==1||(args.length==2&&args[1]==Configuration.class))&&args[0]==boolean.class;
   if((query||callback)&&hooked.add(m))XposedBridge.hookMethod(m,query?queryHook:callbackHook);
  }
 }
 private static void register(Context context){
  if(registered)return;
  Context app=context.getApplicationContext();if(app==null)app=context;
  main=new Handler(Looper.getMainLooper());
  app.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
   // System-supplied shared identity is not filtered by the app's package visibility.
   if("com.oplus.pscanvas".equals(getSentFromPackage())&&getSentFromUid()/100000==android.os.Process.myUid()/100000)accept(intent.getExtras());
  }},new IntentFilter(LiveWorkbenchState.UPDATE),null,main,Context.RECEIVER_EXPORTED);
  registered=true;
  BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
  app.sendOrderedBroadcast(new Intent(QUERY).setPackage("com.oplus.pscanvas").addFlags(Intent.FLAG_RECEIVER_FOREGROUND),null,options.toBundle(),new BroadcastReceiver(){
   public void onReceive(Context c,Intent intent){if(getResultCode()==1)accept(getResultExtras(false));}
  },main,0,null,null);
 }
 private static void accept(Bundle incoming){
  if(incoming==null)return;
  Bundle next=new Bundle(incoming);int count=next.getInt("count",-1);
  if(count<0||count>Caps.MAX_TASKS||next.getInt("containerTaskId",-1)<0)return;
  Bundle old=state;
  if(count==0&&(old==null||old.getInt("containerTaskId",-1)!=next.getInt("containerTaskId",-1)||old.getLong("instanceToken")!=next.getLong("instanceToken")))return;
  IBinder host=next.getBinder(HOST_BINDER);if(host==null||!host.isBinderAlive())return;
  if(!host.equals(linkedHost)){
   IBinder.DeathRecipient death=()->main.post(()->{Bundle current=state;if(current!=null&&host.equals(current.getBinder(HOST_BINDER))){state=null;reconcile();}});
   try{host.linkToDeath(death,0);}catch(RemoteException e){return;}
   if(linkedHost!=null&&hostDeath!=null)linkedHost.unlinkToDeath(hostDeath,0);
   linkedHost=host;hostDeath=death;
  }
  state=next;reconcile();
 }
 private static int scenario(Activity a){
  try{return XposedHelpers.getIntField(XposedHelpers.getObjectField(a,"mActivityExt"),"mScenario");}catch(Throwable e){return -1;}
 }
 private static boolean applies(Activity a){
  Bundle s=state;if(s==null)return false;
  try{IBinder host=s.getBinder(HOST_BINDER);
   return VideoWindowPolicy.applies(a.getPackageName(),a.getTaskId(),android.os.Process.myUid()/100000,scenario(a),s.getInt("containerTaskId",-1),s.getInt("count",-1),s.getInt("userId",-1),s.getStringArray("packages"),s.getIntArray("tasks"),host!=null&&host.isBinderAlive());
  }catch(Throwable e){return false;}
 }
 private static boolean rawMultiWindow(Activity a){
  Object ext=XposedHelpers.getObjectField(a,"mActivityExt");int scenario=XposedHelpers.getIntField(ext,"mScenario");
  return (scenario>0&&scenario!=3&&XposedHelpers.getBooleanField(ext,"mSupportSplitScreenWindowingMode"))||XposedHelpers.getBooleanField(a,"mIsInMultiWindowMode");
 }
 private static void mark(Activity a){
  if(!Boolean.TRUE.equals(activities.put(a,true)))Log.i("WindowDeck","video_window_compat task="+a.getTaskId()+" enabled=true scenario="+scenario(a));
 }
 /** Refresh app caches when membership arrives after resume, without changing ROM fields. */
 private static void reconcile(){
  ArrayList<Activity> snapshot;synchronized(activities){snapshot=new ArrayList<>(activities.keySet());}
  for(Activity a:snapshot){
   if(a==null||a.isDestroyed())continue;
   boolean compat=applies(a),previous=Boolean.TRUE.equals(activities.get(a));
   if(compat==previous)continue;
   activities.put(a,compat);
   try{a.onMultiWindowModeChanged(compat?false:rawMultiWindow(a),a.getResources().getConfiguration());
    Log.i("WindowDeck","video_window_compat task="+a.getTaskId()+" enabled="+compat+" scenario="+scenario(a));
   }catch(Throwable e){Log.w("WindowDeck","video_window_cache_refresh_failed",e);}
  }
 }
}
