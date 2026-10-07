package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Task snapshot drawn in exactly the same presentation coordinates as its leash. */
final class RecoveryCover extends View {
 private Bitmap bitmap;
 private Bitmap backdrop;
 private boolean fullScene;
 private final Matrix bitmapMap=new Matrix();
 private final Path outline=new Path();
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
 RecoveryCover(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 void setBitmap(Bitmap value){bitmap=value;invalidate();}
 void setBackdrop(Bitmap value){
  if(!fullScene||backdrop!=value){fullScene=true;backdrop=value;invalidate();}
 }
 void fit(float[] source,float[] target,int taskWidth,int taskHeight){
  fit(source,target,taskWidth,taskHeight,0);
 }
 void fit(float[] source,float[] target,int taskWidth,int taskHeight,float sourceRadius){
  outline.reset();outline.moveTo(target[0],target[1]);
  for(int i=2;i<8;i+=2)outline.lineTo(target[i],target[i+1]);outline.close();
  if(sourceRadius>0){
   float left=source[0],right=source[0],top=source[1],bottom=source[1];
   for(int i=2;i<8;i+=2){left=Math.min(left,source[i]);right=Math.max(right,source[i]);top=Math.min(top,source[i+1]);bottom=Math.max(bottom,source[i+1]);}
   Matrix taskMap=new Matrix();
   if(taskMap.setPolyToPoly(source,0,target,0,4)){
    outline.reset();outline.addRoundRect(new RectF(left,top,right,bottom),sourceRadius,sourceRadius,Path.Direction.CW);outline.transform(taskMap);
   }
  }
  if(bitmap!=null){
   float[] pixels=source.clone();
   for(int i=0;i<8;i+=2){pixels[i]*=bitmap.getWidth()/(float)taskWidth;pixels[i+1]*=bitmap.getHeight()/(float)taskHeight;}
   bitmapMap.setPolyToPoly(pixels,0,target,0,4);
  }
  invalidate();
 }
 @Override protected void onDraw(Canvas canvas){
  super.onDraw(canvas);
  if(fullScene){
   canvas.drawColor(Ui.CHROME);
   if(backdrop!=null&&!backdrop.isRecycled())canvas.drawBitmap(backdrop,null,new RectF(0,0,getWidth(),getHeight()),paint);
  }
  int save=canvas.save();canvas.clipPath(outline);
  canvas.drawColor(Ui.CHROME);
  if(bitmap!=null&&!bitmap.isRecycled())canvas.drawBitmap(bitmap,bitmapMap,paint);
  canvas.restoreToCount(save);
 }
}
