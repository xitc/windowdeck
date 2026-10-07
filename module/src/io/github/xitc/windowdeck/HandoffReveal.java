package io.github.xitc.windowdeck;

/** Snapshot travel and committed live presentation may finish in either order. */
final class HandoffReveal {
 private boolean liveReady,motionCommitted,backgroundCommitted,revealing,cancelled;
 void liveReady(){if(!cancelled)liveReady=true;}
 void motionCommitted(){if(!cancelled)motionCommitted=true;}
 void backgroundCommitted(){if(!cancelled)backgroundCommitted=true;}
 boolean hasLive(){return liveReady&&!cancelled;}
 boolean isRevealing(){return revealing&&!cancelled;}
 boolean begin(){
  if(cancelled||revealing||!liveReady||!motionCommitted||!backgroundCommitted)return false;
  revealing=true;return true;
 }
 void cancel(){cancelled=true;}
}
