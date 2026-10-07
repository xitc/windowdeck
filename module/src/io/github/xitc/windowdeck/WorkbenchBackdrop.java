package io.github.xitc.windowdeck;

import android.app.Activity;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;

final class WorkbenchBackdrop {
 private static final String TAG="WindowDeck";
 private final Handler main=new Handler(Looper.getMainLooper());
 private final Runnable refreshRunnable=()->refresh("scheduled");
 private Activity activity;
 private HandlerThread thread;
 private Handler bg;
 private BroadcastReceiver receiver;
 private WallpaperManager wallpaperManager;
 private WallpaperManager.OnColorsChangedListener colorsListener;
 private Bitmap owned,nativeBitmap;
 private android.view.SurfaceControl underlay;
 private final BackdropRequests requests=new BackdropRequests();
 private int visualGeneration,appliedRotation=-1,appliedW,appliedH;
 private boolean attached;
 private boolean placeholder;
 boolean ready;
 Runnable onReady;
 private void signalReady(){ready=true;if(onReady!=null)onReady.run();}

 /** True once the window has something to draw, wallpaper or not.
  *
  *  <p>The underlay is fetched on a {@link HandlerThread}, and gating the first entrance frame on
  *  it held the whole entrance behind that async task — which is exactly the gap the entrance
  *  cover exists to hide (TODO A1-4). {@code onCreate} already puts {@link Ui#CHROME} on the
  *  window, so a frame taken now is never black; the wallpaper replaces it when it lands. */
 boolean firstFrameUsable(){return ready||placeholder;}

 /** The wallpaper bitmap currently on screen, or null while the underlay is still loading. */
 Bitmap currentBitmap(){return owned;}
 int visualGeneration(){return visualGeneration;}

