package io.github.xitc.windowdeck;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Keep the committed task picture while the exiting application window rotates.
 * The task stays embedded until the destination-sized host frame commits. */
final class FullscreenRotationBridge {
 interface Listener { void complete(FullscreenRotationBridge bridge,boolean ready,String reason); }
 private final Activity activity;
 private final Handler main,worker;
 private final int task,width,height,orientation;
 private final Listener listener;
 private final long started=SystemClock.uptimeMillis();
 private final Runnable timeout=()->finish(false,"timeout");
 private volatile boolean closed;
 private boolean sourceCommitted,targetPending,finished;
 private Bitmap bitmap;
 private Picture picture;

 FullscreenRotationBridge(Activity activity,Handler main,Handler worker,int task,int width,int height,int orientation,Listener listener){
  this.activity=activity;this.main=main;this.worker=worker;this.task=task;this.width=width;this.height=height;this.orientation=orientation;this.listener=listener;
 }
 void start(SurfaceControl leash){
  if(leash==null||!leash.isValid()||width<=height||height<2)throw new IllegalArgumentException("Missing landscape source");
  SurfaceControl copy=(SurfaceControl)XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckFullscreenSnapshot");
  main.postDelayed(timeout,MotionSpec.COVER_TIMEOUT_MS);
  Log.i("WindowDeck","fullscreen_rotation_prepare task="+task+" buffer="+width+"x"+height+" orientation="+orientation);
  if(!worker.post(()->{
   Bitmap captured=null;
   try{captured=LiveTaskContent.captureFrame(copy,width,height,Math.max(width,height));}
   catch(Throwable e){Log.w("WindowDeck","fullscreen_rotation_capture_failed task="+task,e);}
   finally{copy.release();}
   Bitmap result=captured;
   if(!main.post(()->accept(result))&&result!=null)result.recycle();
  })){copy.release();finish(false,"worker_unavailable");}
 }
 private void accept(Bitmap captured){
  if(closed||finished){if(captured!=null)captured.recycle();return;}
  if(captured==null||captured.getWidth()!=width||captured.getHeight()!=height){
   if(captured!=null)captured.recycle();finish(false,"source_unavailable");return;
  }
  bitmap=captured;
  try{
   picture=new Picture();
   ViewGroup decor=(ViewGroup)activity.getWindow().getDecorView();
   decor.addView(picture,new FrameLayout.LayoutParams(-1,-1));
   picture.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->requestTargetFrame());
   picture.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->{
    if(closed||finished||sourceCommitted)return;
    sourceCommitted=true;
    Log.i("WindowDeck","fullscreen_rotation_source_committed task="+task);
    try{activity.setRequestedOrientation(orientation);requestTargetFrame();}
    catch(Throwable e){Log.w("WindowDeck","fullscreen_rotation_request_failed task="+task,e);finish(false,"orientation_failed");}
   }));
   picture.invalidate();
  }catch(Throwable e){Log.w("WindowDeck","fullscreen_rotation_attach_failed task="+task,e);finish(false,"attach_failed");}
 }
 private boolean destinationReady(){
  if(picture==null||picture.getWidth()<=picture.getHeight()||activity.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_LANDSCAPE)return false;
  int rotation=picture.getDisplay()==null?-1:picture.getDisplay().getRotation();
  return rotation==1||rotation==3;
 }
 private void requestTargetFrame(){
  if(closed||finished||!sourceCommitted||targetPending||!destinationReady())return;
  targetPending=true;
  picture.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->{
   if(closed||finished)return;
   targetPending=false;
   if(destinationReady())finish(true,"frame_committed");else requestTargetFrame();
  }));
  picture.invalidate();
 }
 private void finish(boolean ready,String reason){
  if(closed||finished)return;
  finished=true;main.removeCallbacks(timeout);
  Log.i("WindowDeck","fullscreen_rotation_result task="+task+" result="+(ready?"frame_committed":"degraded")+" reason="+reason+" elapsed_ms="+(SystemClock.uptimeMillis()-started));
  listener.complete(this,ready,reason);
 }
 void close(){
  if(closed)return;
  closed=true;main.removeCallbacks(timeout);
  if(picture!=null&&picture.getParent() instanceof ViewGroup)((ViewGroup)picture.getParent()).removeView(picture);
  if(bitmap!=null){bitmap.recycle();bitmap=null;}
 }
 private final class Picture extends View {
  private final Matrix map=new Matrix();
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  Picture(){super(activity);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);setContentDescription("WindowDeck fullscreen rotation");}
  @Override protected void onDraw(Canvas canvas){
   canvas.drawColor(Color.BLACK);
   if(bitmap==null||bitmap.isRecycled())return;
   float w=bitmap.getWidth(),h=bitmap.getHeight();
   // The portrait card already presents a landscape buffer clockwise. Once
   // the host is landscape, draw that same buffer in its natural coordinates.
   float[] source=getHeight()>getWidth()?new float[]{0,h,0,0,w,0,w,h}:new float[]{0,0,w,0,w,h,0,h};
   float[] target={0,0,getWidth(),0,getWidth(),getHeight(),0,getHeight()};
   if(map.setPolyToPoly(source,0,target,0,4))canvas.drawBitmap(bitmap,map,paint);
  }
  @Override protected void onDetachedFromWindow(){super.onDetachedFromWindow();close();}
 }
}
