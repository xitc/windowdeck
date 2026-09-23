package dev.windowdeck.app;
/** Cover-crop a source buffer into a destination plate. Row is left, top, width, height. */
final class SurfaceFit {
 static int[] coverCrop(int srcW,int srcH,int dstW,int dstH){
  if(srcW<=0||srcH<=0)return new int[]{0,0,1,1};
  if(dstW<=0||dstH<=0)return new int[]{0,0,srcW,srcH};
  double scale=Math.max(dstW/(double)srcW,dstH/(double)srcH);
  int visW=Math.min(srcW,Math.max(1,(int)Math.round(dstW/scale)));
  int visH=Math.min(srcH,Math.max(1,(int)Math.round(dstH/scale)));
  int x=Math.max(0,(srcW-visW)/2),y=Math.max(0,(srcH-visH)/2);
  if(x+visW>srcW)x=srcW-visW;
  if(y+visH>srcH)y=srcH-visH;
  return new int[]{x,y,visW,visH};
 }
 static float coverScale(int[] crop,int dstW,int dstH){
  if(crop==null||crop[2]<=0||crop[3]<=0)return 1f;
  return (float)Math.max(dstW/(double)crop[2],dstH/(double)crop[3]);
 }
}
