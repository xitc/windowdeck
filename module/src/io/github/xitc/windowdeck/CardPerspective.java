package io.github.xitc.windowdeck;
/** Projective side cards with one vanishing point for the entire rail. */
final class CardPerspective {
 // The 56 dp phone side plate uses the matching FOS vanishing distance.
 static final int VANISH_LR=470;
 static final int VANISH_TB=2950;

 static float[] columnQuad(int w,int h,float density,float localHorizon){
  return column(w,h,ratio(w,Math.round(VANISH_LR*density)),localHorizon);
 }
 private static float[] column(int w,int h,float f,float localHorizon){
  // Keep the common vanishing point even when it lies outside this card.
  float horizon=localHorizon;
  float top=(1f-f)*horizon,bottom=f*h+(1f-f)*horizon;
  return new float[]{0,0,w,top,w,bottom,0,h};
 }
 /** Physical display orientation selects the original phone resource; IME does not. */
 static float[] railQuad(int w,int h,int mode,float density,boolean portrait,
                         float left,float top,int stageWidth,int stageHeight){
  return railTargetQuad(w,h,mode,density,portrait,left,top,stageWidth,stageHeight,
    mode==PaneLayout.LEFT_RIGHT?w:h);
 }
 /** List destinations use the standard rail extent; a refreshed holder calls
  * railQuad with its measured extent. Quantize in display coordinates first. */
 static float[] railTargetQuad(int w,int h,int mode,float density,boolean portrait,
                         float left,float top,int stageWidth,int stageHeight,int standardExtent){
  if(w<=1||h<=1)return new float[]{0,0,w,0,w,h,0,h};
  boolean vertical=mode==PaneLayout.LEFT_RIGHT;
  int vanish=Math.round((vertical==portrait?VANISH_LR:VANISH_TB)*density);
  float f=ratio(standardExtent,vanish);
  if(vertical){
   int first=(int)(top+(stageHeight/2-top)*(1f-f));
   int second=(int)(first+h*f);
   return new float[]{0,0,w,first-top,w,second-top,0,h};
  }
  int first=(int)(left+(stageWidth/2-left)*(1f-f));
  int second=(int)(first+w*f);
  return new float[]{0,0,w,0,second-left,h,first-left,h};
 }
 /** A shared horizon may lie beyond a card, on either axis. Never clip that overflow. */
 static int[] bounds(int w,int h,float[] quad){
  float left=0,top=0,right=w,bottom=h;
  for(int i=0;i<8;i+=2){left=Math.min(left,quad[i]);right=Math.max(right,quad[i]);top=Math.min(top,quad[i+1]);bottom=Math.max(bottom,quad[i+1]);}
  return new int[]{(int)Math.floor(left),(int)Math.floor(top),(int)Math.ceil(right),(int)Math.ceil(bottom)};
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
