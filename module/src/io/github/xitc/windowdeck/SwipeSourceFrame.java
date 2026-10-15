package io.github.xitc.windowdeck;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XposedHelpers;

/**
 * Source-task pixels for one swipe gesture.
 *
 * <p>The gesture-start snapshot and the release-layer capture are both owned here, including the
 * generation that cancels a late callback. Sampling the simulator rect stays on {@link SourceMotion}.
 * Cover transactions and the workbench launch stay with {@link SwipeReleaseHandoff}.
 */
final class SwipeSourceFrame {
 private static final String TAG="WindowDeck";
 private final SourceMotion motion=new SourceMotion();
 private int generation;
 private int task=-1;
 private Bitmap frame;

 SourceMotion motion() { return motion; }

 void resetMotion() { motion.reset(); }

 /**
  * Drops any frame from the previous attempt and returns the generation a capture thread must echo.
  * A callback whose generation does not match is recycled.
  */
 int openGeneration() {
  int token=++generation;
  HandoffCover.recycle(frame);
  frame=null;
  task=-1;
  return token;
 }

 /** Gesture end: forget the motion trail and invalidate an in-flight snapshot. */
 void endGesture() {
  motion.reset();
  generation++;
  HandoffCover.recycle(frame);
  frame=null;
  task=-1;
 }

 /**
  * Background task snapshot. Posted back to the panel; ignored when the gesture moved on or the
  * running task changed. Does not cover the finger-driven task.
  */
 void schedule(View panel,Object swipe,int sourceTask,int token) {
  final long captureStarted=SystemClock.uptimeMillis();
  new Thread(()->{
   Bitmap shot=HandoffCover.captureTask(sourceTask);
   panel.post(()->{
    if(generation!=token||CandidateProbe.runningTask(swipe)!=sourceTask) {
     HandoffCover.recycle(shot);
     return;
    }
    if(!HandoffCover.taskFrame(shot)) {
     Log.w(TAG,"swipe_source_snapshot_unavailable task="+sourceTask+" ms="+(SystemClock.uptimeMillis()-captureStarted));
     HandoffCover.recycle(shot);
     return;
    }
    frame=shot;
    task=sourceTask;
    Log.i(TAG,"swipe_source_snapshot task="+sourceTask+" ms="+(SystemClock.uptimeMillis()-captureStarted));
   });
  },"windowdeck-source").start();
 }

 /** The held snapshot when it belongs to {@code wanted}, then forgotten. A mismatch is left in place. */
 Bitmap takeFrame(int wanted) {
  if(task!=wanted)return null;
  Bitmap found=frame;
  if(found!=null)frame=null;
  return found;
 }

