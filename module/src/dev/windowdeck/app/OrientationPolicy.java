package dev.windowdeck.app;
/** Android ActivityInfo orientation values, kept pure for geometry regression tests. */
final class OrientationPolicy {
 // Display orientation, not IME-reduced stage geometry, determines presentation.
 static boolean rotatePresentation(int renderW,int renderH,int displayW,int displayH){
  return renderW>renderH&&displayH>displayW;
 }
 static int[] presentationSize(int renderW,int renderH,boolean rotate){
  return rotate?new int[]{renderH,renderW}:new int[]{renderW,renderH};
 }
 static int axis(int requested){
  switch(requested){case 0:case 6:case 8:case 11:return 2;
   case 1:case 7:case 9:case 12:return 1;default:return 0;}
 }
 static int[] bounds(int baseW,int baseH,int displayW,int displayH,int axis){
  int shortEdge=Math.max(1,Math.min(displayW,displayH)),longEdge=Math.max(1,Math.max(displayW,displayH));
  if(axis==2)return new int[]{longEdge,shortEdge};
  if(axis==1)return new int[]{shortEdge,longEdge};
  return new int[]{Math.max(1,baseW),Math.max(1,baseH)};
 }
}
