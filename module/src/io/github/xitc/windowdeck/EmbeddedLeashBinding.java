package io.github.xitc.windowdeck;

/** Immutable organizer evidence; a valid handle alone does not prove ownership. */
final class EmbeddedLeashBinding {
 final int task,layer;
 final boolean embedded,repairable;
 EmbeddedLeashBinding(int container,int task,int layer,int scenario,int taskContainer,boolean vanished,boolean skipReparent){
  this.task=task;this.layer=layer;
  embedded=!vanished&&container>=0&&task>=0&&scenario==2&&taskContainer==container;
  repairable=embedded&&!skipReparent&&layer>=0;
 }
 boolean matches(int task,int layer){return repairable&&this.task==task&&this.layer==layer;}
}
