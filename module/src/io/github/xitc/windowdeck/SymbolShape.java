package io.github.xitc.windowdeck;

/** Shape checks for the launcher members whose names R8 does not keep. */
final class SymbolShape {
 private SymbolShape(){}
 /** Exactly one true entry, or {@code -1} when there are zero or several. */
 static int only(boolean[] matches){
  int found=-1;
  if(matches==null)return -1;
  for(int i=0;i<matches.length;i++){
   if(!matches[i])continue;
   if(found>=0)return -1;
   found=i;
  }
  return found;
 }
 /**
  * Static {@code onGestureEnded} bridge:
  * {@code (handler, float, PointF, PointF, PointF, boolean, boolean, int)}.
  */
 static boolean gestureBridge(Class<?>[] types,Class<?> handler,Class<?> point){
  return types!=null&&types.length==8&&types[0].isAssignableFrom(handler)&&types[1]==float.class
   &&types[2]==point&&types[3]==point&&types[4]==point
   &&types[5]==boolean.class&&types[6]==boolean.class&&types[7]==int.class;
 }
 /** The init-animation method is the single {@code Function4} parameter that survived. */
 static boolean initAnimation(int parameterCount,String parameterClassName){
  return parameterCount==1&&"kotlin.jvm.functions.Function4".equals(parameterClassName);
 }
}
