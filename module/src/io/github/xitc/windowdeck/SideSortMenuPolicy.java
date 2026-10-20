package io.github.xitc.windowdeck;

/** Menu lifetime is independent of the sort generation: a stationary press may leave it open. */
final class SideSortMenuPolicy {
 private enum Phase { CLOSED,OPENING,READY,ACCEPTED }
 private long generation;
 private Phase phase=Phase.CLOSED;
 long open(){phase=Phase.OPENING;return ++generation;}
 void ready(long token){if(token==generation&&phase==Phase.OPENING)phase=Phase.READY;}
 boolean takeAction(long token,boolean busy){
  if(token!=generation||phase!=Phase.READY||busy)return false;
  phase=Phase.ACCEPTED;return true;
 }
 void dismiss(){generation++;phase=Phase.CLOSED;}
 /** Raw-screen position. The last top-rail side opens towards the left of the DOWN point. */
 static int[] position(int left,int top,int right,int paddingLeft,int paddingTop,int downX,int downY,int width,int inset,boolean lastTopSide){
  int x=lastTopSide?Math.min(right-inset-width,downX-width):Math.max(left+paddingLeft+inset,downX);
  return new int[]{x,Math.max(top+paddingTop+inset,downY)};
 }
}
