package io.github.xitc.windowdeck;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/** Ownership lasts for this handler's lifetime, including a delayed end-target callback.
 * C17 creates a new handler for each gesture; never retain the handler or claim another one. */
final class GestureHandoffOwners {
 private final ArrayList<WeakReference<Object>> handlers=new ArrayList<>();
 synchronized boolean claim(Object handler,boolean coverUsable){
  if(handler==null||!coverUsable)return false;
  if(!owns(handler))handlers.add(new WeakReference<>(handler));
  return true;
 }
 synchronized boolean owns(Object handler){
  boolean found=false;
  for(int i=handlers.size()-1;i>=0;i--){
   Object owner=handlers.get(i).get();
   if(owner==null)handlers.remove(i);else if(owner==handler)found=true;
  }
  return handler!=null&&found;
 }
}
