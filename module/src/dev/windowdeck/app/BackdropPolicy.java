package dev.windowdeck.app;

/** Pure helpers for workbench wallpaper orientation. Display rotation matches Surface.ROTATION_*. */
final class BackdropPolicy {
 static final int DIM=0x1e000000;
 static int rotationDegrees(int displayRotation){
  if(displayRotation==1)return -90;
  if(displayRotation==2)return 180;
  if(displayRotation==3)return 90;
  return 0;
 }
 static boolean shouldRotate(int bitmapW,int bitmapH,int displayW,int displayH){
  if(bitmapW<=0||bitmapH<=0||displayW<=0||displayH<=0)return false;
  return (bitmapW>bitmapH)!=(displayW>displayH);
 }
 static boolean needsBitmapRotation(boolean preoriented,int displayRotation,int bitmapW,int bitmapH,int displayW,int displayH){
  if(rotationDegrees(displayRotation)==0)return false;
  if(preoriented)return shouldRotate(bitmapW,bitmapH,displayW,displayH);
  return true;
 }
 static boolean lightBars(int colorHints){return (colorHints&1)!=0;}
}
