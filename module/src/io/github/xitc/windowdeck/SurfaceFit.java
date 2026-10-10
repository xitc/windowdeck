package io.github.xitc.windowdeck;
/** Cover-crop a source buffer into a destination plate. Row is left, top, width, height. */
final class SurfaceFit {
 /** Perspective changes the homogeneous denominator across the source crop.
  * A rotated, translated, or sheared rectangle is still affine; comparing its
  * corners with an axis-aligned plate does not distinguish these cases. */
 static boolean hasPerspective(float[] matrix,float[] source){
  if(matrix==null||matrix.length!=9||source==null||source.length!=8)throw new IllegalArgumentException("matrix and source quad required");
  for(float value:matrix)if(!Float.isFinite(value))throw new IllegalArgumentException("finite matrix required");
  float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
  for(int i=0;i<8;i+=2){
   if(!Float.isFinite(source[i])||!Float.isFinite(source[i+1]))throw new IllegalArgumentException("finite source required");
   float denominator=matrix[6]*source[i]+matrix[7]*source[i+1]+matrix[8];
   if(!Float.isFinite(denominator))throw new IllegalArgumentException("finite denominator required");
   min=Math.min(min,denominator);max=Math.max(max,denominator);
  }
  if(min<=0&&max>=0)throw new IllegalArgumentException("mapping crosses infinity");
  return max-min>1e-5f*Math.max(Math.abs(min),Math.abs(max));
 }
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
 static float[] sourceQuad(int presentedWidth,int[] crop,boolean rotate){
  float x=crop[0],y=crop[1],r=x+crop[2],b=y+crop[3];
  return rotate?new float[]{y,presentedWidth-x,y,presentedWidth-r,b,presentedWidth-r,b,presentedWidth-x}:new float[]{x,y,r,y,r,b,x,b};
 }
 static float coverScale(int[] crop,int dstW,int dstH){
  if(crop==null||crop[2]<=0||crop[3]<=0)return 1f;
  return (float)Math.max(dstW/(double)crop[2],dstH/(double)crop[3]);
 }
}
