package io.github.xitc.windowdeck;

/** One bounded entrance. A stale callback can never start or complete a later run. */
final class EntranceRun {
 enum Phase { IDLE, WAITING, RUNNING, SETTLING, COMPLETED, CANCELLED, TIMED_OUT }
 private int generation;
 private long deadline;
 private Phase phase=Phase.IDLE;
 int begin(long now,long waitMs){generation++;phase=Phase.WAITING;deadline=now+waitMs;return generation;}
 boolean waiting(int token){return token==generation&&phase==Phase.WAITING;}
 boolean running(int token){return token==generation&&phase==Phase.RUNNING;}
 boolean start(int token,long now,long runMs){
  if(!waiting(token)||now>=deadline)return false;
  phase=Phase.RUNNING;deadline=now+runMs;return true;
 }
 boolean settling(int token){return token==generation&&phase==Phase.SETTLING;}
 boolean settle(int token,long now,long settleMs){
  if(!running(token)||now>=deadline)return false;
  phase=Phase.SETTLING;deadline=now+settleMs;return true;
 }
 boolean complete(int token,long now){if(!settling(token)||now>=deadline)return false;phase=Phase.COMPLETED;return true;}
 boolean timeout(int token,long now){
  if(token!=generation||(phase!=Phase.WAITING&&phase!=Phase.RUNNING&&phase!=Phase.SETTLING)||now<deadline)return false;
  phase=Phase.TIMED_OUT;return true;
 }
 void cancel(){if(phase==Phase.WAITING||phase==Phase.RUNNING||phase==Phase.SETTLING)phase=Phase.CANCELLED;}
 long remaining(long now){return Math.max(0,deadline-now);}
 Phase phase(){return phase;}
}
