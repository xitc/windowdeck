package io.github.xitc.windowdeck;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.window.OnBackAnimationCallback;
import android.window.BackEvent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.window.OnBackInvokedDispatcher;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Owns surfaces state for the workbench. The activity is only the nested Host. */
final class TaskSurfaceRenderer {
 interface Host {
  void abandonEntrance(WorkbenchSlot slot);
  void abortFullscreen(FullscreenController.FullscreenRun run,String reason,boolean finishHost);
  ValueAnimator addAnimation();
  Button addCard();
  ValueAnimator animation();
  void applyRotationIdentity(WorkbenchSlot slot,int taskId,ActivityManager.RunningTaskInfo snapshot,boolean reread);
  void applyTaskDirections();
  WorkbenchBackdrop backdrop();
  boolean backgrounded();
  boolean beginDrag(WorkbenchSlot s);
  boolean closing();
  void commitRotation(WorkbenchSlot slot,int taskId,RotationIdentity identity);
  Context context();
  boolean deferPortrait();
  void ensureRotationMotion(TaskIdentityController.RotationRun run);
  boolean entranceInputBlocked();
  void fail(Throwable e);
  void finishDrag(WorkbenchSlot s,boolean commit);
  boolean beginSideSort(WorkbenchSlot s);
  void moveSideSort(float dx,float dy);
  void releaseSideSort();
  void dismissSideSortMenu();
  boolean sideSortLifted(WorkbenchSlot s);
  void finishTaskRotation(TaskIdentityController.RotationRun run,String result);
  boolean focusPrimary(String reason);
  FullscreenController.FullscreenRun fullscreenRun();
  boolean gestureHandoff();
  void handleTaskRootBack(WorkbenchSlot s,int task);
  boolean handoffPresentation();
  Runnable immersivePoll();
  Intent intent();
  void layoutCaption();
  int layoutMode();
  LayoutSwitchVisuals layoutSwitchVisuals();
  int mainWindowPercent();
  void maybeAnimateAddedCard();
  void menu(WorkbenchSlot s);
  void onEmbeddedTaskVanished(WorkbenchSlot s,int task);
  PackageManager packageManager();
  WorkbenchSlot pendingEntrance();
  int pendingHangFront();
  boolean poseHeld();
  WorkbenchSlot poseSlot();
  void promote(int index);
  void publishState(int count);
  void publishState(int count,int generation,int attempt);
  void queueTaskDirections();
  void raiseHangContainer();
  RotationIdentity readIdentity(ActivityManager.RunningTaskInfo task);
  boolean recovering();
  void refreshIme();
  void refreshStatus();
  void removeSlot(WorkbenchSlot s);
  void removeSlot(WorkbenchSlot s,boolean taskGone);
  ReplacementController<WorkbenchSlot> replacements();
  void requestCoverPose(WorkbenchSlot slot);
  void recordResize(long elapsed);
  void recordRotationSubmission(TaskIdentityController.RotationRun run);
  Resources resources();
  int rotationSubmitGeneration();
  WorkbenchSlot rotationSubmitSlot();
  int rotationSubmitTask();
  RotationTransactions.Host rotationTransactions();
  void setPendingHangFront(int value);
  CanvasStage stage();
  TextView status();
  boolean stopped();
  SwitchPreparation switchPreparation();
  boolean switching();
  int taskId();
  TaskIdentityController.RotationRun taskRotationRun();
  void updateDirectionEnvironment(WorkbenchSlot slot);
  boolean waitingForSourceRelease();
  boolean writeFullscreenSideHidden(WorkbenchSlot s,SurfaceControl.Transaction t);
  boolean writeHeldPose(WorkbenchSlot s,SurfaceControl.Transaction t);
  void runOnUiThread(Runnable action);
  void scheduleLayout();
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 TaskSurfaceRenderer(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 boolean atomicRotate=AtomicPresentation.DEFAULT;
 final Rect naturalBounds=new Rect();
 Method surfaceViewRoot,surfaceRootControl,surfaceRelativeLayer;
 ViewTreeObserver.OnPreDrawListener motionFitListener;


 int effectiveMode(){return host.layoutMode();}
 boolean rotatePrimaryIme(){return !host.closing()&&!host.stopped()&&!host.backgrounded()&&!session.slots.isEmpty()&&session.primary>=0&&session.primary<session.slots.size()&&!session.slots.get(session.primary).released&&rotatePresentation(session.slots.get(session.primary));}
 boolean rotatePresentation(WorkbenchSlot s){
  android.util.DisplayMetrics dm=host.resources().getDisplayMetrics();
  return s.orientationAxis==2&&OrientationPolicy.rotatePresentation(s.renderBounds.width(),s.renderBounds.height(),dm.widthPixels,dm.heightPixels);
 }
 int[] presentationSize(WorkbenchSlot s){return OrientationPolicy.presentationSize(s.renderBounds.width(),s.renderBounds.height(),rotatePresentation(s));}
 int layoutGap(){return Math.max(1,Ui.dp(host.context(),1));}
 int sideWidth(){
  return effectiveMode()==PaneLayout.LEFT_RIGHT?PaneLayout.leftRightSideW(host.stage().getWidth(),layoutGap()):PaneLayout.topBottomSideW(host.stage().getWidth(),layoutGap());
 }
 int previewHeight(){
  return PaneLayout.previewHeight(host.stage().getWidth(),host.stage().getHeight(),layoutGap(),sideWidth(),0,effectiveMode());
 }
 int[][] allocation(){return PaneLayout.compute(host.stage().getWidth(),host.stage().getHeight(),session.slots.size(),session.primary,layoutGap(),sideWidth(),previewHeight(),effectiveMode());}
 CardLayout.Result cardGeometry(){
  int[][] sizes=new int[session.slots.size()][2];
  for(int i=0;i<session.slots.size();i++)sizes[i]=presentationSize(session.slots.get(i));
  CardLayout.Result result=CardLayout.compute(host.stage().getWidth(),host.stage().getHeight(),session.primary,layoutGap(),sideWidth(),previewHeight(),effectiveMode(),0,Ui.dp(host.context(),48),sizes);
  return CardLayout.scaleMain(result,session.primary,host.stage().getWidth(),host.stage().getHeight(),layoutGap(),effectiveMode(),Ui.dp(host.context(),48),host.mainWindowPercent());
 }
 void layoutAddCard(int[] box){
  host.addCard().setVisibility(box==null?View.GONE:View.VISIBLE);
  if(box!=null){
   FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(box[2],box[3]);lp.leftMargin=box[0];lp.topMargin=box[1];host.addCard().setLayoutParams(lp);
   clipSide(host.addCard(),box[2],box[3],true);
  }
  host.layoutCaption();
 }
 void inputRole(WorkbenchSlot s,boolean isPrimary){
  // The entrance must not hand the main card's touches to its task while the plate is still
  // moving — but it has no reason to hold the whole rail, because side cards are previews
  // either way. Both flags now drop at the first committed entrance frame rather than at the
  // end of the animation (TODO D3); finishArrival() keeps the 1500 ms escape hatch.
  boolean blocked=host.taskRotationRun()!=null||host.switching()||host.switchPreparation().pending()||host.recovering()||host.handoffPresentation();
  boolean entranceRunning=host.entranceInputBlocked()||host.animation()!=null||host.addAnimation()!=null;
  boolean preview=!isPrimary||blocked||entranceRunning;s.card.preview=preview;
  s.card.gesturesEnabled=host.layoutSwitchVisuals()==null&&host.taskRotationRun()==null&&!host.handoffPresentation()&&TransitionPolicy.gesturesEnabled(isPrimary,host.recovering(),entranceRunning,host.entranceInputBlocked());s.card.vertical=effectiveMode()==PaneLayout.TOP_BOTTOM;s.pin.setVisibility(s.pinned?View.VISIBLE:View.GONE);
  s.card.setContentDescription(s.label+(isPrimary?"主应用，顶部菜单":"，点击切换，长按管理"));
  s.icon.setVisibility(isPrimary?View.GONE:View.VISIBLE);
  positionCardBadges(s,isPrimary);
  applyCardRadius(s,isPrimary);
  try{if(rom.interceptInput.getBoolean(s.surface)!=preview){rom.interceptInput.setBoolean(s.surface,preview);s.surface.requestLayout();}}catch(Throwable e){host.fail(e);}
 }
 void positionCardBadges(WorkbenchSlot s,boolean isPrimary){
  // The enlarged main can overlap the lower part of a top rail. Keep side
  // chrome in its exposed top strip, as Vivo's phone holder does.
  boolean top=!isPrimary&&effectiveMode()==PaneLayout.TOP_BOTTOM;
  positionBadge(s.icon,top,Ui.dp(host.context(),6));
  positionBadge(s.pin,top,Ui.dp(host.context(),8));
 }
 static void positionBadge(View badge,boolean top,int inset){
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)badge.getLayoutParams();
  int gravity=Gravity.LEFT|(top?Gravity.TOP:Gravity.BOTTOM),above=top?inset:0,below=top?0:inset;
  if(lp.gravity==gravity&&lp.topMargin==above&&lp.bottomMargin==below)return;
  lp.gravity=gravity;lp.topMargin=above;lp.bottomMargin=below;badge.setLayoutParams(lp);
 }
 int cardRadius(boolean isPrimary){return Ui.dp(host.context(),isPrimary?Ui.MAIN_RADIUS:Ui.SIDE_RADIUS);}
 void applyCardRadius(WorkbenchSlot s,boolean isPrimary){
  if(s.card==null)return;
  if(s.motionPose!=null){motionOutline(s);return;}

  int radius=cardRadius(isPrimary);
  s.renderRadius=radius;
  if(host.stage().ownsSurfaceAperture){motionOutline(s);return;}
  if(isPrimary){
   if(s.card.getBackground() instanceof android.graphics.drawable.GradientDrawable)((android.graphics.drawable.GradientDrawable)s.card.getBackground()).setCornerRadius(radius);
   else s.card.setBackground(Ui.bg(Ui.CARD,radius));
  }else if(s.card.getBackground()!=null)s.card.setBackground(null);
  clipSide(s.card,s.card.getWidth(),s.card.getHeight(),!isPrimary);
  if(s.surface==null||rom.cornerRadius==null)return;
  // The rounded task supplies the projected outline. A rectangular SurfaceView
  // outline (and its retained native radius) must not cut through a tilted edge.
  boolean projected=AtomicPresentation.perspective(atomicRotate,!isPrimary)&&rom.leashMatrix4!=null;
  int surfaceRadius=projected?0:radius;
  Ui.round(s.surface,surfaceRadius);s.surface.setClipToOutline(!projected);
  try{
   if(Math.abs(rom.cornerRadius.getFloat(s.surface)-surfaceRadius)<0.5f)return;
   rom.cornerRadius.setFloat(s.surface,surfaceRadius);
   rom.viewApi.getMethod("setCornerRadius",float.class).invoke(s.surface,(float)surfaceRadius);
  }catch(Throwable e){Log.w(TAG,"corner_radius_failed slot="+s.id,e);}
 }
 void updateNaturalBounds(){
  if(session.slots.isEmpty()||host.stage().getWidth()<100||host.stage().getHeight()<100)return;
  android.util.DisplayMetrics dm=host.resources().getDisplayMetrics();
  int shortEdge=Math.max(1,Math.min(dm.widthPixels,dm.heightPixels));
  int longEdge=Math.max(1,Math.max(dm.widthPixels,dm.heightPixels));
  naturalBounds.set(0,0,shortEdge,longEdge);
  for(WorkbenchSlot s:session.slots)updateRenderBounds(s);
  host.refreshIme();
 }
 void updateRenderBounds(WorkbenchSlot s){
  android.util.DisplayMetrics dm=host.resources().getDisplayMetrics();
  int[] size=OrientationPolicy.bounds(naturalBounds.width(),naturalBounds.height(),dm.widthPixels,dm.heightPixels,s.orientationAxis);
  if(s.renderBounds.width()!=size[0]||s.renderBounds.height()!=size[1]){s.renderBounds.set(0,0,size[0],size[1]);Log.i(TAG,"render_bounds slot="+s.id+" axis="+s.orientationAxis+" size="+size[0]+"x"+size[1]);}
 }
 void resizeSurface(WorkbenchSlot s){
  if(s.surface==null||s.released||s.renderBounds.isEmpty())return;
  boolean measure=host.switching();long start=measure?System.nanoTime():0;
  if(s.resizedTask!=s.taskId||!s.resizedBounds.equals(s.renderBounds)){
   try{rom.resizeMethod.invoke(s.surface,new Rect(s.renderBounds));s.resizedBounds.set(s.renderBounds);s.resizedTask=s.taskId;}
   catch(Throwable e){host.fail(e);}
   finally{if(measure)host.recordResize(System.nanoTime()-start);}
  }
  int[] plate=surfacePlate(s);scheduleLeashFit(s,plate[0],plate[1]);
 }
 void reparentSurface(WorkbenchSlot s){
  if(s.surface==null||s.released||!s.embedded||host.stopped()||host.backgrounded())return;
  try{Method method=rom.canvas.viewMethod("reparent");method.setAccessible(true);method.invoke(s.surface);s.surface.setBackground(null);
   // reparent may return early while a task is being restored. Reapply our
   // display transform explicitly, including when the plate was recreated.
   try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){if(writeLeashFit(s,fit)){if(s.motionPose!=null||(host.poseHeld()&&s==host.poseSlot())||(host.taskRotationRun()!=null&&host.taskRotationRun().slot==s&&host.taskRotationRun().changingLayout)||(host.rotationTransactions()!=null&&host.rotationTransactions().pending())||projectedHostFrameReady(s)){submitMotionFit(fit);host.stage().invalidate();}else fit.apply();}}}
  catch(Throwable e){Log.w(TAG,"surface_sync_failed slot="+s.id,e);}
 }
 void syncSurface(WorkbenchSlot s){
  if(host.closing()||s.released||s.surface==null||s.taskId<0)return;
  reparentSurface(s);
  int[] plate=surfacePlate(s);scheduleLeashFit(s,plate[0],plate[1]);
 }
 void refitEmbeddedTask(WorkbenchSlot s){
  if(host.closing()||host.stopped()||host.backgrounded()||s.released||s.surface==null||s.taskId<0||host.poseHeld()||host.deferPortrait())return;
  try{
   SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface);
   if(!EmbeddedTaskGuard.mayReparent(s.surface,s.taskId,leash))return;
   // Do not call native reparent(): it also updates WM container metadata and
   // can cause another task-info callback. Only reconcile the compositor here.
   try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
    if(writeLeashFit(s,fit)){submitMotionFit(fit);host.stage().invalidate();}
   }
  }catch(Throwable e){Log.w(TAG,"embedded_refit_failed slot="+s.id,e);}
 }
 int[] surfacePlate(WorkbenchSlot s){
  ViewGroup.LayoutParams lp=s.surface.getLayoutParams();
  int w=lp!=null&&lp.width>0?lp.width:s.surface.getWidth();
  int h=lp!=null&&lp.height>0?lp.height:s.surface.getHeight();
  return new int[]{Math.max(1,w),Math.max(1,h)};
 }
 void fitSurface(WorkbenchSlot s,int width,int height){
  if(s.surface==null||s.renderBounds.isEmpty())return;
  boolean rotate=rotatePresentation(s);
  boolean preview=session.slots.indexOf(s)!=session.primary;
  // Size the SurfaceView to the plate and let the task leash map into it.
  // Scaling the full-size SurfaceView overflows; FlexibleTaskView's parent
  // surface cannot clip that. Use the ROM's rotate-leash flag for games.
  try{
   if(rom.rotateTaskLeash.getBoolean(s.surface)!=rotate){rom.rotateTaskLeash.setBoolean(s.surface,rotate);s.surface.post(()->syncSurface(s));}
   if(rom.reparentAlign!=null)rom.reparentAlign.setInt(s.surface,preview&&!rotate?2:0);
  }catch(Throwable e){host.fail(e);return;}
  int w=Math.max(1,width),h=Math.max(1,height);
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)s.surface.getLayoutParams();
  if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;s.surface.setLayoutParams(lp);}
  s.surface.setPivotX(0);s.surface.setPivotY(0);s.surface.setScaleX(1);s.surface.setScaleY(1);
  s.surface.setTranslationX(0);s.surface.setTranslationY(0);
  s.surface.setClipBounds(s.motionPose==null&&preview&&effectiveMode()!=PaneLayout.LEFT_RIGHT?new Rect(0,0,w,h):null);
  fitRecoveryCover(s,w,h);
  scheduleLeashFit(s,w,h);
 }
 void createWindow(WorkbenchSlot slot){
  if(host.waitingForSourceRelease()&&slot==host.pendingEntrance()){Log.i(TAG,"defer_embed_wait task="+slot.sourceTaskId);return;}
  try{
   updateRenderBounds(slot);
   slot.card=new PreviewCard(host.context());applyCardRadius(slot,session.slots.indexOf(slot)==session.primary);
   slot.card.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
    if(!host.closing()&&!slot.released&&r>l&&b>t&&(l!=ol||t!=ot||r!=or||b!=ob)){fitRecoveryCover(slot,r-l,b-t);scheduleLeashFit(slot,r-l,b-t);}
   });
   slot.card.setOnClickListener(v->host.promote(session.slots.indexOf(slot)));
   slot.card.beginDrag=()->host.beginDrag(slot);slot.card.cancelDrag=()->host.finishDrag(slot,false);slot.card.dismiss=()->host.finishDrag(slot,true);
   slot.card.beginSort=()->host.beginSideSort(slot);slot.card.moveSort=(x,y)->host.moveSideSort(x,y);slot.card.releaseSort=host::releaseSideSort;slot.card.dismissSortMenu=host::dismissSideSortMenu;
   slot.surface=(View)rom.canvas.constructor.newInstance(host.context());
   EmbeddedTaskGuard.watch(slot.surface,host.taskId(),embedded->{
    slot.embedded=embedded;
    if(!embedded&&host.fullscreenRun()!=null&&host.fullscreenRun().lifecycle.open()&&host.fullscreenRun().main==slot){host.abortFullscreen(host.fullscreenRun(),"task_detached",true);return;}
    if(!embedded){if(host.taskRotationRun()!=null&&host.taskRotationRun().slot==slot)host.finishTaskRotation(host.taskRotationRun(),"detached");slot.drawEvidence.invalidate();slot.resizedTask=-1;slot.fittedPose=null;clearProjection(slot);Log.i(TAG,"task_transform_released slot="+slot.id+" task="+slot.taskId);}
    else refitEmbeddedTask(slot);
   });
   ((SurfaceView)slot.surface).getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
   ((SurfaceView)slot.surface).getHolder().addCallback(new SurfaceHolder.Callback(){
    public void surfaceCreated(SurfaceHolder holder){restorePlate();}
    public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){restorePlate();}
    public void surfaceDestroyed(SurfaceHolder holder){if(host.taskRotationRun()!=null&&host.taskRotationRun().slot==slot)host.finishTaskRotation(host.taskRotationRun(),"surface_destroyed");slot.drawEvidence.invalidate();slot.windowDrawn=false;slot.contentProbeUnavailable=false;slot.fittedPose=null;slot.resizedTask=-1;}
    private void restorePlate(){handler.post(()->{
     if(host.closing()||slot.released)return;
     if(host.backdrop()!=null)host.backdrop().restoreSurface();
     syncSurface(slot);
     Log.i(TAG,"surface_restored slot="+slot.id+" task="+slot.taskId);
    });}
   });
   try{Field disable=SurfaceView.class.getDeclaredField("mDisableBackgroundLayer");disable.setAccessible(true);disable.setBoolean(slot.surface,true);}catch(Throwable e){Log.w(TAG,"surface_background_disable_failed",e);}
   slot.surface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(!host.closing()&&!slot.released&&r>l&&b>t&&(l!=ol||t!=ot||r!=or||b!=ob))resizeSurface(slot);});
   Class<?> listener=rom.canvas.listener;
   Object proxy=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(p,m,args)->{
    if(m.getDeclaringClass()==Object.class){if(m.getName().equals("toString"))return "WorkbenchListener";if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==args[0];}
    if(host.closing()||slot.released)return null;
    if(m.getName().equals("onTaskCreated")||m.getName().equals("onTaskChanged")){
     ComponentName top=(ComponentName)args[1];
     int id=(Integer)args[0];if(slot.sourceTaskId>=0&&slot.taskId<0&&id!=slot.sourceTaskId){slot.failed=true;Log.e(TAG,"add_existing_identity_mismatch expected="+slot.sourceTaskId+" actual="+id);handler.post(()->{if(host.replacements().current()!=null&&host.replacements().current().fresh==slot)host.replacements().cancel(host.replacements().current(),"identity_mismatch");else host.removeSlot(slot,true);});return null;}
     if(host.replacements().current()!=null&&host.replacements().current().fresh==slot&&!host.replacements().bind(host.replacements().current(),id)){slot.failed=true;handler.post(()->{if(host.replacements().current()!=null&&host.replacements().current().fresh==slot)host.replacements().cancel(host.replacements().current(),"task_changed");});return null;}
     boolean firstTask=slot.taskId<0,taskChanged=slot.taskId!=id;if(taskChanged)Log.i(TAG,"task_ready slot="+slot.id+" task="+id);slot.taskId=id;
     if(taskChanged)host.publishState(session.slots.size());
     host.applyRotationIdentity(slot,id,null,true);
     if(!slot.taskOrientation.bound(id)){
      Log.i(TAG,"rotation_activity_callback_fallback slot="+slot.id+" task="+id+" component="+TaskIdentityController.flatten(top));
      host.commitRotation(slot,id,RotationIdentity.choose(TaskIdentityController.flatten(top),null,null,null,slot.component.getPackageName()));
     }
     handler.removeCallbacks(host.immersivePoll());handler.post(host.immersivePoll());
     host.updateDirectionEnvironment(slot);int nextAxis=slot.taskOrientation.axis();if(slot.taskOrientation.bound()&&firstTask&&slot.orientationAxis!=nextAxis){slot.orientationAxis=nextAxis;handler.post(host::scheduleLayout);}else host.queueTaskDirections();
     slot.failed=false;host.refreshStatus();handler.post(()->{syncSurface(slot);host.focusPrimary("task_ready");host.maybeAnimateAddedCard();if(host.deferPortrait()&&id==slot.sourceTaskId)host.requestCoverPose(slot);if(id==host.pendingHangFront()||(firstTask&&slot.pickerLaunch&&!host.stopped()&&!host.backgrounded())){host.setPendingHangFront(-1);host.raiseHangContainer();}if(firstTask)slot.pickerLaunch=false;});
    }else if(m.getName().equals("onTaskRectOrientationChanged")){
     ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)args[0];Rect requested=args[1] instanceof Rect?new Rect((Rect)args[1]):null;
     if(info!=null&&requested!=null&&info.taskId==slot.taskId){
      RotationIdentity callbackIdentity=host.readIdentity(info);
      host.applyRotationIdentity(slot,info.taskId,info,true);
      int rotation=TaskIdentityController.taskRotation(info);
      if(slot.taskOrientation.update(info.taskId,callbackIdentity.rotationClass,requested.left,requested.top,requested.right,requested.bottom,rotation)){
       int axis=slot.taskOrientation.axis();
       Log.i(TAG,"task_orientation slot="+slot.id+" task="+info.taskId+" component="+slot.taskOrientation.rotationClass()+" axis="+axis+" bounds="+requested+" rotation="+slot.taskOrientation.rotation()+" previous_rotation="+slot.taskOrientation.previousRotation()+" source="+slot.taskOrientation.source()+" "+slot.taskOrientation.record());
      }else if(!java.util.Objects.equals(callbackIdentity.rotationClass,slot.taskOrientation.rotationClass())){
       Log.i(TAG,"task_orientation stale_activity slot="+slot.id+" task="+info.taskId+" callback="+callbackIdentity.rotationClass+" current="+slot.taskOrientation.rotationClass());
      }
      // Rebinding can change the direction even when the rect is stale or invalid.
      // Claim the native transaction before FlexibleTaskView can apply its final
      // orientation. Posting this work lets a target pose escape for one frame.
      host.applyTaskDirections();
     }
    }else if(m.getName().equals("onInitialized")){
     boolean success=Boolean.TRUE.equals(args[0]);
     if(host.replacements().current()!=null&&host.replacements().current().fresh==slot)host.replacements().initialize(host.replacements().current(),success);
     if(!success){slot.failed=true;Log.e(TAG,"task_start_failed slot="+slot.id);handler.post(()->{if(host.replacements().current()!=null&&host.replacements().current().fresh==slot)host.replacements().cancel(host.replacements().current(),"initialization_failed");else host.abandonEntrance(slot);});host.refreshStatus();}
    }
    else if(m.getName().equals("onTaskWindowDraw")&&((Integer)args[0])==slot.taskId){boolean drawn=Boolean.TRUE.equals(args[1]);Log.i(TAG,"task_draw slot="+slot.id+" drawn="+drawn);if(!drawn){slot.windowDrawn=false;slot.drawEvidence.invalidate();}else if(host.gestureHandoff()&&slot.orientationAxis==2&&!slot.drawEvidence.hasContent())handler.post(()->{syncSurface(slot);host.maybeAnimateAddedCard();});else{slot.windowDrawn=true;handler.post(()->{syncSurface(slot);host.maybeAnimateAddedCard();});}}
    else if(m.getName().equals("onBackPressedOnTaskRoot")){int task=(Integer)args[0];handler.post(()->host.handleTaskRootBack(slot,task));}
    else if(m.getName().equals("onTaskRemovalStarted")){int task=(Integer)args[0];handler.post(()->host.onEmbeddedTaskVanished(slot,task));}
    return null;
   });
   rom.canvas.viewMethod("setListener").invoke(slot.surface,(Executor)host::runOnUiThread,proxy);
   Bundle config=new Bundle();config.putInt("scenario",2);config.putParcelable("launchBounds",new Rect(slot.renderBounds));
   config.putBoolean("flexible_embeed_task_config_with_container",false);
   // reparentRotate otherwise adds a ColorDrawable which our transparent plate
   // immediately clears. That requests layout, and the layout callback reparents
   // again. Our wallpaper and content covers already own the plate background.
   config.putBoolean("use_default_background_color",false);
   config.putBoolean("need_rotate_task_leash",host.deferPortrait()&&slot.sourceTaskId>=0&&slot.sourceTaskId==host.intent().getIntExtra("windowdeck_create_source_task",-1)?false:rotatePresentation(slot));
   // Native rotation otherwise promotes the Surface above the host window,
   // bypassing preview interception and recovery covers. Keep host controls on top.
   config.putBoolean("zorder_on_top",false);
   if(slot.sourceTaskId>=0)config.putInt("taskId",slot.sourceTaskId);
   else config.putParcelable("intent",slot.launchIntent!=null?new Intent(slot.launchIntent):new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(slot.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   config.putInt("userId",slot.sourceTaskId>=0?slot.sourceUserId:android.os.Process.myUid()/100000);config.putFloat("cornerRadius",cardRadius(session.slots.indexOf(slot)==session.primary));config.putInt("reparent_align",session.slots.indexOf(slot)==session.primary?0:2);
   config.putBoolean("intercept_input_event",true);config.putBoolean("allow_task_detach_from_embedding",true);config.putBoolean("key_intercept_back_key",true);config.putBoolean("flexible_key_remove_task_detach",false);config.putInt("use_view_snapshot",1);
   rom.canvas.viewMethod("init").invoke(slot.surface,config);rom.canvas.viewMethod("setEnforceStart").invoke(slot.surface,true);
   if(rom.viewTransaction!=null){
    slot.transaction=(SurfaceControl.Transaction)rom.viewTransaction.get(slot.surface);
    LeashTransactions.register(slot.transaction,t->{
     if(host.taskRotationRun()==null&&slot.orientationAxis!=slot.taskOrientation.axis()
       &&Looper.myLooper()==handler.getLooper())host.applyTaskDirections();
     boolean fitted=writeLeashFit(slot,t);
     // A moving card also changes its SurfaceView size. Merge the native reparent
     // into the same host draw as its View geometry; an immediate apply can expose
     // a fit for the old plate before the new layout. ViewRoot.merge consumes it.
     boolean rotationOwns=(host.taskRotationRun()!=null&&host.taskRotationRun().slot==slot&&host.taskRotationRun().changingLayout)
       ||(host.rotationTransactions()!=null&&host.rotationTransactions().pending());
     boolean fullscreenOwns=host.fullscreenRun()!=null&&host.fullscreenRun().lifecycle.open()&&host.fullscreenRun().main==slot;
     if(fitted&&(slot.motionPose!=null||(host.deferPortrait()&&slot==host.poseSlot())||rotationOwns||fullscreenOwns||projectedHostFrameReady(slot))&&(!host.closing()||fullscreenOwns)&&!host.stopped()&&!host.backgrounded()
       &&Looper.myLooper()==handler.getLooper()&&host.stage().isAttachedToWindow()&&host.stage().isHardwareAccelerated()){
      submitMotionFit(t);host.stage().invalidate();return true;
     }
     return false;
    });
   }
   int[] presentation=presentationSize(slot);
   slot.card.addView(slot.surface,new FrameLayout.LayoutParams(presentation[0],presentation[1]));
   slot.icon=new ImageView(host.context());slot.icon.setImageDrawable(host.packageManager().getActivityIcon(slot.component));slot.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);Ui.round(slot.icon,Ui.dp(host.context(),6));
   FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(Ui.dp(host.context(),22),Ui.dp(host.context(),22),Gravity.BOTTOM|Gravity.LEFT);badge.leftMargin=Ui.dp(host.context(),6);badge.bottomMargin=Ui.dp(host.context(),6);slot.card.addView(slot.icon,badge);
   slot.pin=Ui.text(host.context(),"钉",9,0xffffffff);slot.pin.setGravity(Gravity.CENTER);slot.pin.setBackground(Ui.bg(0xff365db5,Ui.dp(host.context(),8)));slot.pin.setContentDescription("已固定，先取消固定才能替换或移出");
   FrameLayout.LayoutParams pinLp=new FrameLayout.LayoutParams(Ui.dp(host.context(),16),Ui.dp(host.context(),16),Gravity.BOTTOM|Gravity.LEFT);pinLp.leftMargin=Ui.dp(host.context(),32);pinLp.bottomMargin=Ui.dp(host.context(),8);slot.card.addView(slot.pin,pinLp);
   positionCardBadges(slot,session.slots.indexOf(slot)==session.primary);
   host.stage().addView(slot.card);host.layoutCaption();host.status().bringToFront();
   handler.postDelayed(()->{if(!host.closing()&&!slot.released&&slot.taskId<0){slot.failed=true;host.abandonEntrance(slot);host.refreshStatus();Log.w(TAG,"startup_timeout slot="+slot.id);}},10000);
  }catch(Throwable e){slot.failed=true;if(host.replacements().current()!=null&&host.replacements().current().fresh==slot)host.replacements().cancel(host.replacements().current(),"create_failed");host.fail(e);}
 }
 float[] displayQuad(WorkbenchSlot s,int w,int h){
  if(s.motionPose!=null)return CardMotion.localQuad(s.motionPose,w,h);
  return roleQuad(w,h,s.card.getLeft(),s.card.getTop(),session.slots.indexOf(s)!=session.primary);
 }
 float[] roleQuad(int w,int h,float left,float top,boolean preview){
  return roleQuad(w,h,left,top,preview,false);
 }
 float[] roleQuad(int w,int h,float left,float top,boolean preview,boolean target){
  if(!AtomicPresentation.perspective(atomicRotate,preview)||rom.leashMatrix4==null)
   return new float[]{0,0,w,0,w,h,0,h};
  CanvasStage stage=host.stage();
  CardSpace space=stage.cardSpace();
  int extent=target?(effectiveMode()==PaneLayout.LEFT_RIGHT?
    PaneLayout.leftRightSideW(stage.getWidth(),layoutGap()):PaneLayout.topBottomSideH(stage.getHeight(),layoutGap())):
    effectiveMode()==PaneLayout.LEFT_RIGHT?w:h;
  return CardPerspective.railTargetQuad(w,h,effectiveMode(),space.density,space.portrait,
    left+space.x,top+space.y,space.width,space.height,extent);
 }
 CardMotion.Pose rolePose(WorkbenchSlot s,float[] box,boolean preview){
  // Vivo maps the whole task bounds to the card (snapshot fitXY), not a
  // centered cover crop. Both rails retain the header and all content edges.
  int[] src=presentationSize(s),crop=new int[]{0,0,src[0],src[1]};
  return CardMotion.at(box,roleQuad(Math.round(box[2]),Math.round(box[3]),box[0],box[1],preview,true),
   new float[]{crop[0],crop[1],crop[2],crop[3]},cardRadius(!preview));
 }
 float[] cardBox(WorkbenchSlot s){return new float[]{s.card.getX(),s.card.getY(),Math.max(1,s.card.getWidth()*s.card.getScaleX()),Math.max(1,s.card.getHeight()*s.card.getScaleY())};}
 CardMotion.Pose currentPose(WorkbenchSlot s){
  if(s.released||!s.embedded||s.surface==null)return null;
  float[] box=cardBox(s);
  CardMotion.Pose pose=s.motionPose!=null?s.motionPose:s.fittedPose;
  if(s.motionPose==null){
   try{if(s.fittedTask!=s.taskId||s.fittedAxis!=s.orientationAxis||!s.fittedBounds.equals(s.renderBounds)||s.fittedLayer!=LiveTaskContent.layerId((SurfaceControl)rom.taskLeash.get(s.surface)))pose=null;}
   catch(Throwable e){pose=null;}
  }
  return pose==null?null:CardMotion.rebox(pose,box);
 }
 CardMotion.Pose surfacePose(WorkbenchSlot s,int w,int h){
  if(s.motionPose!=null)return s.motionPose;
  boolean preview=session.slots.indexOf(s)!=session.primary;
  float[] box=cardBox(s),quad=displayQuad(s,w,h);int[] src=presentationSize(s);
  int[] crop=new int[]{0,0,src[0],src[1]};
  for(int i=0;i<8;i+=2){quad[i]*=box[2]/Math.max(1,w);quad[i+1]*=box[3]/Math.max(1,h);}
  float radius=(s.renderRadius<0?cardRadius(!preview):s.renderRadius)*Math.min(s.card.getScaleX(),s.card.getScaleY());
  return CardMotion.at(box,quad,new float[]{crop[0],crop[1],crop[2],crop[3]},radius);
 }
 void fitCover(WorkbenchSlot s,RecoveryCover cover,int w,int h){
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)cover.getLayoutParams();
  if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;cover.setLayoutParams(lp);}
  int[] src=presentationSize(s);
  CardMotion.Pose pose=surfacePose(s,w,h);
  cover.fit(CardMotion.sourceQuad(pose,src[0],rotatePresentation(s)),displayQuad(s,w,h),s.renderBounds.width(),s.renderBounds.height(),CardMotion.sourceRadius(pose));
 }
 void fitRecoveryCover(WorkbenchSlot s,int w,int h){
  if(s.recoveryCover!=null)fitCover(s,s.recoveryCover,w,h);
 }
 void scheduleLeashFit(WorkbenchSlot s,int w,int h){
  if(s.surface==null||s.fitPosted)return;
  s.fitPosted=true;
  s.surface.post(()->{s.fitPosted=false;if(!host.closing()&&!s.released&&s.surface!=null)reparentSurface(s);});
 }
 static float[] identity4(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
 void clearProjection(WorkbenchSlot s){
  if(s.card!=null)host.stage().surfaceAperture(s.card,null);
  SurfaceControl leash=s.projectedLeash;
  if(Log.isLoggable("WindowDeckContour",Log.DEBUG))Log.d("WindowDeckContour","projection_clear slot="+s.id+" task="+s.taskId+" layer="+LiveTaskContent.layerId(leash)+" valid="+(leash!=null&&leash.isValid())+" stopped="+host.stopped());
  if(leash==null||!leash.isValid()||rom.leashMatrix4==null)return;
  try(SurfaceControl.Transaction reset=new SurfaceControl.Transaction()){
   // Only reset our projective matrix. The system owns fullscreen scale/position.
   rom.leashMatrix4.invoke(reset,leash,(Object)identity4());reset.apply();s.projectedLeash=null;s.perspectiveApplied=false;
  }catch(Throwable e){Log.w(TAG,"projection_clear_failed slot="+s.id,e);}
 }
 Rect taskBuffer(WorkbenchSlot slot){
  try{
   Field info=rom.canvas.taskInfo;info.setAccessible(true);
   ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)info.get(slot.surface);
   if(task==null||task.taskId!=slot.taskId)return null;
   // configuration is inherited from TaskInfo: getDeclaredField on RunningTaskInfo
   // always failed in dev.134 and silently selected the release crop as buffer size.
   Object config=task.getClass().getField("configuration").get(task);
   Object wc=config.getClass().getField("windowConfiguration").get(config);
   Rect bounds=(Rect)wc.getClass().getMethod("getBounds").invoke(wc);
   return bounds!=null&&bounds.width()>1&&bounds.height()>1?new Rect(0,0,bounds.width(),bounds.height()):null;
  }catch(Throwable e){return null;}
 }
 boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t){
  // Native reparent callbacks can arrive after exit has locked normal fitting.
  if(s.fullscreenHidden)return host.writeFullscreenSideHidden(s,t);
  if(host.poseHeld()&&s==host.poseSlot())return host.writeHeldPose(s,t);
  boolean fullscreenOwns=host.fullscreenRun()!=null&&host.fullscreenRun().lifecycle.open()&&host.fullscreenRun().main==s;
  if((host.closing()&&!fullscreenOwns)||host.stopped()||host.backgrounded()||!s.embedded||s.released||s.surface==null||s.card==null||rom.taskLeash==null)return false;
  int w=s.surface.getWidth(),h=s.surface.getHeight();if(w<2||h<2)return false;
  boolean preview=session.slots.indexOf(s)!=session.primary,rotate=rotatePresentation(s);
  try{
   SurfaceControl leash=(SurfaceControl)rom.taskLeash.get(s.surface);
   SurfaceControl plate=((SurfaceView)s.surface).getSurfaceControl();
   if(leash==null||!leash.isValid()||plate==null||!plate.isValid()){
    host.stage().surfaceAperture(s.card,null);return false;
   }
   writeSurfaceOrder(s,t,plate,preview);
   t.setCrop(plate,new Rect(0,0,w,h));
   try{Field bg=SurfaceView.class.getDeclaredField("mBackgroundControl");bg.setAccessible(true);SurfaceControl background=(SurfaceControl)bg.get(s.surface);if(background!=null&&background.isValid())t.setVisibility(background,false);}catch(Throwable ignored){}
   float[] target=displayQuad(s,w,h);
   TaskIdentityController.RotationRun rotation=host.taskRotationRun();
   if(s.renderBounds.isEmpty())return false;
   int[] src=presentationSize(s);
   CardMotion.Pose pose=surfacePose(s,w,h);
   // FlexibleTaskView also rounds the parent SurfaceView plate. During a main→side
   // exchange the retained plate is initially scaled about 5x; its fixed radius
   // would clip the correctly rounded task into an excessively round rectangle.
   // A managed aperture keeps the task leash as contour owner even when settled.
   // Older unsupported hosts retain their native steady-state fallback.
   if(rom.leashRadius!=null)rom.leashRadius.invoke(t,plate,(host.stage().ownsSurfaceAperture||s.motionPose!=null||
     (AtomicPresentation.perspective(atomicRotate,preview)&&rom.leashMatrix4!=null))?0f:
     (float)(s.renderRadius<0?cardRadius(!preview):s.renderRadius));
   float x=pose.crop[0],y=pose.crop[1],r=x+pose.crop[2],b=y+pose.crop[3];
   float[] source=CardMotion.sourceQuad(pose,src[0],rotate);
   Rect rect=rotate?new Rect((int)Math.floor(y),(int)Math.floor(src[0]-r),(int)Math.ceil(b),(int)Math.ceil(src[0]-x)):new Rect((int)Math.floor(x),(int)Math.floor(y),(int)Math.ceil(r),(int)Math.ceil(b));
   int[] projectedBounds=CardPerspective.bounds(w,h,target);
   t.setCrop(plate,new Rect(projectedBounds[0],projectedBounds[1],projectedBounds[2],projectedBounds[3]));
   if(rotation!=null&&rotation.slot==s&&rotation.changingLayout){
    host.ensureRotationMotion(rotation);
    target=rotation.nativeMotion.target(source,target,rotation.fraction);
    // Official enter does not shrink. The plate crop clips the overflow, the same way the screen does.
    t.setCrop(plate,new Rect(0,0,w,h));
   }
   Matrix map=new Matrix();if(!map.setPolyToPoly(source,0,target,0,4))return false;
   float[] m=new float[9];map.getValues(m);
   boolean perspective=SurfaceFit.hasPerspective(m,source)&&rom.leashMatrix4!=null;
   boolean presentationMatrix=rom.leashMatrix4!=null&&SurfaceFit.usePresentationMatrix(m,source,
     rotation!=null&&rotation.slot==s&&rotation.changingLayout);
   if(presentationMatrix&&!perspective&&s.perspectiveApplied)
    Log.i(TAG,"presentation_matrix_residual slot="+s.id+" task="+s.taskId+" policy=retain_full_map");
   if(!presentationMatrix&&rom.leashMatrix4!=null){rom.leashMatrix4.invoke(t,leash,(Object)identity4());s.projectedLeash=null;}
   s.perspectiveApplied=perspective;
   t.setCrop(leash,rect);
   if(rom.leashRadius!=null)rom.leashRadius.invoke(t,leash,CardMotion.sourceRadius(pose));
   // A native wallpaper underlay fills the SurfaceView hole. No rectangular
   // card-colored layer may remain outside the projective task outline.
   if(presentationMatrix){
    Matrix base=new Matrix();base.setPolyToPoly(source,0,new float[]{0,0,w,0,w,h,0,h},0,4);
    float[] affine=new float[9];base.getValues(affine);
    rom.leashMatrix.invoke(t,leash,affine[0],affine[3],affine[1],affine[4]);t.setPosition(leash,affine[2],affine[5]);
    Matrix inverse=new Matrix();if(!base.invert(inverse))return false;
    Matrix tilt=new Matrix();tilt.setConcat(inverse,map);float[] tiltValues=new float[9];tilt.getValues(tiltValues);
    float[] m4={tiltValues[0],tiltValues[1],0,tiltValues[2],tiltValues[3],tiltValues[4],0,tiltValues[5],0,0,1,0,tiltValues[6],tiltValues[7],0,tiltValues[8]};
    if(Log.isLoggable("WindowDeckContour",Log.DEBUG)&&LiveTaskContent.layerId(s.projectedLeash)!=LiveTaskContent.layerId(leash))Log.d("WindowDeckContour","projection_adopt slot="+s.id+" task="+s.taskId+" old="+LiveTaskContent.layerId(s.projectedLeash)+" layer="+LiveTaskContent.layerId(leash));
    rom.leashMatrix4.invoke(t,leash,(Object)m4);s.projectedLeash=leash;
   }else if(rom.leashMatrix!=null){
    rom.leashMatrix.invoke(t,leash,m[0],m[3],m[1],m[4]);t.setPosition(leash,m[2],m[5]);
   }
   if(host.layoutSwitchVisuals()!=null&&!preview)host.layoutSwitchVisuals().fitBlur(t,plate,w,h,
     pose.radius/Math.max(0.0001f,Math.min(pose.box[2]/w,pose.box[3]/h)));
   // Native SurfaceView only punches its unprojected rectangle in the host.
   // Use the presentation quad (before independent task rotation) for that hole.
   float[] viewport=displayQuad(s,w,h);
   if(host.stage().ownsSurfaceAperture)
    host.stage().surfaceAperture(s.card,viewport,pose.crop[2],pose.crop[3],CardMotion.sourceRadius(pose));
   else host.stage().surfaceAperture(s.card,CardMotion.projective(viewport,w,h)?viewport:null);
   if(Log.isLoggable("WindowDeckContour",Log.DEBUG))Log.d("WindowDeckContour","surface_contour task="+s.taskId
     +" motion="+(s.motionPose!=null)+" managed="+host.stage().ownsSurfaceAperture+" plate="+w+"x"+h
     +" crop="+Arrays.toString(pose.crop)+" source_radius="+CardMotion.sourceRadius(pose)
     +" aperture_radius="+s.card.apertureRadius+" quad="+Arrays.toString(viewport));
   s.fittedPose=pose;
   // Local coordinates are meaningful only under this plate. WM can reparent
   // a still-embedded task during an Activity transition, retaining our scale.
   if(EmbeddedTaskGuard.mayReparent(s.surface,s.taskId,leash))t.reparent(leash,plate);
   s.fittedTask=s.taskId;s.fittedAxis=s.orientationAxis;s.fittedLayer=LiveTaskContent.layerId(leash);s.fittedBounds.set(s.renderBounds);
   if(++s.leashWrites<=3)Log.i(TAG,"leash_atomic slot="+s.id+" perspective="+perspective+" rotate="+rotate+" plate="+w+"x"+h+" matrix="+java.util.Arrays.toString(m));
   if(rotation!=null&&rotation.slot==s&&rotation.changingLayout){
    int step=Math.min(4,(int)(rotation.fraction*4));
    if(step>rotation.lastLoggedStep){
     rotation.lastLoggedStep=step;
     Log.i(TAG,"task_rotation_frame task="+rotation.task+" generation="+rotation.generation+" fraction="+rotation.fraction+" branch="+(perspective?"perspective":"affine")+" plate="+w+"x"+h+" render="+s.renderBounds+" actual="+taskBuffer(s)+" sampled="+Arrays.toString(rotation.nativeMotion.sampledMatrix(rotation.fraction))+" map="+Arrays.toString(m));
    }
   }
   // Live pixels remain visible throughout; probes run only after the motion.
   if(rotation!=null&&rotation.slot==s&&rotation.changingLayout)t.setAlpha(leash,1f);
   return true;
  }catch(Throwable e){Log.w(TAG,"leash_fit_failed slot="+s.id,e);return false;}
 }
 void writeSurfaceOrder(WorkbenchSlot s,SurfaceControl.Transaction t,SurfaceControl plate,boolean preview)throws ReflectiveOperationException{
  if(surfaceViewRoot==null){surfaceViewRoot=View.class.getDeclaredMethod("getViewRootImpl");surfaceViewRoot.setAccessible(true);}
  Object root=surfaceViewRoot.invoke(s.surface);
  if(root==null)throw new IllegalStateException("Missing host ViewRoot");
  if(surfaceRootControl==null)surfaceRootControl=root.getClass().getMethod("getSurfaceControl");
  SurfaceControl viewRootSurface=(SurfaceControl)surfaceRootControl.invoke(root);
  if(viewRootSurface==null||!viewRootSurface.isValid())throw new IllegalStateException("Missing host surface");
  // AOSP's setter records the next traversal's requested sublayer. Also write it
  // into this fit transaction, so role swaps do not wait for another traversal.
  boolean lifted=host.sideSortLifted(s);
  ((SurfaceView)s.surface).setZOrderMediaOverlay(!preview||lifted);
  if(surfaceRelativeLayer==null)surfaceRelativeLayer=SurfaceControl.Transaction.class.getMethod("setRelativeLayer",SurfaceControl.class,SurfaceControl.class,int.class);
  surfaceRelativeLayer.invoke(t,plate,viewRootSurface,lifted?-1:preview?-2:-1);
 }
 void apply(WorkbenchSlot s,int[] r){
  if(s.card==null)return;
  fitSurface(s,r[2],r[3]);
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)s.card.getLayoutParams();
  if(lp==null)lp=new FrameLayout.LayoutParams(r[2],r[3]);
  if(lp.width!=r[2]||lp.height!=r[3]||lp.leftMargin!=r[0]||lp.topMargin!=r[1]){
   lp.width=r[2];lp.height=r[3];lp.leftMargin=r[0];lp.topMargin=r[1];s.card.setLayoutParams(lp);
  }
  clipSide(s.card,r[2],r[3],session.slots.indexOf(s)!=session.primary);
  fitRecoveryCover(s,r[2],r[3]);
 }
 void clipSide(View card,int w,int h,boolean side){
  if(card!=null){
   Ui.round(card,Ui.dp(host.context(),side?Ui.SIDE_RADIUS:Ui.MAIN_RADIUS));
   boolean projective=host.stage().ownsSurfaceAperture||AtomicPresentation.perspective(atomicRotate,side);
   for(WorkbenchSlot s:session.slots)if(s.card==card&&s.motionPose!=null){projective=true;break;}
   card.setClipToOutline(!projective);
   if(card instanceof android.view.ViewGroup)((android.view.ViewGroup)card).setClipChildren(!projective);
  }
 }
 void motionOutline(WorkbenchSlot s){
  // The rounded task leash supplies the contour in both motion and steady state.
  // The stage opens the matching projected rounded path; no second View clip.
  s.card.setClipToOutline(false);s.card.setClipChildren(false);
  if(s.card.getBackground()!=null)s.card.setBackground(null);
  if(s.surface!=null){
   s.surface.setClipBounds(null);
   s.surface.setClipToOutline(false);
   // SurfaceView also punches a rounded hole in the host Canvas. That radius is
   // multiplied by the card scale, independently of the SurfaceControl plate.
   // The aperture hook suppresses this native hole only while our contour is
   // present. Without that hook, retain the earlier motion-only fallback.
   try{
    if(rom.cornerRadius!=null&&rom.cornerRadius.getFloat(s.surface)!=0f)rom.cornerRadius.setFloat(s.surface,0f);
    float hole=((Number)rom.viewApi.getMethod("getCornerRadius").invoke(s.surface)).floatValue();
    if(hole!=0f)rom.viewApi.getMethod("setCornerRadius",float.class).invoke(s.surface,0f);
   }catch(Throwable e){Log.w(TAG,"motion_corner_radius_failed slot="+s.id,e);}
  }
 }
 void poseCard(WorkbenchSlot s,CardMotion.Pose pose,CardMotion.Pose target){
  s.motionPose=pose;s.card.setPivotX(0);s.card.setPivotY(0);
  s.card.setTranslationX(pose.box[0]-target.box[0]);s.card.setTranslationY(pose.box[1]-target.box[1]);
  s.card.setScaleX(pose.box[2]/target.box[2]);s.card.setScaleY(pose.box[3]/target.box[3]);motionOutline(s);
 }
 boolean projectedHostFrameReady(WorkbenchSlot s){
  return s.card!=null&&s.card.surfaceAperture!=null&&Looper.myLooper()==handler.getLooper()
    &&host.stage().isAttachedToWindow()&&host.stage().isHardwareAccelerated();
 }
 void submitMotionFit(SurfaceControl.Transaction fit){
  TaskIdentityController.RotationRun run=host.taskRotationRun();
  if(run!=null)host.recordRotationSubmission(run);
  if(host.rotationTransactions()!=null&&((run!=null&&run.changingLayout)||host.rotationTransactions().pending())){
   // A native transaction for another slot may arrive in the same host draw.
   // Always include the owned task's latest angle in the privileged final fit.
   if(host.rotationSubmitSlot()==null||!writeLeashFit(host.rotationSubmitSlot(),fit))throw new IllegalStateException("rotation fit unavailable");
   try{host.rotationTransactions().submit(host.stage(),fit,(SurfaceControl)rom.taskLeash.get(host.rotationSubmitSlot().surface),host.rotationSubmitTask(),host.rotationSubmitGeneration());}
   catch(ReflectiveOperationException e){throw new IllegalStateException("rotation leash unavailable",e);}return;
  }
  AttachedSurfaceControl root=host.stage().getRootSurfaceControl();
  if(root==null||!root.applyTransactionOnDraw(fit))throw new IllegalStateException("Missing host draw transaction");
 }
 void removeMotionFitListener(){
  if(motionFitListener!=null){host.stage().getViewTreeObserver().removeOnPreDrawListener(motionFitListener);motionFitListener=null;}
 }
 void raiseMain(){
  if(session.primary<0||session.primary>=session.slots.size())return;
  WorkbenchSlot s=session.slots.get(session.primary);host.stage().mainCard=s.card;if(s.card!=null)s.card.bringToFront();
  if(host.status()!=null)host.status().bringToFront();
 }
