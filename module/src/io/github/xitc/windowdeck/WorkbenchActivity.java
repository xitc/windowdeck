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

/** Coordinates workbench lifecycle. Domain state lives on the controllers; this class is their Host. */
public final class WorkbenchActivity extends Activity implements
 TaskIdentityController.Host, HandoffController.Host, EntranceCoverController.Host, ArrivalController.Host, HangPickController.Host, WorkbenchMenuController.Host, TaskSurfaceRenderer.Host, LayoutMotionController.Host, PortraitPoseController.Host, FullscreenController.Host, TaskReleaseController.Host, WorkbenchBackController.Host, WorkbenchStatePublisher.Host {
 private static final String TAG="WindowDeck";
 /** True while a workbench is on screen. CanvasImeBridge only pushes IME state for our container. */
 static volatile boolean foreground;
 private static final String ADD_TO_LIVE_WORKBENCH="io.github.xitc.windowdeck.ADD_TO_LIVE_WORKBENCH";
 private static final String REVEAL_ADDED_CARD="io.github.xitc.windowdeck.REVEAL_ADDED_CARD";
 final WorkbenchSession session=new WorkbenchSession();
 final CanvasBinding rom=new CanvasBinding();
 final LiveContentPump pump=new LiveContentPump();
 final Handler handler=new Handler(Looper.getMainLooper());
 final TaskIdentityController identity=new TaskIdentityController(this,session,handler,rom,pump);
 final HandoffController handoff=new HandoffController(this,session,handler,rom);
 final EntranceCoverController cover=new EntranceCoverController(this,session,handler,rom,pump);
 final ArrivalController arrival=new ArrivalController(this,session,handler,rom);
 final HangPickController hang=new HangPickController(this,session,handler,rom,pump);
 final WorkbenchMenuController menu=new WorkbenchMenuController(this,session,handler,rom);
 final TaskSurfaceRenderer surfaces=new TaskSurfaceRenderer(this,session,handler,rom);
 final LayoutMotionController layout=new LayoutMotionController(this,session,handler,rom);
 final PortraitPoseController portrait=new PortraitPoseController(this,session,handler,rom);
 final FullscreenController fullscreen=new FullscreenController(this,session,handler,rom);
 final TaskReleaseController release=new TaskReleaseController(this,session,handler,rom);
 final WorkbenchBackController back=new WorkbenchBackController(this,session,handler,rom);
 final WorkbenchStatePublisher publisher=new WorkbenchStatePublisher(this,session,handler);
 CanvasStage stage;
 TextView status;
 Button addCard;
 ControlDots more;
 boolean initialized,closing,layoutPosted,stopped,backgrounded;
 boolean addReceiverRegistered,launcherReceiverRegistered;
 int imeBottom;
 LinearLayout contentRoot;
 android.graphics.Insets contentSystemInsets=android.graphics.Insets.NONE;
 int windowImeBottom;
 WorkbenchBackdrop backdrop;
 boolean keepSurfaceOnBackground=TransitionPolicy.KEEP_SURFACE_DEFAULT;
 boolean detachLeashBeforeTransition=TransitionPolicy.DETACH_LEASH_DEFAULT;
 final ReplacementController<WorkbenchSlot> replacements=new ReplacementController<>(
  new ReplacementController.Scheduler(){
   public void post(Runnable action,long delay){handler.postDelayed(action,delay);}
   public void remove(Runnable action){handler.removeCallbacks(action);}
  },new ReplacementController.Host<WorkbenchSlot>(){
   public void prepare(ReplacementController.Run<WorkbenchSlot> run,Runnable ready){
    Log.i(TAG,"replace_prepare slot="+run.fresh.id+" was="+run.old.id);menu.prepareRecovery(run.old,ready);
   }
   public boolean install(ReplacementController.Run<WorkbenchSlot> run){
    if(closing||stopped||backgrounded||run.old.released||run.old.pinned||session.slots.indexOf(run.old)!=run.index)return false;
    session.slots.set(run.index,run.fresh);surfaces.updateNaturalBounds();surfaces.createWindow(run.fresh);if(replacements.current()!=run)return false;hang.transferCover(run.old,run.fresh);layout.layoutCards(false);layout.refreshStatus();return true;
   }
   public boolean valid(ReplacementController.Run<WorkbenchSlot> run){
    return !closing&&!stopped&&!backgrounded&&!run.old.released&&!run.old.pinned&&!run.fresh.failed
     &&session.slots.indexOf(run.fresh)==run.index&&session.primary==run.primary;
   }
   public int task(WorkbenchSlot slot){return slot.taskId;}
   public String surfaceKey(){return cover.entranceSurfaceKey();}
   public void commitFrame(Runnable committed){
    try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
     for(WorkbenchSlot s:session.slots)if(!surfaces.writeLeashFit(s,fit))throw new IllegalStateException("Unfitted replacement slot "+s.id);
     fit.addTransactionCommittedListener((Executor)handler::post,()->{
      stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(committed));stage.invalidate();
     });fit.apply();
    }
   }
   public void committed(ReplacementController.Run<WorkbenchSlot> run){hang.commitReplacement(run);}
   public void rolledBack(ReplacementController.Run<WorkbenchSlot> run,String reason){hang.restoreReplacement(run,reason);}
   public void failed(Throwable error){Log.w(TAG,"replace_fit_failed",error);}
  });
 final OnBackAnimationCallback hostBack=new OnBackAnimationCallback(){
  public void onBackStarted(BackEvent e){back.backStartedWithIme=back.embeddedImeVisible();Log.i(TAG,"back_gesture_started ime="+back.backStartedWithIme);}
  public void onBackProgressed(BackEvent e){}
  public void onBackCancelled(){back.backStartedWithIme=false;Log.i(TAG,"back_gesture_cancelled");}
  public void onBackInvoked(){back.handleHostBack();}
 };
 final BroadcastReceiver addReceiver=new BroadcastReceiver(){
  @Override public void onReceive(Context context,Intent intent){
   if(intent==null)return;
   if(ADD_TO_LIVE_WORKBENCH.equals(intent.getAction())){
    Log.i(TAG,"add_broadcast_received uid="+getSentFromUid()+" task="+intent.getIntExtra("windowdeck_add_task_id",-1)+" container="+intent.getIntExtra("windowdeck_container_task_id",-1));
    handler.post(()->handoff.addExistingTask(intent));
   }else if(REVEAL_ADDED_CARD.equals(intent.getAction()))handler.post(()->handoff.revealAddedCard(intent));
  }
 };
 final BroadcastReceiver launcherReceiver=new BroadcastReceiver(){
  @Override public void onReceive(Context context,Intent intent){
   // This separate receiver preserves the DUMP-protected root fallback. Never trust an
   // intent extra as caller identity; the launcher explicitly shares the system-supplied UID.
   setResultCode(2);
   String[] packages=getPackageManager().getPackagesForUid(getSentFromUid());
   boolean launcher=false;
   if(packages!=null)for(String pkg:packages)if("com.android.launcher".equals(pkg))launcher=true;
   if(intent!=null&&VideoWindowCompat.QUERY.equals(intent.getAction())){
    boolean video=false;if(packages!=null)for(String pkg:packages)if(VideoWindowPolicy.target(pkg))video=true;
    if(video&&getSentFromUid()/100000==android.os.Process.myUid()/100000&&!closing&&initialized){setResultExtras(publisher.liveState(session.slots.size()));setResultCode(1);}
    return;
   }
   if(!launcher||intent==null||getSentFromUid()/100000!=android.os.Process.myUid()/100000){Log.w(TAG,"direct_add_sender_denied uid="+getSentFromUid());return;}
   if(LauncherExistingAppHook.SELECT.equals(intent.getAction())){setResultCode(hang.selectExistingFromLauncher(intent)?1:2);return;}
   if(LauncherExistingAppHook.LAUNCH.equals(intent.getAction())){setResultCode(hang.launchFromPicker(intent)?1:2);return;}
   if(LiveWorkbenchState.QUERY.equals(intent.getAction())){
    if(!closing&&initialized){setResultExtras(publisher.liveState(session.slots.size()));setResultCode(1);}
    return;
   }
   if(handoff.addExistingTask(intent)){handoff.revealAddedCard(intent);setResultCode(1);}
   // 3 tells the launcher the host answered and refused: it must raise the container
   // rather than fall through to the root path. 0 stays reserved for "no host at all".
   else setResultCode(3);
  }
 };

 @Override protected void onCreate(Bundle state){
  setTheme(android.R.style.Theme_Material_NoActionBar);super.onCreate(state);
  // Default ROTATION_ANIMATION_ROTATE fades this portrait window through white.
  // The landscape cover is already up; the display should cut underneath it.
  seamlessRotation();
  // Only this custom container owns a portrait canvas. Embedded activities retain their
  // orientation requests; do not change global auto-rotate or the native canvas activity.
  // Keep the host in its final orientation. The source task keeps its landscape buffer.
  portrait.deferPortrait=state==null&&getIntent().getBooleanExtra("windowdeck_defer_portrait",false);
  if(portrait.deferPortrait){
   if(getIntent().getBooleanExtra("windowdeck_wait_source_release",false))portrait.sourceRelease=new SourceRelease();
   portrait.deferSourceRotation=getIntent().getIntExtra("windowdeck_defer_rotation",-1);
   if(portrait.deferSourceRotation!=1&&portrait.deferSourceRotation!=3){
    Log.w(TAG,"defer_source_rotation_invalid rotation="+portrait.deferSourceRotation);
    android.os.ResultReceiver reply=getIntent().getParcelableExtra("windowdeck_handoff_ready",android.os.ResultReceiver.class);
    if(reply!=null)reply.send(HandoffProtocol.FAILED,Bundle.EMPTY);
    finish();return;
   }
   // C17 creates the ContainerActivity with fixed rotation 0 before newActivity/onCreate.
   // Requesting landscape here produces an extra 0→1→0 CHANGE/snapshot transition.
   // Like its native small-window rotation layer, bridge pixels in the destination
   // window while the original task is embedded; never rotate the host to fit the task.
   setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
   Log.i(TAG,"canvas_orientation requested=portrait source_rotation="+portrait.deferSourceRotation+" activity_rotation="+(getDisplay()==null?-1:getDisplay().getRotation())+" policy=snapshot_rotation_bridge");
  }else{
   setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
   Log.i(TAG,"canvas_orientation requested=portrait policy=independent_tasks");
  }
  // A stale launcher request must not create an empty replacement container.
  if(state==null&&getIntent().hasExtra("windowdeck_add_task_id")){Log.w(TAG,"add_task_no_container");finish();return;}
  // ActivityManager checks DUMP before delivering this exported receiver.
  // Root am broadcast may report an unknown sender UID (-1) in onReceive.
  IntentFilter addFilter=new IntentFilter(ADD_TO_LIVE_WORKBENCH);addFilter.addAction(REVEAL_ADDED_CARD);
  registerReceiver(addReceiver,addFilter,android.Manifest.permission.DUMP,handler,Context.RECEIVER_EXPORTED);addReceiverRegistered=true;
  IntentFilter launcherFilter=new IntentFilter("io.github.xitc.windowdeck.LAUNCHER_ADD_TO_WORKBENCH");launcherFilter.addAction(LiveWorkbenchState.QUERY);launcherFilter.addAction(LauncherExistingAppHook.SELECT);launcherFilter.addAction(LauncherExistingAppHook.LAUNCH);launcherFilter.addAction(VideoWindowCompat.QUERY);
  registerReceiver(launcherReceiver,launcherFilter,android.Manifest.permission.REORDER_TASKS,handler,Context.RECEIVER_EXPORTED);launcherReceiverRegistered=true;
  getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,hostBack);
  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
  LinearLayout root=contentRoot=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0);setContentView(root);getWindow().setBackgroundDrawable(new ColorDrawable(Ui.CHROME));seamlessRotation();Ui.overlaySystemBars(this,false);
  root.setOnApplyWindowInsetsListener((v,insets)->{
   contentSystemInsets=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
   windowImeBottom=insets.getInsets(WindowInsets.Type.ime()).bottom;
   applyImeInsets();
   return insets;
  });
  root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->applyImeInsets());
  layout.layoutMode=state==null?PaneLayout.LEFT_RIGHT:state.getInt("layoutMode",PaneLayout.LEFT_RIGHT);
  if(layout.layoutMode!=PaneLayout.TOP_BOTTOM)layout.layoutMode=PaneLayout.LEFT_RIGHT;
  surfaces.atomicRotate=getIntent().getBooleanExtra(AtomicPresentation.EXTRA,AtomicPresentation.DEFAULT);
  layout.mainWindowPercent=MainWindowSettings.read(this,layout.mainWindowPercent);
  Log.i(TAG,"atomic_rotate="+surfaces.atomicRotate+" extra="+AtomicPresentation.EXTRA);
  keepSurfaceOnBackground=getIntent().getBooleanExtra(TransitionPolicy.EXTRA_KEEP_SURFACE,TransitionPolicy.KEEP_SURFACE_DEFAULT);
  detachLeashBeforeTransition=getIntent().getBooleanExtra(TransitionPolicy.EXTRA_DETACH_LEASH,TransitionPolicy.DETACH_LEASH_DEFAULT);
  Log.i(TAG,"transition_policy keep_surface="+keepSurfaceOnBackground+" detach_leash="+detachLeashBeforeTransition);
  stage=new CanvasStage(this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
  try{
   identity.rotationTransactions=new RotationTransactions.Host(this,handler,getTaskId(),publisher.stateToken,(task,generation)->{
    TaskIdentityController.RotationRun run=identity.taskRotationRun;
    if(run!=null&&run.task==task&&run.generation==generation)identity.finishTaskRotation(run,"submit_failed");
   });identity.rotationTransactions.connect();
  }catch(Exception e){Log.e(TAG,"rotation_submitter_host_unavailable",e);}
  if(portrait.deferPortrait){
   // WM must see the destination snapshot on this window's first drawn frame.
   // This gate does not wait for task draw/SurfaceView readiness (which needs traversal).
   portrait.poseBootstrapListener=()->{
    if(closing||!portrait.deferPortrait||(portrait.poseBridgeFitted&&backdrop!=null&&backdrop.ready)){
     stage.getViewTreeObserver().removeOnPreDrawListener(portrait.poseBootstrapListener);portrait.poseBootstrapListener=null;return true;
    }
    return false;
   };
   stage.getViewTreeObserver().addOnPreDrawListener(portrait.poseBootstrapListener);
  }
  addCard=Ui.button(this,"＋");addCard.setTextSize(28);addCard.setTextColor(Ui.FROST_PLUS);addCard.setGravity(Gravity.CENTER);addCard.setIncludeFontPadding(false);addCard.setPadding(0,0,0,0);addCard.setContentDescription("添加应用");addCard.setBackground(Ui.frost(Ui.dp(this,Ui.SIDE_RADIUS)));Ui.round(addCard,Ui.dp(this,Ui.SIDE_RADIUS));addCard.setOnClickListener(v->{hang.hangReplace=false;hang.hangForAdd();});stage.addView(addCard);
  more=Ui.more(this);more.setOnClickListener(v->menu.primaryMenu());more.setOnLongClickListener(v->{menu.primarySizeSlider();return true;});menu.registerControlBarLight();
  status=Ui.text(this,"正在准备窗口…",12,Ui.TEXT);status.setGravity(Gravity.CENTER);status.setBackground(Ui.bg(0x99000000,Ui.dp(this,8)));status.setPadding(Ui.dp(this,12),Ui.dp(this,6),Ui.dp(this,12),Ui.dp(this,6));status.setVisibility(View.GONE);
  FrameLayout.LayoutParams statusLp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);statusLp.bottomMargin=Ui.dp(this,16);stage.addView(status,statusLp);
  try{
   if(!"com.oplus.pscanvas".equals(getPackageName()))throw new IllegalStateException("需要从系统容器启动");
   rom.canvas=CanvasContract.require();rom.viewApi=rom.canvas.view;rom.managerApi=rom.canvas.manager;
   rom.manager=rom.canvas.getInstance.invoke(null);rom.resizeMethod=rom.canvas.viewMethod("resize");
   rom.interceptInput=rom.canvas.interceptInput;rom.rotateTaskLeash=rom.canvas.rotateTaskLeash;
   rom.cornerRadius=rom.canvas.cornerRadius;rom.taskLeash=rom.canvas.taskLeash;
   rom.reparentAlign=rom.canvas.reparentAlign;rom.superLocked=rom.canvas.superLocked;
   try{
    rom.viewTransaction=rom.canvas.viewTransaction;
    rom.leashMatrix=SurfaceControl.Transaction.class.getDeclaredMethod("setMatrix",SurfaceControl.class,float.class,float.class,float.class,float.class);rom.leashMatrix.setAccessible(true);
    rom.leashMatrix4=SurfaceControl.Transaction.class.getDeclaredMethod("setTransformationMatrix4x4",SurfaceControl.class,float[].class);rom.leashMatrix4.setAccessible(true);
    rom.leashRadius=SurfaceControl.Transaction.class.getDeclaredMethod("setCornerRadius",SurfaceControl.class,float.class);
    Log.i(TAG,"leash_perspective_api=matrix4x4");
   }catch(Throwable e){Log.w(TAG,"leash_perspective_unavailable",e);}
   backdrop=new WorkbenchBackdrop();backdrop.onReady=handoff::requestEntranceFrame;backdrop.attach(this);
   String[] input=state!=null?state.getStringArray("components"):null;
   if(input==null)input=new String[]{getIntent().getStringExtra("windowdeck_app_a"),getIntent().getStringExtra("windowdeck_app_b"),getIntent().getStringExtra("windowdeck_app_c"),getIntent().getStringExtra("windowdeck_app_d"),getIntent().getStringExtra("windowdeck_app_e")};
   int initialSource=state==null?getIntent().getIntExtra("windowdeck_create_source_task",-1):-1;
   if(initialSource>=0){
    WorkbenchSlot fresh=identity.validateSourceTask(initialSource,getIntent().getIntExtra("windowdeck_create_source_user",-1));
    session.slots.add(fresh);handoff.acceptGestureSource(fresh);
    handoff.handoffReady=getIntent().getParcelableExtra("windowdeck_handoff_ready",android.os.ResultReceiver.class);
    handoff.handoffDeadline=SystemClock.uptimeMillis()+(fresh.orientationAxis==2?MotionSpec.LANDSCAPE_REVEAL_MS:MotionSpec.COVER_TIMEOUT_MS);
    handoff.portraitHandoffLimit=getIntent().getLongExtra(HandoffProtocol.PORTRAIT_LIMIT,0);
    if(handoff.portraitHandoffLimit>0){
     long now=SystemClock.uptimeMillis();
     if(!PortraitHandoffDeadline.validHostStart(portrait.deferPortrait,fresh.orientationAxis,handoff.handoffReady!=null,
       handoff.portraitHandoffLimit,now,MotionSpec.COVER_TIMEOUT_MS))throw new IllegalArgumentException(
         "portrait handoff deadline axis="+fresh.orientationAxis+" defer="+portrait.deferPortrait
         +" reply="+(handoff.handoffReady!=null)+" remaining_ms="+(handoff.portraitHandoffLimit-now));
     handoff.handoffDeadline=Math.min(handoff.handoffDeadline,handoff.portraitHandoffLimit);
     Bundle started=new Bundle();started.putInt(HandoffProtocol.TASK,initialSource);started.putInt(HandoffProtocol.CONTAINER,getTaskId());
     started.putLong(HandoffProtocol.READY_DEADLINE,handoff.handoffDeadline);
     handoff.handoffReady.send(HandoffProtocol.HOST_STARTED,started);
     Log.i(TAG,"handoff_host_started task="+initialSource+" ready_deadline="+handoff.handoffDeadline+" limit="+handoff.portraitHandoffLimit);
    }
    handoff.watchHandoffDeadline(fresh);
    input=new String[0];
   }
   int[] savedSources=state==null?null:state.getIntArray("sourceTaskIds");
   for(String component:input)if(component!=null&&!component.isEmpty()){
    if(session.slots.size()>=Caps.MAX_TASKS)break;WorkbenchSlot restored=identity.validate(ComponentName.unflattenFromString(component),null);
    int index=session.slots.size();if(savedSources!=null&&index<savedSources.length&&savedSources[index]>=0){restored.sourceTaskId=savedSources[index];restored.sourceUserId=android.os.Process.myUid()/100000;}
    session.slots.add(restored);
   }
   if(session.slots.isEmpty())throw new IllegalArgumentException("请至少选择一个应用");
   if(state!=null){String pinned=state.getString("pinned");for(WorkbenchSlot slot:session.slots)slot.pinned=slot.component.flattenToString().equals(pinned);}
   session.primary=state==null?0:Math.max(0,Math.min(session.slots.size()-1,state.getInt("primary",0)));
   publisher.publishState(session.slots.size());
   stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
    if(closing||r-l<100||b-t<100)return;
    if(initialized&&r-l==or-ol&&b-t==ob-ot)return;
    if(!initialized){initialized=true;surfaces.updateNaturalBounds();if(!portrait.waitingForSourceRelease())for(WorkbenchSlot s:new ArrayList<>(session.slots))surfaces.createWindow(s);if(!handoff.gestureHandoff&&handoff.entrance.pending()!=null&&handoff.entrance.pending().card!=null)handoff.entrance.pending().card.setAlpha(0f);}
    scheduleLayout();
   });
   Log.i(TAG,"workbench_created version="+Version.NAME+" container="+getTaskId()+" count="+session.slots.size()+" cap="+Caps.MAX_TASKS);
   if(portrait.deferPortrait)handler.post(()->portrait.requestCoverPose(handoff.entrance.pending()));
  }catch(Throwable e){fail(e);if(getIntent().hasExtra("windowdeck_create_source_task"))finish();}
 }
 @Override public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){
  super.dump(prefix,fd,writer,args);
  writer.println(prefix+"WindowDeck IME: "+CanvasImeBridge.diagnostic());
  writer.println(prefix+"WindowDeck switch: generation="+layout.switchGeneration+" primary="+session.primary+" animation="+(layout.animation!=null)+" settling="+layout.settlingInput+" arrival="+arrival.screenArrival+" recovering="+menu.recovering+" stopped="+stopped+" backgrounded="+backgrounded+" closing="+closing);
  for(WorkbenchSlot s:session.slots){
   writer.println(prefix+"  slot="+s.id+" task="+s.taskId+" embedded="+s.embedded+" released="+s.released+" primary="+(session.slots.indexOf(s)==session.primary));
   if(s.card!=null)writer.println(prefix+"    card layout="+s.card.getLeft()+","+s.card.getTop()+","+s.card.getWidth()+","+s.card.getHeight()+" translation="+s.card.getTranslationX()+","+s.card.getTranslationY()+" scale="+s.card.getScaleX()+","+s.card.getScaleY()+" alpha="+s.card.getAlpha()+" layoutRequested="+s.card.isLayoutRequested());
   writer.println(prefix+"    motion="+(s.motionPose==null?"none":Arrays.toString(s.motionPose.box))+" fitted="+(s.fittedPose==null?"none":Arrays.toString(s.fittedPose.box))+" fittedTask="+s.fittedTask+" fittedLayer="+s.fittedLayer+" render="+s.renderBounds);
   try{
    if(s.surface!=null)writer.println(prefix+"    surface size="+s.surface.getWidth()+"x"+s.surface.getHeight()+" attached="+s.surface.isAttachedToWindow()+" layoutRequested="+s.surface.isLayoutRequested()+" leash="+LiveTaskContent.layerId((SurfaceControl)rom.taskLeash.get(s.surface))+" plate="+LiveTaskContent.layerId(((SurfaceView)s.surface).getSurfaceControl()));
   }catch(Throwable e){writer.println(prefix+"    surface unavailable="+e.getClass().getSimpleName());}
  }
 }
 @Override protected void onPause(){identity.immersiveForeground=false;handler.removeCallbacks(identity.immersivePoll);if(identity.systemRotationObserver!=null)identity.systemRotationObserver.close();menu.dismissPrimaryMenu();menu.cancelDrag();if(!closing&&!arrival.screenArrival&&!portrait.deferPortrait&&identity.taskRotationRun==null)layout.cancelAnimation();if(initialized&&!closing&&!arrival.screenArrival&&!portrait.deferPortrait&&identity.taskRotationRun==null)layout.layoutCards(false);super.onPause();}
 @Override protected void onResume(){super.onResume();menu.refreshMainWindowSize();if(identity.rotationTransactions!=null)identity.rotationTransactions.connect();CanvasImeBridge.start(this);identity.immersiveForeground=true;if(identity.systemRotationObserver==null)identity.systemRotationObserver=new SystemRotationObserver(this,handler,identity::queueTaskDirections);identity.systemRotationObserver.start();if(identity.orientationObserver==null){identity.orientationObserver=new TaskOrientationObserver(handler,identity::onTaskOrientationRequested);identity.orientationObserver.start();}foreground=true;stopped=false;backgrounded=false;if(initialized&&!closing)publisher.publishState(session.slots.size());handoff.noteGestureFront();if(backdrop!=null)backdrop.onResume();handler.post(release::restorePendingTasks);handler.post(hang::consumePickerLaunch);handler.post(hang::consumeHangAdd);handler.post(arrival::beginHangEntrance);handler.post(handoff::maybeAnimateAddedCard);handler.post(identity::queueTaskDirections);handler.post(identity.immersivePoll);handler.postDelayed(()->{if(stopped||backgrounded||closing)return;for(WorkbenchSlot s:session.slots)surfaces.syncSurface(s);back.focusPrimary("resume");handoff.maybeAnimateAddedCard();},350);}
 @Override protected void onStop(){CanvasImeBridge.stop(this);foreground=false;stopped=true;if(replacements.current()!=null)replacements.cancel(replacements.current(),"host_stopped");if(fullscreen.fullscreenRun!=null&&fullscreen.fullscreenRun.lifecycle.open())fullscreen.abortFullscreen(fullscreen.fullscreenRun,"host_stopped",true);if(identity.taskRotationRun!=null)identity.finishTaskRotation(identity.taskRotationRun,"host_stopped");if(arrival.screenArrival||handoff.handoffPresentation)layout.cancelAnimation();if(handoff.gestureHandoff&&handoff.entrance.pending()!=null)handoff.failHandoff(handoff.entrance.pending(),"host_stopped");for(WorkbenchSlot s:session.slots){s.drawEvidence.invalidate();surfaces.clearProjection(s);}back.backGeneration++;back.forwardingBack=false;super.onStop();}
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);menu.refreshMainWindowSize();backgrounded=false;stopped=false;if(intent.hasExtra("windowdeck_add_task_id"))handler.post(()->handoff.addExistingTask(intent));Log.i(TAG,"workbench_resume container="+getTaskId()+" primary="+session.primary+" layout="+layout.layoutMode+" tasks="+layout.taskIds());handler.post(()->{for(WorkbenchSlot s:session.slots)surfaces.syncSurface(s);back.focusPrimary("entry");});}
 public void scheduleLayout(){if(layoutPosted)return;layoutPosted=true;stage.post(()->{layoutPosted=false;if(!closing){menu.cancelDrag();surfaces.updateNaturalBounds();if(portrait.deferPortrait&&portrait.poseHeld){if(!portrait.poseSettling)portrait.expandPosePlate(portrait.poseSlot);}else if(arrival.screenArrival)arrival.rebaseArrivalLayout();else{if(identity.taskRotationRun==null||!identity.taskRotationRun.changingLayout||identity.taskRotationRun.started)layout.cancelAnimation();layout.layoutCards(false);}for(WorkbenchSlot s:session.slots)surfaces.resizeSurface(s);handoff.requestEntranceFrame();Log.i(TAG,"layout width="+stage.getWidth()+" height="+stage.getHeight()+" ime="+imeBottom+" count="+session.slots.size());layout.scheduleLayoutEvidence("layout");}});}
