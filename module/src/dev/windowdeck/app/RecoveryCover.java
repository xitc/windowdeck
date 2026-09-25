package dev.windowdeck.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Task snapshot drawn in exactly the same presentation coordinates as its leash. */
final class RecoveryCover extends View {
 private Bitmap bitmap;
 private final Matrix bitmapMap=new Matrix();
 private final Path outline=new Path();
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
 RecoveryCover(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 void setBitmap(Bitmap value){bitmap=value;invalidate();}
 void fit(float[] source,float[] target,int taskWidth,int taskHeight){
  outline.reset();outline.moveTo(target[0],target[1]);
  for(int i=2;i<8;i+=2)outline.lineTo(target[i],target[i+1]);outline.close();
  if(bitmap!=null){
   float[] pixels=source.clone();
   for(int i=0;i<8;i+=2){pixels[i]*=bitmap.getWidth()/(float)taskWidth;pixels[i+1]*=bitmap.getHeight()/(float)taskHeight;}
   bitmapMap.setPolyToPoly(pixels,0,target,0,4);
  }
  invalidate();
 }
 @Override protected void onDraw(Canvas canvas){
  super.onDraw(canvas);int save=canvas.save();canvas.clipPath(outline);
  canvas.drawColor(Ui.CHROME);
  if(bitmap!=null&&!bitmap.isRecycled())canvas.drawBitmap(bitmap,bitmapMap,paint);
  canvas.restoreToCount(save);
 }
}
