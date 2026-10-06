package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import de.robv.android.xposed.XposedHelpers;

/** C17 transition buffer. Independent of the recents leash; never reparents the live task.
 * No new motion or fade: retain the release frame until the embedded task has drawn.
 * The bitmap is GPU backed, transient, and never persisted. Secure content uses WM defaults.
 */
final class HandoffCover {
 private static HandoffCover active;
 private final Handler main=new Handler(Looper.getMainLooper());
 private WindowManager windows;
 private ImageView view;
 private Bitmap bitmap;
 private boolean closed,dispatched;
 private final long started=SystemClock.uptimeMillis();
 private Runnable destination;
 private final Runnable commitTimeout=()->dispatch("commit_timeout");
 private final Runnable releaseTimeout=()->close("watchdog");

 static HandoffCover create(Context context){
  if(active!=null)active.close("superseded");
  HandoffCover cover=new HandoffCover();active=cover;
  try{
   cover.windows=context.getSystemService(WindowManager.class);
   Rect bounds=cover.windows.getMaximumWindowMetrics().getBounds();
   // Android 17 moved the privileged capture API from ScreenCapture to ScreenCaptureInternal.
   Class<?> capture=Class.forName("android.window.ScreenCaptureInternal");
   Object builder=XposedHelpers.newInstance(Class.forName("android.window.ScreenCaptureInternal$CaptureArgs$Builder"));
   XposedHelpers.callMethod(builder,"setSourceCrop",new Rect(0,0,bounds.width(),bounds.height()));
   Object args=XposedHelpers.callMethod(builder,"build");
   Object listener=XposedHelpers.callStaticMethod(capture,"createSyncCaptureListener");
   Object wm=XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),"getWindowManagerService");
   XposedHelpers.callMethod(wm,"captureDisplay",context.getDisplay().getDisplayId(),args,listener);
   Object buffer=XposedHelpers.callMethod(listener,"getBuffer");
   if(buffer==null)throw new IllegalStateException("No capture buffer");
   cover.bitmap=(Bitmap)XposedHelpers.callMethod(buffer,"asBitmap");
   if(cover.bitmap==null)throw new IllegalStateException("No capture bitmap");
   cover.view=new ImageView(context);
   cover.view.setScaleType(ImageView.ScaleType.FIT_XY);
   cover.view.setImageBitmap(cover.bitmap);
   WindowManager.LayoutParams p=new WindowManager.LayoutParams(
    WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
    2015, // TYPE_SECURE_SYSTEM_OVERLAY: launcher holds INTERNAL_SYSTEM_WINDOW.
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
     |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_FULLSCREEN
     |WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.OPAQUE);
   p.windowAnimations=0;
   p.gravity=Gravity.TOP|Gravity.LEFT;p.setTitle("WindowDeck handoff");
   p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
   p.setFitInsetsTypes(0);
   XposedHelpers.callMethod(p,"setTrustedOverlay");
   cover.windows.addView(cover.view,p);
   cover.main.postDelayed(cover.releaseTimeout,2000);
   Log.i("WindowDeck","handoff_cover_created capture_ms="+(SystemClock.uptimeMillis()-cover.started));
  }catch(Throwable e){Log.w("WindowDeck","handoff_cover_unavailable",e);cover.close("unavailable");}
  return cover;
 }
 void dispatchWhenDrawn(Runnable action){
  destination=action;
  if(closed||view==null){dispatch("unavailable");return;}
  view.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->dispatch("frame_committed")));
  view.invalidate();main.postDelayed(commitTimeout,250);
 }
 private void dispatch(String reason){
  if(dispatched||destination==null)return;
  dispatched=true;main.removeCallbacks(commitTimeout);
  if(!"frame_committed".equals(reason))close(reason);
  Log.i("WindowDeck","handoff_cover_dispatch reason="+reason+" ms="+(SystemClock.uptimeMillis()-started));
  Runnable action=destination;destination=null;action.run();
 }
 void close(String reason){
  if(closed)return;closed=true;
  main.removeCallbacks(commitTimeout);main.removeCallbacks(releaseTimeout);
  if(view!=null){try{windows.removeViewImmediate(view);}catch(Throwable e){Log.w("WindowDeck","handoff_cover_remove",e);}view.setImageDrawable(null);view=null;}
  // Let RenderThread release its bitmap reference naturally after window teardown.
  bitmap=null;if(active==this)active=null;
  Log.i("WindowDeck","handoff_cover_removed reason="+reason+" ms="+(SystemClock.uptimeMillis()-started));
 }
}
