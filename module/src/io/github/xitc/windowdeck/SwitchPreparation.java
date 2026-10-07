package io.github.xitc.windowdeck;

/** Bounded restoration before an existing task's switch. Old commit attempts are inert. */
final class SwitchPreparation {
 enum Phase { IDLE, WAITING, COMMITTING, READY, CANCELLED, TIMED_OUT }
 private Phase phase=Phase.IDLE;
 private int generation,attempt;
 private long deadline;
 int begin(long now,long waitMs){generation++;attempt=0;phase=Phase.WAITING;deadline=now+waitMs;return generation;}
 boolean pending(){return phase==Phase.WAITING||phase==Phase.COMMITTING;}
 boolean current(int token){return token==generation&&pending();}
 boolean waiting(int token){return token==generation&&phase==Phase.WAITING;}
 int commit(int token,long now){
  if(!waiting(token)||now>=deadline)return -1;
  phase=Phase.COMMITTING;return ++attempt;
 }
 boolean committing(int token,int ticket){return token==generation&&ticket==attempt&&phase==Phase.COMMITTING;}
 boolean retry(int token,int ticket,long now){
  if(!committing(token,ticket)||now>=deadline)return false;
  phase=Phase.WAITING;return true;
 }
 boolean ready(int token,int ticket,long now){
  if(!committing(token,ticket)||now>=deadline)return false;
  phase=Phase.READY;return true;
 }
 boolean timeout(int token,long now){if(!current(token)||now<deadline)return false;phase=Phase.TIMED_OUT;return true;}
 void cancel(){if(pending())phase=Phase.CANCELLED;}
 long remaining(long now){return Math.max(0,deadline-now);}
 Phase phase(){return phase;}
}
