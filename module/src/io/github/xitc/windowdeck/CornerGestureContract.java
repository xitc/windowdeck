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
 final Field interaction,delegate,panel,handles,simulatorField,paramsField,manager,active,targetTask,targetBounds,sourceMatrix;
 final Method simulatorGetter,paramsGetter,controllerGetter,finish,finishDirection,runningTask;
 final Field gestureState;
 final boolean modern;
 CornerGestureContract(ClassLoader loader,Class<?> handler) throws Exception {
  this(loader,handler,null);
 }
 CornerGestureContract(ClassLoader loader,Class<?> handler,Field modernPanel) throws Exception {
  modern=modernPanel!=null;
  Class<?> recents=modern?field(handler,"V").getType():null;
  boolean newer=modern&&recents.getName().equals("lz8");
  if(modern&&!newer&&!recents.getName().equals("rz8"))throw new NoSuchFieldException("17.5 Recents controller");
  Class<?> service=modern?loader.loadClass("com.android.quickstep.TouchInteractionService").getSuperclass():
   loader.loadClass("com.android.quickstep.OplusBaseTouchInteractionService");
  if(modern)require(service.getName().equals(newer?"q97":"y97"),"17.5 input service");
  consumer=modern?loader.loadClass(newer?"wj7":"gk7"):consumer(service,handler);
  serviceInput=modern?returns(method(service,"A",InputEvent.class),void.class):serviceInput(service);
  panel=modernPanel;
  eventDisplay=returns(method(InputEvent.class,"getDisplayId"),int.class);
  Class<?> displays=loader.loadClass("android.hardware.display.DisplayManagerGlobal");
  displayGlobal=RomSymbols.method(displays,"getInstance");displayGlobal.setAccessible(true);
  require(Modifier.isStatic(displayGlobal.getModifiers())&&displayGlobal.getReturnType()==displays,"display manager");
  Class<?> info=loader.loadClass("android.view.DisplayInfo");
  displayInfo=returns(method(displays,"getDisplayInfo",int.class),info);
  displayRotation=field(info,"rotation");displayWidth=field(info,"logicalWidth");displayHeight=field(info,"logicalHeight");
  require(displayRotation.getType()==int.class&&displayWidth.getType()==int.class&&displayHeight.getType()==int.class,"display geometry");
  assistant=loader.loadClass(modern?(newer?"w17":"c27"):"ca.d");
  handles=field(handler,modern?"u":RomSymbols.SOURCE_HANDLES);
  Class<?> handle=handles.getType().getComponentType();
  require(handle!=null,"remote target handle array");
  simulatorField=modern?field(handle,"a"):null;
  paramsField=modern?field(handle,"b"):null;
  simulatorGetter=modern?null:method(handle,RomSymbols.SOURCE_SIMULATOR);
  paramsGetter=modern?null:method(handle,RomSymbols.SOURCE_PARAMS);
  simulator=modern?simulatorField.getType():simulatorGetter.getReturnType();
  Class<?> params=modern?paramsField.getType():paramsGetter.getReturnType();
  input=returns(method(consumer,"onMotionEvent",MotionEvent.class),void.class);
  start=returns(method(consumer,modern?"y":"z",long.class,MotionEvent.class),void.class);
  interaction=field(consumer,modern?"E":"y");
  require(ContextWrapper.class.isAssignableFrom(consumer)&&interaction.getType().isAssignableFrom(handler),"input handler");
  build=build(simulator,params,modern?"e":"f");
  Class<?> builder=build.getParameterTypes()[0],target=build.getParameterTypes()[1];
  crop=modern?null:returns(method(simulator,RomSymbols.SOURCE_CROP),RectF.class);
  radius=modern?null:returns(method(simulator,RomSymbols.SOURCE_RADIUS),float.class);
  apply=modern?returns(method(simulator,"i",params,boolean.class),void.class):returns(method(simulator,"b",params),void.class);
  cancel=returns(method(handler,modern?"X":"D0"),void.class);
  gestureStarted=returns(method(handler,modern?"H":"c0"),boolean.class);
  sourceMatrix=field(simulator,modern?"x":RomSymbols.SOURCE_MATRIX);
  require(sourceMatrix.getType()==Matrix.class,"simulator matrix");
  matrix=returns(method(builder,"i",Matrix.class),builder);
  corners=returns(method(builder,"e",float.class,float.class),void.class);
  clip=returns(method(builder,"n",Rect.class),builder);alpha=returns(method(builder,"b",float.class),builder);
  assistantInput=returns(method(assistant,"onMotionEvent",MotionEvent.class),void.class);delegate=field(assistant,modern?"c":"a");
  require(delegate.getType().isInterface()&&delegate.getType().isAssignableFrom(consumer),"assistant delegate");
  gestureState=modern?field(handler,"t"):null;
  runningTask=modern?returns(method(gestureState.getType(),"c"),int.class):null;
  manager=field(handler,modern?"m0":RomSymbols.HANDLER_TASK_ANIM);
  active=modern?field(manager.getType(),"B"):null;
  require(!modern||active.getType()==recents,"active Recents controller");
  controllerGetter=modern?null:method(manager.getType(),RomSymbols.TASK_ANIM_CONTROLLER);
  finishDirection=modern?returns(method(recents,"o",boolean.class),void.class):null;
  finish=modern?returns(method(manager.getType(),"r",boolean.class),void.class):null;
  navigationBar=RomSymbols.method(loader.loadClass("com.android.launcher3.util.DisplayController"),"hasNavigationBar");
  require(Modifier.isStatic(navigationBar.getModifiers())&&navigationBar.getReturnType()==boolean.class,"navigation mode");
  navigationBar.setAccessible(true);
  targetTask=field(target,modern?"a":RomSymbols.SOURCE_TASK);
  targetBounds=field(target,modern?"i":RomSymbols.SOURCE_BOUNDS);
  require(targetTask.getType()==int.class&&targetBounds.getType()==Rect.class,"source task and bounds");
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
 private static Method build(Class<?> simulator,Class<?> params,String name) throws Exception {
  Method found=null;
  for(Method m:simulator.getDeclaredMethods()){
   Class<?>[] p=m.getParameterTypes();
   if(!m.getName().equals(name)||Modifier.isStatic(m.getModifiers())||m.getReturnType()!=void.class
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
