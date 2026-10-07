package io.github.xitc.windowdeck;

/** The destination's snapshot frame must precede recents release and live embedding. */
final class SourceRelease {
 private boolean bootstrap,requested,released,cancelled;
 boolean commitBootstrap(){
  if(cancelled||bootstrap)return false;
  bootstrap=true;return true;
 }
 boolean requestRelease(){
  if(cancelled||!bootstrap||requested)return false;
  requested=true;return true;
 }
 boolean completeRelease(){
  if(cancelled||!requested||released)return false;
  released=true;return true;
 }
 boolean mayEmbed(){return !cancelled&&released;}
 boolean mayRetireCover(){return !cancelled&&released;}
 void cancel(){cancelled=true;}
}
