package io.github.xitc.windowdeck;

import java.util.function.BiPredicate;

/** Geometry may travel before live pixels; background never implies live readiness. */
final class HandoffPreparation<T> {
 /** PREPARED may precede draw/fit for the original task in a stable single-card layout. */
 static boolean singleTask(int count,int task,int sourceTask,boolean geometryReady){
  return count==1&&task>=0&&task==sourceTask&&geometryReady;
 }
 enum Event { PREPARED, BACKGROUND, READY }
 enum Action { WAIT, START, START_LIVE, BACKGROUND, LIVE, IGNORE, REJECT }
 private final BiPredicate<T,T> same;
 private T geometry;
 private boolean started;
 HandoffPreparation(BiPredicate<T,T> same){this.same=same;}
 Action receive(Event event,T data,boolean safe){
  if(data==null)return Action.REJECT;
  if(geometry!=null&&!same.test(geometry,data))return Action.REJECT;
  if(event==Event.BACKGROUND&&geometry==null)return Action.IGNORE;
  geometry=data;
  if(started){
   if(event==Event.READY)return Action.LIVE;
   return event==Event.BACKGROUND?Action.BACKGROUND:Action.IGNORE;
  }
  if(event!=Event.READY&&!safe)return Action.WAIT;
  started=true;
  return event==Event.READY?Action.START_LIVE:Action.START;
 }
}
