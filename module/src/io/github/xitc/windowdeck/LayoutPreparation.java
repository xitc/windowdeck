package io.github.xitc.windowdeck;

/** One bounded preparation request. UI owns completion; the worker only reads captureAllowed. */
final class LayoutPreparation {
 static final long WAIT_MS=250;
 static final int MAX_EDGE=1024;
 enum Result { READY, TIMEOUT, STALE, CANCELLED }
 final long started,deadline;
 private final String sourceKey;
 private volatile boolean finished;
 LayoutPreparation(long now,String key){started=now;deadline=now+WAIT_MS;sourceKey=key;}
 boolean captureAllowed(long now){return !finished&&now<deadline;}
 Result finish(long now,String currentKey){
  if(finished)return Result.CANCELLED;
  finished=true;
  if(!sourceKey.equals(currentKey))return Result.STALE;
  return now>=deadline?Result.TIMEOUT:Result.READY;
 }
 void cancel(){finished=true;}
}
