package io.github.xitc.windowdeck;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

/** Clears the host wallpaper above a projected task, before chrome is drawn.
 * This affects only host pixels, never the SurfaceView's RenderNode transform. */
final class SurfaceAperture {
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final Path path=new Path();
 private final Matrix projection=new Matrix();
 SurfaceAperture(){paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));}
 /** Clip lower host chrome out of the same rounded contour as the live task hole. */
 boolean clipOutside(Canvas canvas,float[] quad,float width,float height,float radius,float left,float top,float sx,float sy){
  if(quad==null||quad.length!=8||width<=0||height<=0||sx<=0||sy<=0||radius<0)return false;
  for(float v:new float[]{width,height,radius,left,top,sx,sy})if(!Float.isFinite(v))return false;
  for(float v:quad)if(!Float.isFinite(v))return false;
  if(!projection.setPolyToPoly(new float[]{0,0,width,0,width,height,0,height},0,quad,0,4))return false;
  path.rewind();path.addRoundRect(new RectF(0,0,width,height),radius,radius,Path.Direction.CW);path.transform(projection);
  projection.setScale(sx,sy);projection.postTranslate(left,top);path.transform(projection);
  canvas.clipOutPath(path);return true;
 }
 void draw(Canvas canvas,float[] quad,float width,float height,float radius,float left,float top,float sx,float sy,float alpha){
  if(width<=0||height<=0){draw(canvas,quad,left,top,sx,sy,alpha);return;}
  if(quad==null||quad.length!=8||alpha<=0||sx<=0||sy<=0||!Float.isFinite(radius)||radius<0)return;
  for(float v:new float[]{width,height,left,top,sx,sy,alpha})if(!Float.isFinite(v))return;
  for(float v:quad)if(!Float.isFinite(v))return;
  if(!projection.setPolyToPoly(new float[]{0,0,width,0,width,height,0,height},0,quad,0,4))return;
  path.rewind();path.addRoundRect(new RectF(0,0,width,height),radius,radius,Path.Direction.CW);path.transform(projection);
  paint.setAlpha(Math.round(255*Math.min(1,alpha)));
  int save=canvas.save();canvas.translate(left,top);canvas.scale(sx,sy);canvas.drawPath(path,paint);canvas.restoreToCount(save);
 }
 void draw(Canvas canvas,float[] quad,float left,float top,float sx,float sy,float alpha){
  if(quad==null||quad.length!=8||alpha<=0||sx<=0||sy<=0)return;
  path.rewind();
  for(int i=0;i<8;i+=2){
   float x=left+quad[i]*sx,y=top+quad[i+1]*sy;
   if(!Float.isFinite(x)||!Float.isFinite(y))return;
   if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
  }
  path.close();paint.setAlpha(Math.round(255*Math.min(1,alpha)));
  // No saveLayer: erase the window pixels, not a temporary offscreen bitmap.
  canvas.drawPath(path,paint);
 }
}
