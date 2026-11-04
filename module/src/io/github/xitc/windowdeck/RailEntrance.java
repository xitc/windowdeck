package io.github.xitc.windowdeck;

/** vivo's ordinary container entry: one measured rail, no delayed side start. */
final class RailEntrance {
 static final long DURATION_MS=500;
 static boolean valid(int[] value){
  return value!=null&&value.length==4&&value[0]>0&&value[1]>value[0]
    &&(value[2]==PaneLayout.LEFT_RIGHT||value[2]==PaneLayout.TOP_BOTTOM)
    &&value[3]>0&&value[3]<=(value[2]==PaneLayout.LEFT_RIGHT?value[0]:value[1]);
 }
 static float offset(int extent,long elapsed){
  return -extent*(1-CornerGesture.enterFraction(Math.max(0,Math.min(1,elapsed/(float)DURATION_MS))));
 }
 private RailEntrance(){}
}
