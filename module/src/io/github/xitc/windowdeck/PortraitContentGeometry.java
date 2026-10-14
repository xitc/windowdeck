package io.github.xitc.windowdeck;

/** A portrait task bitmap keeps task-space crop separate from the moving card outline.
 * All crops are L/T/R/B; quads are screen-space LT, RT, RB, LB. */
final class PortraitContentGeometry {
 static final class Frame {
  final float[] crop,quad;
  Frame(float[] crop,float[] quad){this.crop=crop;this.quad=quad;}
 }
 private final float[] fromCrop,toCrop,fromQuad,toQuad;
 private final int width,height;
 private PortraitContentGeometry(int width,int height,float[] crop,float[] box,float[] targetCrop,float[] targetQuad){
  this.width=width;this.height=height;fromCrop=crop.clone();toCrop=targetCrop.clone();
  fromQuad=quad(box);toQuad=targetQuad.clone();
 }
 /** Only a positive, unrotated task matrix whose mapped crop is this release rectangle. */
 static boolean sourceValid(int width,int height,float[] crop,float[] box,float[] matrix){
  if(width<2||height<=width||!cropValid(crop,width,height)||!rectValid(box)||!finite(matrix,9))return false;
  if(matrix[0]<=0||matrix[4]<=0||Math.abs(matrix[1])>1e-5f||Math.abs(matrix[3])>1e-5f
    ||Math.abs(matrix[6])>1e-5f||Math.abs(matrix[7])>1e-5f||Math.abs(matrix[8]-1)>1e-5f)return false;
  float[] mapped={crop[0]*matrix[0]+matrix[2],crop[1]*matrix[4]+matrix[5],
    crop[2]*matrix[0]+matrix[2],crop[3]*matrix[4]+matrix[5]};
  return close(mapped,box,.01f);
 }
 static PortraitContentGeometry create(int width,int height,float[] crop,float[] box,
   int[] targetSize,float[] targetCrop,float[] targetQuad,float[] targetBox){
  if(width<2||height<=width||!cropValid(crop,width,height)||!rectValid(box)
    ||targetSize==null||targetSize.length!=2||targetSize[0]!=width||targetSize[1]!=height
    ||!cropValid(targetCrop,width,height)||!rectValid(targetBox)
    ||!rectQuadValid(targetQuad)||!close(quad(targetBox),targetQuad,1f))return null;
  return new PortraitContentGeometry(width,height,crop,box,targetCrop,targetQuad);
 }
 boolean matches(int[] size,float[] crop,float[] quad){
  return size!=null&&size.length==2&&size[0]==width&&size[1]==height
    &&java.util.Arrays.equals(toCrop,crop)&&java.util.Arrays.equals(toQuad,quad);
 }
 Frame frame(float fraction){
  float f=Float.isNaN(fraction)?0:Math.max(0,Math.min(1,fraction));
  return new Frame(blend(fromCrop,toCrop,f),blend(fromQuad,toQuad,f));
 }
 private static float[] blend(float[] from,float[] to,float f){
  float[] out=new float[from.length];
  for(int i=0;i<out.length;i++)out[i]=f==0?from[i]:f==1?to[i]:from[i]+(to[i]-from[i])*f;
  return out;
 }
 private static boolean cropValid(float[] crop,int width,int height){
  return rectValid(crop)&&crop[0]>=0&&crop[1]>=0&&crop[2]<=width&&crop[3]<=height;
 }
 private static boolean rectValid(float[] rect){return finite(rect,4)&&rect[2]-rect[0]>=1&&rect[3]-rect[1]>=1;}
 private static boolean rectQuadValid(float[] quad){
  return finite(quad,8)&&quad[2]-quad[0]>=1&&quad[5]-quad[1]>=1
    &&quad[0]==quad[6]&&quad[2]==quad[4]&&quad[1]==quad[3]&&quad[5]==quad[7];
 }
 private static boolean finite(float[] values,int count){
  if(values==null||values.length!=count)return false;
  for(float value:values)if(!Float.isFinite(value))return false;
  return true;
 }
 private static boolean close(float[] from,float[] to,float tolerance){
  for(int i=0;i<from.length;i++)if(Math.abs(from[i]-to[i])>tolerance)return false;
  return true;
 }
 private static float[] quad(float[] box){return new float[]{box[0],box[1],box[2],box[1],box[2],box[3],box[0],box[3]};}
}
