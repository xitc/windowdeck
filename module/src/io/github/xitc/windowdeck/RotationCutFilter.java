package io.github.xitc.windowdeck;

import java.lang.reflect.Array;

/** Construct the complete filter before registration; missing exact-task support fails closed. */
final class RotationCutFilter {
 static Object create(Class<?> filterType,Class<?> requirementType,Object component,int task,Object cookie) throws ReflectiveOperationException {
  if(task<=0||component==null||cookie==null)throw new IllegalArgumentException("Missing canvas task identity");
  Object host=requirementType.getConstructor().newInstance(),display=requirementType.getConstructor().newInstance();
  set(host,"mTopActivity",component);set(host,"mMustBeTask",true);set(host,"mMustBeIndependent",false);set(host,"mModes",new int[]{6});
  set(host,"mLaunchCookie",cookie);
  set(display,"mFlags",32);set(display,"mModes",new int[]{6});
  Object requirements=Array.newInstance(requirementType,2);Array.set(requirements,0,host);Array.set(requirements,1,display);
  Object filter=filterType.getConstructor().newInstance();
  set(filter,"mTypeSet",new int[]{6});set(filter,"mRequirements",requirements);
  return filter;
 }
 private static void set(Object target,String name,Object value) throws ReflectiveOperationException {target.getClass().getField(name).set(target,value);}
 private RotationCutFilter(){}
}
