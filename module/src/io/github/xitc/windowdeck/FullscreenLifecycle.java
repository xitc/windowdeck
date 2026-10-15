package io.github.xitc.windowdeck;

/** UI-thread fullscreen lifecycle; Android resources and effects belong to the host. */
final class FullscreenLifecycle {
 enum Phase {
  ARMED, SOURCE_PENDING, RUNNING, SETTLING, COMMIT_PENDING, FRAME_PENDING,
  FINISHED_STARTED, FINISHED_UNSTARTED, CANCELLED
 }
 private Phase phase=Phase.ARMED;
 private int commitAttempt;
 Phase phase(){return phase;}
 boolean closed(){return phase==Phase.FINISHED_STARTED||phase==Phase.FINISHED_UNSTARTED||phase==Phase.CANCELLED;}
 boolean open(){return !closed();}
 boolean started(){
  return phase==Phase.RUNNING||phase==Phase.SETTLING||phase==Phase.COMMIT_PENDING
   ||phase==Phase.FRAME_PENDING||phase==Phase.FINISHED_STARTED;
 }
 boolean requestSource(){return move(Phase.ARMED,Phase.SOURCE_PENDING);}
 boolean acceptSource(){return move(Phase.SOURCE_PENDING,Phase.RUNNING);}
 boolean animationEnded(){return move(Phase.RUNNING,Phase.SETTLING);}
 int requestCommit(){
  if(!move(Phase.SETTLING,Phase.COMMIT_PENDING))return 0;
  return ++commitAttempt;
 }
 boolean rollbackCommit(){return move(Phase.COMMIT_PENDING,Phase.SETTLING);}
 boolean acceptCommit(int attempt){
  return attempt==commitAttempt&&move(Phase.COMMIT_PENDING,Phase.FRAME_PENDING);
 }
 boolean finish(){
  if(closed())return false;
  phase=started()?Phase.FINISHED_STARTED:Phase.FINISHED_UNSTARTED;return true;
 }
 boolean abort(){if(closed())return false;phase=Phase.CANCELLED;return true;}
 private boolean move(Phase from,Phase to){if(phase!=from)return false;phase=to;return true;}
}
