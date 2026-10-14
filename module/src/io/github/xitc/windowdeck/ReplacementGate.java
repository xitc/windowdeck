package io.github.xitc.windowdeck;

/** One replacement's task identity and terminal state; callbacks cannot revive it. */
final class ReplacementGate {
 private boolean initialized,finished;
 private int task=-1;
 boolean initialize(boolean success){if(finished)return false;if(!success){finished=true;return false;}initialized=true;return true;}
 boolean bind(int id){if(finished||id<0)return false;if(task>=0&&task!=id){finished=true;return false;}task=id;return true;}
 boolean ready(int id,boolean content){return !finished&&initialized&&task>=0&&task==id&&content;}
 boolean finish(){if(finished)return false;finished=true;return true;}
 boolean finished(){return finished;}
}