public void applyImeInsets(){
  if(contentRoot==null||contentRoot.getHeight()<=0||closing)return;
  int[] location=new int[2];contentRoot.getLocationOnScreen(location);
  int displayBottom=CanvasImeBridge.bottomInset(this,location[1],location[1]+contentRoot.getHeight());
  int keyboard=ImeAvoidance.resolve(displayBottom,windowImeBottom);
  android.graphics.Insets sys=contentSystemInsets;
  int bottom=Math.max(sys.bottom,keyboard);
  if(contentRoot.getPaddingLeft()!=sys.left||contentRoot.getPaddingTop()!=sys.top
    ||contentRoot.getPaddingRight()!=sys.right||contentRoot.getPaddingBottom()!=bottom)
   contentRoot.setPadding(sys.left,sys.top,sys.right,bottom);
  if(imeBottom!=keyboard){imeBottom=keyboard;Log.i(TAG,"ime bottom="+keyboard+" source="+(displayBottom>=0?"display":"window"));}
 }
 @Override protected void onDestroy(){if(fullscreen.fullscreenRun!=null&&fullscreen.fullscreenRun.lifecycle.open())fullscreen.abortFullscreen(fullscreen.fullscreenRun,"destroyed",false);if(identity.rotationTransactions!=null)identity.rotationTransactions.close();CanvasImeBridge.stop(this);handler.removeCallbacks(identity.immersivePoll);if(identity.systemRotationObserver!=null){identity.systemRotationObserver.close();identity.systemRotationObserver=null;}handler.removeCallbacks(identity.applyDirections);identity.directionPosted=false;if(identity.orientationObserver!=null){identity.orientationObserver.close();identity.orientationObserver=null;}portrait.clearHeldPose();hang.clearHangSourceFrame();hang.dismissHang();menu.dismissPrimaryMenu();menu.unregisterControlBarLight();menu.detachCaption();cover.closeEntranceCover("destroy");pump.quit();if(addReceiverRegistered){unregisterReceiver(addReceiver);addReceiverRegistered=false;}if(launcherReceiverRegistered){unregisterReceiver(launcherReceiver);launcherReceiverRegistered=false;}if(backdrop!=null){backdrop.detach();backdrop=null;}getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(hostBack);if(!closing){if(!isChangingConfigurations())publisher.publishState(0);closing=true;release.releaseWindows();}super.onDestroy();}
 @Override protected void onSaveInstanceState(Bundle b){String[] components=new String[session.slots.size()];int[] sourceTaskIds=new int[session.slots.size()];for(int i=0;i<session.slots.size();i++){WorkbenchSlot s=session.slots.get(i);components[i]=s.component.flattenToString();sourceTaskIds[i]=s.sourceTaskId>=0?(s.taskId>=0?s.taskId:s.sourceTaskId):-1;}b.putStringArray("components",components);b.putIntArray("sourceTaskIds",sourceTaskIds);b.putInt("primary",session.primary);b.putInt("layoutMode",layout.layoutMode);for(WorkbenchSlot s:session.slots)if(s.pinned)b.putString("pinned",s.component.flattenToString());super.onSaveInstanceState(b);}
 @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);Log.i(TAG,"configuration orientation="+c.orientation+" tasks="+layout.taskIds()+" defer="+portrait.deferPortrait+" pose="+portrait.poseHeld);if(portrait.poseHeld)portrait.logHeldLayer("defer_rotation_config");if(backdrop!=null)backdrop.onConfigurationChanged();if(stage!=null)scheduleLayout();}
 @Override public void finish(){if(retainInsteadOfFinish("finish"))return;super.finish();}
 @Override public void finishAndRemoveTask(){if(retainInsteadOfFinish("finish_remove"))return;super.finishAndRemoveTask();}
 @Override public void finishAfterTransition(){if(retainInsteadOfFinish("finish_transition"))return;super.finishAfterTransition();}
 @Override public void onBackPressed(){back.handleHostBack();}
 private boolean retainInsteadOfFinish(String reason){
  if(!keepGroup())return false;
  // A forwarded key is still navigating inside the main app. Do not leave or destroy the group.
  if(back.forwardingBack){Log.i(TAG,"finish_swallowed reason="+reason);return true;}
  back.backgroundWorkbench(reason);return true;
 }
 private boolean keepGroup(){return !closing&&initialized&&!session.slots.isEmpty();}
