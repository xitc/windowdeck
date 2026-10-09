package io.github.xitc.windowdeck;

/** Maps the actual IME source frame to a smaller bottom-right rectangle. */
final class ImeFloatGeometry {
 final float scale,x,y,a,b,c,d;
 final boolean rotated;
 private ImeFloatGeometry(float scale,float x,float y,boolean rotated){
  this.scale=scale;this.x=x;this.y=y;this.rotated=rotated;
  a=d=rotated?0:scale;b=rotated?-scale:0;c=rotated?scale:0;
 }
 static ImeFloatGeometry fit(int left,int top,int right,int bottom,
   int displayLeft,int displayTop,int displayRight,int displayBottom,
   int leashX,int leashY,int margin){
  return fit(left,top,right,bottom,displayLeft,displayTop,displayRight,displayBottom,leashX,leashY,margin,false);
 }
 static ImeFloatGeometry fit(int left,int top,int right,int bottom,
   int displayLeft,int displayTop,int displayRight,int displayBottom,
   int leashX,int leashY,int margin,boolean rotate){
  int w=right-left,h=bottom-top,dw=displayRight-displayLeft,dh=displayBottom-displayTop;
  // Do not shrink a native floating keyboard or an unknown/landscape display.
  if(w<=0||h<=0||dw<=0||dh<=dw||w<dw*.7f||left<displayLeft||top<displayTop||right>displayRight||bottom>displayBottom)return null;
  int targetW=rotate?h:w,targetH=rotate?w:h;
  float scale=Math.min(.72f,Math.min((dw-2f*margin)/targetW,(dh-2f*margin)/targetH));
  if(scale<=0||scale>=1)return null;
  float targetLeft=displayRight-margin-targetW*scale,targetTop=displayBottom-margin-targetH*scale;
  return rotate?new ImeFloatGeometry(scale,targetLeft+scale*(bottom-leashY),targetTop-scale*(left-leashX),true)
    :new ImeFloatGeometry(scale,targetLeft-scale*(left-leashX),targetTop-scale*(top-leashY),false);
 }
}
