package dev.windowdeck.app;
/** Projective side cards with a shared vertical vanishing point. */
final class CardPerspective {
 static final int VANISH_LR=512;
 static final int VANISH_TB=3215;

 static float[] columnQuad(int w,int h,float density,float localHorizon){
  float f=ratio(w,Math.round(320*density));
  // Keep the common vanishing point even when it lies outside this card.
  float horizon=localHorizon;
  float top=(1f-f)*horizon,bottom=f*h+(1f-f)*horizon;
  return new float[]{0,0,w,top,w,bottom,0,h};
 }
 static float ratio(int size,int vanish){
  if(vanish<=0)return 1f;
  if(size>=vanish)return 0.9f;
  return (vanish-size)/(float)vanish;
 }

 /** Local quad LTx,LTy,RTx,RTy,RBx,RBy,LBx,LBy. Shortened edge stays centered. */
 static float[] quad(int w,int h,int mode){return quad(w,h,mode,1f);}
 static float[] quad(int w,int h,int mode,float density){
  if(w<=1||h<=1)return new float[]{0,0,w,0,w,h,0,h};
  if(mode==PaneLayout.TOP_BOTTOM){
   float f=ratio(h,Math.round(VANISH_TB*density)),inset=w*(1f-f)/2f;
   return new float[]{0,0,w,0,w-inset,h,inset,h};
  }
  float f=ratio(w,Math.round(VANISH_LR*density)),inset=h*(1f-f)/2f;
  return new float[]{0,0,w,inset,w,h-inset,0,h};
 }
}
