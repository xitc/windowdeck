package io.github.xitc.windowdeck;

/** Display IME state is independent of a focused embedded app's window Insets. */
final class ImeAvoidance {
 private ImeAvoidance() {}

 static int bottom(boolean visible,int left,int top,int right,int bottom,
   int displayLeft,int displayTop,int displayRight,int displayBottom,
   int hostTop,int hostBottom){
  if(!visible||right<=left||bottom<=top||displayRight<=displayLeft
    ||displayBottom<=displayTop||hostBottom<=hostTop)return 0;
  // Floating/narrow or detached IMEs must not reserve the whole area below them.
  if(left>displayLeft||right<displayRight||bottom<displayBottom
    ||top<displayTop||top>=displayBottom)return 0;
  return Math.max(0,hostBottom-Math.max(hostTop,top));
 }

 static int resolve(int displayBottom,int windowBottom){
  // -1 means no display state for this host session; zero is authoritative hidden.
  return displayBottom>=0?displayBottom:Math.max(0,windowBottom);
 }
}