 void attach(Activity a){
  activity=a;
  if(thread==null){thread=new HandlerThread("WindowDeckBackdrop");thread.start();bg=new Handler(thread.getLooper());}
  attached=true;placeholder=true;
  refresh("attach");
  register();
 }
 void onConfigurationChanged(){
  if(!attached||activity==null)return;
  int rotation=displayRotation();int[] size=displaySize();
  if(rotation==appliedRotation&&size[0]==appliedW&&size[1]==appliedH&&owned!=null)return;
  refresh("config");
 }
 void onResume(){restoreSurface();}
 private final Runnable restoreUnderlay=()->{
  if(!attached||activity==null||activity.isFinishing())return;
  if(!usable(owned)){refresh("resume");return;}
  int[] size=displaySize();if(!applyUnderlay(activity,owned,size[0],size[1]))main.postDelayed(this::restoreSurface,MotionSpec.SURFACE_RETRY_MS);
 };
 void restoreSurface(){
  if(!attached||activity==null)return;
  // A valid Java handle can belong to the old window surface after Recents.
  // Reparent the cached wallpaper into the current root after traversal.
  main.removeCallbacks(restoreUnderlay);
  activity.getWindow().getDecorView().postOnAnimation(()->{
   main.removeCallbacks(restoreUnderlay);main.post(restoreUnderlay);
  });
 }
 void detach(){
  attached=false;requests.invalidate();ready=false;placeholder=false;onReady=null;main.removeCallbacksAndMessages(null);unregister();
  if(thread!=null){thread.quitSafely();thread=null;bg=null;}
  if(underlay!=null){try(android.view.SurfaceControl.Transaction t=new android.view.SurfaceControl.Transaction()){t.reparent(underlay,null).apply();}underlay.release();underlay=null;}
  recycle(nativeBitmap);nativeBitmap=null;
  owned=null;activity=null;
 }
 private void register(){
  if(activity==null||receiver!=null)return;
  receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){scheduleRefresh("wallpaper");}};
  try{activity.registerReceiver(receiver,new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED),Context.RECEIVER_EXPORTED);}
  catch(Throwable e){Log.w(TAG,"backdrop_receiver_failed",e);receiver=null;}
  try{
   wallpaperManager=WallpaperManager.getInstance(activity);
   colorsListener=(colors,which)->{if((which&WallpaperManager.FLAG_SYSTEM)!=0)scheduleRefresh("colors");};
   wallpaperManager.addOnColorsChangedListener(colorsListener,main);
  }catch(Throwable e){Log.w(TAG,"backdrop_colors_failed",e);}
 }
 private void unregister(){
  if(activity!=null&&receiver!=null){try{activity.unregisterReceiver(receiver);}catch(Throwable ignored){}receiver=null;}
  if(wallpaperManager!=null&&colorsListener!=null){try{wallpaperManager.removeOnColorsChangedListener(colorsListener);}catch(Throwable ignored){}}
  wallpaperManager=null;colorsListener=null;
 }
 private void scheduleRefresh(String reason){
  main.removeCallbacks(refreshRunnable);
  Log.i(TAG,"backdrop_schedule reason="+reason);
  main.postDelayed(refreshRunnable,400);
 }
 private void refresh(String reason){
  if(!attached||activity==null||bg==null)return;
  final int rotation=displayRotation();
  final int[] size=displaySize();
  final int gen=requests.begin(rotation,size[0],size[1],"scheduled".equals(reason));
  if(gen<0){Log.i(TAG,"backdrop_coalesced reason="+reason);return;}
  final long began=android.os.SystemClock.uptimeMillis();
  Log.i(TAG,"backdrop_refresh reason="+reason+" rotation="+rotation+" size="+size[0]+"x"+size[1]);
  final Activity host=activity;
  bg.post(()->{
   if(!requests.current(gen))return;
   Prepared prepared=load(host,rotation,size[0],size[1]);
   main.post(()->{Log.i(TAG,"backdrop_load_finished token="+gen+" ms="+(android.os.SystemClock.uptimeMillis()-began));apply(gen,host,prepared,rotation,size[0],size[1]);});
  });
 }
 private Prepared load(Activity host,int rotation,int displayW,int displayH){
  try{
   Bitmap src=fetchDrawable(host);
   if(!usable(src))return Prepared.fallback("empty");
   Bitmap prepared=prepare(src,rotation,displayW,displayH);
   if(!usable(prepared))return Prepared.fallback("prepare");
   return new Prepared(prepared,"wallpaper",lightWallpaper(host));
  }catch(Throwable e){
   Log.w(TAG,"backdrop_load_failed",e);
   return Prepared.fallback(e.getClass().getSimpleName());
  }
 }
 private void apply(int gen,Activity host,Prepared prepared,int rotation,int displayW,int displayH){
  if(!attached||host!=activity||host.isFinishing()||host.isDestroyed()||!requests.complete(gen)){
   recycle(prepared==null?null:prepared.bitmap);
   return;
  }
  Window window=host.getWindow();
  visualGeneration++;
  if(prepared==null||prepared.bitmap==null){
   window.setBackgroundDrawable(new ColorDrawable(Ui.CHROME));
   Ui.overlaySystemBars(host,false);
   Bitmap previous=owned;owned=null;
   appliedRotation=rotation;appliedW=displayW;appliedH=displayH;
   Log.w(TAG,"backdrop_fallback reason="+(prepared==null?"null":prepared.source));
   signalReady();
   if(previous!=null)main.postDelayed(()->{if(previous!=owned)recycle(previous);},1000);
   return;
  }
  BitmapDrawable drawable=new BitmapDrawable(host.getResources(),prepared.bitmap);
  drawable.setGravity(Gravity.FILL);
  window.setBackgroundDrawable(drawable);
  Ui.overlaySystemBars(host,prepared.light);
  Bitmap previous=owned;owned=prepared.bitmap;
  ready=false;
  if(!applyUnderlay(host,owned,displayW,displayH))restoreSurface();
  appliedRotation=rotation;appliedW=displayW;appliedH=displayH;
  Log.i(TAG,"backdrop_applied source="+prepared.source+" size="+prepared.bitmap.getWidth()+"x"+prepared.bitmap.getHeight()+" lightBars="+prepared.light);
  if(previous!=null&&previous!=owned)main.postDelayed(()->{if(previous!=owned)recycle(previous);},1000);
 }
 private boolean applyUnderlay(Activity host,Bitmap bitmap,int width,int height){
  try{
   java.lang.reflect.Method rootMethod=android.view.View.class.getDeclaredMethod("getViewRootImpl");rootMethod.setAccessible(true);
   Object root=rootMethod.invoke(host.getWindow().getDecorView());
   if(root==null)return false;
   android.view.SurfaceControl parent=(android.view.SurfaceControl)root.getClass().getMethod("getSurfaceControl").invoke(root);
   if(parent==null||!parent.isValid())return false;
   if(underlay==null||!underlay.isValid())underlay=new android.view.SurfaceControl.Builder().setName("WindowDeckWallpaperUnderlay").setParent(parent).setBufferSize(bitmap.getWidth(),bitmap.getHeight()).build();
   Bitmap next=bitmap.copy(Bitmap.Config.HARDWARE,false);
   try(android.hardware.HardwareBuffer buffer=next.getHardwareBuffer();android.view.SurfaceControl.Transaction t=new android.view.SurfaceControl.Transaction()){
    t.reparent(underlay,parent).setBuffer(underlay,buffer).setLayer(underlay,-10000).setScale(underlay,width/(float)bitmap.getWidth(),height/(float)bitmap.getHeight()).setPosition(underlay,0,0).setVisibility(underlay,true);
    t.addTransactionCommittedListener(main::post,()->{if(attached&&activity==host&&owned==bitmap){signalReady();Log.i(TAG,"wallpaper_underlay_ready committed=true");}});t.apply();
   }
   Bitmap previous=nativeBitmap;nativeBitmap=next;if(previous!=null)main.postDelayed(()->recycle(previous),1000);
   return true;
  }catch(Throwable e){Log.w(TAG,"wallpaper_underlay_failed",e);return false;}
 }
 private static Bitmap fetchDrawable(Activity host){
  try{
   WallpaperManager wm=WallpaperManager.getInstance(host);
   Drawable drawable=wm.getFastDrawable();
   if(drawable==null)drawable=wm.peekDrawable();
   if(drawable instanceof BitmapDrawable)return ((BitmapDrawable)drawable).getBitmap();
   if(drawable==null)return null;
   int w=Math.max(1,drawable.getIntrinsicWidth()),h=Math.max(1,drawable.getIntrinsicHeight());
   Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
   Canvas canvas=new Canvas(bitmap);drawable.setBounds(0,0,w,h);drawable.draw(canvas);
   return bitmap;
  }catch(Throwable e){Log.w(TAG,"backdrop_drawable_failed",e);return null;}
 }
 private static Bitmap prepare(Bitmap src,int rotation,int displayW,int displayH){
  Bitmap working=copySoftware(src);
  if(working==null)return null;
  if(BackdropPolicy.needsBitmapRotation(false,rotation,working.getWidth(),working.getHeight(),displayW,displayH)){
   Bitmap rotated=rotate(working,BackdropPolicy.rotationDegrees(rotation));
   if(rotated!=working){recycleOwned(working);working=rotated;}
  }
  int width=Math.min(displayW,working.getWidth()),height=Math.max(1,Math.round(width*displayH/(float)displayW));
  Bitmap fitted=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
  int[] crop=BackdropPolicy.centerCrop(working.getWidth(),working.getHeight(),displayW,displayH);
  new Canvas(fitted).drawBitmap(working,new Rect(crop[0],crop[1],crop[2],crop[3]),new Rect(0,0,width,height),new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
  recycleOwned(working);working=fitted;
  new Canvas(working).drawColor(BackdropPolicy.DIM);
  return working;
 }
 private static Bitmap copySoftware(Bitmap src){
  if(!usable(src))return null;
  Bitmap copy=src.copy(Bitmap.Config.ARGB_8888,true);
  if(copy!=null)return copy;
  Bitmap created=Bitmap.createBitmap(src.getWidth(),src.getHeight(),Bitmap.Config.ARGB_8888);
  new Canvas(created).drawBitmap(src,0,0,null);
  return created;
 }
 private static Bitmap rotate(Bitmap src,int degrees){
  if(degrees==0||!usable(src))return src;
  Matrix matrix=new Matrix();matrix.postRotate(degrees);
  return Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),matrix,true);
 }
 private static boolean lightWallpaper(Activity host){
  // WM already computes wallpaper colors. Rebuilding a palette from the entire prepared
  // bitmap serialized the ready handshake behind unnecessary CPU work on every cold entry.
  try{WallpaperColors colors=WallpaperManager.getInstance(host).getWallpaperColors(WallpaperManager.FLAG_SYSTEM);return colors!=null&&BackdropPolicy.lightBars(colors.getColorHints());}
  catch(Throwable e){return false;}
 }
 private int displayRotation(){
  // This underlay belongs to the fixed portrait workbench, not the fullscreen app
  // selected on the launcher. Do not start a landscape load during that transition.
  return 0;
 }
 private int[] displaySize(){
  Rect bounds=activity.getWindowManager().getMaximumWindowMetrics().getBounds();
  return new int[]{Math.max(1,Math.min(bounds.width(),bounds.height())),Math.max(1,Math.max(bounds.width(),bounds.height()))};
 }
 private static boolean usable(Bitmap bitmap){return bitmap!=null&&!bitmap.isRecycled()&&bitmap.getWidth()>0&&bitmap.getHeight()>0;}
 private static void recycle(Bitmap bitmap){recycleOwned(bitmap);}
 private static void recycleOwned(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
 private static final class Prepared {
  final Bitmap bitmap; final String source; final boolean light;
  Prepared(Bitmap bitmap,String source,boolean light){this.bitmap=bitmap;this.source=source;this.light=light;}
  static Prepared fallback(String reason){return new Prepared(null,reason,false);}
 }
}
