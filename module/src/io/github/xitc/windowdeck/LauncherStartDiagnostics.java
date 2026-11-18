package io.github.xitc.windowdeck;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;

/** Observe Launcher 17.5.5's app launch request and the actual callback it defers. */
final class LauncherStartDiagnostics {
 private static final String REQUEST="windowdeck_launch_request";
 private static final String PREVIOUS="windowdeck_launch_previous";
 private static final String TOUCH="windowdeck_icon_touch", CLICK="windowdeck_icon_click";
 private static final ThreadLocal<Request> active=new ThreadLocal<>();
 private static final ThreadLocal<View> touchDispatch=new ThreadLocal<>();
 private static final ThreadLocal<Click> clickDispatch=new ThreadLocal<>();

 private static final class Time {
  final long at,clock;
  Time(long at){
   this.at=at;
   clock=SystemClock.elapsedRealtime()-Math.max(0,SystemClock.uptimeMillis()-at);
  }
  void record(Request request,HandoffDiagnostics.Event event,String reason){
   HandoffDiagnostics.timeline(event,reason,request.task,request.id,at,clock,null);
  }
 }
 private static final class Touch {
  final long downTime;
  final Time down;
  Time terminal;
  String phase;
  boolean used;
  Touch(long downTime,Time down){this.downTime=downTime;this.down=down;}
 }
 private static final class Click {
  final View view;
  final Time time=new Time(SystemClock.uptimeMillis());
  final Time down,terminal;
  final String phase,reason;
  Click(View view,String reason,Touch touch){
   this.view=view;this.reason=reason;
   down=touch==null?null:touch.down;
   terminal=touch==null?null:touch.terminal;
   phase=touch==null?null:touch.phase;
  }
  void record(Request request){
   if(down!=null)down.record(request,HandoffDiagnostics.Event.APP_TOUCH,"icon_down");
   if(terminal!=null)terminal.record(request,HandoffDiagnostics.Event.APP_TOUCH,phase);
   time.record(request,HandoffDiagnostics.Event.APP_CLICK,reason);
  }
 }

 private static final class Request {
  final String id=UUID.randomUUID().toString();
  final long created=SystemClock.elapsedRealtime();
  final ComponentName component;
  final int task;
  Request(Intent intent){
   component=intent.getComponent();
   task=LiveWorkbenchState.diagnosticTask(component.getPackageName(),android.os.Process.myUid()/100000);
  }
  boolean matches(Intent intent){
   return intent!=null&&component.equals(intent.getComponent())&&SystemClock.elapsedRealtime()-created<=120_000;
  }
  void record(HandoffDiagnostics.Event event,String reason){
   HandoffDiagnostics.timeline(event,reason,task,id);
  }
 }

 static String currentId(Intent intent){
  Request value=forIntent(intent);
  return value==null?null:value.id;
 }
 private static Request forIntent(Intent intent){
  Request scoped=active.get();
  if(scoped!=null&&scoped.matches(intent))return scoped;
  if(intent==null)return null;
  Object saved=XposedHelpers.getAdditionalInstanceField(intent,REQUEST);
  return saved instanceof Request&&((Request)saved).matches(intent)?(Request)saved:null;
 }
 private static Request begin(Intent intent,String origin,Click click,ClassLoader loader){
  if(intent==null||intent.getComponent()==null||!Intent.ACTION_MAIN.equals(intent.getAction())
    ||!intent.hasCategory(Intent.CATEGORY_LAUNCHER))return null;
  Request value=new Request(intent);
  XposedHelpers.setAdditionalInstanceField(intent,REQUEST,value);
  if(click!=null)click.record(value);
  long at=SystemClock.uptimeMillis(),clock=SystemClock.elapsedRealtime();
  ArrayList<String> frames=new ArrayList<>(8);
  for(StackTraceElement frame:Thread.currentThread().getStackTrace()){
   String type=frame.getClassName(),name=type+"#"+frame.getMethodName();
   if(!HandoffDiagnostics.launcherFrame(name))continue;
   // Obfuscated classes have no package prefix. Use the originating loader to
   // keep those frames from Launcher while excluding framework and Hook frames.
   try{if(Class.forName(type,false,loader).getClassLoader()==loader)frames.add(name);}
   catch(ClassNotFoundException|LinkageError|SecurityException ignored){}
  }
  if(frames.size()>8)frames.subList(4,frames.size()-4).clear();
  HandoffDiagnostics.timeline(HandoffDiagnostics.Event.APP_REQUEST,origin,value.task,value.id,
    at,clock,frames.toArray(new String[0]));
  return value;
 }

