package io.github.xitc.windowdeck;

import java.util.LinkedHashMap;

/** Authenticates a single host instance and orders its completed draw transactions. */
final class RotationFrameOrder {
 private final int uid,container;
 private final long instance;
 private long lastFrame;
 private int lastGeneration,lastTask=-1;
 RotationFrameOrder(int uid,int container,long instance){
  if(uid<0||container<0||instance==0)throw new IllegalArgumentException("invalid rotation host");
  this.uid=uid;this.container=container;this.instance=instance;
 }
 synchronized boolean accept(int caller,int container,long instance,int task,int generation,long frame){
  if(caller!=uid||container!=this.container||instance!=this.instance||task<0||generation<1
    ||frame<=lastFrame||generation<lastGeneration||(generation==lastGeneration&&task!=lastTask))return false;
  lastFrame=frame;lastGeneration=generation;lastTask=task;return true;
 }
 /** Buffer callbacks can arrive after the next draw has already been dispatched.
  * Retain ready draws until earlier owners have completed or delegated. */
 static final class Queue<T> {
  private static final class Entry<T> {final T value;boolean ready;Entry(T value){this.value=value;}}
  private final LinkedHashMap<Long,Entry<T>> entries=new LinkedHashMap<>();
  private long next;
  synchronized long add(T value){
   if(value==null||next==Long.MAX_VALUE)throw new IllegalStateException("invalid rotation draw");
   long frame=++next;entries.put(frame,new Entry<>(value));return frame;
  }
  synchronized boolean ready(long frame){Entry<T> entry=entries.get(frame);if(entry==null||entry.ready)return false;entry.ready=true;return true;}
  synchronized T poll(){
   if(entries.isEmpty())return null;
   java.util.Map.Entry<Long,Entry<T>> first=entries.entrySet().iterator().next();
   if(!first.getValue().ready)return null;
   entries.remove(first.getKey());return first.getValue().value;
  }
  synchronized boolean pending(){return !entries.isEmpty();}
 }
}
