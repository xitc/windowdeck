package io.github.xitc.windowdeck;

import android.content.Context;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** C17 transition buffer. Independent of the recents leash; never reparents the live task.
 * Retains the release frame until the embedded task has drawn. A validated source/target
 * geometry can then continue the same task as a transient snapshot, with a static fallback.
 * The bitmap is GPU backed, transient, and never persisted. Secure content uses WM defaults.
 *
 * <p>Three ingress paths use it, all logged with the same {@code handoff_cover_*} tags so one
 * logcat filter sees them together:
 * <ul>
 *   <li><b>swipe</b> — {@code LauncherSwipeHook}, created while the recents animation is still
 *       running (the original use, unchanged);</li>
 *   <li><b>hang add / replace</b> — {@code WorkbenchActivity.bringWorkbenchForward()};</li>
 *   <li><b>existing app picked from the launcher</b> —
 *       {@code WorkbenchActivity.raiseHangContainer()}.</li>
 * </ul>
 *
 * <p>The two workbench paths create it <em>at raise time</em>, not when the hang starts: the user
 * has to see the launcher to pick an app, so a cover spanning the whole hang would black it out.
 * The gap it has to hide is the container surface rebuild (600–1700 ms of {@code destroySurface}
 * → {@code resetDrawState}), during which the ROM's transition has already hidden the launcher and
 * the wallpaper — see {@code docs/启动动画对比与改进.md} §1.1.1.
 */
final class HandoffCover {
 /** Supplies a workbench-local bitmap when the screen capture is not available. */
 interface Snapshot { Bitmap capture(); }

 private static final String TAG="WindowDeck";
 private static HandoffCover active;
 /** Last non-black, non-white frame taken at gesture start. Released by the next cover or gesture end. */
 private static Bitmap warm;
 private final Handler main=new Handler(Looper.getMainLooper());
 private WindowManager windows;
 private FrameLayout view;
 private SnapshotMotionView scene;
 private Bitmap bitmap;
 private Bitmap sourceFrame;
 private Bitmap poseSnapshot;
 private IBinder sourceToken;
 private boolean sourcePixelsPending;
 private boolean closed,dispatched;
 private String closedReason;
 private ValueAnimator motion,backgroundMotion;
 private Runnable pendingSourceMotion;
 private float geometryProgress,backgroundProgress;
 private boolean backgroundStarted;
 private Consumer<String> closedListener;
 private Consumer<Boolean> motionResult;
 private final HandoffReveal reveal=new HandoffReveal();
 private int width,height,captureWidth,captureHeight,captureRotation,viewportTurns=Integer.MIN_VALUE;
 private final long started=SystemClock.uptimeMillis();
 private RectF plannedSource,plannedTarget;
 private float plannedSourceRadius,plannedTargetRadius;
 private int plannedRotation=Integer.MIN_VALUE;
 private float[] plannedVelocity;
 private long plannedSampled;
 private boolean planned,sweepOwned,pendingAccept,planRejected,conceal,releaseShown,snapshotHeld,visualDetached;
 private Runnable underlay;
 private boolean underlayRan;
 private final String reason;
 private Runnable destination;
 private final Runnable commitTimeout=()->dispatch("commit_timeout");
 private final Runnable releaseTimeout=this::onCoverTimeout;

 private HandoffCover(String reason){this.reason=reason;}

 static HandoffCover create(Context context){
  return create(context,"swipe",null,false,null);
 }

 /** Landscape swipe. {@code holdBleed} keeps the first buffer chrome until the release crop is armed.
  *  {@code warm} replaces a black or white capture and is recycled when unused. */
 static HandoffCover create(Context context,boolean holdBleed,Bitmap warm){
  return create(context,"swipe",null,holdBleed,warm);
 }
 static HandoffCover create(Context context,boolean holdBleed,Bitmap warm,Bitmap taskFrame){
  return create(context,holdBleed,warm,taskFrame,false);
 }
 static HandoffCover create(Context context,boolean holdBleed,Bitmap warm,Bitmap taskFrame,boolean preferTaskFrame){
  HandoffCover cover=create(context,"swipe",null,holdBleed,warm);
  // A valid recents display capture can already contain its source card blended
  // with an earlier frame. Keep that capture as background; move the opaque task
  // snapshot independently, and pass those same pixels to the destination host.
  if(!cover.closed&&(preferTaskFrame||!coverFrame(cover.bitmap))&&taskFrame(taskFrame)){
   cover.sourceFrame=taskFrame;cover.scene.sourceFrame(taskFrame);
   Log.i(TAG,"handoff_source_frame task_snapshot=true live=false size="+taskFrame.getWidth()+"x"+taskFrame.getHeight());
  }else{
   recycle(taskFrame);
   if(!cover.closed&&!coverFrame(cover.bitmap))cover.close("source_pixels_unavailable");
  }
  return cover;
 }

 /** A first landscape entry stays in the source application's native transition.
  * No system-overlay token is created, so AsyncRotationController cannot rotate
  * an old overlay buffer independently of the destination application window. */
 static HandoffCover createSource(android.app.Activity source,Bitmap warmFrame){
  WindowManager.LayoutParams attached=(WindowManager.LayoutParams)source.getWindow().getDecorView().getLayoutParams();
  IBinder token=attached==null?null:attached.token;
  if(token==null)throw new IllegalStateException("Missing source application token");
  return create(source,"swipe",null,true,warmFrame,null,token);
 }

