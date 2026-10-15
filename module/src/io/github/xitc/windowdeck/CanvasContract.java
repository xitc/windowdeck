package io.github.xitc.windowdeck;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

/** The pscanvas embedding boundary. Resolution never invokes a ROM service. */
final class CanvasContract {
 static final String TASK_VIEW="com.oplus.flexiblewindow.FlexibleTaskView";
 static final String WINDOW_MANAGER="com.oplus.flexiblewindow.FlexibleWindowManager";
 private static volatile CanvasContract cached;
 final Class<?> view,manager,listener;
 final Constructor<?> constructor;
 final Method getInstance,getService,getTasks;
 private final ClassLoader loader;
 final Field interceptInput,rotateTaskLeash,cornerRadius,taskLeash,reparentAlign,superLocked,viewTransaction,drawState,taskInfo;
 private final Map<String,Method> viewMethods=new HashMap<>(),managerMethods=new HashMap<>();

 private CanvasContract(ClassLoader loader) throws ReflectiveOperationException {
  this.loader=loader;
  view=Class.forName(TASK_VIEW,false,loader);
  manager=Class.forName(WINDOW_MANAGER,false,loader);
  listener=Class.forName(TASK_VIEW+"$Listener",false,loader);
  require(SurfaceView.class.isAssignableFrom(view),TASK_VIEW+" must extend SurfaceView");
  require(listener.isInterface(),TASK_VIEW+"$Listener must be an interface");
  constructor=view.getConstructor(Context.class);
  interceptInput=field("mInterceptInputEvent",boolean.class);
  rotateTaskLeash=field("mNeedRotateTaskLeash",boolean.class);
  cornerRadius=field("mCornerRadius",float.class);
  taskLeash=field("mTaskLeash",SurfaceControl.class);
  reparentAlign=field("mReparentAlign",int.class);
  superLocked=field("mSuperLocked",boolean.class);
  viewTransaction=field("mTransaction",SurfaceControl.Transaction.class);
  drawState=field("mDrawState",int.class);
  taskInfo=field("mTaskInfo",ActivityManager.RunningTaskInfo.class);
  viewMethod("resize",void.class,Rect.class);
  viewMethod("setListener",void.class,Executor.class,listener);
  viewMethod("init",void.class,Bundle.class);
  viewMethod("setEnforceStart",void.class,boolean.class);
  viewMethod("setNoNeedStartEmbedded",void.class,boolean.class);
  viewMethod("release",void.class);
  viewMethod("releaseExtraView",void.class);
  viewMethod("reparent",void.class);
  viewMethod("interceptBackPressedOnTaskRoot",void.class,boolean.class);
  managerMethod("isAppSupportPocketStudio",boolean.class,Intent.class,int.class);
  managerMethod("removeEmbeddedContainerTask",void.class,int.class,int.class);
  managerMethod("setFocusAppForEmbeddedTask",void.class,int.class);
  getInstance=method(manager,"getInstance",manager,true);
  Class<?> atm=Class.forName("android.app.ActivityTaskManager",false,loader);
  Class<?> service=Class.forName("android.app.IActivityTaskManager",false,loader);
  getService=method(atm,"getService",service,true);
  getTasks=method(service,"getTasks",List.class,false,int.class,boolean.class,boolean.class,int.class);
 }
 static synchronized CanvasContract load(ClassLoader loader) throws ReflectiveOperationException {
  if(cached!=null&&cached.loader==loader)return cached;
  CanvasContract result=new CanvasContract(loader);
  cached=result;return result;
 }
 static CanvasContract require(){
  CanvasContract result=cached;
  if(result==null)throw new IllegalStateException("pscanvas contract was not validated");
  return result;
 }
 Method viewMethod(String name){return Objects.requireNonNull(viewMethods.get(name),name);}
 Method managerMethod(String name){return Objects.requireNonNull(managerMethods.get(name),name);}
 List<?> tasks() throws ReflectiveOperationException {
  return (List<?>)getTasks.invoke(getService.invoke(null),100,false,false,0);
 }
 /** Optional visual/recovery members retain their existing guarded fallback. */
 Method optionalViewMethod(String name,Class<?>... args) throws ReflectiveOperationException {
  Method method=view.getDeclaredMethod(name,args);method.setAccessible(true);return method;
 }
 Field optionalViewField(String name) throws ReflectiveOperationException {
  Field field=view.getDeclaredField(name);field.setAccessible(true);return field;
 }
 private Field field(String name,Class<?> type) throws ReflectiveOperationException {
  Field field=view.getDeclaredField(name);
  require(field.getType()==type&&!Modifier.isStatic(field.getModifiers()),TASK_VIEW+"."+name+" expected instance "+type.getName()+", actual "+field);
  field.setAccessible(true);return field;
 }
 private void viewMethod(String name,Class<?> result,Class<?>... args) throws ReflectiveOperationException {
  viewMethods.put(name,method(view,name,result,false,args));
 }
 private void managerMethod(String name,Class<?> result,Class<?>... args) throws ReflectiveOperationException {
  managerMethods.put(name,method(manager,name,result,false,args));
 }
 private static Method method(Class<?> owner,String name,Class<?> result,boolean isStatic,Class<?>... args) throws ReflectiveOperationException {
  Method method=owner.getMethod(name,args);
  require(Modifier.isStatic(method.getModifiers())==isStatic&&result.isAssignableFrom(method.getReturnType()),
   owner.getName()+"."+name+" expected "+result.getName()+" static="+isStatic+", actual "+method);
  return method;
 }
 private static void require(boolean condition,String detail) throws ReflectiveOperationException {
  if(!condition)throw new ReflectiveOperationException(detail);
 }
}
