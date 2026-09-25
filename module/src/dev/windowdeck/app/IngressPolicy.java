package dev.windowdeck.app;

/** Live task identity takes precedence over persistent state from a previous boot. */
final class IngressPolicy {
 static boolean canEnter(int liveContainer,int publishedContainer,int count){
  return liveContainer<0||(liveContainer==publishedContainer&&count>0&&count<3);
 }
}
