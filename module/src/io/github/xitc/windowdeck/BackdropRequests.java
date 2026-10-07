package io.github.xitc.windowdeck;

/** One background load per display target; a wallpaper change supersedes the old request. */
final class BackdropRequests {
 private int generation,rotation=-1,width,height;
 private boolean pending,applied;
 synchronized int begin(int rotation,int width,int height,boolean changed){
  if(!changed&&(pending||applied)&&this.rotation==rotation&&this.width==width&&this.height==height)return -1;
  this.rotation=rotation;this.width=width;this.height=height;
  pending=true;applied=false;return ++generation;
 }
 synchronized boolean current(int token){return pending&&token==generation;}
 synchronized boolean complete(int token){
  if(!current(token))return false;
  pending=false;applied=true;return true;
 }
 synchronized void invalidate(){generation++;pending=false;applied=false;}
}
