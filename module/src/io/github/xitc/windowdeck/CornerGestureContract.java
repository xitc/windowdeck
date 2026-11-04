package io.github.xitc.windowdeck;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.content.ContextWrapper;
import android.view.MotionEvent;
import android.view.InputEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolve C17 types from their owners, then validate before installing any hook. */
final class CornerGestureContract {
 final Class<?> consumer,simulator,assistant;
 final Method serviceInput,eventDisplay,input,start,build,crop,radius,apply,cancel,assistantInput,navigationBar,gestureStarted;
 final Method matrix,corners,clip,alpha;
 final Method displayGlobal,displayInfo;
 final Field displayRotation,displayWidth,displayHeight;
 final Field interaction,delegate;
 CornerGestureContract(ClassLoader loader,Class<?> handler) throws Exception {
  Class<?> service=loader.loadClass("com.android.quickstep.OplusBaseTouchInteractionService");
  consumer=consumer(service,handler);serviceInput=serviceInput(service);
  eventDisplay=returns(method(InputEvent.class,"getDisplayId"),int.class);
  Class<?> displays=loader.loadClass("android.hardware.display.DisplayManagerGlobal");
  displayGlobal=RomSymbols.method(displays,"getInstance");displayGlobal.setAccessible(true);
  require(Modifier.isStatic(displayGlobal.getModifiers())&&displayGlobal.getReturnType()==displays,"display manager");
  Class<?> info=loader.loadClass("android.view.DisplayInfo");
  displayInfo=returns(method(displays,"getDisplayInfo",int.class),info);
  displayRotation=field(info,"rotation");displayWidth=field(info,"logicalWidth");displayHeight=field(info,"logicalHeight");
  require(displayRotation.getType()==int.class&&displayWidth.getType()==int.class&&displayHeight.getType()==int.class,"display geometry");
  assistant=loader.loadClass("ca.d");
  Class<?> handle=field(handler,RomSymbols.SOURCE_HANDLES).getType().getComponentType();
  require(handle!=null,"remote target handle array");
  simulator=method(handle,RomSymbols.SOURCE_SIMULATOR).getReturnType();
  Class<?> params=method(handle,RomSymbols.SOURCE_PARAMS).getReturnType();
  input=returns(method(consumer,"onMotionEvent",MotionEvent.class),void.class);
  start=returns(method(consumer,"z",long.class,MotionEvent.class),void.class);
  interaction=field(consumer,"y");
  build=build(simulator,params);
  Class<?> builder=build.getParameterTypes()[0],target=build.getParameterTypes()[1];
  crop=returns(method(simulator,RomSymbols.SOURCE_CROP),RectF.class);
  radius=returns(method(simulator,RomSymbols.SOURCE_RADIUS),float.class);
  apply=returns(method(simulator,"b",params),void.class);cancel=returns(method(handler,"D0"),void.class);
  gestureStarted=returns(method(handler,"c0"),boolean.class);
  require(field(simulator,RomSymbols.SOURCE_MATRIX).getType()==Matrix.class,"simulator matrix");
  matrix=returns(method(builder,"i",Matrix.class),builder);
  corners=returns(method(builder,"e",float.class,float.class),void.class);
  clip=returns(method(builder,"n",Rect.class),builder);alpha=returns(method(builder,"b",float.class),builder);
  assistantInput=returns(method(assistant,"onMotionEvent",MotionEvent.class),void.class);delegate=field(assistant,"a");
  require(delegate.getType().isInterface()&&delegate.getType().isAssignableFrom(consumer),"assistant delegate");
  navigationBar=RomSymbols.method(loader.loadClass("com.android.launcher3.util.DisplayController"),"hasNavigationBar");
  require(Modifier.isStatic(navigationBar.getModifiers())&&navigationBar.getReturnType()==boolean.class,"navigation mode");
  navigationBar.setAccessible(true);
  require(field(target,RomSymbols.SOURCE_TASK).getType()==int.class
    &&field(target,RomSymbols.SOURCE_BOUNDS).getType()==Rect.class,"source task and bounds");
 }
 /** The unique framework InputEvent callback precedes C17's MotionEvent transforms. */
 private static Method serviceInput(Class<?> service) throws Exception {
  Method found=null;
  for(Method m:service.getDeclaredMethods()){
   Class<?>[] p=m.getParameterTypes();
   if(Modifier.isStatic(m.getModifiers())||m.getReturnType()!=void.class||p.length!=1||p[0]!=InputEvent.class)continue;
   require(found==null,"ambiguous service input");found=m;
  }
  require(found!=null,"service input");found.setAccessible(true);return found;
 }
 /** The service's ordinary-app factory exposes its concrete consumer without constructing it. */
 private static Class<?> consumer(Class<?> service,Class<?> handler) throws Exception {
  Class<?> found=null;
  for(Method factory:service.getDeclaredMethods()){
   Class<?> type=factory.getReturnType();
   if(Modifier.isStatic(factory.getModifiers())||factory.getParameterTypes().length!=1
     ||!ContextWrapper.class.isAssignableFrom(type)||Modifier.isAbstract(type.getModifiers()))continue;
   try{
    Class<?> interaction=field(type,"y").getType();
    if(interaction==Object.class||!interaction.isAssignableFrom(handler))continue;
    returns(method(interaction,"c0"),boolean.class);
    returns(method(type,"onMotionEvent",MotionEvent.class),void.class);
    returns(method(type,"z",long.class,MotionEvent.class),void.class);
   }catch(ReflectiveOperationException mismatch){continue;}
   require(found==null||found==type,"ambiguous input consumer factory");found=type;
  }
  require(found!=null,"ordinary-app input consumer factory");return found;
 }
 private static Method build(Class<?> simulator,Class<?> params) throws Exception {
  Method found=null;
  for(Method m:simulator.getDeclaredMethods()){
   Class<?>[] p=m.getParameterTypes();
   if(!m.getName().equals("f")||Modifier.isStatic(m.getModifiers())||m.getReturnType()!=void.class
     ||p.length!=3||p[2]!=params)continue;
   require(found==null,"ambiguous simulator transaction");found=m;
  }
  require(found!=null,"simulator transaction");found.setAccessible(true);return found;
 }
 private static Field field(Class<?> type,String name) throws Exception {
  Field f=RomSymbols.field(type,name);require(!Modifier.isStatic(f.getModifiers()),"instance field "+f);f.setAccessible(true);return f;
 }
 private static Method method(Class<?> type,String name,Class<?>...args) throws Exception {
  Method m=RomSymbols.method(type,name,args);require(!Modifier.isStatic(m.getModifiers()),"instance method "+m);m.setAccessible(true);return m;
 }
 private static Method returns(Method method,Class<?> type) throws ReflectiveOperationException {
  require(method.getReturnType()==type,"return type "+method);return method;
 }
 private static void require(boolean ok,String detail) throws ReflectiveOperationException {
  if(!ok)throw new ReflectiveOperationException("corner contract: "+detail);
 }
}