 void sample(Object handler,int task) {
  try {
   Object[] handles=(Object[])XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES);
   if(handles==null||handles.length!=1)return;
   Object simulator=XposedHelpers.callMethod(handles[0],RomSymbols.SOURCE_SIMULATOR);
   Matrix matrix=new Matrix((Matrix)XposedHelpers.getObjectField(simulator,RomSymbols.SOURCE_MATRIX));
   RectF rect=new RectF((RectF)XposedHelpers.callMethod(simulator,RomSymbols.SOURCE_CROP));
   matrix.mapRect(rect);
   motion.sample(task,SystemClock.uptimeMillis(),new float[]{rect.left,rect.top,rect.right,rect.bottom});
  } catch(Throwable ignored) { motion.reset(); }
 }

 /** Window.getAttributes().token can still be null before WindowManager's
  * adjustment. Read the attached decor's actual application LayoutParams,
  * never View.getWindowToken(), which is an IWindow binder rather than an app token. */
 static Activity activity(View panel) {
  try {
   Context context=panel.getContext();
   Activity found=null;
   for(int i=0;i<16&&context!=null;i++) {
    if(context instanceof Activity) { found=(Activity)context; break; }
    if(!(context instanceof ContextWrapper))break;
    Context next=((ContextWrapper)context).getBaseContext();
    if(next==context)break;
    context=next;
   }
   if(found==null) {
    Class<?> launcher=XposedHelpers.findClass(RomSymbols.LAUNCHER_CLASS,panel.getContext().getClassLoader());
    Object tracker=XposedHelpers.getStaticObjectField(launcher,RomSymbols.LAUNCHER_TRACKER);
    Object tracked=tracker==null?null:XposedHelpers.callMethod(tracker,RomSymbols.TRACKER_ACTIVITY);
    if(tracked instanceof Activity) found=(Activity)tracked;
   }
   if(found==null||found.isFinishing()||found.isDestroyed())return null;
   View decor=found.getWindow().getDecorView();
   if(!decor.isAttachedToWindow()||!(decor.getLayoutParams() instanceof WindowManager.LayoutParams))return null;
   WindowManager.LayoutParams attached=(WindowManager.LayoutParams)decor.getLayoutParams();
   return attached.type>=1&&attached.type<=99&&attached.token!=null?found:null;
  } catch(Throwable e) { Log.w(TAG,"swipe_source_window_unavailable",e); return null; }
 }

 /** Only the single fullscreen handle and the uniquely matching task target. */
 static final class Capture {
  final SurfaceControl leash,visibility;
  final int task,layer,left,top,width,height;
  private boolean visibilityClosed,hidden;

  Capture(SurfaceControl leash,SurfaceControl visibility,int task,int layer,Rect bounds) {
   this.leash=leash;
   this.visibility=visibility;
   this.task=task;
   this.layer=layer;
   left=bounds.left;
   top=bounds.top;
   width=bounds.width();
   height=bounds.height();
  }

  void conceal(SurfaceControl.Transaction transaction) {
   if(!visibilityClosed&&visibility.isValid()) { transaction.setAlpha(visibility,0f); hidden=true; }
  }

  void closeVisibility() {
   if(visibilityClosed)return;
   visibilityClosed=true;
   try {
    if(hidden&&visibility.isValid()) {
     try(SurfaceControl.Transaction restore=new SurfaceControl.Transaction()) { restore.setAlpha(visibility,1f).apply(); }
    }
   } catch(Throwable e) { Log.w(TAG,"swipe_source_visibility_restore_failed",e); }
   finally { visibility.release(); }
  }
 }

 static Capture capture(Object handler,int task) {
  SurfaceControl copied=null,visibility=null;
  try {
   Object[] handles=(Object[])XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES);
   if(handles==null||handles.length!=1)return null;
   Object params=XposedHelpers.callMethod(handles[0],RomSymbols.SOURCE_PARAMS);
   Object targets=params==null?null:XposedHelpers.callMethod(params,RomSymbols.SOURCE_TARGETS);
   Object[] apps=targets==null?null:(Object[])XposedHelpers.getObjectField(targets,RomSymbols.SOURCE_APPS);
   Object match=null;
   if(apps!=null) {
    for(Object app:apps) {
     if(XposedHelpers.getIntField(app,RomSymbols.SOURCE_TASK)==task) {
      if(match!=null)return null;
      match=app;
     }
    }
   }
   if(match==null)return null;
   SurfaceControl leash=(SurfaceControl)XposedHelpers.getObjectField(match,RomSymbols.SOURCE_LEASH);
   Rect bounds=(Rect)XposedHelpers.getObjectField(match,RomSymbols.SOURCE_BOUNDS);
   if(leash==null||!leash.isValid()||bounds==null||bounds.width()<2||bounds.height()<2)return null;
   int layer=LiveTaskContent.layerId(leash);
   if(layer<0)return null;
   copied=(SurfaceControl)XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckReleasePixels");
   if(!copied.isValid()||LiveTaskContent.layerId(copied)!=layer) { copied.release(); return null; }
   visibility=(SurfaceControl)XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckReleaseVisibility");
   return new Capture(copied,visibility,task,layer,bounds);
  } catch(Throwable e) {
   if(copied!=null)copied.release();
   if(visibility!=null)visibility.release();
   Log.w(TAG,"swipe_release_layer_unavailable",e);
   return null;
  }
 }

 static void capturePixels(Capture source,Object manager,Object controller,Handler main,HandoffCover[] cover) {
  final long began=SystemClock.uptimeMillis();
  Log.i(TAG,"swipe_release_capture task="+source.task+" layer="+source.layer+" buffer="+source.width+"x"+source.height+" capture_started_uptime_ms="+began);
  new Thread(()->{
   Bitmap pixels=null;
   try { pixels=LiveTaskContent.captureFrame(source.leash,source.width,source.height,Math.max(source.width,source.height)); }
   catch(Throwable e) { Log.w(TAG,"swipe_release_capture_failed task="+source.task,e); }
   finally { source.leash.release(); }
   final Bitmap shot=pixels;
   final long capturedAt=SystemClock.uptimeMillis();
   main.post(()->{
    HandoffCover current=cover[0];
    if(current==null||current.isClosed()) { HandoffCover.recycle(shot); return; }
    try {
     if(XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER)!=controller) {
      HandoffCover.recycle(shot);
      current.close("release_capture_superseded");
      return;
     }
    } catch(Throwable e) {
     HandoffCover.recycle(shot);
     current.close("release_capture_identity_failed");
     return;
    }
    Log.i(TAG,"swipe_release_pixels task="+source.task+" layer="+source.layer+" ms="+(SystemClock.uptimeMillis()-began)+" capture_completed_uptime_ms="+capturedAt);
    current.acceptSourcePixels(shot);
   });
  },"windowdeck-release-pixels").start();
 }

 static void planLandscape(View panel,HandoffCover cover,RectF releaseRect,float releaseRadius,float[] velocity,long released) {
  try {
   WindowManager wm=panel.getContext().getSystemService(WindowManager.class);
   if(wm==null)return;
   Rect bounds=wm.getMaximumWindowMetrics().getBounds();
   int shortEdge=Math.min(bounds.width(),bounds.height()),longEdge=Math.max(bounds.width(),bounds.height());
   int unit=Math.max(1,Ui.dp(panel.getContext(),1));
   int top=portraitTopInset(panel,unit);
   int mainPercent=MainWindowSettings.read(panel.getContext(),CardLayout.MAIN_DEFAULT_PERCENT);
   int[] card=LandscapeEntrance.card(shortEdge,longEdge-top,0,top,unit,longEdge,shortEdge,mainPercent);
   if(card==null)return;
   RectF predicted=new RectF(card[0],card[1],card[2],card[3]);
   Log.i(TAG,"swipe_landscape_plan stage="+shortEdge+"x"+(longEdge-top)+" origin=0,"+top+" card="+predicted);
   cover.planRelease(releaseRect,releaseRadius,predicted,Ui.dp(panel.getContext(),Ui.MAIN_RADIUS),0,velocity,released);
  } catch(Throwable e) { Log.w(TAG,"swipe_landscape_plan_failed",e); }
 }

 /** Portrait status/cutout only. A landscape nav-bar inset must not become the stage origin. */
 private static int portraitTopInset(View panel,int unit) {
  android.view.WindowInsets insets=panel.getRootWindowInsets();
  if(insets!=null) {
   android.graphics.Insets status=insets.getInsets(android.view.WindowInsets.Type.statusBars());
   android.graphics.Insets cutout=insets.getInsets(android.view.WindowInsets.Type.displayCutout());
   int top=Math.max(status.top,cutout.top);
   if(top>0)return top;
  }
  return unit*40;
 }
}