void resetCardTransform(WorkbenchSlot s){s.card.setTranslationX(0);s.card.setTranslationY(0);s.card.setScaleX(1);s.card.setScaleY(1);s.motionFromRadius=-1f;s.renderRadius=-1;applyCardRadius(s,session.slots.indexOf(s)==session.primary);}
void transformCard(WorkbenchSlot s,int[] from,int[] target,float fraction){
  s.card.setPivotX(0);s.card.setPivotY(0);float remaining=1-fraction;
  s.card.setTranslationX((from[0]-target[0])*remaining);s.card.setTranslationY((from[1]-target[1])*remaining);
  float scaleX=1+(from[2]/(float)Math.max(1,target[2])-1)*remaining;
  float scaleY=1+(from[3]/(float)Math.max(1,target[3])-1)*remaining;
  s.card.setScaleX(scaleX);s.card.setScaleY(scaleY);
  animateCardRadius(s,fraction,scaleX,scaleY);
}
void animateCardRadius(WorkbenchSlot s,float fraction,float scaleX,float scaleY){
  if(s.card==null)return;
  boolean isPrimary=session.slots.indexOf(s)==session.primary;
  if(AtomicPresentation.perspective(atomicRotate,!isPrimary))return;
  float target=cardRadius(isPrimary),from=s.motionFromRadius<0?target:s.motionFromRadius;
  int radius=Math.round(MotionSpec.viewRadius(from,target,fraction,scaleX,scaleY));
  if(radius==s.renderRadius)return;
  s.renderRadius=radius;
  if(host.stage().ownsSurfaceAperture){motionOutline(s);return;}
  Ui.round(s.card,radius);
  if(s.card.getBackground() instanceof android.graphics.drawable.GradientDrawable)
   ((android.graphics.drawable.GradientDrawable)s.card.getBackground()).setCornerRadius(radius);
}
}