private void seamlessRotation(){
 WindowManager.LayoutParams attrs=getWindow().getAttributes();
 attrs.rotationAnimation=WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
 getWindow().setAttributes(attrs);
}
 public void fail(Throwable e){while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();Log.e(TAG,"workbench_error",e);if(status!=null){status.setVisibility(View.VISIBLE);status.setText("无法启动："+e.getClass().getSimpleName()+" · "+e.getMessage());}}
 public ValueAnimator addAnimation(){return layout.addAnimation;}
 public boolean backgrounded(){return backgrounded;}
 public boolean closing(){return closing;}
 public Context context(){return this;}
 public CardMotion.Pose currentPose(WorkbenchSlot s){return surfaces.currentPose(s);}
 public WorkbenchSlot dragging(){return menu.dragging;}
 public boolean initialized(){return initialized;}
 public void inputRole(WorkbenchSlot s,boolean isPrimary){surfaces.inputRole(s,isPrimary);}
 public boolean layoutPosted(){return layoutPosted;}
 public PackageManager packageManager(){return getPackageManager();}
 public String packageName(){return getPackageName();}
 public WorkbenchSlot pendingEntrance(){return handoff.entrance.pending();}
 public boolean poseHeld(){return portrait.poseHeld;}
 public boolean recovering(){return menu.recovering;}
 public Resources resources(){return getResources();}
 public void scheduleLayoutEvidence(String reason){layout.scheduleLayoutEvidence(reason);}
 public boolean screenArrival(){return arrival.screenArrival;}
 public CanvasStage stage(){return stage;}
 public boolean stopped(){return stopped;}
 public void submitMotionFit(SurfaceControl.Transaction fit){surfaces.submitMotionFit(fit);}
 public void supersedeHangEntrance(){hang.supersedeHangEntrance();}
 public WorkbenchSlot releaseEntrancePresentation(){return handoff.releaseEntrancePresentation();}
 public boolean switching(){return layout.switching();}
 public void syncSurface(WorkbenchSlot s){surfaces.syncSurface(s);}
 public Rect taskBuffer(WorkbenchSlot slot){return surfaces.taskBuffer(slot);}
 public void updateRenderBounds(WorkbenchSlot s){surfaces.updateRenderBounds(s);}
 public boolean writeLeashFit(WorkbenchSlot s,SurfaceControl.Transaction t){return surfaces.writeLeashFit(s,t);}
 public void animateAddedCard(WorkbenchSlot slot){hang.animateAddedCard(slot);}
 public ValueAnimator animation(){return layout.animation;}
 public WorkbenchBackdrop backdrop(){return backdrop;}
 public void beginHangEntrance(){arrival.beginHangEntrance();}
 public void cancelAnimation(){layout.cancelAnimation();}
 public void cancelAnimation(boolean preservePose){layout.cancelAnimation(preservePose);}
 public void cancelForLayout(boolean preservePose){arrival.cancelForLayout(preservePose);}
 public CardLayout.Result cardGeometry(){return surfaces.cardGeometry();}
 public int cardRadius(boolean isPrimary){return surfaces.cardRadius(isPrimary);}
 public void clearHeldPose(){portrait.clearHeldPose();}
 public void closeEntranceCover(String why){cover.closeEntranceCover(why);}
 public void createWindow(WorkbenchSlot slot){surfaces.createWindow(slot);}
 public boolean deferPortrait(){return portrait.deferPortrait;}
 public Display display(){return getDisplay();}
 public boolean focusPrimary(String reason){return back.focusPrimary(reason);}
 public boolean hangQuiet(){return hang.hangQuiet;}
 public void layoutCaption(){menu.layoutCaption();}
 public void layoutCards(boolean animate){layout.layoutCards(animate);}
 public void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source){layout.layoutCards(animate,source);}
 public WorkbenchSlot pendingPickerLaunch(){return hang.pendingPickerLaunch;}
 public boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus){return arrival.poseScreenArrival(fresh,fromEdge,hungMain,originalPlus);}
 public boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus,long waitMs){return arrival.poseScreenArrival(fresh,fromEdge,hungMain,originalPlus,waitMs);}
 public void prepareReplacement(int index,WorkbenchSlot old,WorkbenchSlot fresh){hang.prepareReplacement(index,old,fresh);}
 public int[] presentationSize(WorkbenchSlot s){return surfaces.presentationSize(s);}
 public void probeLandscapePresented(WorkbenchSlot s,SurfaceControl leash){cover.probeLandscapePresented(s,leash);}
 public void publishState(int count){publisher.publishState(count);}
 public void publishState(int count,int generation,int attempt){publisher.publishState(count,generation,attempt);}
 public boolean readOriginalPlus(){return hang.readOriginalPlus();}
 public void refreshStatus(){layout.refreshStatus();}
 public void releaseEntrance(){cover.releaseEntrance();}
 public void releaseSlot(WorkbenchSlot s){release.releaseSlot(s);}
 public void releaseSlot(WorkbenchSlot s,boolean toFront){release.releaseSlot(s,toFront);}
 public void releaseSlot(WorkbenchSlot s,boolean toFront,boolean retainPlate){release.releaseSlot(s,toFront,retainPlate);}
 public ReplacementController<WorkbenchSlot> replacements(){return replacements;}
 public void requestCoverPose(WorkbenchSlot slot){portrait.requestCoverPose(slot);}
 public boolean rotatePresentation(WorkbenchSlot s){return surfaces.rotatePresentation(s);}
 public Rect screenRect(View view){return hang.screenRect(view);}
 public void setAddAnimation(ValueAnimator value){layout.addAnimation=value;}
 public void setDeferPortrait(boolean value){portrait.deferPortrait=value;}
 public SourceRelease sourceRelease(){return portrait.sourceRelease;}
 public int[] surfacePlate(WorkbenchSlot s){return surfaces.surfacePlate(s);}
 public CardMotion.Pose surfacePose(WorkbenchSlot s,int w,int h){return surfaces.surfacePose(s,w,h);}
 public int taskDrawState(WorkbenchSlot s) throws Exception{return cover.taskDrawState(s);}
 public int taskId(){return getTaskId();}
 public void unlockEntranceInput(){cover.unlockEntranceInput();}
 public void updateNaturalBounds(){surfaces.updateNaturalBounds();}
 public WorkbenchSlot validateSourceTask(int id,int user) throws Exception{return identity.validateSourceTask(id,user);}
 public MotionSpec.Scene arrivalScene(){return arrival.arrivalScene;}
 public WorkbenchSlot arrivalSlot(){return arrival.arrivalSlot;}
 public void cancelHandoffPresentation(){handoff.cancelHandoffPresentation();}
 public void clearInputBlock(){handoff.clearInputBlock();}
 public boolean consumeInputBlock(){return handoff.consumeInputBlock();}
 public boolean entranceInputBlocked(){return handoff.entrance.inputBlocked();}
 public void failHandoff(WorkbenchSlot expected,String reason){handoff.failHandoff(expected,reason);}
 public void failSwitchPreparation(int token,String reason){layout.failSwitchPreparation(token,reason);}
 public void finishArrival(String result){arrival.finishArrival(result);}
 public boolean finishing(){return isFinishing();}
 public void dropHangEntranceDue(){arrival.dropHangEntranceDue();}
 public boolean gestureHandoff(){return handoff.gestureHandoff;}
 public Bitmap hangFace(WorkbenchSlot s){return hang.hangFace(s);}
 public Bitmap hangSourceFrame(){return hang.hangSourceFrame;}
 public int hangSourceTask(){return hang.hangSourceTask;}
 public int hangTask(){return hang.hangTask;}
 public void markFrameReady(){handoff.markFrameReady();}
 public void maybeAnimateAddedCard(){handoff.maybeAnimateAddedCard();}
 public SwitchPreparation switchPreparation(){return layout.switchPreparation;}
 public int switchPrepareToken(){return layout.switchPrepareToken;}
 public WindowManager windowManager(){return getWindowManager();}
 public int[][] allocation(){return surfaces.allocation();}
 public void apply(WorkbenchSlot s,int[] r){surfaces.apply(s,r);}
 public void clearHangReturnGeometry(){hang.clearHangReturnGeometry();}
 public void clearHangSourceFrame(){hang.clearHangSourceFrame();}
 public int effectiveMode(){return surfaces.effectiveMode();}
 public HandoffCover entranceCover(){return cover.entranceCover;}
 public String entranceSurfaceKey(){return cover.entranceSurfaceKey();}
 public String entranceWaitReason(){return cover.entranceWaitReason;}
 public boolean hangReplace(){return hang.hangReplace;}
 public CardMotion.Pose hangReturnPose(WorkbenchSlot s){return hang.hangReturnPose(s);}
 public IdentityHashMap<WorkbenchSlot,Rect> hangReturnRects(){return hang.hangReturnRects;}
 public void layoutAddCard(int[] box){surfaces.layoutAddCard(box);}
 public ViewTreeObserver.OnPreDrawListener motionFitListener(){return surfaces.motionFitListener;}
 public void poseCard(WorkbenchSlot s,CardMotion.Pose pose,CardMotion.Pose target){surfaces.poseCard(s,pose,target);}
 public void raiseMain(){surfaces.raiseMain();}
 public void releaseEntranceCoverWhenDrawn(){cover.releaseEntranceCoverWhenDrawn();}
 public void removeMotionFitListener(){surfaces.removeMotionFitListener();}
 public void resetCardTransform(WorkbenchSlot s){surfaces.resetCardTransform(s);}
 public CardMotion.Pose rolePose(WorkbenchSlot s,float[] box,boolean preview){return surfaces.rolePose(s,box,preview);}
 public void setAnimation(ValueAnimator value){layout.animation=value;}
 public void setMotionFitListener(ViewTreeObserver.OnPreDrawListener value){surfaces.motionFitListener=value;}
 public void setSettlingInput(boolean value){layout.settlingInput=value;}
 public boolean settlingInput(){return layout.settlingInput;}
 public boolean addExistingTask(Intent request){return handoff.addExistingTask(request);}
 public boolean attachTaskEntrance(WorkbenchSlot fresh,boolean replace,boolean hangValue,Intent request) throws Exception{return handoff.attachTaskEntrance(fresh,replace,hangValue,request);}
 public boolean backgroundWorkbench(String reason){return back.backgroundWorkbench(reason);}
 public void cancelSwitchPreparation(String reason){layout.cancelSwitchPreparation(reason);}
 public void clearRecoveryCovers(){menu.clearRecoveryCovers();}
 public ContentResolver contentResolver(){return getContentResolver();}
 public void fitSurface(WorkbenchSlot s,int width,int height){surfaces.fitSurface(s,width,height);}
 public void queueExistingSwitch(WorkbenchSlot selected,long sessionValue){layout.queueExistingSwitch(selected,sessionValue);}
 public void reorderTask(int id,boolean front) throws Exception{release.reorderTask(id,front);}
 public void restoreContainerFocus(){release.restoreContainerFocus();}
 public void restorePendingTasks(){release.restorePendingTasks();}
 public void setRecovering(boolean value){menu.recovering=value;}
 public void showNativePicker(java.util.Set<String> excluded,NativeAppPicker.Selection callback){NativeAppPicker.show(this,excluded,callback);}
 public long stateToken(){return publisher.stateToken;}
 public WorkbenchSlot validate(ComponentName c,WorkbenchSlot replacing) throws Exception{return identity.validate(c,replacing);}
 public void withEntranceCover(String reason,Runnable action){cover.withEntranceCover(reason,action);}
 public Activity activity(){return this;}
 public boolean backReady(){return back.backReady();}
 public void chooseApp(WorkbenchSlot replacing){hang.chooseApp(replacing);}
 public void closeWorkbench(){back.closeWorkbench();}
 public void exitToFullscreen(){fullscreen.exitToFullscreen();}
 public void hangForReplace(){hang.hangForReplace();}
 public int layoutMode(){return layout.layoutMode;}
 public LayoutSwitchVisuals layoutSwitchVisuals(){return layout.layoutSwitchVisuals;}
 public int mainWindowPercent(){return layout.mainWindowPercent;}
 public ControlDots more(){return more;}
 public void promote(int index){layout.promote(index);}
 public void setMainWindowPercent(int value){layout.mainWindowPercent=value;}
 public TaskIdentityController.RotationRun taskRotationRun(){return identity.taskRotationRun;}
 public void toggleImeFloating(){CanvasImeBridge.toggleFloating(this);}
 public void toggleLayout(){layout.toggleLayout();}
 public Window window(){return getWindow();}
 public void abandonEntrance(WorkbenchSlot slot){handoff.abandonEntrance(slot);}
 public void abortFullscreen(FullscreenController.FullscreenRun run,String reason,boolean finishHost){fullscreen.abortFullscreen(run,reason,finishHost);}
 public Button addCard(){return addCard;}
 public void applyRotationIdentity(WorkbenchSlot slot,int taskId,ActivityManager.RunningTaskInfo snapshot,boolean reread){identity.applyRotationIdentity(slot,taskId,snapshot,reread);}
 public void applyTaskDirections(){identity.applyTaskDirections();}
 public boolean beginDrag(WorkbenchSlot s){return menu.beginDrag(s);}
 public void commitRotation(WorkbenchSlot slot,int taskId,RotationIdentity identityValue){identity.commitRotation(slot,taskId,identityValue);}
 public void ensureRotationMotion(TaskIdentityController.RotationRun run){identity.ensureRotationMotion(run);}
 public void finishDrag(WorkbenchSlot s,boolean commit){menu.finishDrag(s,commit);}
 public void finishTaskRotation(TaskIdentityController.RotationRun run,String result){identity.finishTaskRotation(run,result);}
 public FullscreenController.FullscreenRun fullscreenRun(){return fullscreen.fullscreenRun;}
 public void handleTaskRootBack(WorkbenchSlot s,int task){back.handleTaskRootBack(s,task);}
 public boolean handoffPresentation(){return handoff.handoffPresentation;}
 public Runnable immersivePoll(){return identity.immersivePoll;}
 public Intent intent(){return getIntent();}
 public void menu(WorkbenchSlot s){menu.menu(s);}
 public void onEmbeddedTaskVanished(WorkbenchSlot s,int task){release.onEmbeddedTaskVanished(s,task);}
 public int pendingHangFront(){return hang.pendingHangFront;}
 public WorkbenchSlot poseSlot(){return portrait.poseSlot;}
 public void queueTaskDirections(){identity.queueTaskDirections();}
 public void raiseHangContainer(){hang.raiseHangContainer();}
 public RotationIdentity readIdentity(ActivityManager.RunningTaskInfo task){return identity.readIdentity(task);}
 public void refreshIme(){CanvasImeBridge.refresh(this);}
 public void removeSlot(WorkbenchSlot s){menu.removeSlot(s);}
 public void removeSlot(WorkbenchSlot s,boolean taskGone){menu.removeSlot(s,taskGone);}
 public void recordResize(long elapsed){layout.recordResize(elapsed);}
 public void recordRotationSubmission(TaskIdentityController.RotationRun run){identity.recordRotationSubmission(run);}
 public int rotationSubmitGeneration(){return identity.rotationSubmitGeneration();}
 public WorkbenchSlot rotationSubmitSlot(){return identity.rotationSubmitSlot();}
 public int rotationSubmitTask(){return identity.rotationSubmitTask();}
 public RotationTransactions.Host rotationTransactions(){return identity.rotationTransactions;}
 public void setPendingHangFront(int value){hang.pendingHangFront=value;}
 public TextView status(){return status;}
 public void updateDirectionEnvironment(WorkbenchSlot slot){identity.updateDirectionEnvironment(slot);}
 public boolean waitingForSourceRelease(){return portrait.waitingForSourceRelease();}
 public boolean writeFullscreenSideHidden(WorkbenchSlot s,SurfaceControl.Transaction t){return fullscreen.writeFullscreenSideHidden(s,t);}
 public boolean writeHeldPose(WorkbenchSlot s,SurfaceControl.Transaction t){return portrait.writeHeldPose(s,t);}
 public void blockInput(){handoff.blockInput();}
 public float[] cardBox(WorkbenchSlot s){return surfaces.cardBox(s);}
 public boolean coverReleasePending(){return cover.coverReleasePending;}
 public void detachCaption(){menu.detachCaption();}
 public void fitRecoveryCover(WorkbenchSlot s,int w,int h){surfaces.fitRecoveryCover(s,w,h);}
 public int requestedOrientation(){return getRequestedOrientation();}
 public void releasePendingEntrance(WorkbenchSlot s){handoff.releasePendingEntrance(s);}
 public void resetPendingEntrance(){handoff.resetPendingEntrance();}
 public void applyCardRadius(WorkbenchSlot s,boolean isPrimary){surfaces.applyCardRadius(s,isPrimary);}
 public void clipSide(View card,int w,int h,boolean side){surfaces.clipSide(card,w,h,side);}
 public long handoffDeadline(){return handoff.handoffDeadline;}
 public String handoffLayoutKey(){return handoff.handoffLayoutKey();}
 public String handoffLayoutKey(boolean requireLive){return handoff.handoffLayoutKey(requireLive);}
 public android.os.ResultReceiver handoffReady(){return handoff.handoffReady;}
 public void motionOutline(WorkbenchSlot s){surfaces.motionOutline(s);}
 public void cancelDrag(){menu.cancelDrag();}
 public void dismissPrimaryMenu(){menu.dismissPrimaryMenu();}
 public void releaseWindows(){release.releaseWindows();}
 public void setClosing(boolean value){closing=value;}
 public void stopIme(){CanvasImeBridge.stop(this);}
 public void clearProjection(WorkbenchSlot s){surfaces.clearProjection(s);}
 public boolean detachLeashBeforeTransition(){return detachLeashBeforeTransition;}
 public Runnable recoveryFinish(){return menu.recoveryFinish;}
 public void setRecoveryFinish(Runnable value){menu.recoveryFinish=value;}
 public String taskIds(){return layout.taskIds();}
 public void dismissHang(){hang.dismissHang();}
 public int imeBottom(){return imeBottom;}
 public boolean keepSurfaceOnBackground(){return keepSurfaceOnBackground;}
 public int modalWindows(){return menu.modalWindows;}
 public PopupWindow primaryPopup(){return menu.primaryPopup;}
 public void setBackgrounded(boolean value){backgrounded=value;}
 public Object systemService(String name){return getSystemService(name);}
 public long hangStarted(){return hang.hangStarted;}
 public boolean hanging(){return hang.hanging;}
 public boolean rotatePrimaryIme(){return surfaces.rotatePrimaryIme();}
 public void showWorkbenchDialog(AlertDialog dialog){menu.showWorkbenchDialog(dialog);}
 Runnable trackPickerPopup(PopupWindow popup){return menu.trackPickerPopup(popup);}
}
