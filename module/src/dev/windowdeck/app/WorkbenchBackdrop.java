package dev.windowdeck.app;

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
 private Object manager;
 private HandlerThread thread;
 private Handler bg;
 private BroadcastReceiver receiver;
 private WallpaperManager wallpaperManager;
 private WallpaperManager.OnColorsChangedListener colorsListener;
 private Bitmap owned,nativeBitmap;
 private android.view.SurfaceControl underlay;
 private int generation,appliedRotation=-1,appliedW,appliedH;
 private boolean attached;

 void attach(Activity a,Object flexibleManager){
  activity=a;manager=flexibleManager;
  if(thread==null){thread=new HandlerThread("WindowDeckBackdrop");thread.start();bg=new Handler(thread.getLooper());}
  attached=true;
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
  int[] size=displaySize();applyUnderlay(activity,owned,size[0],size[1]);
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
  attached=false;main.removeCallbacksAndMessages(null);unregister();
  if(thread!=null){thread.quitSafely();thread=null;bg=null;}
  if(underlay!=null){try(android.view.SurfaceControl.Transaction t=new android.view.SurfaceControl.Transaction()){t.reparent(underlay,null).apply();}underlay.release();underlay=null;}
  recycle(nativeBitmap);nativeBitmap=null;
  owned=null;activity=null;manager=null;
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
  final int gen=++generation;
  final int rotation=displayRotation();
  final int[] size=displaySize();
  Log.i(TAG,"backdrop_refresh reason="+reason+" rotation="+rotation+" size="+size[0]+"x"+size[1]);
  final Object flexible=manager;
  final Activity host=activity;
  bg.post(()->{
   Prepared prepared=load(host,flexible,rotation,size[0],size[1]);
   main.post(()->apply(gen,host,prepared,rotation,size[0],size[1]));
  });
 }
 private Prepared load(Activity host,Object flexible,int rotation,int displayW,int displayH){
  try{
   Bitmap src=fetchFlexible(flexible);
   boolean preoriented=true,blurred=true;String source="flexible";
   if(!usable(src)){src=fetchOplus(host);preoriented=false;source="oplus";}
   if(!usable(src)){src=fetchDrawable(host);preoriented=false;blurred=false;source="drawable";}
   if(!usable(src))return Prepared.fallback("empty");
   Bitmap prepared=prepare(src,preoriented,blurred,rotation,displayW,displayH);
   if(!usable(prepared))return Prepared.fallback("prepare");
   return new Prepared(prepared,source,lightWallpaper(prepared));
  }catch(Throwable e){
   Log.w(TAG,"backdrop_load_failed",e);
   return Prepared.fallback(e.getClass().getSimpleName());
  }
 }
 private void apply(int gen,Activity host,Prepared prepared,int rotation,int displayW,int displayH){
  if(gen!=generation||!attached||host!=activity||host.isFinishing()||host.isDestroyed()){
   recycle(prepared==null?null:prepared.bitmap);
   return;
  }
  Window window=host.getWindow();
  if(prepared==null||prepared.bitmap==null){
   window.setBackgroundDrawable(new ColorDrawable(Ui.CHROME));
   Ui.overlaySystemBars(host,false);
   Bitmap previous=owned;owned=null;
   appliedRotation=rotation;appliedW=displayW;appliedH=displayH;
   Log.w(TAG,"backdrop_fallback reason="+(prepared==null?"null":prepared.source));
   if(previous!=null)main.postDelayed(()->{if(previous!=owned)recycle(previous);},1000);
   return;
  }
  BitmapDrawable drawable=new BitmapDrawable(host.getResources(),prepared.bitmap);
  drawable.setGravity(Gravity.FILL);
  window.setBackgroundDrawable(drawable);
  Ui.overlaySystemBars(host,prepared.light);
  Bitmap previous=owned;owned=prepared.bitmap;
  applyUnderlay(host,owned,displayW,displayH);
  appliedRotation=rotation;appliedW=displayW;appliedH=displayH;
  Log.i(TAG,"backdrop_applied source="+prepared.source+" size="+prepared.bitmap.getWidth()+"x"+prepared.bitmap.getHeight()+" lightBars="+prepared.light);
  if(previous!=null&&previous!=owned)main.postDelayed(()->{if(previous!=owned)recycle(previous);},1000);
 }
 private void applyUnderlay(Activity host,Bitmap bitmap,int width,int height){
  try{
   java.lang.reflect.Method rootMethod=android.view.View.class.getDeclaredMethod("getViewRootImpl");rootMethod.setAccessible(true);
   Object root=rootMethod.invoke(host.getWindow().getDecorView());
   android.view.SurfaceControl parent=(android.view.SurfaceControl)root.getClass().getMethod("getSurfaceControl").invoke(root);
   if(underlay==null||!underlay.isValid())underlay=new android.view.SurfaceControl.Builder().setName("WindowDeckWallpaperUnderlay").setParent(parent).setBufferSize(bitmap.getWidth(),bitmap.getHeight()).build();
   Bitmap next=bitmap.copy(Bitmap.Config.HARDWARE,false);
   try(android.hardware.HardwareBuffer buffer=next.getHardwareBuffer();android.view.SurfaceControl.Transaction t=new android.view.SurfaceControl.Transaction()){
    t.reparent(underlay,parent).setBuffer(underlay,buffer).setLayer(underlay,-10000).setScale(underlay,width/(float)bitmap.getWidth(),height/(float)bitmap.getHeight()).setPosition(underlay,0,0).setVisibility(underlay,true).apply();
   }
   Bitmap previous=nativeBitmap;nativeBitmap=next;if(previous!=null)main.postDelayed(()->recycle(previous),1000);
   Log.i(TAG,"wallpaper_underlay_ready");
  }catch(Throwable e){Log.w(TAG,"wallpaper_underlay_failed",e);}
 }
 private static Bitmap fetchFlexible(Object flexible){
  if(flexible==null)return null;
  try{return (Bitmap)flexible.getClass().getMethod("getCurrentBlurWallpaper").invoke(flexible);}
  catch(Throwable e){Log.w(TAG,"backdrop_flexible_failed",e);return null;}
 }
 private static Bitmap fetchOplus(Activity host){
  try{
   Class<?> cls=Class.forName("android.app.OplusWallpaperManager");
   Object inst=cls.getDeclaredConstructor().newInstance();
   return (Bitmap)cls.getMethod("getBlurWallpaperBitmap",Context.class,int.class).invoke(inst,host,25);
  }catch(Throwable e){Log.w(TAG,"backdrop_oplus_failed",e);return null;}
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
 private static Bitmap prepare(Bitmap src,boolean preoriented,boolean blurred,int rotation,int displayW,int displayH){
  Bitmap working=copySoftware(src);
  if(working==null)return null;
  if(BackdropPolicy.needsBitmapRotation(preoriented,rotation,working.getWidth(),working.getHeight(),displayW,displayH)){
   Bitmap rotated=rotate(working,BackdropPolicy.rotationDegrees(rotation));
   if(rotated!=working){recycleOwned(working);working=rotated;}
  }
  if(!blurred){
   Bitmap next=downscaleBlur(working);
   if(next!=working){recycleOwned(working);working=next;}
  }
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
 private static Bitmap downscaleBlur(Bitmap src){
  if(!usable(src))return src;
  int w=Math.max(1,src.getWidth()/4),h=Math.max(1,src.getHeight()/4);
  Bitmap small=Bitmap.createScaledBitmap(src,w,h,true);
  Bitmap blurred=Bitmap.createScaledBitmap(small,src.getWidth(),src.getHeight(),true);
  if(small!=src&&small!=blurred)recycleOwned(small);
  return blurred;
 }
 private static boolean lightWallpaper(Bitmap bitmap){
  try{return BackdropPolicy.lightBars(WallpaperColors.fromBitmap(bitmap).getColorHints());}
  catch(Throwable e){return false;}
 }
 private int displayRotation(){
  Display display=activity.getDisplay();
  if(display==null)display=activity.getWindowManager().getDefaultDisplay();
  return display==null?0:display.getRotation();
 }
 private int[] displaySize(){
  Rect bounds=activity.getWindowManager().getMaximumWindowMetrics().getBounds();
  return new int[]{Math.max(1,bounds.width()),Math.max(1,bounds.height())};
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
