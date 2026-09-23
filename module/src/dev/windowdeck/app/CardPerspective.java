package dev.windowdeck.app;
/** Mild trapezoid toward the main window. Does not aim at screen center. */
final class CardPerspective {
 static final int VANISH_LR=512;
 static final int VANISH_TB=3215;

 static float ratio(int size,int vanish){
  if(vanish<=0)return 1f;
  if(size>=vanish)return 0.9f;
  return (vanish-size)/(float)vanish;
 }

 /** Local quad LTx,LTy,RTx,RTy,RBx,RBy,LBx,LBy. Shortened edge stays centered. */
 static float[] quad(int w,int h,int mode){
  if(w<=1||h<=1)return new float[]{0,0,w,0,w,h,0,h};
  if(mode==PaneLayout.TOP_BOTTOM){
   float f=ratio(h,VANISH_TB),inset=w*(1f-f)/2f;
   return new float[]{0,0,w,0,w-inset,h,inset,h};
  }
  float f=ratio(w,VANISH_LR),inset=h*(1f-f)/2f;
  return new float[]{0,0,w,inset,w,h-inset,0,h};
 }
}
