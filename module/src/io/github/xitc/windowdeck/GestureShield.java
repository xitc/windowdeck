package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Short landscape flash cover. It comes down before the add-to-workbench chip can be aimed. */
final class GestureShield {
 private static final String TAG="WindowDeck";
 private static int generation,retired;
 private static boolean retireKeep;
 private static WindowManager windows;
 private static View view;
 private static Bitmap frame;

 private GestureShield(){}

 /** Drops any shield from the previous gesture. The returned token rejects a late capture. */
 static int begin(){
  int token=++generation;
  dismiss(false);
  // dismiss() marks this token retired. A shield for the gesture that just started is still allowed.
  retired=token-1;retireKeep=false;
  HandoffCover.forgetWarm();
  return token;
 }

 static void show(Context context,Bitmap bitmap,int gen){
  if(gen!=generation||!HandoffCover.coverFrame(bitmap)){
   if(bitmap!=null)Log.i(TAG,"gesture_shield_skipped gen="+gen+" current="+generation);
   HandoffCover.recycle(bitmap);return;
  }
  if(gen==retired){
   if(retireKeep)HandoffCover.remember(bitmap);
   else HandoffCover.recycle(bitmap);
   return;
  }
  Bitmap previous=detach();
  if(previous!=null&&previous!=bitmap)HandoffCover.recycle(previous);
  try{
   windows=context.getSystemService(WindowManager.class);
   FrameLayout root=new FrameLayout(context);
   root.setBackgroundColor(Ui.CHROME);
   View picture=new View(context){
    @Override protected void onDraw(Canvas canvas){
     Bitmap current=frame;
     if(current==null||current.isRecycled()||getWidth()<1||getHeight()<1)return;
     drawFrame(canvas,current,getWidth(),getHeight());
    }
   };
   picture.setWillNotDraw(false);
   root.addView(picture,new FrameLayout.LayoutParams(-1,-1));
   frame=bitmap;view=root;
   try{windows.addView(root,params(2015));}
   catch(Throwable e){
    Log.w(TAG,"gesture_shield_type_2015_failed",e);
    windows.addView(root,params(2038));
   }
   raise(root);
   Log.i(TAG,"gesture_shield_shown");
  }catch(Throwable e){
   Log.w(TAG,"gesture_shield_failed",e);
   Bitmap failed=frame;frame=null;View root=view;view=null;
   if(root!=null&&windows!=null)try{windows.removeViewImmediate(root);}catch(Throwable ignored){}
   HandoffCover.recycle(failed!=null?failed:bitmap);
  }
 }

 /** Removes the overlay and returns its bitmap. The caller decides whether to keep it. */
 static Bitmap detach(){
  retired=generation;
  Bitmap held=frame;frame=null;
  View root=view;view=null;
  if(root!=null&&windows!=null){
   try{windows.removeViewImmediate(root);}catch(Throwable e){Log.w(TAG,"gesture_shield_remove",e);}
   Log.i(TAG,"gesture_shield_hidden");
  }
  return held;
 }

 static void dismiss(boolean keep){
  retired=generation;retireKeep=keep;
  Bitmap held=detach();
  if(held==null)return;
  if(keep)HandoffCover.remember(held);
  else HandoffCover.recycle(held);
 }

 private static void drawFrame(Canvas canvas,Bitmap bitmap,int viewW,int viewH){
  int bw=bitmap.getWidth(),bh=bitmap.getHeight();
  if(bw<=bh||viewW>=viewH){canvas.drawBitmap(bitmap,null,new RectF(0,0,viewW,viewH),null);return;}
  float[] src={0,0,bw,0,bw,bh,0,bh},dst=new float[8],corners={0,0,bw,0,bw,bh,0,bh};
  for(int i=0;i<8;i+=2){
   float[] p=DisplayGeometry.point(corners[i],corners[i+1],bw,bh,1);
   dst[i]=p[0]*viewW/bh;dst[i+1]=p[1]*viewH/bw;
  }
  Matrix matrix=new Matrix();
  if(matrix.setPolyToPoly(src,0,dst,0,4))canvas.drawBitmap(bitmap,matrix,null);
 }

 private static void raise(View root){
  root.post(()->{
   if(view!=root)return;
   try{
    Object vri=XposedHelpers.callMethod(root,"getViewRootImpl");
    if(vri==null)return;
    SurfaceControl sc=(SurfaceControl)XposedHelpers.callMethod(vri,"getSurfaceControl");
    if(sc==null||!sc.isValid())return;
    try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){transaction.setLayer(sc,Integer.MAX_VALUE).apply();}
   }catch(Throwable e){Log.w(TAG,"gesture_shield_raise_failed",e);}
  });
 }

 private static WindowManager.LayoutParams params(int type){
  WindowManager.LayoutParams p=new WindowManager.LayoutParams(
   WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,type,
   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_FULLSCREEN
    |WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
   PixelFormat.TRANSLUCENT);
  p.windowAnimations=0;p.gravity=Gravity.TOP|Gravity.LEFT;p.setTitle("WindowDeck shield");
  p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
  p.setFitInsetsTypes(0);
  if(type==2015)try{XposedHelpers.callMethod(p,"setTrustedOverlay");}catch(Throwable ignored){}
  return p;
 }
}