 static void install(Class<?> handler){
  ArrayList<XC_MethodHook.Unhook> hooks=new ArrayList<>();
  try{
   if(!"h84".equals(RomSymbols.field(handler,"t").getType().getName()))return;
   ClassLoader loader=handler.getClassLoader();
   Class<?> launcher=loader.loadClass("com.android.launcher3.Launcher");
   Class<?> concrete=loader.loadClass("com.android.launcher.Launcher");
   Class<?> item=loader.loadClass("com.android.launcher3.model.data.ItemInfo");
   Class<?> launch=loader.loadClass("he5"), coordinator=loader.loadClass("ee5");
   Class<?> controller=loader.loadClass("wi"), deferred=loader.loadClass("pd0");
   Class<?> workspace=loader.loadClass("t00"), shortcut=loader.loadClass("s00");
   Class<?> appLauncher=loader.loadClass("com.android.launcher3.views.AppLauncher");
   Field intent=typedField(launch,"b",Intent.class);
   Field kind=typedField(launch,"a",loader.loadClass("com.android.launcher.launch.LaunchType"));
   Field queued=typedField(controller,"o",Runnable.class);
   Method submit=coordinator.getDeclaredMethod("b",launch);
   Method check=controller.getDeclaredMethod("h",Context.class,Intent.class,Supplier.class,Runnable.class);
   Method icon=launcher.getDeclaredMethod("executeIconLaunch",launch,View.class,Intent.class,item,concrete);
   Method iconContinuation=launcher.getDeclaredMethod("d",launcher,View.class,Intent.class,item,Object.class);
   if(submit.getReturnType()!=void.class||check.getReturnType()!=boolean.class
     ||icon.getReturnType()!=boolean.class||iconContinuation.getReturnType()!=void.class)
    throw new NoSuchMethodException("launch diagnostic return types");

   hooks.add(XposedBridge.hookMethod(submit,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
     if(p.args[0]==null)return;
     Object type=kind.get(p.args[0]);
     String origin=type instanceof Enum<?>&&"ICON".equals(((Enum<?>)type).name())?"icon":"coordinator";
     Object click=XposedHelpers.removeAdditionalInstanceField(p.args[0],CLICK);
     begin((Intent)intent.get(p.args[0]),origin,click instanceof Click?(Click)click:null,loader);
    }
   }));
   hooks.add(XposedBridge.hookMethod(icon,intentScope(2)));
   hooks.add(XposedBridge.hookMethod(iconContinuation,intentScope(2)));
   hooks.add(XposedBridge.hookMethod(check,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam p){
     Intent target=(Intent)p.args[1];
     Request request=forIntent(target);
     if(request==null)request=begin(target,"transition_check",null,loader);
     if(request!=null)p.setObjectExtra(REQUEST,request);
    }
    @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
     Request request=(Request)p.getObjectExtra(REQUEST);
     Object callback=p.args[3];
     if(request!=null&&!p.hasThrowable()&&Boolean.TRUE.equals(p.getResult())&&deferred.isInstance(callback)
       &&queued.get(p.thisObject)==callback){
      XposedHelpers.setAdditionalInstanceField(callback,REQUEST,request);
      request.record(HandoffDiagnostics.Event.APP_DEFERRED,"transition_queue");
     }
    }
   }));
   hooks.add(XposedBridge.hookMethod(deferred.getDeclaredMethod("run"),callbackScope("transition_queue")));
   hooks.add(XposedBridge.hookMethod(workspace.getDeclaredConstructor(Bundle.class,Context.class,Intent.class),rememberDispatch(2)));
   hooks.add(XposedBridge.hookMethod(shortcut.getDeclaredConstructor(appLauncher,Intent.class,Bundle.class,item),rememberDispatch(1)));
   hooks.add(XposedBridge.hookMethod(workspace.getDeclaredMethod("run"),callbackScope("workspace_dispatch")));
   hooks.add(XposedBridge.hookMethod(shortcut.getDeclaredMethod("run"),callbackScope("shortcut_dispatch")));
   installInput(loader,launcher,item,launch);
   Log.i("WindowDeck","launcher_start_diagnostics_ready");
  }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
   for(XC_MethodHook.Unhook hook:hooks)hook.unhook();
   Log.w("WindowDeck","launcher_start_diagnostics_unavailable",e);
  }
 }
 private static void installInput(ClassLoader loader,Class<?> launcher,Class<?> item,Class<?> launch){
  ArrayList<XC_MethodHook.Unhook> hooks=new ArrayList<>();
  try{
   installInputHooks(loader,launcher,item,launch,typedField(launch,"d",View.class),hooks);
   Log.i("WindowDeck","launcher_start_input_diagnostics_ready");
  }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
   for(XC_MethodHook.Unhook hook:hooks)hook.unhook();
   Log.w("WindowDeck","launcher_start_input_diagnostics_unavailable",e);
  }
 }
 private static void installInputHooks(ClassLoader loader,Class<?> launcher,Class<?> item,Class<?> launch,
   Field launchView,ArrayList<XC_MethodHook.Unhook> hooks) throws ReflectiveOperationException {
  Class<?> bubble=loader.loadClass("com.android.launcher3.OplusBubbleTextView");
  Class<?> oplusClick=loader.loadClass("com.android.launcher3.touch.OplusItemClickHandler");
  Class<?> itemClick=loader.loadClass("com.android.launcher3.touch.ItemClickHandler");
  hooks.add(XposedBridge.hookMethod(bubble.getDeclaredMethod("dispatchTouchEvent",MotionEvent.class),new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){
    MotionEvent event=(MotionEvent)p.args[0];
    if(event==null)return;
    View view=(View)p.thisObject;
    int action=event.getActionMasked();
    if(action==MotionEvent.ACTION_DOWN){
     XposedHelpers.setAdditionalInstanceField(view,TOUCH,new Touch(event.getDownTime(),new Time(event.getEventTime())));
    }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
     Touch observed=touch(view);
     if(observed==null||observed.downTime!=event.getDownTime())observed=new Touch(event.getDownTime(),null);
     observed.terminal=new Time(event.getEventTime());
     observed.phase=action==MotionEvent.ACTION_UP?"icon_up":"icon_cancel";
     XposedHelpers.setAdditionalInstanceField(view,TOUCH,observed);
    }else return;
    p.setObjectExtra(PREVIOUS,touchDispatch.get());
    p.setObjectExtra(TOUCH,Boolean.TRUE);
    touchDispatch.set(view);
   }
   @Override protected void afterHookedMethod(MethodHookParam p){
    if(p.getObjectExtra(TOUCH)==null)return;
    View previous=(View)p.getObjectExtra(PREVIOUS);
    if(previous==null)touchDispatch.remove();else touchDispatch.set(previous);
   }
  }));
  hooks.add(XposedBridge.hookMethod(oplusClick.getDeclaredMethod("onClick",View.class),clickScope("oplus_icon")));
  hooks.add(XposedBridge.hookMethod(itemClick.getDeclaredMethod("startAppShortcutOrInfoActivity",View.class,item,launcher),
    clickScope("shortcut_or_app")));
  for(java.lang.reflect.Constructor<?> constructor:launch.getDeclaredConstructors()){
   hooks.add(XposedBridge.hookMethod(constructor,new XC_MethodHook(){
    @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
     Click click=clickDispatch.get();
     if(click!=null&&!p.hasThrowable()&&launchView.get(p.thisObject)==click.view)
      XposedHelpers.setAdditionalInstanceField(p.thisObject,CLICK,click);
    }
   }));
  }
 }
 private static Touch touch(View view){
  Object value=XposedHelpers.getAdditionalInstanceField(view,TOUCH);
  return value instanceof Touch?(Touch)value:null;
 }
 private static XC_MethodHook clickScope(String entry){
  return new XC_MethodHook(){
   @Override protected void beforeHookedMethod(MethodHookParam p){
    View view=(View)p.args[0];
    Click previous=clickDispatch.get();
    if(view==null||(previous!=null&&previous.view==view))return;
    Touch observed=touch(view);
    String relation="without_touch";
    if(observed!=null&&!observed.used){
     if(touchDispatch.get()==view)relation="touch_dispatch";
     else if("icon_up".equals(observed.phase)&&SystemClock.uptimeMillis()-observed.terminal.at<=500
       &&inPostedViewClick())relation="posted_click";
    }
    if("without_touch".equals(relation))observed=null;
    else observed.used=true;
    p.setObjectExtra(PREVIOUS,previous);
    p.setObjectExtra(CLICK,Boolean.TRUE);
    clickDispatch.set(new Click(view,entry+"_"+relation,observed));
   }
   @Override protected void afterHookedMethod(MethodHookParam p){
    if(p.getObjectExtra(CLICK)==null)return;
    Click previous=(Click)p.getObjectExtra(PREVIOUS);
    if(previous==null)clickDispatch.remove();else clickDispatch.set(previous);
   }
  };
 }
 private static boolean inPostedViewClick(){
  for(StackTraceElement frame:Thread.currentThread().getStackTrace())
   if("android.view.View$PerformClick".equals(frame.getClassName())&&"run".equals(frame.getMethodName()))return true;
  return false;
 }
 private static Field typedField(Class<?> type,String name,Class<?> expected) throws NoSuchFieldException {
  Field field=RomSymbols.field(type,name);
  if(field.getType()!=expected)throw new NoSuchFieldException(type.getName()+"."+name);
  field.setAccessible(true);
  return field;
 }
 private abstract static class RequestScope extends XC_MethodHook {
  abstract Request request(MethodHookParam p);
  @Override protected void beforeHookedMethod(MethodHookParam p){
   Request value=request(p);
   if(value==null)return;
   p.setObjectExtra(PREVIOUS,active.get());
   p.setObjectExtra(REQUEST,value);
   active.set(value);
  }
  @Override protected void afterHookedMethod(MethodHookParam p){
   if(p.getObjectExtra(REQUEST)==null)return;
   Request previous=(Request)p.getObjectExtra(PREVIOUS);
   if(previous==null)active.remove();else active.set(previous);
  }
 }
 private static XC_MethodHook intentScope(int index){
  return new RequestScope(){
   @Override Request request(MethodHookParam p){return forIntent((Intent)p.args[index]);}
  };
 }
 private static XC_MethodHook callbackScope(String reason){
  return new RequestScope(){
   @Override Request request(MethodHookParam p){
    Object value=XposedHelpers.getAdditionalInstanceField(p.thisObject,REQUEST);
    if(!(value instanceof Request))return null;
    Request request=(Request)value;
    request.record(HandoffDiagnostics.Event.APP_CALLBACK,reason);
    return request;
   }
  };
 }
 private static XC_MethodHook rememberDispatch(int index){
  return new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p){
    Request request=forIntent((Intent)p.args[index]);
    if(request!=null&&!p.hasThrowable())XposedHelpers.setAdditionalInstanceField(p.thisObject,REQUEST,request);
   }
  };
 }
}