 /** Only this release's layer capture can arm the source-token cover. */
 void acceptSourcePixels(Bitmap frame){
  if(closed||visualDetached||!sourcePixelsPending||poseSnapshot!=null){recycle(frame);return;}
  if(!taskFrame(frame)){recycle(frame);close("release_pixels_unavailable");return;}
  sourceFrame=frame;scene.sourceFrame(frame);sourcePixelsPending=false;
  Log.i(TAG,"handoff_source_frame source=release_layers task_snapshot=false live=false size="+frame.getWidth()+"x"+frame.getHeight());
  tryStartPlan();
 }

 /** Same buffer, but able to fall back to a workbench snapshot when the display cannot be
  *  captured — for the workbench process the privileged capture API is not guaranteed. */
 static HandoffCover create(Context context,String reason,Snapshot fallback){
  return create(context,reason,fallback,false,null);
 }
 /** The caller retains its task snapshot for the host arrival. Copy it only if
  * captureDisplay returned black and the task fills that same source viewport. */
 static HandoffCover create(Context context,String reason,Snapshot fallback,Bitmap taskFrame){
  return create(context,reason,fallback,false,null,taskFrame);
 }

 private static HandoffCover create(Context context,String reason,Snapshot fallback,boolean holdBleed,Bitmap warmFrame){
  return create(context,reason,fallback,holdBleed,warmFrame,null);
 }
 private static HandoffCover create(Context context,String reason,Snapshot fallback,boolean holdBleed,Bitmap warmFrame,Bitmap taskFrame){
  return create(context,reason,fallback,holdBleed,warmFrame,taskFrame,null);
 }
 private static HandoffCover create(Context context,String reason,Snapshot fallback,boolean holdBleed,Bitmap warmFrame,Bitmap taskFrame,IBinder sourceToken){
  if(active!=null)active.close("superseded");
  HandoffCover cover=new HandoffCover(reason);
  cover.sourceToken=sourceToken;cover.sourcePixelsPending=sourceToken!=null;
  try{
   cover.windows=context.getSystemService(WindowManager.class);
   Rect bounds=cover.windows.getMaximumWindowMetrics().getBounds();
   String source="display";
   // C17 Display.getRotation() may return the launcher's compact-window
   // rotation (portrait), even while captureDisplay returns the landscape
   // display. Read the compositor display's WM rotation instead.
   int captureRotation=captureRotation(context);
   Bitmap captured=captureNow(context);
   if(captureRotation!=captureRotation(context)){
    Log.w(TAG,"handoff_capture_rotation_changed");recycle(captured);captured=null;
   }
   if(usable(captured)&&!coverFrame(captured)&&taskFrame(taskFrame)
     &&DisplayGeometry.sameAspect(taskFrame.getWidth(),taskFrame.getHeight(),captured.getWidth(),captured.getHeight())){
    Bitmap copy=taskFrame.copy(Bitmap.Config.ARGB_8888,false);
    if(copy!=null){
     Bitmap sized=Bitmap.createScaledBitmap(copy,captured.getWidth(),captured.getHeight(),true);
     if(sized!=copy)recycle(copy);
     recycle(captured);captured=sized;source="task_snapshot";
     Log.i(TAG,"handoff_cover_bitmap source=task_snapshot live=false size="+captured.getWidth()+"x"+captured.getHeight());
    }
   }
   Bitmap passed=warmFrame,stored=borrowWarm();
   if(stored==passed)stored=null;
   if(coverFrame(captured)){recycle(passed);recycle(stored);}
   else if(!holdBleed&&coverFrame(passed)){
    recycle(captured);recycle(stored);captured=passed;source="warm";
    Log.i(TAG,"handoff_cover_bitmap source=warm");
   }else if(!holdBleed&&coverFrame(stored)){
    recycle(captured);recycle(passed);captured=stored;source="warm";
    Log.i(TAG,"handoff_cover_bitmap source=warm");
   }else{
    recycle(passed);recycle(stored);
    if(!usable(captured)){source="snapshot";captured=fallback==null?null:fallback.capture();}
    else Log.w(TAG,"handoff_cover_bitmap source=display usable=false");
   }
   if(!usable(captured))throw new IllegalStateException("No cover bitmap");
   cover.bitmap=captured;
   // Launcher stays portrait while the source display can be landscape. Its
   // maximum WindowMetrics therefore cannot describe a display capture. Keep
   // the full returned buffer and its own coordinate size; never crop to the
   // launcher's portrait bounds or stretch that crop into a different axis.
   cover.captureWidth=captured.getWidth();cover.captureHeight=captured.getHeight();
   cover.width=bounds.width();cover.height=bounds.height();
   cover.captureRotation="snapshot".equals(source)?0:captureRotation;
   cover.view=new FrameLayout(context);
   cover.scene=new SnapshotMotionView(context,captured,cover.captureWidth,cover.captureHeight);
   if(holdBleed){
    // Stay out of composition until the release bitmap is in a buffer. An opaque
    // window with no buffer is what the compositor draws as a full white frame.
    cover.conceal=true;cover.view.setBackgroundColor(0);cover.scene.holdBleed();
    cover.scene.whenReleasePainted(cover::showRelease);
   }else cover.view.setBackgroundColor(Ui.CHROME);
   cover.view.addView(cover.scene,new FrameLayout.LayoutParams(-1,-1));
   cover.view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
    if(cover.closed||cover.visualDetached||r<=l||b<=t)return;
    int vw=r-l,vh=b-t;
    // The sweep already locked the portrait stage. The 3168×1440 → 1440×3168 swap
    // is the same coordinate system, not a new travel and not a reason to close.
    if(cover.retainLockedViewport(vw,vh)){
     Log.i(TAG,"handoff_cover_layout reason="+reason+" viewport="+vw+"x"+vh+" locked=true turns="+DisplayGeometry.turns(cover.captureRotation,cover.plannedRotation));
     return;
    }
    if((cover.motion!=null||cover.sweepOwned)&&(vw!=cover.width||vh!=cover.height)){cover.close("display_changed_during_motion");return;}
    cover.width=vw;cover.height=vh;
    int wmRotation=context.getDisplay().getRotation();
    try{wmRotation=captureRotation(context);}catch(Throwable e){Log.w(TAG,"handoff_viewport_rotation_unavailable",e);}
    int viewportTurns=DisplayGeometry.viewportTurns(cover.captureWidth,cover.captureHeight,cover.width,cover.height,
      cover.captureRotation,wmRotation,context.getDisplay().getRotation());
    if(viewportTurns<0){cover.close("viewport_geometry_changed");return;}
    cover.viewportTurns=viewportTurns;
    cover.scene.rebase(viewportTurns);
    Log.i(TAG,"handoff_cover_layout reason="+reason+" viewport="+cover.width+"x"+cover.height+" wm_rotation="+wmRotation+" context_rotation="+context.getDisplay().getRotation()+" turns="+viewportTurns);
    cover.tryStartPlan();
   });
   cover.attach(context);
   cover.raise();
   active=cover;
   Log.i(TAG,"handoff_cover_created reason="+reason+" source="+source+" capture="+cover.captureWidth+"x"+cover.captureHeight+" rotation="+cover.captureRotation+" window="+cover.width+"x"+cover.height+" capture_ms="+(SystemClock.uptimeMillis()-cover.started));
   cover.main.postDelayed(cover.releaseTimeout,MotionSpec.COVER_TIMEOUT_MS);
  }catch(Throwable e){
   Log.w(TAG,"handoff_cover_unavailable reason="+reason,e);
   if(cover.bitmap!=warmFrame)recycle(warmFrame);
   cover.close("unavailable");
  }
  return cover;
 }

 /** Android 17 moved the privileged capture API from ScreenCapture to ScreenCaptureInternal. */
 private static int captureRotation(Context context) throws Exception {
  int displayId=context.getDisplay().getDisplayId();
  if(displayId==0){
   Object wm=XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),"getWindowManagerService");
   return (Integer)XposedHelpers.callMethod(wm,"getDefaultDisplayRotation");
  }
  Object global=XposedHelpers.callStaticMethod(Class.forName("android.hardware.display.DisplayManagerGlobal"),"getInstance");
  Object info=XposedHelpers.callMethod(global,"getDisplayInfo",displayId);
  return XposedHelpers.getIntField(info,"rotation");
 }

 static Bitmap captureNow(Context context){
  try{return captureDisplay(context.getDisplay().getDisplayId());}
  catch(Throwable e){Log.w(TAG,"handoff_cover_capture_failed",e);return null;}
 }

 /** The display id is resolved on the main thread. The capture itself can run in the background. */
 static Bitmap captureNow(int displayId){return captureDisplay(displayId);}

 private static Bitmap captureDisplay(int displayId){
  try{
   Class<?> capture=Class.forName("android.window.ScreenCaptureInternal");
   Object builder=XposedHelpers.newInstance(Class.forName("android.window.ScreenCaptureInternal$CaptureArgs$Builder"));
   Object args=XposedHelpers.callMethod(builder,"build");
   Object listener=XposedHelpers.callStaticMethod(capture,"createSyncCaptureListener");
   Object wm=XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),"getWindowManagerService");
   XposedHelpers.callMethod(wm,"captureDisplay",displayId,args,listener);
   Object buffer=XposedHelpers.callMethod(listener,"getBuffer");
   if(buffer==null)return null;
   return (Bitmap)XposedHelpers.callMethod(buffer,"asBitmap");
  }catch(Throwable e){Log.w(TAG,"handoff_cover_capture_failed",e);return null;}
 }

 private void attach(Context context){
  if(sourceToken!=null){
   WindowManager.LayoutParams application=params(WindowManager.LayoutParams.TYPE_APPLICATION);
   application.token=sourceToken;
   windows.addView(view,application);
   Log.i(TAG,"handoff_cover_window type=application source_token=true");
   return;
  }
  // 2015 (TYPE_SECURE_SYSTEM_OVERLAY) is what the launcher path uses and sits highest. It needs
  // INTERNAL_SYSTEM_WINDOW, which this process may not hold; 2038 is already proven here by the
  // hang shelf, so fall back to it rather than leaving the gap uncovered.
  try{windows.addView(view,params(2015));return;}
  catch(Throwable e){Log.w(TAG,"handoff_cover_type_2015_failed reason="+reason,e);}
  windows.addView(view,params(2038));
 }

 private WindowManager.LayoutParams params(int type){
  WindowManager.LayoutParams p=new WindowManager.LayoutParams(
   WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
   type,
   WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_FULLSCREEN
    |WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
   // Before its first buffer commits, an opaque overlay can be composed as a white plane.
   // Keep the source visible during attachment; the scene itself paints an opaque snapshot.
   PixelFormat.TRANSLUCENT);
  p.windowAnimations=0;
  if(conceal){
   p.alpha=0f;
   p.rotationAnimation=WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
  }
  p.gravity=Gravity.TOP|Gravity.LEFT;p.setTitle("WindowDeck handoff");
  p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
  p.setFitInsetsTypes(0);
  if(type==2015)try{XposedHelpers.callMethod(p,"setTrustedOverlay");}catch(Throwable ignored){}
  return p;
 }

 private static boolean usable(Bitmap bitmap){
  return bitmap!=null&&!bitmap.isRecycled()&&bitmap.getWidth()>0&&bitmap.getHeight()>0;
 }

 static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}

 /** Opaque pixels that are neither a black buffer nor a near-white rotation buffer. */
 static boolean coverFrame(Bitmap bitmap){
  int[] pixels=sample(bitmap);
  return TaskSurfaceEvidence.presented(pixels)&&!TaskSurfaceEvidence.washed(pixels);
 }
 static boolean taskFrame(Bitmap bitmap){return TaskSurfaceEvidence.opaqueSnapshot(sample(bitmap));}
 static boolean visibleTaskFrame(Bitmap bitmap){return TaskSurfaceEvidence.presented(sample(bitmap));}
 /** Off-thread preparation; the caller rejects late results from a different gesture/task. */
 static Bitmap captureTask(int task){
  try{
   Object wm=XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),"getWindowManagerService");
   return (Bitmap)XposedHelpers.callMethod(wm,"snapshotTaskForRecents",task);
  }catch(Throwable e){Log.w(TAG,"swipe_source_snapshot_failed task="+task,e);return null;}
 }

 private static int[] sample(Bitmap bitmap){
  if(!usable(bitmap))return null;
  Bitmap copy=null;
  try{
   Bitmap read=bitmap;
   if(bitmap.getConfig()!=Bitmap.Config.ARGB_8888){
    copy=bitmap.copy(Bitmap.Config.ARGB_8888,false);
    if(!usable(copy))return null;
    read=copy;
   }
   int w=read.getWidth(),h=read.getHeight(),n=0;
   int[] out=new int[9];
   for(int y=1;y<=3;y++)for(int x=1;x<=3;x++)out[n++]=read.getPixel(Math.min(w-1,w*x/4),Math.min(h-1,h*y/4));
   return out;
  }catch(Throwable e){Log.w(TAG,"handoff_cover_sample_failed",e);return null;}
  finally{recycle(copy);}
 }

 static void remember(Bitmap bitmap){
  if(bitmap!=null&&bitmap.isRecycled())bitmap=null;
  Bitmap old=warm;warm=bitmap;
  if(old!=null&&old!=warm)recycle(old);
 }

 static void forgetWarm(){recycle(borrowWarm());}

 private static Bitmap borrowWarm(){Bitmap frame=warm;warm=null;return frame;}

 /** Runs once, before the cover fades, so the launcher recents leash is already hidden. */
 void concealUnderlay(Runnable action){if(!closed&&action!=null)underlay=action;}

 void hideUnderlay(){
  // The first source-token buffer must be shown before hiding launcher UI.
  if(sourceToken!=null&&!releaseShown&&!closed)return;
  Runnable action=underlay;
  if(action==null||underlayRan)return;
  underlayRan=true;
  try{action.run();}catch(Throwable e){Log.w(TAG,"handoff_underlay_failed",e);}
 }

 private void raise(){
  if(view==null)return;
  view.post(()->{
   if(closed||view==null)return;
   try{
    Object vri=XposedHelpers.callMethod(view,"getViewRootImpl");
    if(vri==null)return;
    SurfaceControl sc=(SurfaceControl)XposedHelpers.callMethod(vri,"getSurfaceControl");
    if(sc==null||!sc.isValid())return;
    try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){transaction.setLayer(sc,Integer.MAX_VALUE).apply();}
    Log.i(TAG,"handoff_cover_raised");
   }catch(Throwable e){Log.w(TAG,"handoff_cover_raise_failed",e);}
  });
 }

 void dispatchWhenDrawn(Runnable action){
  if(dispatched){action.run();return;}
  if(destination!=null)return;
  destination=action;
  if(closed||view==null){dispatch(closedReason==null?"unavailable":closedReason);return;}
  if(conceal&&!releaseShown){main.postDelayed(commitTimeout,MotionSpec.FRAME_TIMEOUT_MS);return;}
  armDispatch();
 }
 /** The release bitmap has been drawn. Present that buffer before the portrait host starts. */
 private void showRelease(){
  if(closed||releaseShown||view==null)return;
  view.invalidate();
  view.getViewTreeObserver().registerFrameCommitCallback(()->main.post(this::presentRelease));
 }
 private void presentRelease(){
  if(closed||releaseShown||view==null)return;
  releaseShown=true;
  try{
   WindowManager.LayoutParams shown=(WindowManager.LayoutParams)view.getLayoutParams();
   shown.alpha=1f;windows.updateViewLayout(view,shown);
  }catch(Throwable e){
   Log.w(TAG,"handoff_cover_show_failed",e);
   if(sourceToken!=null){close("source_window_show_failed");return;}
  }
  Log.i(TAG,"handoff_cover_opaque ms="+(SystemClock.uptimeMillis()-started));
  if(sourceToken!=null){
   // The earlier callback committed a buffer while this window had alpha 0.
   // Draw its alpha-1 layout before veiling the task above the Launcher window.
   view.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->{
    if(closed||visualDetached||view==null)return;
    hideUnderlay();
    Runnable start=pendingSourceMotion;pendingSourceMotion=null;
    if(start!=null)view.postOnAnimation(()->{if(!closed&&!visualDetached)start.run();});
    if(destination!=null)armDispatch();
   }));view.invalidate();return;
  }
  hideUnderlay();
  if(destination!=null)armDispatch();
 }
 private void armDispatch(){
  if(closed||view==null||dispatched||destination==null)return;
  view.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->dispatch("frame_committed")));
  view.invalidate();main.removeCallbacks(commitTimeout);main.postDelayed(commitTimeout,MotionSpec.FRAME_TIMEOUT_MS);
 }
 private void onCoverTimeout(){
  if(closed||visualDetached)return;
  long age=SystemClock.uptimeMillis()-started;
  if(sweepOwned&&!reveal.hasLive()&&age<MotionSpec.LANDSCAPE_REVEAL_MS){
   if(!snapshotHeld){snapshotHeld=true;Log.i(TAG,"handoff_snapshot_held ms="+age+" cap_ms="+MotionSpec.LANDSCAPE_REVEAL_MS);}
   main.postDelayed(releaseTimeout,Math.max(16L,Math.min(MotionSpec.COVER_TIMEOUT_MS,MotionSpec.LANDSCAPE_REVEAL_MS-age)));
   return;
  }
  if(reveal.isRevealing()&&age<MotionSpec.LANDSCAPE_REVEAL_MS){main.postDelayed(releaseTimeout,MotionSpec.CROSS_FADE_MS+32);return;}
  close("watchdog");
 }
 boolean isClosed(){return closed;}
 /** Bootstrap may arrive before the launcher travel ends. Never freeze it mid-flight. */
 boolean posePending(){return !closed&&!visualDetached&&(sourcePixelsPending||planned||(motion!=null&&geometryProgress<1f));}
 /** Current on-screen card and its pixels. Crop is capture space, not the task buffer.
  * A HardwareBuffer parcel shares the GPU buffer rather than display-sized pixel bytes. */
 android.os.Bundle samplePose(){
  if(closed||scene==null||view==null||visualDetached||posePending())return null;
  // Freeze the visual pose until the host has committed that exact pose and live pixels.
  if(motion!=null)motion.pause();
  float[] corners=scene.sampleStageCorners();
  float[] crop=scene.sampleCrop();
  int[] stage=scene.sampleStage();
  if(corners==null||crop==null||stage==null)return null;
  for(float v:corners)if(!Float.isFinite(v))return null;
  android.os.Bundle pose=new android.os.Bundle();
  pose.putFloatArray(HandoffProtocol.CORNERS,corners);
  pose.putFloatArray(HandoffProtocol.CROP,crop);
  pose.putIntArray(HandoffProtocol.POSE_STAGE,stage);
  pose.putFloat(HandoffProtocol.RADIUS,scene.sampleRadius());
  try{
   Bitmap pixels=sourceFrame==null?bitmap:sourceFrame;
   if(!usable(pixels))return null;
   if(poseSnapshot==null)poseSnapshot=pixels.getConfig()==Bitmap.Config.HARDWARE?pixels:pixels.copy(Bitmap.Config.HARDWARE,false);
   if(!usable(poseSnapshot))return null;
   pose.putParcelable(HandoffProtocol.SNAPSHOT,poseSnapshot.getHardwareBuffer());
   pose.putParcelable(HandoffProtocol.SNAPSHOT_COLOR_SPACE,new android.graphics.ParcelableColorSpace(poseSnapshot.getColorSpace()));
   pose.putFloatArray(HandoffProtocol.SNAPSHOT_CROP,sourceFrame==null?crop.clone():new float[]{0,0,pixels.getWidth(),pixels.getHeight()});
  }catch(Throwable e){Log.w(TAG,"handoff_pose_snapshot_failed",e);return null;}
  return pose;
 }
 /** Called after native source release and live-pose commit for a source-token cover. */
 boolean detachVisual(){
  if(visualDetached)return true;
  if(closed||view==null)return false;
  try{windows.removeViewImmediate(view);}catch(Throwable e){Log.w(TAG,"handoff_cover_motion_release_failed",e);return false;}
  visualDetached=true;
  pendingSourceMotion=null;
  main.removeCallbacks(releaseTimeout);
  if(motion!=null){ValueAnimator old=motion;motion=null;old.cancel();}
  if(backgroundMotion!=null){ValueAnimator old=backgroundMotion;backgroundMotion=null;old.cancel();}
  Log.i(TAG,"handoff_cover_motion_released");
  return true;
 }
 void onClosed(Consumer<String> listener){closedListener=listener;if(closed)listener.accept(closedReason);}
 boolean canPrepare(RectF source,RectF target,int targetRotation,float targetRadius,boolean hasBackdrop){
  if(closed||view==null)return false;
  if(hasBackdrop)return true;
  if(source==null||target==null)return false;
  int turns=DisplayGeometry.turns(captureRotation,targetRotation);
  float[] from=DisplayGeometry.rect(new float[]{source.left,source.top,source.right,source.bottom},captureWidth,captureHeight,turns);
  // Rounded rectangles are convex; interpolation toward a rounded target
  // containing the source rect cannot uncover the old task while wallpaper loads.
  return DisplayGeometry.roundedTargetCovers(from,new float[]{target.left,target.top,target.right,target.bottom},targetRadius);
 }
 /** Landscape only. Portrait keeps the host-timed path. A quarter-turn starts on the
  *  swapped-capture stage immediately; it does not wait for the portrait viewport. */
 void planRelease(RectF source,float sourceRadius,RectF target,float targetRadius,int targetRotation,float[] velocity,long sampledAt){
  if(closed||view==null)return;
  int turns=DisplayGeometry.turns(captureRotation,targetRotation);
  if(turns==0)return;
  if(!valid(source,captureWidth,captureHeight)||target==null||target.width()<1||target.height()<1
    ||!Float.isFinite(target.left)||!Float.isFinite(target.top)||!Float.isFinite(target.right)||!Float.isFinite(target.bottom)
    ||!Float.isFinite(sourceRadius)||!Float.isFinite(targetRadius)||sourceRadius<0||targetRadius<0){
   Log.w(TAG,"handoff_plan_skipped turns="+turns+" source="+source+" target="+target);return;
  }
  plannedSource=new RectF(source);plannedTarget=new RectF(target);
  plannedSourceRadius=sourceRadius;plannedTargetRadius=targetRadius;plannedRotation=targetRotation;
  plannedVelocity=velocity==null?null:velocity.clone();plannedSampled=sampledAt;planned=true;
  Log.i(TAG,"handoff_plan_release turns="+turns+" source="+source+" target="+target+" window="+width+"x"+height+" viewport_turns="+viewportTurns);
  hideUnderlay();
  tryStartPlan();
 }
 boolean ownsTravel(){return motion!=null||sweepOwned;}
 /** Recents finish can precede the display's portrait relayout. Do not launch
  * the new container with the old landscape configuration under this cover. */
 void whenPortraitViewportCommitted(Runnable action){
  final long requested=SystemClock.uptimeMillis();
  Runnable check=new Runnable(){public void run(){
   if(closed||view==null)return;
   boolean portrait=false;
   try{portrait=view.getWidth()>0&&view.getWidth()<view.getHeight()&&captureRotation(view.getContext())==0;}
   catch(Throwable e){Log.w(TAG,"handoff_launch_viewport_unavailable",e);}
   if(!portrait){
    if(SystemClock.uptimeMillis()-requested>=MotionSpec.FRAME_TIMEOUT_MS){close("portrait_launch_viewport_timeout");return;}
    main.postDelayed(this,16);return;
   }
   view.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->{
    if(closed||view==null)return;
    try{if(view.getWidth()<=0||view.getWidth()>=view.getHeight()||captureRotation(view.getContext())!=0){main.post(this);return;}}
    catch(Throwable e){close("portrait_launch_viewport_unavailable");return;}
    Log.i(TAG,"handoff_launch_viewport_committed wait_ms="+(SystemClock.uptimeMillis()-requested));
    action.run();
   }));
   view.invalidate();
  }};
  main.post(check);
 }
 private void tryStartPlan(){
  if(!planned||sourcePixelsPending||motion!=null||sweepOwned||closed||view==null||scene==null)return;
  int turns=DisplayGeometry.turns(captureRotation,plannedRotation);
  if(!valid(plannedSource,captureWidth,captureHeight)){planned=false;return;}
  if(sourceFrame!=null&&!DisplayGeometry.sameAspect(sourceFrame.getWidth(),sourceFrame.getHeight(),plannedSource.width(),plannedSource.height())){
   close("source_snapshot_geometry_changed");return;
  }
  int stageW=(turns&1)==0?captureWidth:captureHeight,stageH=(turns&1)==0?captureHeight:captureWidth;
  if((turns&1)==1){
   if(!valid(plannedTarget,stageW,stageH)){
    if(!planRejected){planRejected=true;Log.w(TAG,"handoff_plan_target_rejected target="+plannedTarget+" stage="+stageW+"x"+stageH);}
    return;
   }
  }else{
   if(view.getWidth()!=width||view.getHeight()!=height||width<1||height<1)return;
   if(viewportTurns!=turns||!DisplayGeometry.matches(captureWidth,captureHeight,width,height,turns))return;
   if(!valid(plannedTarget,width,height)){
    if(!planRejected){planRejected=true;Log.w(TAG,"handoff_plan_target_rejected target="+plannedTarget+" window="+width+"x"+height);}
    return;
   }
  }
  planned=false;
  beginMotion(plannedSource,plannedSourceRadius,plannedTarget,plannedTargetRadius,null,turns,plannedVelocity,plannedSampled,false);
 }
 /** Quarter-turn travel keeps one stage. The capture-sized view and the portrait view are both that stage. */
 private boolean retainLockedViewport(int viewW,int viewH){
  if(!sweepOwned&&motion==null)return false;
  int turns=DisplayGeometry.turns(captureRotation,plannedRotation);
  if((turns&1)!=1)return false;
  return DisplayGeometry.sameStage(viewW,viewH,captureHeight,captureWidth,turns);
 }
 /** Returns false for unsupported/off-screen geometry; never invents a source rectangle. */
 boolean animateTo(RectF source,float sourceRadius,RectF target,float targetRadius,Bitmap targetBackdrop,int targetRotation,float[] velocity,long sampledAt,boolean liveReady,Consumer<Boolean> result){
  int turns=DisplayGeometry.turns(captureRotation,targetRotation);
  if(closed||view==null||scene==null||!usable(bitmap)||source==null||target==null
    ||!Float.isFinite(sourceRadius)||!Float.isFinite(targetRadius)||sourceRadius<0||targetRadius<0
    ||!Float.isFinite(target.left)||!Float.isFinite(target.top)||!Float.isFinite(target.right)||!Float.isFinite(target.bottom)
    ||target.width()<1||target.height()<1){
   Log.w(TAG,"handoff_geometry_rejected source="+source+" capture="+captureWidth+"x"+captureHeight+" source_rotation="+captureRotation+" target="+target+" window="+width+"x"+height+" target_rotation="+targetRotation+" turns="+turns);return false;
  }
  if(motion!=null||sweepOwned)return adopt(target,targetRadius,targetBackdrop,turns,liveReady,result);
  if(turns!=0&&planned){
   if(edgeDelta(plannedTarget,target)>1f)Log.i(TAG,"handoff_target_retarget from="+plannedTarget+" to="+target);
   plannedTarget.set(target);plannedTargetRadius=targetRadius;plannedRotation=targetRotation;
   motionResult=result;
   Log.i(TAG,"handoff_target_adopted pending=true target="+target+" turns="+turns+" ms="+(SystemClock.uptimeMillis()-started));
   tryStartPlan();
   Drawable wallpaper=usable(targetBackdrop)?new android.graphics.drawable.BitmapDrawable(view.getResources(),targetBackdrop):null;
   if(liveReady)reveal.liveReady();
   if(wallpaper!=null||liveReady)startBackground(wallpaper,MotionSpec.ADD_CARD_MS);
   maybeReveal();
   return acceptIfTravelDone(result);
  }
  if(view.getWidth()!=width||view.getHeight()!=height||!valid(source,captureWidth,captureHeight)||!valid(target,width,height)||!DisplayGeometry.matches(captureWidth,captureHeight,width,height,turns)){
   Log.w(TAG,"handoff_geometry_rejected source="+source+" capture="+captureWidth+"x"+captureHeight+" source_rotation="+captureRotation+" target="+target+" window="+width+"x"+height+" target_rotation="+targetRotation+" turns="+turns);return false;
  }
  motionResult=result;
  beginMotion(source,sourceRadius,target,targetRadius,targetBackdrop,turns,velocity,sampledAt,liveReady);
  return motion!=null||sweepOwned;
 }
 private boolean adopt(RectF target,float targetRadius,Bitmap targetBackdrop,int turns,boolean liveReady,Consumer<Boolean> result){
  if(scene==null||scene.sweepTurns()!=turns){
   Log.w(TAG,"handoff_geometry_rejected adopt scene_turns="+(scene==null?-1:scene.sweepTurns())+" host_turns="+turns+" target="+target);return false;
  }
  if(edgeDelta(plannedTarget,target)>1f)Log.i(TAG,"handoff_target_retarget from="+plannedTarget+" to="+target);
  scene.retarget(target,targetRadius);
  if(plannedTarget!=null)plannedTarget.set(target);
  plannedTargetRadius=targetRadius;motionResult=result;
  Drawable wallpaper=usable(targetBackdrop)?new android.graphics.drawable.BitmapDrawable(view.getResources(),targetBackdrop):null;
  if(liveReady)reveal.liveReady();
  // Travel was already started by the release plan. A late host reply must
  // not add another full entrance duration after the card has landed.
  if(wallpaper!=null||liveReady)startBackground(wallpaper,180);
  maybeReveal();
  Log.i(TAG,"handoff_target_adopted target="+target+" turns="+turns+" live_ready="+liveReady+" ms="+(SystemClock.uptimeMillis()-started));
  return acceptIfTravelDone(result);
 }
 private boolean acceptIfTravelDone(Consumer<Boolean> result){
  if(!pendingAccept)return true;
  pendingAccept=false;motionResult=null;if(result!=null)result.accept(true);return true;
 }
 private static float edgeDelta(RectF from,RectF to){
  if(from==null||to==null)return 0;
  return Math.max(Math.max(Math.abs(from.left-to.left),Math.abs(from.top-to.top)),Math.max(Math.abs(from.right-to.right),Math.abs(from.bottom-to.bottom)));
 }
 private void beginMotion(RectF source,float sourceRadius,RectF target,float targetRadius,Bitmap targetBackdrop,int turns,float[] velocity,long sampledAt,boolean liveReady){
  if(closed||view==null||scene==null)return;
  float[] rebased=DisplayGeometry.rect(new float[]{source.left,source.top,source.right,source.bottom},captureWidth,captureHeight,turns);
  RectF portrait=new RectF(rebased[0],rebased[1],rebased[2],rebased[3]);
  Drawable wallpaper=usable(targetBackdrop)?new android.graphics.drawable.BitmapDrawable(view.getResources(),targetBackdrop):null;
  if((turns&1)==1){
   // Lock before the first invalidate. A draw with turns still 0 paints the gray cut.
   sweepOwned=true;scene.lockStage(turns,captureHeight,captureWidth);
   scene.configureSweep(source,sourceRadius,target,targetRadius,wallpaper);
  }else{
   scene.rebase(turns);
   if(turns!=0){sweepOwned=true;scene.configureSweep(source,sourceRadius,target,targetRadius,wallpaper);}
   else scene.configure(portrait,sourceRadius,target,targetRadius,wallpaper);
  }
  if(liveReady)reveal.liveReady();
  if(wallpaper!=null||liveReady)startBackground(wallpaper,MotionSpec.ADD_CARD_MS);
  view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
   if(closed||visualDetached)return;
   int w=r-l,h=b-t;
   if(w<1||h<1||retainLockedViewport(w,h))return;
   if(w!=width||h!=height)close("display_changed");
  });
  ValueAnimator run=ValueAnimator.ofFloat(0,1);motion=run;
  SnapshotMotionView moving=scene;
  // Age is the real sample age. Do not clamp it under 120 ms to force a nonzero start speed.
  final float[] speed={0};
  final long[] last={0},gap={0};final int[] frames={0};
  run.setDuration(MotionSpec.ADD_CARD_MS);run.setInterpolator(null);
  run.addUpdateListener(a->{if(!closed&&motion==a){long now=SystemClock.uptimeMillis();if(last[0]>0)gap[0]=Math.max(gap[0],now-last[0]);last[0]=now;frames[0]++;float time=(Float)a.getAnimatedValue();geometryProgress=EntranceMotion.fraction(time,speed[0]);moving.progress(geometryProgress,backgroundProgress);}});
  run.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(closed||motion!=a)return;motion=null;
   Log.i(TAG,"handoff_motion_frames callbacks="+frames[0]+" max_gap_ms="+gap[0]+" owner=ui_animator");
   final boolean[] committed={false};
   moving.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->{
    if(closed||committed[0])return;committed[0]=true;
    reveal.motionCommitted();maybeReveal();
   }));moving.invalidate();
   main.postDelayed(()->{if(!closed&&!committed[0]&&!visualDetached){committed[0]=true;close("motion_commit_timeout");}},MotionSpec.FRAME_TIMEOUT_MS);
  }});
  Runnable start=()->{
   long age=SystemClock.uptimeMillis()-sampledAt;
   speed[0]=EntranceMotion.startSpeed(new float[]{portrait.left,portrait.top,portrait.right,portrait.bottom},new float[]{target.left,target.top,target.right,target.bottom},DisplayGeometry.velocity(velocity,turns),age,MotionSpec.ADD_CARD_MS);
   run.start();
   Log.i(TAG,"handoff_snapshot_motion source="+portrait+" target="+target+" rotation_turns="+turns+" duration_ms="+MotionSpec.ADD_CARD_MS+" waiting_ms="+(SystemClock.uptimeMillis()-started)+" age_ms="+age+" curve="+EntranceMotion.curve(speed[0])+" start_speed="+speed[0]+" sweep="+(turns!=0)+" playhead_ms=0");
  };
  // Paint the matching release pose while Recents is still above Launcher.
  // Travel starts after the source-token window is shown and that task is veiled.
  if(sourceToken!=null&&!underlayRan)pendingSourceMotion=start;else start.run();
 }
 void allowReveal(Bitmap backdrop){if(!closed){
  prepareBackdrop(backdrop);reveal.liveReady();maybeReveal();
 }}
 void prepareBackdrop(Bitmap backdrop){if(!closed){
  Drawable wallpaper=usable(backdrop)?new android.graphics.drawable.BitmapDrawable(view.getResources(),backdrop):new android.graphics.drawable.ColorDrawable(Ui.CHROME);
  startBackground(wallpaper,180);
 }}
 private void startBackground(Drawable wallpaper,long duration){
  if(closed||backgroundStarted)return;
  backgroundStarted=true;
  Log.i(TAG,"handoff_background_start ms="+(SystemClock.uptimeMillis()-started)+" duration_ms="+duration);
  scene.wallpaper(wallpaper==null?new android.graphics.drawable.ColorDrawable(Ui.CHROME):wallpaper);
  ValueAnimator fade=ValueAnimator.ofFloat(0,1);backgroundMotion=fade;
  // Loading overlaps geometry; reveal still waits for this background's committed frame.
  fade.setDuration(duration);fade.setInterpolator(null);
  fade.addUpdateListener(a->{if(!closed&&backgroundMotion==a){backgroundProgress=EntranceMotion.backdropFraction((Float)a.getAnimatedValue());scene.progress(geometryProgress,backgroundProgress);}});
  fade.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(closed||backgroundMotion!=a)return;backgroundMotion=null;
   scene.getViewTreeObserver().registerFrameCommitCallback(()->main.post(()->{if(!closed){reveal.backgroundCommitted();maybeReveal();}}));scene.invalidate();
  }});fade.start();
 }

 private void maybeReveal(){if(!closed&&reveal.begin()){Log.i(TAG,"handoff_reveal_ready ms="+(SystemClock.uptimeMillis()-started));fadeToLive();}}
 private boolean valid(RectF rect,int w,int h){return rect!=null&&Float.isFinite(rect.left)&&Float.isFinite(rect.top)&&Float.isFinite(rect.right)&&Float.isFinite(rect.bottom)
  &&rect.width()>=1&&rect.height()>=1&&rect.left>=0&&rect.top>=0&&rect.right<=w&&rect.bottom<=h;}
 private void fadeToLive(){
  if(closed||view==null)return;
  hideUnderlay();
  ValueAnimator fade=ValueAnimator.ofFloat(1,0);motion=fade;
  fade.setDuration(MotionSpec.CROSS_FADE_MS);fade.setInterpolator(MotionSpec::geometry);
  fade.addUpdateListener(a->{if(!closed&&motion==a)view.setAlpha((Float)a.getAnimatedValue());});
  fade.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(closed||motion!=a)return;motion=null;
   Consumer<Boolean> result=motionResult;motionResult=null;
   if(result!=null)result.accept(true);else pendingAccept=true;
  }});fade.start();
 }
 private void dispatch(String reason){
  if(dispatched||destination==null)return;
  if(closed)reason=closedReason;
  dispatched=true;main.removeCallbacks(commitTimeout);
  if(!"frame_committed".equals(reason))close(reason);
  Log.i(TAG,"handoff_cover_dispatch reason="+reason+" ms="+(SystemClock.uptimeMillis()-started));
  Runnable action=destination;destination=null;action.run();
 }
 void close(String why){
  if(closed)return;closed=true;closedReason=why;reveal.cancel();pendingSourceMotion=null;
  main.removeCallbacks(commitTimeout);main.removeCallbacks(releaseTimeout);
  if(backgroundMotion!=null){backgroundMotion.cancel();backgroundMotion=null;}
  if(motion!=null){ValueAnimator old=motion;motion=null;old.cancel();}
  hideUnderlay();
  if(view!=null){if(!visualDetached)try{windows.removeViewImmediate(view);}catch(Throwable e){Log.w(TAG,"handoff_cover_remove",e);}view.removeAllViews();view=null;}
  // Let RenderThread release its bitmap reference naturally after window teardown.
  bitmap=null;sourceFrame=null;poseSnapshot=null;scene=null;if(active==this)active=null;
  Log.i(TAG,"handoff_cover_removed reason="+reason+" why="+why+" ms="+(SystemClock.uptimeMillis()-started));
  Consumer<Boolean> result=motionResult;motionResult=null;if(result!=null)result.accept(false);
  if(closedListener!=null)closedListener.accept(why);
  if(destination!=null)dispatch(why);
 }
}
