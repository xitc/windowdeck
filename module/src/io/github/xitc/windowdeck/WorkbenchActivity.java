package io.github.xitc.windowdeck;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.BackEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

public final class WorkbenchActivity extends Activity {
 private static final String TAG="WindowDeck";
 /** True while a workbench is on screen. CanvasImeBridge only pushes IME state for our container. */
 static volatile boolean foreground;
 private final ArrayList<Slot> slots=new ArrayList<>();
 private TaskOrientationObserver orientationObserver;
 private SystemRotationObserver systemRotationObserver;
 private boolean directionPosted,immersiveForeground;
 private final Runnable immersivePoll=this::pollImmersiveDirection;
 private final Runnable applyDirections=this::applyTaskDirections;
 private RotationRun taskRotationRun;
 private int rotationGeneration;
 private RotationTransactions.Host rotationTransactions;
 private int rotationSubmitTask=-1,rotationSubmitGeneration;
 private Slot rotationSubmitSlot;
 private static final class RotationRun {
  final Slot slot;final int task,axis,turn,layer,width,height;
  int generation,lastLoggedStep=-1;
  NativeTaskRotation nativeMotion;ValueAnimator animator;
  float fraction;boolean changingLayout,started,probing,commitLogged;
  RotationRun(Slot s,int next,int layer){this.slot=s;task=s.taskId;axis=next;turn=next==2?1:-1;this.layer=layer;width=s.renderBounds.width();height=s.renderBounds.height();}
 }
 private CanvasStage stage; private TextView status; private Button addCard; private ControlDots more; private PopupWindow primaryPopup; private boolean captionAttached;
 private final java.util.HashMap<Integer,Boolean> contentLight=new java.util.HashMap<>();
 private Object controlBarCallback;
 private Field interceptInput,rotateTaskLeash,cornerRadius,taskLeash,reparentAlign,superLocked; private Method resizeMethod;
 private Method leashMatrix,leashMatrix4,leashRadius; private Field viewTransaction;
 private boolean settlingInput; private int switchGeneration;
 private ViewTreeObserver.OnPreDrawListener motionFitListener;
 private final SwitchPreparation switchPreparation=new SwitchPreparation();
 private int switchPrepareToken;
 private Slot switchPrepareTarget;
 private ArrayList<Slot> switchPrepareSlots;
 private int[] switchPrepareTasks;
 private int switchPreparePrimary;
 private long switchPrepareSession;
 private int switchOriginPrepareToken;
 private long switchStarted, lastAnimationFrame, maxFrameGap, resizeNanos; private int animationFrames, resizeCalls, motionFitFailures;
 private long arrivalLastFrame,arrivalFrameGap;private int arrivalFrames,arrivalFaceGeneration;
 private Slot arrivalFaceSlot;private RecoveryCover arrivalFace;private Bitmap arrivalFaceBitmap;
 private Bitmap hangSourceFrame;private int hangSourceTask=-1,hangSourceGeneration;
 private int layoutMode=PaneLayout.LEFT_RIGHT;
 private boolean atomicRotate=AtomicPresentation.DEFAULT;
 private boolean initialized,closing,layoutPosted; private int primary,nextId,imeBottom;
 private Class<?> viewApi,managerApi; private Object manager; private ValueAnimator animation,addAnimation;
 private Slot dragging; private boolean recovering; private Runnable recoveryFinish;
 private boolean backgrounded,stopped,forwardingBack,hanging,hangSawHome;
 private final HangShelf hangShelf=new HangShelf();
 private final Runnable hangWatch=this::watchHang;
 private int hangTask=-1,hangUser=-1,pendingHangFront=-1,hangEntranceGeneration,hangStableId=-1,hangStableHits,hangConsumeTries;
 private boolean hangEntranceDue,hangQuiet,hangReplace,screenArrival;
 private Slot arrivalSlot;
 private int[][] arrivalFrom;
 private String arrivalGeometry;
 private boolean arrivalFromEdge,arrivalHungMain,arrivalOriginalPlus;
 private int layoutEvidenceGeneration;
 private MotionSpec.Scene arrivalScene=MotionSpec.Scene.ADD;
 private final Runnable hangConsume=this::ensureHangConsumed;
 private final EntranceRun arrivalRun=new EntranceRun();
 private int arrivalToken;
 private String entranceWaitReason="none";
 private final Runnable arrivalUnlock=this::checkArrivalDeadline;
 private View entranceFlying;
 private Slot entranceSlot;
 private String hangNote;
 private long hangStarted;
 private Runnable clearHangSuppress;
 private long lastExistingSession;
 private int lastExistingTask=-1;
 private int backGeneration;
 private int modalWindows;
 private boolean backStartedWithIme;
 private WorkbenchBackdrop backdrop;
 private final OnBackAnimationCallback hostBack=new OnBackAnimationCallback(){
  public void onBackStarted(BackEvent e){backStartedWithIme=embeddedImeVisible();Log.i(TAG,"back_gesture_started ime="+backStartedWithIme);}
  public void onBackProgressed(BackEvent e){}
  public void onBackCancelled(){backStartedWithIme=false;Log.i(TAG,"back_gesture_cancelled");}
  public void onBackInvoked(){handleHostBack();}
 };
 private final Rect naturalBounds=new Rect();
 private final Handler handler=new Handler(Looper.getMainLooper());
 private HandlerThread contentThread;
 private Handler contentWorker;
 private final long stateToken=SystemClock.elapsedRealtimeNanos();
 private final android.os.IBinder videoCompatHost=new android.os.Binder();
 private int statePublishGeneration;
 private static final String ADD_TO_LIVE_WORKBENCH="io.github.xitc.windowdeck.ADD_TO_LIVE_WORKBENCH";
 private static final String REVEAL_ADDED_CARD="io.github.xitc.windowdeck.REVEAL_ADDED_CARD";
 private final BroadcastReceiver addReceiver=new BroadcastReceiver(){
  @Override public void onReceive(Context context,Intent intent){
   if(intent==null)return;
   if(ADD_TO_LIVE_WORKBENCH.equals(intent.getAction())){
    Log.i(TAG,"add_broadcast_received uid="+getSentFromUid()+" task="+intent.getIntExtra("windowdeck_add_task_id",-1)+" container="+intent.getIntExtra("windowdeck_container_task_id",-1));
    handler.post(()->addExistingTask(intent));
   }else if(REVEAL_ADDED_CARD.equals(intent.getAction()))handler.post(()->revealAddedCard(intent));
  }
 };
 private boolean addReceiverRegistered;
 private boolean launcherReceiverRegistered;
 private final BroadcastReceiver launcherReceiver=new BroadcastReceiver(){
  @Override public void onReceive(Context context,Intent intent){
   // This separate receiver preserves the DUMP-protected root fallback. Never trust an
   // intent extra as caller identity; the launcher explicitly shares the system-supplied UID.
   setResultCode(2);
   String[] packages=getPackageManager().getPackagesForUid(getSentFromUid());
   boolean launcher=false;
   if(packages!=null)for(String pkg:packages)if("com.android.launcher".equals(pkg))launcher=true;
   if(intent!=null&&VideoWindowCompat.QUERY.equals(intent.getAction())){
    boolean video=false;if(packages!=null)for(String pkg:packages)if(VideoWindowPolicy.target(pkg))video=true;
    if(video&&getSentFromUid()/100000==android.os.Process.myUid()/100000&&!closing&&initialized){setResultExtras(liveState(slots.size()));setResultCode(1);}
    return;
   }
   if(!launcher||intent==null){Log.w(TAG,"direct_add_sender_denied uid="+getSentFromUid());return;}
   if(LauncherExistingAppHook.SELECT.equals(intent.getAction())){setResultCode(selectExistingFromLauncher(intent)?1:2);return;}
   if(LiveWorkbenchState.QUERY.equals(intent.getAction())){
    if(!closing&&initialized){setResultExtras(liveState(slots.size()));setResultCode(1);}
    return;
   }
   if(addExistingTask(intent)){revealAddedCard(intent);setResultCode(1);}
   // 3 tells the launcher the host answered and refused: it must raise the container
   // rather than fall through to the root path. 0 stays reserved for "no host at all".
   else setResultCode(3);
  }
 };
 private Slot pendingEntrance;
 private boolean entranceFrontReady,entranceFrameReady,entranceFramePending,createdFromGesture;
 /** Blocks the entrance until the first frame carrying the final geometry has been committed,
  *  instead of until the animation ends (TODO D3). */
 private boolean entranceInputBlocked;
 private HandoffCover entranceCover;
 private int coverWaitGeneration;
 private boolean coverReleasePending;
 private boolean keepSurfaceOnBackground=TransitionPolicy.KEEP_SURFACE_DEFAULT;
 private boolean detachLeashBeforeTransition=TransitionPolicy.DETACH_LEASH_DEFAULT;
 private boolean gestureHandoff;
 private android.os.ResultReceiver handoffReady;
 /** Landscape entrance: one portrait host, with a release snapshot bridging live reparent/rotation. */
 private boolean deferPortrait,poseHeld,poseQuerySent,portraitAfterPose;
 private int deferSourceRotation=-1;
 private float[] heldCorners,heldCrop;
 private int[] heldStage;
 private float heldRadius;
 private int heldLayer=-1;
 private boolean poseSettling,poseReported;
 private SourceRelease sourceRelease;
 private boolean sourceBootstrapFramePending;
 private ViewTreeObserver.OnPreDrawListener poseFitListener;
 private ViewTreeObserver.OnPreDrawListener poseBootstrapListener;
 private RecoveryCover poseBridge;
 private Bitmap poseBridgeBitmap;
 private float[] poseBridgeSource;
 private boolean poseBridgeFitted;
 private Slot poseSlot;
 private android.os.ResultReceiver poseReply;
 private long handoffDeadline;
 private boolean handoffPresentation;
 private int presentationGeneration;
 private android.os.ResultReceiver presentationReply;
 private String presentationLayoutKey,presentationLiveKey;
 private Bundle presentationGeometry;
 private boolean presentationLiveReady;
 private final class Slot {
  final int id; final ComponentName component; final String label; int taskId=-1,sourceTaskId=-1,sourceUserId=-1,orientationAxis; ComponentName activeComponent; final Rect renderBounds=new Rect(); boolean failed,released,pinned,windowDrawn,handoffPolling,entranceWaitScheduled,entranceDrawTimedOut,fitPosted;
  SurfaceControl.Transaction transaction; volatile boolean embedded=true; SurfaceControl projectedLeash; boolean perspectiveApplied; int leashWrites;
  final TaskSurfaceEvidence drawEvidence=new TaskSurfaceEvidence();
  final TaskOrientation taskOrientation=new TaskOrientation();
  final ImmersiveOrientation immersiveOrientation=new ImmersiveOrientation();
  int lastRequestedVisibleTypes=-1;
  long nextContentProbe; boolean contentProbeUnavailable; String drawDetail="unobserved";
  /** Last corner radius written by {@link WorkbenchActivity#transformCard}, so the outline is
   *  only invalidated when it actually changes (TODO D2). */
  int renderRadius=-1;
  float motionFromRadius=-1f;
  volatile CardMotion.Pose motionPose, fittedPose;
  int fittedTask=-1,fittedLayer=-1,fittedAxis;final Rect fittedBounds=new Rect();
  final Rect resizedBounds=new Rect(); int resizedTask=-1;
  PreviewCard card; ImageView icon; RecoveryCover recoveryCover; TextView pin; View surface;
  int pendingTask=-1; boolean quietDetach;
  Slot(ComponentName c,String l){id=nextId++;component=c;label=l;}
 }
 @Override protected void onCreate(Bundle state){
  setTheme(android.R.style.Theme_Material_NoActionBar);super.onCreate(state);
  // Default ROTATION_ANIMATION_ROTATE fades this portrait window through white.
  // The landscape cover is already up; the display should cut underneath it.
  seamlessRotation();
  // Only this custom container owns a portrait canvas. Embedded activities retain their
  // orientation requests; do not change global auto-rotate or the native canvas activity.
  // Keep the host in its final orientation. The source task keeps its landscape buffer.
  deferPortrait=state==null&&getIntent().getBooleanExtra("windowdeck_defer_portrait",false);
  if(deferPortrait){
   if(getIntent().getBooleanExtra("windowdeck_wait_source_release",false))sourceRelease=new SourceRelease();
   deferSourceRotation=getIntent().getIntExtra("windowdeck_defer_rotation",-1);
   if(deferSourceRotation!=1&&deferSourceRotation!=3){
    Log.w(TAG,"defer_source_rotation_invalid rotation="+deferSourceRotation);
    android.os.ResultReceiver reply=getIntent().getParcelableExtra("windowdeck_handoff_ready",android.os.ResultReceiver.class);
    if(reply!=null)reply.send(HandoffProtocol.FAILED,Bundle.EMPTY);
    finish();return;
   }
   // C17 creates the ContainerActivity with fixed rotation 0 before newActivity/onCreate.
   // Requesting landscape here produces an extra 0→1→0 CHANGE/snapshot transition.
   // Like its native small-window rotation layer, bridge pixels in the destination
   // window while the original task is embedded; never rotate the host to fit the task.
   setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
   Log.i(TAG,"canvas_orientation requested=portrait source_rotation="+deferSourceRotation+" activity_rotation="+(getDisplay()==null?-1:getDisplay().getRotation())+" policy=snapshot_rotation_bridge");
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
  IntentFilter launcherFilter=new IntentFilter("io.github.xitc.windowdeck.LAUNCHER_ADD_TO_WORKBENCH");launcherFilter.addAction(LiveWorkbenchState.QUERY);launcherFilter.addAction(LauncherExistingAppHook.SELECT);launcherFilter.addAction(VideoWindowCompat.QUERY);
  registerReceiver(launcherReceiver,launcherFilter,android.Manifest.permission.REORDER_TASKS,handler,Context.RECEIVER_EXPORTED);launcherReceiverRegistered=true;
  getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,hostBack);
  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0);setContentView(root);getWindow().setBackgroundDrawable(new ColorDrawable(Ui.CHROME));seamlessRotation();Ui.overlaySystemBars(this,false);
  root.setOnApplyWindowInsetsListener((v,insets)->{
   android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
   int keyboard=insets.getInsets(WindowInsets.Type.ime()).bottom;
   v.setPadding(sys.left,sys.top,sys.right,Math.max(sys.bottom,keyboard));
   if(imeBottom!=keyboard){imeBottom=keyboard;Log.i(TAG,"ime bottom="+keyboard);}
   return insets;
  });
  layoutMode=state==null?PaneLayout.LEFT_RIGHT:state.getInt("layoutMode",PaneLayout.LEFT_RIGHT);
  if(layoutMode!=PaneLayout.TOP_BOTTOM)layoutMode=PaneLayout.LEFT_RIGHT;
  atomicRotate=getIntent().getBooleanExtra(AtomicPresentation.EXTRA,AtomicPresentation.DEFAULT);
  Log.i(TAG,"atomic_rotate="+atomicRotate+" extra="+AtomicPresentation.EXTRA);
  keepSurfaceOnBackground=getIntent().getBooleanExtra(TransitionPolicy.EXTRA_KEEP_SURFACE,TransitionPolicy.KEEP_SURFACE_DEFAULT);
  detachLeashBeforeTransition=getIntent().getBooleanExtra(TransitionPolicy.EXTRA_DETACH_LEASH,TransitionPolicy.DETACH_LEASH_DEFAULT);
  Log.i(TAG,"transition_policy keep_surface="+keepSurfaceOnBackground+" detach_leash="+detachLeashBeforeTransition);
  stage=new CanvasStage(this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
  try{
   rotationTransactions=new RotationTransactions.Host(this,handler,getTaskId(),stateToken,(task,generation)->{
    RotationRun run=taskRotationRun;
    if(run!=null&&run.task==task&&run.generation==generation)finishTaskRotation(run,"submit_failed");
   });rotationTransactions.connect();
  }catch(Exception e){Log.e(TAG,"rotation_submitter_host_unavailable",e);}
  if(deferPortrait){
   // WM must see the destination snapshot on this window's first drawn frame.
   // This gate does not wait for task draw/SurfaceView readiness (which needs traversal).
   poseBootstrapListener=()->{
    if(closing||!deferPortrait||(poseBridgeFitted&&backdrop!=null&&backdrop.ready)){
     stage.getViewTreeObserver().removeOnPreDrawListener(poseBootstrapListener);poseBootstrapListener=null;return true;
    }
    return false;
   };
   stage.getViewTreeObserver().addOnPreDrawListener(poseBootstrapListener);
  }
  addCard=Ui.button(this,"＋");addCard.setTextSize(28);addCard.setTextColor(Ui.FROST_PLUS);addCard.setGravity(Gravity.CENTER);addCard.setIncludeFontPadding(false);addCard.setPadding(0,0,0,0);addCard.setContentDescription("添加应用");addCard.setBackground(Ui.frost(Ui.dp(this,Ui.SIDE_RADIUS)));Ui.round(addCard,Ui.dp(this,Ui.SIDE_RADIUS));addCard.setOnClickListener(v->{hangReplace=false;hangForAdd();});stage.addView(addCard);
  more=Ui.more(this);more.setOnClickListener(v->primaryMenu());registerControlBarLight();
  status=Ui.text(this,"正在准备窗口…",12,Ui.TEXT);status.setGravity(Gravity.CENTER);status.setBackground(Ui.bg(0x99000000,Ui.dp(this,8)));status.setPadding(Ui.dp(this,12),Ui.dp(this,6),Ui.dp(this,12),Ui.dp(this,6));status.setVisibility(View.GONE);
  FrameLayout.LayoutParams statusLp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);statusLp.bottomMargin=Ui.dp(this,16);stage.addView(status,statusLp);
  try{
   if(!"com.oplus.pscanvas".equals(getPackageName()))throw new IllegalStateException("需要从系统容器启动");
   viewApi=Class.forName("com.oplus.flexiblewindow.FlexibleTaskView");resizeMethod=viewApi.getMethod("resize",Rect.class);interceptInput=viewApi.getDeclaredField("mInterceptInputEvent");interceptInput.setAccessible(true);managerApi=Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager");manager=managerApi.getMethod("getInstance").invoke(null);
   rotateTaskLeash=viewApi.getDeclaredField("mNeedRotateTaskLeash");rotateTaskLeash.setAccessible(true);cornerRadius=viewApi.getDeclaredField("mCornerRadius");cornerRadius.setAccessible(true);
   try{taskLeash=viewApi.getDeclaredField("mTaskLeash");taskLeash.setAccessible(true);reparentAlign=viewApi.getDeclaredField("mReparentAlign");reparentAlign.setAccessible(true);}
   catch(Throwable e){Log.w(TAG,"leash_fields_unavailable",e);}
   // Read by the view's own surface-destroyed guard before it reports task
   // visibility; held down during teardown, see releaseSlot.
   try{superLocked=viewApi.getDeclaredField("mSuperLocked");superLocked.setAccessible(true);}
   catch(Throwable e){Log.w(TAG,"super_lock_unavailable",e);}
   try{
    viewTransaction=viewApi.getDeclaredField("mTransaction");viewTransaction.setAccessible(true);
    leashMatrix=SurfaceControl.Transaction.class.getDeclaredMethod("setMatrix",SurfaceControl.class,float.class,float.class,float.class,float.class);leashMatrix.setAccessible(true);
    leashMatrix4=SurfaceControl.Transaction.class.getDeclaredMethod("setTransformationMatrix4x4",SurfaceControl.class,float[].class);leashMatrix4.setAccessible(true);
    leashRadius=SurfaceControl.Transaction.class.getDeclaredMethod("setCornerRadius",SurfaceControl.class,float.class);
    Log.i(TAG,"leash_perspective_api=matrix4x4");
   }catch(Throwable e){Log.w(TAG,"leash_perspective_unavailable",e);}
   backdrop=new WorkbenchBackdrop();backdrop.onReady=this::requestEntranceFrame;backdrop.attach(this);
   String[] input=state!=null?state.getStringArray("components"):null;
   if(input==null)input=new String[]{getIntent().getStringExtra("windowdeck_app_a"),getIntent().getStringExtra("windowdeck_app_b"),getIntent().getStringExtra("windowdeck_app_c"),getIntent().getStringExtra("windowdeck_app_d"),getIntent().getStringExtra("windowdeck_app_e")};
   int initialSource=state==null?getIntent().getIntExtra("windowdeck_create_source_task",-1):-1;
   if(initialSource>=0){
    Slot fresh=validateSourceTask(initialSource,getIntent().getIntExtra("windowdeck_create_source_user",-1));
    slots.add(fresh);pendingEntrance=fresh;createdFromGesture=true;gestureHandoff=true;entranceInputBlocked=true;
    handoffReady=getIntent().getParcelableExtra("windowdeck_handoff_ready",android.os.ResultReceiver.class);
    handoffDeadline=SystemClock.uptimeMillis()+(fresh.orientationAxis==2?MotionSpec.LANDSCAPE_REVEAL_MS:MotionSpec.COVER_TIMEOUT_MS);
    watchHandoffDeadline(fresh);
    input=new String[0];
   }
   int[] savedSources=state==null?null:state.getIntArray("sourceTaskIds");
   for(String component:input)if(component!=null&&!component.isEmpty()){
    if(slots.size()>=Caps.MAX_TASKS)break;Slot restored=validate(ComponentName.unflattenFromString(component),null);
    int index=slots.size();if(savedSources!=null&&index<savedSources.length&&savedSources[index]>=0){restored.sourceTaskId=savedSources[index];restored.sourceUserId=android.os.Process.myUid()/100000;}
    slots.add(restored);
   }
   if(slots.isEmpty())throw new IllegalArgumentException("请至少选择一个应用");
   if(state!=null){String pinned=state.getString("pinned");for(Slot slot:slots)slot.pinned=slot.component.flattenToString().equals(pinned);}
   primary=state==null?0:Math.max(0,Math.min(slots.size()-1,state.getInt("primary",0)));
   publishState(slots.size());
   stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
    if(closing||r-l<100||b-t<100)return;
    if(initialized&&r-l==or-ol&&b-t==ob-ot)return;
    if(!initialized){initialized=true;updateNaturalBounds();if(!waitingForSourceRelease())for(Slot s:new ArrayList<>(slots))createWindow(s);if(!gestureHandoff&&pendingEntrance!=null&&pendingEntrance.card!=null)pendingEntrance.card.setAlpha(0f);}
    scheduleLayout();
   });
   Log.i(TAG,"workbench_created version="+Version.NAME+" container="+getTaskId()+" count="+slots.size()+" cap="+Caps.MAX_TASKS);
   if(deferPortrait)handler.post(()->requestCoverPose(pendingEntrance));
  }catch(Throwable e){fail(e);if(getIntent().hasExtra("windowdeck_create_source_task"))finish();}
 }
 private Slot validate(ComponentName c,Slot replacing) throws Exception {
  if(c==null||c.getPackageName().equals(getPackageName())||c.getPackageName().equals("io.github.xitc.windowdeck"))throw new IllegalArgumentException("应用参数无效");
  for(Slot s:slots)if(s!=replacing&&s.component.getPackageName().equals(c.getPackageName()))throw new IllegalArgumentException("该应用已在工作台中");
  ActivityInfo ai=getPackageManager().getActivityInfo(c,0);
  Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c);
  if(!Boolean.TRUE.equals(managerApi.getMethod("isAppSupportPocketStudio",Intent.class,int.class).invoke(manager,intent,-1)))throw new IllegalArgumentException(ai.loadLabel(getPackageManager())+" 不支持此窗口模式");
  Slot slot=new Slot(c,ai.loadLabel(getPackageManager()).toString());slot.activeComponent=c;slot.orientationAxis=OrientationPolicy.axis(ai.screenOrientation);Log.i(TAG,"app_orientation component="+c.flattenToShortString()+" requested="+ai.screenOrientation+" axis="+slot.orientationAxis);return slot;
 }
 private ActivityManager.RunningTaskInfo runningTask(int id) throws Exception {
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  java.util.List<?> tasks=(java.util.List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,false,0);
  for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;if(task.taskId==id)return task;}
  return null;
 }
 private Slot validateSourceTask(int id,int user) throws Exception {
   ActivityManager.RunningTaskInfo task=runningTask(id);
   int taskUser=task==null?-1:task.getClass().getField("userId").getInt(task);
   if(task==null||taskUser!=user||taskUser!=android.os.Process.myUid()/100000||task.baseActivity==null)throw new IllegalArgumentException("任务身份或用户不匹配");
   RotationIdentity identity=readIdentity(task);
   if("cross_package".equals(identity.unavailable)){Log.i(TAG,"slot=- task="+id+" "+identity.record());throw new IllegalArgumentException("任务身份或用户不匹配");}
   if(((Integer)task.getClass().getMethod("getWindowingMode").invoke(task))!=1)throw new IllegalArgumentException("仅接收全屏任务");
   String pkg=task.baseActivity.getPackageName();
   Intent launcher=getPackageManager().getLaunchIntentForPackage(pkg);
   if(launcher==null||launcher.getComponent()==null)throw new IllegalArgumentException("任务应用没有桌面入口");
   if(!Boolean.TRUE.equals(managerApi.getMethod("isAppSupportPocketStudio",Intent.class,int.class).invoke(manager,launcher,id)))throw new IllegalArgumentException("任务不支持嵌入");
   Slot fresh=validate(launcher.getComponent(),null);fresh.sourceTaskId=id;fresh.sourceUserId=user;
   ResolvedRotation resolved=resolveRotation(identity);
   if(resolved.identity.rotationClass==null){fresh.activeComponent=null;fresh.orientationAxis=0;}
   else{
    ComponentName chosen=ComponentName.unflattenFromString(resolved.identity.rotationClass);
    if(chosen!=null)fresh.activeComponent=chosen;
    fresh.orientationAxis=resolved.manifestAxis;
   }
   if(resolved.identity.unavailable!=null)Log.i(TAG,"slot="+fresh.id+" task="+id+" "+resolved.identity.record());
   return fresh;
 }
 private void onTaskOrientationRequested(int task,int requested){
  if(closing)return;
  for(Slot s:slots)if(!s.released&&s.taskId==task){
   applyRotationIdentity(s,task,null,true);
   if(s.taskOrientation.request(task,requested)){
    Log.i(TAG,"task_orientation_requested slot="+s.id+" task="+task+" requested="+requested+" axis="+s.taskOrientation.axis()+" "+s.taskOrientation.record());
    applyTaskDirections();
   }
   break;
  }
 }
 /** Manifest axis of the chosen class. ActivityInfo.targetActivity is not followed. */
 private ResolvedRotation resolveRotation(RotationIdentity identity){
  if(identity.rotationClass==null)return new ResolvedRotation(identity,0);
  ComponentName component=ComponentName.unflattenFromString(identity.rotationClass);
  if(component==null)return new ResolvedRotation(identity.nameNotFound(),0);
  try{return new ResolvedRotation(identity,OrientationPolicy.axis(getPackageManager().getActivityInfo(component,0).screenOrientation));}
  catch(PackageManager.NameNotFoundException ignored){return new ResolvedRotation(identity.nameNotFound(),0);}
 }
 private void applyRotationIdentity(Slot slot,int taskId,ActivityManager.RunningTaskInfo snapshot,boolean reread){
  if(slot==null||taskId<0||(slot.taskId>=0&&slot.taskId!=taskId))return;
  RotationIdentity identity=reread?readTaskIdentity(taskId,snapshot):snapshot==null?null:readIdentity(snapshot);
  if(identity!=null)commitRotation(slot,taskId,identity);
 }
 private void commitRotation(Slot slot,int taskId,RotationIdentity identity){
  int manifestAxis=0;
  if(identity.rotationClass!=null){ResolvedRotation resolved=resolveRotation(identity);identity=resolved.identity;manifestAxis=resolved.manifestAxis;}
  if(!slot.taskOrientation.needsRebind(taskId,identity))return;
  slot.taskOrientation.bind(taskId,identity,manifestAxis);
  slot.immersiveOrientation.bind(taskId,identity.rotationClass,"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility()));
  slot.activeComponent=identity.rotationClass==null?null:ComponentName.unflattenFromString(identity.rotationClass);
  Log.i(TAG,"slot="+slot.id+" task="+taskId+" "+identity.record());
 }
 private RotationIdentity readTaskIdentity(int taskId,ActivityManager.RunningTaskInfo fallback){
  try{ActivityManager.RunningTaskInfo fresh=runningTask(taskId);if(fresh!=null)return readIdentity(fresh);}
  catch(Throwable e){Log.w(TAG,"rotation_activity_read_failed task="+taskId,e);}
  if(fallback!=null&&fallback.taskId==taskId)return readIdentity(fallback);
  return null;
 }
 private RotationIdentity readIdentity(ActivityManager.RunningTaskInfo task){
  if(task==null)return RotationIdentity.choose(null,null,null,null,null);
  String base=task.baseActivity==null?null:task.baseActivity.getPackageName();
  return RotationIdentity.choose(flatten(task.topActivity),flatten(task.origActivity),flatten(componentField(task,"realActivity")),activityInfoComponent(task),base);
 }
 private static String flatten(ComponentName component){return component==null?null:component.flattenToString();}
 private static ComponentName componentField(Object task,String name){
  try{Object value=task.getClass().getField(name).get(task);return value instanceof ComponentName?(ComponentName)value:null;}
  catch(Throwable ignored){return null;}
 }
 private static String activityInfoComponent(Object task){
  try{
   Object info=task.getClass().getField("topActivityInfo").get(task);
   if(info==null)return null;
   String pkg=(String)info.getClass().getField("packageName").get(info);
   String name=(String)info.getClass().getField("name").get(info);
   if(pkg==null||pkg.isEmpty()||name==null||name.isEmpty())return null;
   if(name.startsWith("."))name=pkg+name;
   return pkg+"/"+name;
  }catch(Throwable ignored){return null;}
 }
 private static final class ResolvedRotation {
  final RotationIdentity identity; final int manifestAxis;
  ResolvedRotation(RotationIdentity identity,int manifestAxis){this.identity=identity;this.manifestAxis=manifestAxis;}
 }
 private void updateDirectionEnvironment(Slot slot){
  SystemRotationObserver observer=systemRotationObserver;
  slot.taskOrientation.environment(observer==null?-1:observer.proposed,observer!=null&&observer.settingsKnown,observer!=null&&observer.locked,observer==null?-1:observer.user);
 }
 private void pollImmersiveDirection(){
  handler.removeCallbacks(immersivePoll);
  if(closing||stopped||backgrounded||!immersiveForeground||!initialized)return;
  boolean eligible=false;
  for(Slot slot:slots)if(!slot.released&&slot.taskId>=0&&"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility()))eligible=true;
  if(!eligible)return;
  try{
   Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
   java.util.List<?> tasks=(java.util.List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,false,0);
   for(Object value:tasks){ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)value;
    for(Slot slot:slots)if(!slot.released&&slot.taskId==info.taskId&&"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility())){
     applyRotationIdentity(slot,info.taskId,info,false);
     String identity=slot.taskOrientation.rotationClass();
     if(!"youtube_dynamic_compat".equals(slot.taskOrientation.eligibility())||identity==null){
      if(slot.immersiveOrientation.clear()){slot.taskOrientation.immersive(false);queueTaskDirections();}
      continue;
     }
     int types=info.getClass().getField("requestedVisibleTypes").getInt(info);
     if(slot.lastRequestedVisibleTypes!=types){slot.lastRequestedVisibleTypes=types;Log.i(TAG,"youtube_system_bars task="+info.taskId+" component="+identity+" requested_visible_types="+types);}
     if(slot.immersiveOrientation.observe(info.taskId,identity,types,SystemClock.uptimeMillis())){
      slot.taskOrientation.immersive(slot.immersiveOrientation.landscape());
      Log.i(TAG,"youtube_immersive task="+info.taskId+" component="+identity+" requested_visible_types="+types+" landscape="+slot.immersiveOrientation.landscape());
      queueTaskDirections();
     }
    }
   }
  }catch(Throwable e){
   for(Slot slot:slots)if(slot.immersiveOrientation.clear())slot.taskOrientation.immersive(false);
   queueTaskDirections();Log.w(TAG,"youtube_immersive_probe failed",e);return;
  }
  handler.postDelayed(immersivePoll,350);
 }
 private void queueTaskDirections(){
  if(closing||stage==null||directionPosted)return;
  directionPosted=true;handler.post(applyDirections);
 }
 private void applyTaskDirections(){
  handler.removeCallbacks(applyDirections);directionPosted=false;if(closing||stopped||backgrounded||!initialized)return;
  // Keep the latest request while a transition owns geometry and input.
  if(taskRotationRun!=null||pendingEntrance!=null||screenArrival||poseHeld||recovering||switching()||addAnimation!=null||dragging!=null){
   directionPosted=true;handler.postDelayed(applyDirections,50);return;
  }
  boolean changed=false;
  for(Slot s:slots)if(!s.released){updateDirectionEnvironment(s);int next=s.taskOrientation.axis();if(s.orientationAxis!=next){if(beginTaskRotation(s,next))return;s.orientationAxis=next;s.fittedPose=null;changed=true;}}
  if(changed)scheduleLayout();else scheduleLayoutEvidence("task_orientation");
 }
 /** Own the task transform until the final native transaction and host frame commit.
  * ColorOS's curve is reused in this portrait host; this is not a display rotation. */
 private boolean beginTaskRotation(Slot s,int axis){
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  int visual=OrientationPolicy.committedAxis(s.orientationAxis,s.renderBounds.width(),s.renderBounds.height(),dm.widthPixels,dm.heightPixels);
  // Axis 0 is still an unknown request. The committed plate only supplies the start pose.
  if((visual!=1&&visual!=2)||(axis!=1&&axis!=2)||visual==axis||s.surface==null||!s.embedded)return false;
  CardMotion.Pose from=currentPose(s);if(from==null)return false;
  try{
   SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface);
   int layer=LiveTaskContent.layerId(leash);if(layer<0)return false;
   int plateW=s.surface.getWidth(),plateH=s.surface.getHeight();if(plateW<2||plateH<2)return false;
   if(rotationTransactions==null||!rotationTransactions.available()){
    Log.w(TAG,"task_rotation_unavailable task="+s.taskId+" reason=permitted_submitter_missing");return false;
   }
   RotationRun run=new RotationRun(s,axis,layer);
   run.generation=++rotationGeneration;
   taskRotationRun=run;
   for(int i=0;i<slots.size();i++)inputRole(slots.get(i),i==primary);
   Log.i(TAG,"task_rotation_prepare task="+run.task+" from_axis="+s.orientationAxis+" visual_axis="+visual+" to_axis="+axis+" layer="+layer+" generation="+run.generation);
   if(contentThread==null){contentThread=new HandlerThread("WindowDeckLiveContent");contentThread.start();contentWorker=new Handler(contentThread.getLooper());}
   // The app may have reconfigured before this callback. Capturing now and
   // showing that frame later would cover the new player with an old page.
   // Until a true pre-change frame is available, only animate the live enter.
   applyRotationLayout(run);
   handler.postDelayed(()->{if(taskRotationRun==run&&!run.started)finishTaskRotation(run,"prepare_timeout");},RotationMotion.TIMEOUT_MS);
   return true;
  }catch(Throwable e){if(taskRotationRun!=null)finishTaskRotation(taskRotationRun,"prepare_failed");Log.w(TAG,"task_rotation_prepare_failed",e);return false;}
 }
 private boolean rotationCurrent(RotationRun run){
  if(taskRotationRun!=run||closing||stopped||backgrounded||stage==null||!stage.isAttachedToWindow()||run.slot.released||run.slot.surface==null||run.slot.taskId!=run.task||!slots.contains(run.slot))return false;
  try{SurfaceView view=(SurfaceView)run.slot.surface;return view.getHolder().getSurface().isValid()&&LiveTaskContent.layerId(view.getSurfaceControl())>=0&&run.layer==LiveTaskContent.layerId((SurfaceControl)taskLeash.get(view));}catch(Throwable e){return false;}
 }
 private void applyRotationLayout(RotationRun run){
  if(!rotationCurrent(run)||run.changingLayout)return;
  run.changingLayout=true;run.slot.orientationAxis=run.axis;run.slot.fittedPose=null;
  // A native reparent may be intercepted before the queued layout traversal.
  // Its first fit must already sample the animation in the NEW buffer space.
  updateRenderBounds(run.slot);
  scheduleLayout();stage.postOnAnimation(()->startTaskRotation(run));
 }
 private void ensureRotationMotion(RotationRun run){
  int w=run.slot.renderBounds.width(),h=run.slot.renderBounds.height();
  if(run.nativeMotion==null||run.nativeMotion.width!=w||run.nativeMotion.height!=h)
   run.nativeMotion=new NativeTaskRotation(this,run.turn,w,h,run.width,run.height);
 }
 private void startTaskRotation(RotationRun run){
  if(!rotationCurrent(run)){if(taskRotationRun==run)finishTaskRotation(run,"invalidated");return;}
  if(layoutPosted||stage.isLayoutRequested()||run.slot.surface.isLayoutRequested()){stage.postOnAnimation(()->startTaskRotation(run));return;}
  Slot s=run.slot;
  ensureRotationMotion(run);
  long duration=run.nativeMotion.duration;
  ValueAnimator animator=ValueAnimator.ofFloat(0,1);run.animator=animator;run.started=true;animator.setDuration(duration);
  // The resource AnimationSet owns each child's curve. This clock stays linear.
  animator.setInterpolator(new android.view.animation.LinearInterpolator());
  // ValueAnimator already applies the global animator scale. Do not scale twice
  // or let the watchdog cut a deliberately slow developer animation short.
  long timeout=Math.max(RotationMotion.TIMEOUT_MS,Math.round(duration*ValueAnimator.getDurationScale())+1000);
  handler.postDelayed(()->{if(taskRotationRun==run)finishTaskRotation(run,"timeout");},timeout);
  animator.addUpdateListener(a->{
   if(!rotationCurrent(run)){finishTaskRotation(run,"invalidated");return;}
   run.fraction=(Float)a.getAnimatedValue();
   try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){if(!writeLeashFit(s,t)){finishTaskRotation(run,"fit_failed");return;}submitMotionFit(t);stage.invalidate();}
   catch(Throwable e){Log.w(TAG,"task_rotation_frame_failed",e);finishTaskRotation(run,"fit_failed");}
  });
  animator.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){if(taskRotationRun==run&&run.animator==a){run.fraction=1;commitTaskRotation(run);}}});
  Log.i(TAG,"task_rotation_start task="+run.task+" generation="+run.generation+" duration_ms="+duration+" animation="+run.nativeMotion.source+" exit=none reason=no_prechange_frame");animator.start();
 }
 private void commitTaskRotation(RotationRun run){
  if(!rotationCurrent(run)){if(taskRotationRun==run)finishTaskRotation(run,"invalidated");return;}
  if(!run.commitLogged){run.commitLogged=true;Log.i(TAG,"task_rotation_commit task="+run.task+" generation="+run.generation+" render="+run.slot.renderBounds+" actual="+taskBuffer(run.slot));}
  Slot s=run.slot;
  if(!run.probing){
   // The new task configuration and child pixels must exist; target geometry alone
   // does not establish a rendered video frame.
   if(!s.renderBounds.equals(taskBuffer(s))){stage.postOnAnimation(()->commitTaskRotation(run));return;}
   final SurfaceControl copy;
   try{copy=(SurfaceControl)de.robv.android.xposed.XposedHelpers.newInstance(SurfaceControl.class,taskLeash.get(s.surface),"WindowDeckRotationReady");}
   catch(Throwable e){finishTaskRotation(run,"probe_failed");return;}
   run.probing=true;
   final int width=s.renderBounds.width(),height=s.renderBounds.height();
   contentWorker.post(()->{
    boolean ready=false;try{ready=LiveTaskContent.presented(copy,width,height);}catch(Throwable e){Log.w(TAG,"task_rotation_probe_failed",e);}finally{copy.release();}
    final boolean shown=ready;handler.post(()->{
     if(!rotationCurrent(run)){if(taskRotationRun==run)finishTaskRotation(run,"invalidated");return;}
     run.probing=false;if(!shown){handler.postDelayed(()->commitTaskRotation(run),50);return;}
     try(SurfaceControl.Transaction t=new SurfaceControl.Transaction()){
      if(!writeLeashFit(s,t)){finishTaskRotation(run,"fit_failed");return;}
      t.addTransactionCommittedListener(this::runOnUiThread,()->{
       if(!rotationCurrent(run))return;
       stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{if(rotationCurrent(run))finishTaskRotation(run,"completed");}));stage.invalidate();
      });submitMotionFit(t);stage.invalidate();
     }catch(Throwable e){Log.w(TAG,"task_rotation_commit_failed",e);finishTaskRotation(run,"commit_failed");}
    });
   });
  }
 }
 private void finishTaskRotation(RotationRun run,String result){
  if(taskRotationRun!=run)return;taskRotationRun=null;
  if(rotationGeneration==run.generation)rotationGeneration++;
  ValueAnimator old=run.animator;run.animator=null;if(old!=null)old.cancel();
  if(!closing&&!stopped&&!backgrounded&&!run.slot.released&&run.slot.surface!=null)syncSurface(run.slot);
  for(int i=0;i<slots.size();i++)if(slots.get(i).surface!=null&&slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
  Log.i(TAG,"task_rotation_result task="+run.task+" generation="+run.generation+" fraction="+run.fraction+" result="+result);
  if(!closing&&!stopped&&!backgrounded)queueTaskDirections();
 }
 /** WindowConfiguration is hidden from the compile SDK. Unknown is never inferred from the rect. */
 private static int taskRotation(ActivityManager.RunningTaskInfo info){
  try{Object config=info.getClass().getMethod("getConfiguration").invoke(info);Object window=config.getClass().getField("windowConfiguration").get(config);return (Integer)window.getClass().getMethod("getRotation").invoke(window);}
  catch(Throwable ignored){return -1;}
 }
 private boolean addExistingTask(Intent request){
  int id=request.getIntExtra("windowdeck_add_task_id",-1),user=request.getIntExtra("windowdeck_add_user_id",-1);
  int expectedContainer=request.getIntExtra("windowdeck_container_task_id",-1);
  if((pendingEntrance!=null&&pendingEntrance.taskId>=0)||addAnimation!=null){Log.i(TAG,"add_entrance_preempted slot="+(pendingEntrance==null?-1:pendingEntrance.id));if(addAnimation!=null){ValueAnimator old=addAnimation;addAnimation=null;old.cancel();}releaseEntrance();}
  boolean replace=request.getBooleanExtra("windowdeck_hang_replace",false);
  if(id<0||user<0||expectedContainer!=getTaskId()||closing||recovering||!initialized||pendingEntrance!=null||addAnimation!=null||(!replace&&slots.size()>=Caps.MAX_TASKS)){Log.w(TAG,"add_task_rejected id="+id+" user="+user+" container="+expectedContainer+" closing="+closing+" recovering="+recovering+" initialized="+initialized+" entrance="+(pendingEntrance!=null)+" anim="+(addAnimation!=null)+" count="+slots.size()+" replace="+replace);return false;}
  try{
   Slot fresh=validateSourceTask(id,user);
   cancelAnimation();
   boolean hang=request.getBooleanExtra("windowdeck_hang_place",false);
   Slot replaced=null;
   final boolean originalPlus;
   if(replace){
    replaced=slots.get(primary);if(replaced.pinned)throw new IllegalArgumentException("请先取消固定");
    originalPlus=false;primary=HangPlace.place(slots,primary,fresh,true,false,false);
   }else if(hang&&!slots.isEmpty()){
    originalPlus=readOriginalPlus();primary=HangPlace.place(slots,primary,fresh,false,true,originalPlus);
   }else{originalPlus=false;primary=HangPlace.place(slots,primary,fresh,false,false,false);}
   pendingEntrance=fresh;entranceFrontReady=false;entranceFrameReady=false;entranceInputBlocked=true;gestureHandoff=!hang&&!replace;
   handoffReady=gestureHandoff?request.getParcelableExtra("windowdeck_handoff_ready",android.os.ResultReceiver.class):null;
   handoffDeadline=SystemClock.uptimeMillis()+2000;
   if(gestureHandoff)watchHandoffDeadline(fresh);
   publishState(slots.size());updateNaturalBounds();createWindow(fresh);layoutCards(false);refreshStatus();
   if(replaced!=null){replaced.quietDetach=true;releaseSlot(replaced);}
   if(fresh.card!=null)fresh.card.setAlpha(1f);
   boolean edge=(hang||replace)&&slots.size()>1,hungMain=hang&&!replace;
   // Launcher already owns the gesture's moving task. Do not restart it from fullscreen
   // inside the host. Shelf add/replace retains its separate, user-triggered transition.
   if(!gestureHandoff&&!poseScreenArrival(fresh,edge,hungMain,originalPlus)){Slot arriving=fresh;stage.post(()->poseScreenArrival(arriving,edge,hungMain,originalPlus));}
   Log.i(TAG,"add_existing_requested slot="+fresh.id+" task="+id+" user="+user+" container="+getTaskId()+" replace="+replace+" original_plus="+originalPlus);
   return true;
  }catch(Throwable e){Log.w(TAG,"add_existing_failed task="+id,e);if(!hangQuiet)Toast.makeText(this,"未能加入："+(e.getMessage()==null?"原应用保持不变":e.getMessage()),Toast.LENGTH_LONG).show();return false;}
 }
 private boolean readOriginalPlus(){
  try{
   Bundle answer=getContentResolver().call(android.net.Uri.parse("content://io.github.xitc.windowdeck.state"),HangPlace.PREF,null,null);
   boolean value=answer!=null&&answer.getBoolean(HangPlace.PREF,HangPlace.DEFAULT);
   Log.i(TAG,"original_plus="+value);return value;
  }catch(Throwable e){Log.w(TAG,"original_plus_unavailable",e);return false;}
 }
 private void revealAddedCard(Intent request){
  Slot slot=pendingEntrance;
  if(slot==null||request.getIntExtra("windowdeck_container_task_id",-1)!=getTaskId()||request.getIntExtra("windowdeck_add_task_id",-1)!=slot.sourceTaskId)return;
  entranceFrontReady=true;Log.i(TAG,"add_card_front_ready slot="+slot.id+" task="+slot.sourceTaskId);
  requestEntranceFrame();
 }
private void abandonEntrance(Slot slot){
  if(pendingEntrance!=slot)return;
  if(gestureHandoff){failHandoff(slot,"task_unavailable");return;}
  pendingEntrance=null;entranceFrameReady=false;entranceFramePending=false;createdFromGesture=false;
  entranceInputBlocked=false;closeEntranceCover("entrance_abandoned");
  if(slot.card!=null)slot.card.setAlpha(1f);
  if(!closing)layoutCards(false);
}
private void watchHandoffDeadline(Slot expected){
 final long deadline=handoffDeadline;
 handler.postDelayed(()->{
  if(pendingEntrance==expected&&gestureHandoff&&handoffDeadline==deadline)failHandoff(expected,"ready_timeout");
 },Math.max(0L,deadline-SystemClock.uptimeMillis()));
}
private void seamlessRotation(){
 WindowManager.LayoutParams attrs=getWindow().getAttributes();
 attrs.rotationAnimation=WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
 getWindow().setAttributes(attrs);
}
private void failHandoff(Slot expected,String reason){
 if(sourceRelease!=null)sourceRelease.cancel();
 if(deferPortrait){clearHeldPose();deferPortrait=false;setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);}
 if(pendingEntrance!=expected||!gestureHandoff)return;
 Log.w(TAG,"handoff_result result="+reason+" task="+expected.sourceTaskId);
 android.os.ResultReceiver reply=handoffReady;handoffReady=null;gestureHandoff=false;
 abandonEntrance(expected);cancelHandoffPresentation();if(reply!=null)reply.send(HandoffProtocol.FAILED,Bundle.EMPTY);
}
private void requestEntranceFrame(){
  if(deferPortrait)return; // The live-pose path owns layout until rotation/final fit commit.
  sendHandoffBackground();
  if(pendingEntrance==null||!entranceFrontReady||stopped||backgrounded||closing||stage.getWidth()==0||entranceFramePending)return;
  // A measured single landscape target can start the opaque snapshot before live
  // content and wallpaper finish. The launcher only accepts a hole-free expansion.
  if(gestureHandoff&&slots.size()==1&&pendingEntrance.orientationAxis==2&&pendingEntrance.taskId==pendingEntrance.sourceTaskId&&handoffReady!=null
    &&handoffGeometryKey()!=null)beginHandoffPresentation(pendingEntrance,false);
  // Only the window having *something* to draw is required. The wallpaper underlay is loaded on
  // a background thread, and waiting for it held the whole entrance behind an async task
  // (TODO A1-4) — the gap the entrance cover exists to hide is exactly that wait.
  if(gestureHandoff&&(pendingEntrance.taskId<0||!pendingEntrance.windowDrawn||(backdrop!=null&&!backdrop.ready))){
   // The independent source cover stays visible while the target backdrop settles.
   // A placeholder is safe for normal host startup, but must match the final wallpaper handoff.
   stage.postOnAnimation(this::requestEntranceFrame);return;
  }
  Slot expected=pendingEntrance;entranceFramePending=true;
  if(gestureHandoff){commitHandoffLayout(expected);return;}
  stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   if(pendingEntrance!=expected)return;
   if(gestureHandoff){commitHandoffLayout(expected);return;}
   entranceFramePending=false;
   if(pendingEntrance!=expected||stopped||backgrounded||closing)return;
   entranceFrameReady=true;unlockEntranceInput();maybeAnimateAddedCard();
  }));stage.invalidate();
}
 // A drawn new task alone says nothing about the old main's SurfaceView. Wait
 // for every card to reach its final role and size, commit all leash fits together,
 // then require a host frame with the same geometry before releasing the cover.
 private String handoffGeometryKey(){return handoffLayoutKey(false);}
 private String handoffLayoutKey(){return handoffLayoutKey(true);}
 private String handoffLayoutKey(boolean requireLive){
  if(layoutPosted||stage.isLayoutRequested()||animation!=null||addAnimation!=null||stage.getWidth()>=stage.getHeight()||getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return null;
  int[][] target=cardGeometry().cards;
  if(target.length!=slots.size())return null;
  int[] origin=new int[2];stage.getLocationOnScreen(origin);
  StringBuilder key=new StringBuilder().append(Arrays.toString(origin)).append(':').append(getDisplay().getRotation()).append(':').append(cardRadius(true)).append(':').append(primary).append(':').append(stage.getWidth()).append('x').append(stage.getHeight())
   .append(':').append(requireLive&&backdrop!=null?backdrop.visualGeneration():0);
  for(int i=0;i<slots.size();i++){
   Slot s=slots.get(i);int[] r=target[i];
   if(s.released||s.taskId<0||s.card==null||s.surface==null||s.card.isLayoutRequested()||s.surface.isLayoutRequested()
    ||s.card.getLeft()!=r[0]||s.card.getTop()!=r[1]||s.card.getWidth()!=r[2]||s.card.getHeight()!=r[3])return null;
   int[] plate=surfacePlate(s);
   if(s.surface.getWidth()!=plate[0]||s.surface.getHeight()!=plate[1])return null;
   key.append('|').append(s.id).append(':').append(s.taskId).append(':').append(Arrays.toString(r))
    .append(':').append(s.orientationAxis).append(':').append(s.renderBounds).append(':').append(Arrays.toString(plate));
   if(!requireLive)continue;
   try{
    SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface),surface=((SurfaceView)s.surface).getSurfaceControl();
    int state=taskDrawState(s);
    if(!TaskSurfaceEvidence.ready(state)||leash==null||!leash.isValid()||surface==null||!surface.isValid()
      ||!((SurfaceView)s.surface).getHolder().getSurface().isValid())return null;
    key.append(':').append(LiveTaskContent.layerId(leash)).append(':').append(LiveTaskContent.layerId(surface));
   }catch(Throwable e){return null;}
  }
  return key.toString();
 }
 private void retryHandoffLayout(Slot expected){
  if(pendingEntrance!=expected)return;
  entranceFramePending=false;entranceFrameReady=false;
  if(SystemClock.uptimeMillis()>=handoffDeadline){
   failHandoff(expected,"layout_timeout");return;
  }
  stage.postOnAnimation(this::requestEntranceFrame);
 }
 private void commitHandoffLayout(Slot expected){
  if(stopped||backgrounded||closing){entranceFramePending=false;return;}
  String key=handoffLayoutKey();
  if(key==null){retryHandoffLayout(expected);return;}
  // L1: measured geometry can travel under the opaque snapshot while the final fit/frame
  // barrier completes. Multi-card/hot-add retains the existing stable-layout path.
  if(slots.size()==1&&expected.orientationAxis==2&&handoffReady!=null)
   beginHandoffPresentation(expected,false);
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(Slot s:slots)if(!writeLeashFit(s,fit))throw new IllegalStateException("Unfitted slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(pendingEntrance!=expected)return;
    if(stopped||backgrounded||closing){entranceFramePending=false;return;}
    stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(pendingEntrance!=expected)return;
     if(stopped||backgrounded||closing){entranceFramePending=false;return;}
     if(!key.equals(handoffLayoutKey())||(backdrop!=null&&!backdrop.ready)){retryHandoffLayout(expected);return;}
     // Submission is earlier than presentation. Keep the launcher's independent cover until
     // the same layout and surface identities survive two additional display frames.
     stage.postOnAnimation(()->stage.postOnAnimation(()->{
      if(pendingEntrance!=expected)return;
      if(stopped||backgrounded||closing){entranceFramePending=false;return;}
      if(!key.equals(handoffLayoutKey())){retryHandoffLayout(expected);return;}
      // Keep the already-committed input role until the independent motion finishes.
      // Unlocking here requests layout, making beginHandoffPresentation capture a null key.
      entranceFramePending=false;entranceFrameReady=true;
      Log.i(TAG,"handoff_layout_committed task="+expected.taskId+" cards="+slots.size()+" axis="+expected.orientationAxis);
      maybeAnimateAddedCard();
     }));
    }));stage.invalidate();
   });fit.apply();
  }catch(Throwable e){Log.w(TAG,"handoff_layout_commit_failed",e);retryHandoffLayout(expected);}
 }
 private void maybeAnimateAddedCard(){
  Slot slot=pendingEntrance;
  if(deferPortrait){if(slot!=null)requestCoverPose(slot);return;}
  if(screenArrival&&slot!=null&&entranceFrontReady&&!stopped&&!backgrounded&&!closing){pendingEntrance=null;createdFromGesture=false;entranceFrontReady=false;beginHangEntrance();return;}
  if(slot==null||!entranceFrontReady||stopped||backgrounded||closing||slot.released||slot.taskId<0)return;
  if(gestureHandoff&&!slot.windowDrawn){pollHandoffDraw(slot);return;}
  if(!entranceFrameReady){requestEntranceFrame();return;}
  if(!slot.windowDrawn&&!slot.entranceDrawTimedOut){
   if(!slot.entranceWaitScheduled){slot.entranceWaitScheduled=true;handler.postDelayed(()->{if(pendingEntrance==slot){slot.entranceDrawTimedOut=true;maybeAnimateAddedCard();}},120);}
   return;
  }
  pendingEntrance=null;createdFromGesture=false;
  if(gestureHandoff){
   gestureHandoff=false;entranceFrontReady=false;entranceFrameReady=false;
   if(slot.card!=null)slot.card.setAlpha(1f);
   // layoutCards already established the z-order before commitHandoffLayout. Repeating
   // bringToFront here requests a new ViewGroup layout even when the order is unchanged,
   // invalidating the stable geometry immediately before the presentation handshake.
   layoutCaption();
   for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
   focusPrimary("gesture_handoff");
   Log.i(TAG,"gesture_handoff_ready task="+slot.taskId+" host_animation=false");
   if(handoffReady!=null||handoffPresentation)beginHandoffPresentation(slot,true);
   else{unlockEntranceInput();focusPrimary("gesture_without_presentation");}
  }else animateAddedCard(slot);
 }
 private void pollHandoffDraw(Slot slot){
  if(slot.handoffPolling)return;
  slot.handoffPolling=true;
  final long deadline=handoffDeadline;
  Runnable check=new Runnable(){public void run(){
   if(closing||slot.released||pendingEntrance!=slot||!gestureHandoff){slot.handoffPolling=false;return;}
   if(slot.windowDrawn){slot.handoffPolling=false;return;}
   try{
    int state=taskDrawState(slot);
    SurfaceControl leash=(SurfaceControl)taskLeash.get(slot.surface);
    SurfaceControl plate=((SurfaceView)slot.surface).getSurfaceControl();
    boolean surface=leash!=null&&leash.isValid()&&plate!=null&&plate.isValid();
    // Landscape pixels decide. drawState 0 is a cold embed, not a presented frame.
    if(slot.orientationAxis==2){
     if(!surface){
      if(SystemClock.uptimeMillis()<deadline){stage.postOnAnimation(this);return;}
      slot.handoffPolling=false;failHandoff(slot,"draw_state_timeout");return;
     }
     if(!slot.drawEvidence.hasContent()){
      probeLandscapePresented(slot,leash);
      if(SystemClock.uptimeMillis()<deadline){stage.postOnAnimation(this);return;}
      Log.i(TAG,"handoff_black_held task="+slot.taskId);
      slot.handoffPolling=false;failHandoff(slot,"presented_timeout");return;
     }
    }else if(!TaskSurfaceEvidence.ready(state)||!surface){
     if(SystemClock.uptimeMillis()<deadline){stage.postOnAnimation(this);return;}
     slot.handoffPolling=false;failHandoff(slot,"draw_state_timeout");return;
    }
    syncSurface(slot);
    try(SurfaceControl.Transaction committed=new SurfaceControl.Transaction()){
     writeLeashFit(slot,committed);
     committed.addTransactionCommittedListener((Executor)handler::post,()->{
      if(closing||pendingEntrance!=slot||slot.released)return;
      if(slot.orientationAxis==2&&!slot.drawEvidence.hasContent()){slot.handoffPolling=false;stage.postOnAnimation(()->pollHandoffDraw(slot));return;}
      slot.windowDrawn=true;slot.handoffPolling=false;
      Log.i(TAG,"handoff_task_surface_committed task="+slot.taskId+" drawState="+state);
      requestEntranceFrame();
     });committed.apply();
    }
   }catch(Throwable e){Log.w(TAG,"handoff_draw_state_unavailable",e);slot.handoffPolling=false;failHandoff(slot,"draw_state_unavailable");}
  }};
  stage.postOnAnimation(check);
 }
private void beginHandoffPresentation(Slot slot,boolean liveReady){
 if(handoffPresentation){
  if(!liveReady)return;
  if(presentationGeometry==null||presentationGeometry.getInt(HandoffProtocol.TASK,-1)!=slot.sourceTaskId
    ||!Objects.equals(presentationLayoutKey,handoffGeometryKey())||handoffLayoutKey()==null||stopped||backgrounded||closing){
   cancelHandoffPresentation();return;
  }
  presentationLiveKey=handoffLayoutKey();
  presentationLiveReady=true;
  if(!putHandoffBackdrop(presentationGeometry)){cancelHandoffPresentation();return;}
  presentationReply.send(HandoffProtocol.READY,presentationGeometry);
  Log.i(TAG,"handoff_live_ready task="+slot.taskId+" overlapped=true");return;
 }
 final int generation=++presentationGeneration;
 final String key=handoffGeometryKey();
 if(key==null||(liveReady&&handoffLayoutKey()==null))return;
 presentationLayoutKey=key;presentationLiveReady=liveReady;presentationLiveKey=liveReady?handoffLayoutKey():null;
 handoffPresentation=true;presentationReply=handoffReady;handoffReady=null;
 android.os.ResultReceiver done=new android.os.ResultReceiver(handler){
  @Override protected void onReceiveResult(int code,Bundle data){
   if(!handoffPresentation||generation!=presentationGeneration)return;
   String finalKey=handoffLayoutKey();
   boolean valid=code==HandoffProtocol.READY&&presentationLiveReady&&!slot.released&&!closing&&!stopped&&!backgrounded&&key.equals(handoffGeometryKey())&&Objects.equals(presentationLiveKey,finalKey)&&finalKey!=null;
   handoffPresentation=false;presentationReply=null;presentationGeometry=null;presentationLayoutKey=null;presentationLiveKey=null;presentationLiveReady=false;
   if(pendingEntrance==slot&&gestureHandoff){gestureHandoff=false;abandonEntrance(slot);}
   entranceInputBlocked=false;
   Log.i(TAG,"handoff_presentation_result result="+(valid?"completed":"degraded")+" code="+code+" task="+slot.taskId+" stable_start="+(key!=null)+" stable_end="+(finalKey!=null)+" stable_same="+valid);
   for(int i=0;i<slots.size();i++)inputRole(slots.get(i),i==primary);
   if(!closing&&!stopped&&!backgrounded)focusPrimary("handoff_presentation");
   scheduleLayoutEvidence("handoff");
  }
 };
 Rect target=screenRect(slot.card);
 Bundle data=new Bundle();data.putInt(HandoffProtocol.TASK,slot.sourceTaskId);data.putInt(HandoffProtocol.CONTAINER,getTaskId());
 data.putInt(HandoffProtocol.ROTATION,getDisplay().getRotation());
 data.putIntArray(HandoffProtocol.TARGET,new int[]{target.left,target.top,target.right,target.bottom});
 data.putFloat(HandoffProtocol.RADIUS,cardRadius(true));data.putParcelable(HandoffProtocol.COMPLETE,HandoffProtocol.transport(done));
 if(!putHandoffBackdrop(data)){cancelHandoffPresentation();return;}
 presentationGeometry=data;
 presentationReply.send(liveReady?HandoffProtocol.READY:HandoffProtocol.PREPARED,data);
 Log.i(TAG,"handoff_geometry_prepared task="+slot.taskId+" live_ready="+liveReady+" backdrop_ready="+(backdrop==null||backdrop.ready));
 handler.postDelayed(()->{if(handoffPresentation&&generation==presentationGeneration){
  Log.w(TAG,"handoff_presentation_timeout task="+slot.taskId);cancelHandoffPresentation();
 }},handoffHoldMs());
}
private long handoffHoldMs(){
 if(handoffDeadline<=0)return MotionSpec.COVER_TIMEOUT_MS;
 return Math.max(MotionSpec.ADD_CARD_MS,handoffDeadline-SystemClock.uptimeMillis());
}
private boolean putHandoffBackdrop(Bundle data){
 try{if(backdrop!=null&&backdrop.ready&&backdrop.currentBitmap()!=null){
  Bitmap background=backdrop.currentBitmap().copy(Bitmap.Config.HARDWARE,false);
  if(background!=null){data.putParcelable(HandoffProtocol.BACKDROP,background);handler.postDelayed(background::recycle,handoffHoldMs());}
 }return true;}catch(Throwable e){Log.w(TAG,"handoff_backdrop_transport_failed",e);return false;}
}
/** Wallpaper loading can finish before child pixels. It must not unlock live content. */
private void sendHandoffBackground(){
 if(!handoffPresentation||presentationReply==null||presentationGeometry==null||backdrop==null||!backdrop.ready
   ||presentationGeometry.containsKey(HandoffProtocol.BACKDROP)||presentationGeometry.getBoolean("background_sent")||stopped||backgrounded||closing)return;
 if(!Objects.equals(presentationLayoutKey,handoffGeometryKey()))return;
 if(!putHandoffBackdrop(presentationGeometry)){cancelHandoffPresentation();return;}
 // A settled fallback has no bitmap; mark it too so polling does not resend it.
 presentationGeometry.putBoolean("background_sent",true);
 presentationReply.send(HandoffProtocol.BACKGROUND,new Bundle(presentationGeometry));
 Log.i(TAG,"handoff_background_prepared task="+presentationGeometry.getInt(HandoffProtocol.TASK));
}
private void cancelHandoffPresentation(){
 if(!handoffPresentation)return;
 handoffPresentation=false;presentationGeneration++;entranceInputBlocked=false;
 android.os.ResultReceiver reply=presentationReply;presentationReply=null;
 presentationGeometry=null;presentationLayoutKey=null;presentationLiveKey=null;presentationLiveReady=false;
 if(reply!=null)reply.send(HandoffProtocol.CANCELLED,Bundle.EMPTY);
 for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
 Log.i(TAG,"handoff_presentation_result result=cancelled");
}
private void releaseEntrance(){
  cancelHandoffPresentation();
  if(pendingEntrance!=null&&gestureHandoff)failHandoff(pendingEntrance,"preempted");
  hangEntranceGeneration++;hangEntranceDue=false;entranceFrontReady=false;entranceFrameReady=false;entranceFramePending=false;
  entranceInputBlocked=false;closeEntranceCover("entrance_released");
  Slot shown=entranceSlot!=null?entranceSlot:pendingEntrance;pendingEntrance=null;entranceSlot=null;
  if(entranceFlying!=null){if(entranceFlying.getParent()==stage)stage.removeView(entranceFlying);entranceFlying=null;}
  if(shown!=null&&shown.card!=null)shown.card.setAlpha(1f);
  for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
}
/** Drops the entrance input block as soon as the first frame with the final geometry is
 *  committed, so a card becomes tappable ~500 ms earlier than it used to (TODO D3). */
private void unlockEntranceInput(){
  if(!entranceInputBlocked)return;
  entranceInputBlocked=false;
  for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
  Log.i(TAG,"entrance_input_unlocked slots="+slots.size());
}
/** Raises the full-screen buffer for a container raise.
 *
 *  <p>Created at raise time on purpose. The hang flow hands the screen to the launcher so the
 *  user can pick an app; a cover spanning the whole hang would black the launcher out. The gap
 *  that needs covering is the container surface rebuild after {@code backgroundWorkbench()}
 *  destroyed it — see {@code docs/启动动画对比与改进.md} §1.1.1. */
private void raiseEntranceCover(String reason){
  if(entranceCover!=null&&entranceCover.isClosed())entranceCover=null;
  if(entranceCover!=null||closing||isFinishing()||stage==null)return;
  Bitmap source="hang_front".equals(reason)&&hangSourceTask==hangTask?hangSourceFrame:null;
  entranceCover=HandoffCover.create(this,reason,this::snapshotWorkbench,source);
  entranceCover.onClosed(why->{
   if(screenArrival&&("watchdog".equals(why)||"commit_timeout".equals(why)))finishArrival("cover_"+why);
   if(switchPreparation.pending()&&("watchdog".equals(why)||"commit_timeout".equals(why)))failSwitchPreparation(switchPrepareToken,"cover_"+why);
  });
}
/** All container raises wait for the independent cover, including retries and restore. */
private void withEntranceCover(String reason,Runnable action){
  if(closing||isFinishing())return;
  if(stopped||backgrounded)raiseEntranceCover(reason);
  HandoffCover cover=entranceCover;
  Runnable guarded=()->{if(!closing&&!isFinishing())action.run();};
  if(cover==null)guarded.run();else cover.dispatchWhenDrawn(guarded);
}
private void closeEntranceCover(String why){
  coverWaitGeneration++;coverReleasePending=false;
  HandoffCover cover=entranceCover;if(cover==null)return;
  entranceCover=null;cover.close(why);
}
/** Closes the cover on the first frame the workbench actually commits after the raise. That is
 *  the moment the rebuilt surface is on screen, so the hand-over is invisible. A watchdog keeps
 *  a never-drawing window from leaving the buffer up (TODO A1). */
private void releaseEntranceCoverWhenDrawn(){
  if(entranceCover==null)return;
  if(stage==null||stage.getWidth()==0){closeEntranceCover("no_stage");return;}
  if(coverReleasePending)return;
  coverReleasePending=true;
  final int generation=++coverWaitGeneration;
  awaitCoveredSurfaces(generation);
}
/** A host frame can contain only a SurfaceView hole. Require live children and a committed
 *  fit transaction before asking for the host frame that will replace the independent cover. */
private void awaitCoveredSurfaces(int generation){
  if(generation!=coverWaitGeneration||entranceCover==null)return;
  if(entranceCover.isClosed()){closeEntranceCover("cover_expired");return;}
  String key=entranceSurfaceKey();
  if(key==null){handler.postDelayed(()->awaitCoveredSurfaces(generation),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(Slot s:slots)if(!writeLeashFit(s,fit))throw new IllegalStateException("Unfitted entrance slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->commitCoveredFrame(generation,key));
   fit.apply();
  }catch(Throwable e){Log.w(TAG,"entrance_surface_commit_failed",e);handler.postDelayed(()->awaitCoveredSurfaces(generation),MotionSpec.SURFACE_RETRY_MS);}
}
private String entranceSurfaceKey(){
  if(closing||stopped||backgrounded||stage==null||layoutPosted||stage.isLayoutRequested()||slots.isEmpty()||stage.getWidth()>=stage.getHeight()||getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT){
   entranceWaitReason="host closing="+closing+" stopped="+stopped+" backgrounded="+backgrounded+" layout="+layoutPosted;
   return null;
  }
  StringBuilder key=new StringBuilder().append(primary).append(':').append(stage.getWidth()).append('x').append(stage.getHeight());
  for(Slot s:slots){
   if(s.released||s.failed||s.taskId<0||s.surface==null||s.card==null||s.card.isLayoutRequested()||s.surface.isLayoutRequested()){
    entranceWaitReason="slot="+s.id+" task="+s.taskId+" released="+s.released+" failed="+s.failed+" layout_pending";return null;
   }
   try{
    SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface),plate=((SurfaceView)s.surface).getSurfaceControl();
    int state=taskDrawState(s);
    if(!TaskSurfaceEvidence.ready(state)||leash==null||!leash.isValid()||plate==null||!plate.isValid()
     ||!((SurfaceView)s.surface).getHolder().getSurface().isValid()){
     entranceWaitReason="slot="+s.id+" task="+s.taskId+" draw="+state+" "+s.drawDetail+" leash="+(leash!=null&&leash.isValid())+" plate="+(plate!=null&&plate.isValid())+" holder="+((SurfaceView)s.surface).getHolder().getSurface().isValid();return null;
    }
    key.append('|').append(s.id).append(':').append(s.taskId).append(':').append(LiveTaskContent.layerId(leash))
     .append(':').append(LiveTaskContent.layerId(plate)).append(':').append(s.surface.getWidth()).append('x').append(s.surface.getHeight())
     .append(':').append(s.orientationAxis).append(':').append(s.renderBounds);
   }catch(Throwable e){entranceWaitReason="slot="+s.id+" task="+s.taskId+" exception="+e.getClass().getSimpleName();return null;}
  }
  entranceWaitReason="ready";
  return key.toString();
}
/** C17 only publishes hasDrawnWindow in certain canvas transitions. An absent key is unknown;
 * for that case sample the current task's child layers, not a cached task snapshot. */
private int taskDrawState(Slot s) throws Exception {
  Field draw=viewApi.getDeclaredField("mDrawState");draw.setAccessible(true);int cached=draw.getInt(s.surface);
  Field info=viewApi.getDeclaredField("mTaskInfo");info.setAccessible(true);Object task=info.get(s.surface);
  int actual=task==null?-1:task.getClass().getField("taskId").getInt(task);
  boolean hasState=false;int bundleState=-1;
  if(task!=null){
   Object value=task.getClass().getField("mOplusExtraBundle").get(task);
   Field key=viewApi.getDeclaredField("HAS_DRAWN_WINDOW");key.setAccessible(true);String name=(String)key.get(null);
   if(value instanceof Bundle){hasState=((Bundle)value).containsKey(name);bundleState=((Bundle)value).getInt(name,-1);}
  }
  s.drawDetail="cached="+cached+" info_task="+actual+" bundle_has_draw="+hasState+" bundle_draw="+bundleState;
  if(actual!=s.taskId||!s.embedded||!((SurfaceView)s.surface).getHolder().getSurface().isValid()) {s.drawEvidence.invalidate();return -1;}
  SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface),plate=((SurfaceView)s.surface).getSurfaceControl();
  if(!s.drawEvidence.bind(actual,LiveTaskContent.layerId(leash),LiveTaskContent.layerId(plate),s.surface.getWidth(),s.surface.getHeight(),s.renderBounds.width(),s.renderBounds.height()))return -1;
  int state=s.drawEvidence.resolve(cached,bundleState,hasState);
  if(state<0)probeTaskContent(s,leash);
  return state;
}
private void probeTaskContent(Slot s,SurfaceControl leash){
 if(closing||stopped||backgrounded||s.released||s.contentProbeUnavailable||SystemClock.uptimeMillis()<s.nextContentProbe)return;
 int token=s.drawEvidence.beginProbe();if(token<0)return;
 final SurfaceControl captured;
 try{captured=(SurfaceControl)de.robv.android.xposed.XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckContentProbe");}
 catch(Throwable e){s.drawEvidence.completeProbe(token,false);s.contentProbeUnavailable=true;Log.w(TAG,"task_content_probe_copy_failed",e);return;}
 if(contentThread==null){contentThread=new HandlerThread("WindowDeckLiveContent");contentThread.start();contentWorker=new Handler(contentThread.getLooper());}
 final int task=s.taskId,width=s.renderBounds.width(),height=s.renderBounds.height();
 s.nextContentProbe=SystemClock.uptimeMillis()+80;
 contentWorker.post(()->{
  boolean content=false;Throwable failure=null;
  try{content=s.orientationAxis==2?LiveTaskContent.presented(captured,width,height):LiveTaskContent.sample(captured,width,height);}catch(Throwable e){failure=e;}finally{captured.release();}
  final boolean ready=content;final Throwable error=failure;
  handler.post(()->{
   if(closing||stopped||backgrounded||s.released||s.taskId!=task)return;
   try{
    // Rebind first: a replacement layer/plate invalidates an in-flight sample.
    taskDrawState(s);
    if(!s.drawEvidence.completeProbe(token,ready))return;
    if(error!=null){s.contentProbeUnavailable=true;Log.w(TAG,"task_content_probe_unavailable slot="+s.id+" task="+task,error);}
    else if(ready){Log.i(TAG,"task_content_ready slot="+s.id+" task="+task+" evidence=live_child_layers "+s.drawDetail);maybeAnimateAddedCard();}
   }catch(Throwable e){s.drawEvidence.invalidate();Log.w(TAG,"task_content_probe_stale slot="+s.id,e);}
  });
 });
}
/** Draw state 2 can be a black buffer after the game has left the release frame. Hold the snapshot until a non-black child frame, then let the poll commit it. */
private void probeLandscapePresented(Slot s,SurfaceControl leash){
 if(closing||stopped||backgrounded||s.released||s.contentProbeUnavailable||SystemClock.uptimeMillis()<s.nextContentProbe)return;
 if(s.renderBounds.width()<2||s.renderBounds.height()<2)return;
 int token=s.drawEvidence.beginProbe();if(token<0)return;
 final SurfaceControl captured;
 try{captured=(SurfaceControl)de.robv.android.xposed.XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckContentProbe");}
 catch(Throwable e){s.drawEvidence.completeProbe(token,false);s.contentProbeUnavailable=true;Log.w(TAG,"handoff_presented_copy_failed",e);return;}
 if(contentThread==null){contentThread=new HandlerThread("WindowDeckLiveContent");contentThread.start();contentWorker=new Handler(contentThread.getLooper());}
 final int task=s.taskId,width=s.renderBounds.width(),height=s.renderBounds.height();
 s.nextContentProbe=SystemClock.uptimeMillis()+80;
 contentWorker.post(()->{
  boolean shown=false;Throwable failure=null;
  try{shown=LiveTaskContent.presented(captured,width,height);}catch(Throwable e){failure=e;}finally{captured.release();}
  final boolean ready=shown;final Throwable error=failure;
  handler.post(()->{
   if(closing||stopped||backgrounded||s.released||s.taskId!=task)return;
   try{
    taskDrawState(s);
    if(!s.drawEvidence.completeProbe(token,ready))return;
    if(error!=null){s.contentProbeUnavailable=true;Log.w(TAG,"handoff_presented_unavailable slot="+s.id+" task="+task,error);}
    else if(ready){Log.i(TAG,"handoff_frame_presented task="+task);maybeAnimateAddedCard();}
   }catch(Throwable e){s.drawEvidence.invalidate();Log.w(TAG,"handoff_presented_stale slot="+s.id,e);}
  });
 });
}
private void commitCoveredFrame(int generation,String key){
  if(generation!=coverWaitGeneration||entranceCover==null)return;
  if(!key.equals(entranceSurfaceKey())){awaitCoveredSurfaces(generation);return;}
  stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   if(generation!=coverWaitGeneration||entranceCover==null)return;
   // Two animation frames on top of the commit, the same margin releaseSlot() uses before it
   // uncovers resumed tasks. A commit is a submission; the compositor presents it a frame later,
   // and closing on the commit itself could expose one frame of the still-hidden launcher.
   stage.postOnAnimation(()->stage.postOnAnimation(()->{
    if(generation!=coverWaitGeneration||entranceCover==null)return;
    if(!key.equals(entranceSurfaceKey())){awaitCoveredSurfaces(generation);return;}
    closeEntranceCover("frame_committed");unlockEntranceInput();
   }));
  }));
  stage.invalidate();
  // HandoffCover owns the absolute watchdog; retries never extend its deadline.
}
/** Fallback face for the cover when the privileged display capture is refused in this process:
 *  the wallpaper plus every card's own task snapshot, i.e. the workbench as it was last drawn.
 *  "最差也是上一次画面" instead of pure black (TODO A1-2). */
private Bitmap snapshotWorkbench(){
  try{
   int width=stage==null?0:stage.getWidth(),height=stage==null?0:stage.getHeight();
   if(width<2||height<2){Log.w(TAG,"entrance_cover_snapshot_skipped size="+width+"x"+height);return null;}
   Rect display=getWindowManager().getMaximumWindowMetrics().getBounds();
   int displayW=Math.min(display.width(),display.height()),displayH=Math.max(display.width(),display.height());
   int[] origin=new int[2];stage.getLocationOnScreen(origin);
   if(!DisplayGeometry.fitsContent(width,height,origin[0],origin[1],displayW,displayH)){
    Log.w(TAG,"entrance_cover_snapshot_skipped reason=unstable_stage");return null;
   }
   Bitmap out=Bitmap.createBitmap(displayW,displayH,Bitmap.Config.ARGB_8888);
   Canvas canvas=new Canvas(out);canvas.drawColor(Ui.CHROME);
   Bitmap wallpaper=backdrop==null?null:backdrop.currentBitmap();
   if(wallpaper!=null&&!wallpaper.isRecycled()){
    Rect dst=new Rect(0,0,displayW,displayH);
    canvas.drawBitmap(wallpaper,new Rect(0,0,wallpaper.getWidth(),wallpaper.getHeight()),dst,null);
   }
   canvas.translate(origin[0],origin[1]);
   for(Slot s:slots){
    if(s.card==null||s.released)continue;
    int left=Math.round(s.card.getLeft()+s.card.getTranslationX());
    int top=Math.round(s.card.getTop()+s.card.getTranslationY());
    int w=Math.round(s.card.getWidth()*s.card.getScaleX()),h=Math.round(s.card.getHeight()*s.card.getScaleY());
    if(w<=0||h<=0)continue;
    Rect dst=new Rect(left,top,left+w,top+h);
    Bitmap face=s.surface==null?null:hangFace(s);
    if(face!=null&&!face.isRecycled())canvas.drawBitmap(face,new Rect(0,0,face.getWidth(),face.getHeight()),dst,null);
    else canvas.drawRoundRect(new RectF(dst),Ui.dp(this,Ui.MAIN_RADIUS),Ui.dp(this,Ui.MAIN_RADIUS),cardFill());
   }
   Log.i(TAG,"entrance_cover_snapshot slots="+slots.size()+" size="+displayW+"x"+displayH+" stage="+width+"x"+height+" origin="+Arrays.toString(origin));
   return out;
  }catch(Throwable e){Log.w(TAG,"entrance_cover_snapshot_failed",e);return null;}
 }
private android.graphics.Paint cardFill(){
  android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
  paint.setColor(Ui.CARD);return paint;
}
 private void beginHangEntrance(){
  if(!hangEntranceDue||closing||!screenArrival||stopped||backgrounded||animation!=null)return;
  hangEntranceDue=false;playScreenArrival();
 }
 private boolean poseScreenArrival(Slot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus){
  return poseScreenArrival(fresh,fromEdge,hungMain,originalPlus,MotionSpec.ARRIVAL_UNLOCK_MS);
 }
 private boolean poseScreenArrival(Slot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus,long waitMs){
  if(fresh==null||fresh.card==null||stage.getWidth()==0||slots.isEmpty())return false;
  fresh.card.setAlpha(1f);
  int[] loc=new int[2];stage.getLocationOnScreen(loc);
  Rect screen=getWindowManager().getCurrentWindowMetrics().getBounds();
  int[][] target=cardGeometry().cards;if(target.length!=slots.size())return false;
  java.util.HashMap<Slot,Rect> edge=fromEdge?edgeRects(fresh,target,loc,screen,hungMain,originalPlus):new java.util.HashMap<>();
  int[][] from=new int[slots.size()][4];
  int shelf=HangPlace.shelfMain(slots.size(),primary,hungMain,originalPlus);
  for(int i=0;i<slots.size();i++){
   Slot s=slots.get(i);Rect placed=edge.get(s);
   if(s==fresh)from[i]=new int[]{-loc[0],-loc[1],Math.max(1,screen.width()),Math.max(1,screen.height())};
   else if(placed!=null)from[i]=new int[]{placed.left-loc[0],placed.top-loc[1],Math.max(1,placed.width()),Math.max(1,placed.height())};
   else from[i]=new int[]{target[i][0],target[i][1],target[i][2],target[i][3]};
   boolean mainRadius=shelf>=0?i==shelf:i==primary;
   s.motionFromRadius=s==fresh?0f:Ui.dp(this,mainRadius?Ui.MAIN_RADIUS:Ui.SIDE_RADIUS);
   apply(s,target[i]);transformCard(s,from[i],target[i],0f);
  }
  raiseMain();arrivalSlot=fresh;arrivalFrom=from;screenArrival=true;hangEntranceDue=true;entranceInputBlocked=true;
  arrivalGeometry=arrivalGeometryKey();arrivalFromEdge=fromEdge;arrivalHungMain=hungMain;arrivalOriginalPlus=originalPlus;
  arrivalScene=hangReplace?MotionSpec.Scene.REPLACE:MotionSpec.Scene.ADD;
  arrivalToken=arrivalRun.begin(SystemClock.uptimeMillis(),waitMs);
  handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,waitMs);
  if(!stopped&&!backgrounded)handler.post(this::beginHangEntrance);
  Log.i(TAG,"arrive_posed token="+arrivalToken+" task="+fresh.sourceTaskId+" edge="+fromEdge+" original_plus="+originalPlus+" screen="+screen.width()+"x"+screen.height());
  return true;
}
 private String arrivalGeometryKey(){
  StringBuilder key=new StringBuilder().append(stage.getWidth()).append('x').append(stage.getHeight()).append(':').append(effectiveMode());
  for(Slot s:slots)key.append('|').append(s.id).append(':').append(s.orientationAxis).append(':').append(s.renderBounds);
  return key.toString();
 }
 private void rebaseArrivalLayout(){
  if(!screenArrival||Objects.equals(arrivalGeometry,arrivalGeometryKey()))return;
  if(arrivalRun.phase()!=EntranceRun.Phase.WAITING){finishArrival("cancelled_geometry_changed");layoutCards(false);return;}
  long remaining=arrivalRun.remaining(SystemClock.uptimeMillis());
  if(remaining==0){checkArrivalDeadline();layoutCards(false);return;}
  Slot fresh=arrivalSlot;boolean edge=arrivalFromEdge,hung=arrivalHungMain,plus=arrivalOriginalPlus;
  if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}
  clearArrivalFace("orientation_rebase");arrivalRun.cancel();
  Log.i(TAG,"arrive_layout_rebased task="+(fresh==null?-1:fresh.taskId)+" old_token="+arrivalToken+" remaining_ms="+remaining);
  if(!poseScreenArrival(fresh,edge,hung,plus,remaining)){finishArrival("cancelled_geometry_unavailable");layoutCards(false);}
 }
 private java.util.HashMap<Slot,Rect> edgeRects(Slot fresh,int[][] target,int[] loc,Rect screen,boolean hungMain,boolean originalPlus){
  java.util.HashMap<Slot,Rect> map=new java.util.HashMap<>();
  if(slots.size()<2)return map;
  ArrayList<Slot> order=new ArrayList<>();ArrayList<HangShelf.Card> cards=new ArrayList<>();
  Slot previous=null;
  if(hungMain){
   int shelf=HangPlace.shelfMain(slots.size(),primary,true,originalPlus);
   previous=shelf<0?null:slots.get(shelf);
   if(!originalPlus&&previous==fresh&&slots.size()>2)previous=slots.get(slots.size()-2);
   if(previous!=null&&previous!=fresh){order.add(previous);cards.add(new HangShelf.Card(null,previous.component.getPackageName(),placed(previous,target,loc),true));}
  }
  for(Slot s:slots)if(s!=fresh&&s!=previous){order.add(s);cards.add(new HangShelf.Card(null,s.component.getPackageName(),placed(s,target,loc),false));}
  if(cards.isEmpty())return map;
  Rect[] ends=HangShelf.ends(cards,screen,effectiveMode()==PaneLayout.TOP_BOTTOM,screen.height()>screen.width(),Ui.dp(this,20),Ui.dp(this,48));
  for(int i=0;i<order.size()&&i<ends.length;i++)map.put(order.get(i),ends[i]);
  return map;
 }
 private Rect placed(Slot s,int[][] target,int[] loc){int i=slots.indexOf(s);int[] r=target[Math.max(0,i)];return new Rect(r[0]+loc[0],r[1]+loc[1],r[0]+loc[0]+r[2],r[1]+loc[1]+r[3]);}
 private void playScreenArrival(){
  if(!screenArrival||arrivalFrom==null||animation!=null||closing||arrivalRun.phase()!=EntranceRun.Phase.WAITING)return;
  // A fullscreen landscape launch can still be returning the display to our portrait
  // container. Wait for its actual canvas instead of animating against the old display.
  if(stage.getWidth()>=stage.getHeight()||getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT){handler.postDelayed(this::playScreenArrival,MotionSpec.SURFACE_RETRY_MS);return;}
  // The entrance flag swallows every card touch. Drop it before the spring so the end state can receive input.
  pendingEntrance=null;entranceFrontReady=false;entranceFrameReady=false;entranceFramePending=false;createdFromGesture=false;
  final int[][] from=arrivalFrom;final int[][] target=cardGeometry().cards;
  final ArrayList<Slot> current=new ArrayList<>(slots);
  final int token=arrivalToken;
  // Scene timing is explicit: ordinary add 500 ms, replace 300 ms, and only a
  // MULTI_WINDOW scene uses vivo's fixed 600 ms timeline with position delays.
  // Apply the curve after local time conversion, never re-time an already-curved value.
  final MotionSpec.Scene scene=arrivalScene;
  final long total=MotionSpec.duration(scene);
  final int arrivalPrimary=primary;
  final boolean[] moving=new boolean[current.size()];
  final boolean[] faceAttempted={false};
  animation=ValueAnimator.ofFloat(0f,1f);animation.setDuration(total);animation.setInterpolator(null);
  for(int i=0;i<current.size();i++)inputRole(current.get(i),i==primary);
  animation.addUpdateListener(a->{float raw=(Float)a.getAnimatedValue();
   long now=SystemClock.uptimeMillis();if(arrivalLastFrame>0)arrivalFrameGap=Math.max(arrivalFrameGap,now-arrivalLastFrame);arrivalLastFrame=now;arrivalFrames++;
   for(int i=0;i<current.size()&&i<from.length&&i<target.length;i++)if(current.get(i).card!=null){
    int position=MotionSpec.entrancePosition(i,arrivalPrimary);
    float local=MotionSpec.entranceFraction(raw,scene,position);
    if(local>0f&&!moving[i]){moving[i]=true;Log.i(TAG,"arrive_card_start slot="+current.get(i).id+" position="+position+" primary="+(i==arrivalPrimary)+" delay_ms="+MotionSpec.delayFor(scene,position)+" elapsed_ms="+Math.round(raw*total));}
    transformCard(current.get(i),from[i],target[i],scene==MotionSpec.Scene.MULTI_WINDOW?MotionSpec.geometry(local):EntranceMotion.fraction(local,0));
   }});
  animation.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){
   Log.i(TAG,"arrive_animation_end token="+token+" current="+(animation==a)+" phase="+arrivalRun.phase());
   if(animation!=a)return;
   if(!arrivalRun.settle(token,SystemClock.uptimeMillis(),MotionSpec.ARRIVAL_UNLOCK_MS)){checkArrivalDeadline();return;}
   animation=null;entranceInputBlocked=true;stage.watch("arrival_"+token);
   for(int i=0;i<slots.size();i++){resetCardTransform(slots.get(i));inputRole(slots.get(i),i==primary);}
   handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,arrivalRun.remaining(SystemClock.uptimeMillis()));
   Log.i(TAG,"arrive_settling token="+token+" task="+(arrivalSlot==null?-1:arrivalSlot.taskId));
   settleArrivalFrame(token,true);
  }});
  final ValueAnimator expected=animation;
  final boolean[] started={false};
  final int[] waits={0};
  Runnable start=()->{
   if(started[0]||animation!=expected||!screenArrival||closing||stopped||backgrounded)return;
   if(entranceSurfaceKey()==null)return;
   if(!arrivalRun.start(token,SystemClock.uptimeMillis(),total+MotionSpec.FRAME_TIMEOUT_MS)){checkArrivalDeadline();return;}
   handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,arrivalRun.remaining(SystemClock.uptimeMillis()));
   started[0]=true;arrivalLastFrame=arrivalFrameGap=0;arrivalFrames=0;unlockEntranceInput();expected.start();releaseEntranceCoverWhenDrawn();
   Log.i(TAG,"arrive_animation_start token="+token+" slots="+current.size()+" scene="+scene+" duration_ms="+total+" curve="+(scene==MotionSpec.Scene.MULTI_WINDOW?"coui_geometry":"c17_rapid_path"));
  };
  Runnable prepare=new Runnable(){public void run(){
   if(started[0]||animation!=expected||!screenArrival||closing||!arrivalRun.waiting(token))return;
   if(arrivalRun.remaining(SystemClock.uptimeMillis())==0){checkArrivalDeadline();return;}
   String key=entranceSurfaceKey();
   if(key==null){
    if(++waits[0]==30)for(Slot s:current)try{Log.w(TAG,"arrival_surface_wait slot="+s.id+" task="+s.taskId+" draw="+taskDrawState(s)+" "+s.drawDetail+" stage_layout="+stage.isLayoutRequested()+" card_layout="+s.card.isLayoutRequested()+" surface_layout="+s.surface.isLayoutRequested()+" holder_valid="+((SurfaceView)s.surface).getHolder().getSurface().isValid());}catch(Throwable e){Log.w(TAG,"arrival_surface_wait_failed",e);}
    handler.postDelayed(this,MotionSpec.SURFACE_RETRY_MS);return;
   }
   if(!faceAttempted[0]){faceAttempted[0]=true;if(prepareArrivalFace(arrivalSlot,this)){return;}}
   try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
    for(Slot s:current)if(!writeLeashFit(s,fit))throw new IllegalStateException("Unfitted arrival slot "+s.id);
    fit.addTransactionCommittedListener((Executor)handler::post,()->{
     if(animation!=expected||started[0])return;
     stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
      if(key.equals(entranceSurfaceKey()))start.run();
      if(!started[0])handler.post(this);
     }));stage.invalidate();
    });fit.apply();
   }catch(Throwable e){Log.w(TAG,"arrival_surface_commit_failed",e);handler.postDelayed(this,MotionSpec.SURFACE_RETRY_MS);}
  }};
  handler.post(prepare);
  Log.i(TAG,"arrive_waiting_surfaces slots="+current.size());
}
 private void checkArrivalDeadline(){
  EntranceRun.Phase phase=arrivalRun.phase();
  if(phase!=EntranceRun.Phase.WAITING&&phase!=EntranceRun.Phase.RUNNING&&phase!=EntranceRun.Phase.SETTLING)return;
  long remaining=arrivalRun.remaining(SystemClock.uptimeMillis());
  if(remaining>0){handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,remaining+1);return;}
  if(arrivalRun.timeout(arrivalToken,SystemClock.uptimeMillis())){
   Log.w(TAG,"arrive_timeout phase="+phase+" token="+arrivalToken+" waiting_for="+entranceWaitReason+" "+stage.trace());
   finishArrival("timeout_"+phase);
  }
 }
 private void finishArrival(String result){
  if(!"completed".equals(result))arrivalRun.cancel();
  Log.i(TAG,"arrive_result result="+result+" token="+arrivalToken+" task="+(arrivalSlot==null?-1:arrivalSlot.taskId));
  Log.i(TAG,"arrive_motion_frames callbacks="+arrivalFrames+" max_gap_ms="+arrivalFrameGap+" owner=ui_animator");
  stage.stopWatching();
  handler.removeCallbacks(arrivalUnlock);
  if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}
  boolean locked=screenArrival||pendingEntrance!=null||settlingInput;
  screenArrival=false;hangEntranceDue=false;arrivalSlot=null;arrivalFrom=null;
  pendingEntrance=null;entranceFrontReady=false;entranceFrameReady=false;entranceFramePending=false;createdFromGesture=false;settlingInput=false;
  entranceInputBlocked=false;closeEntranceCover("arrival_"+result);
  for(Slot s:slots)if(s.card!=null)resetCardTransform(s);
  for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
  try{Method method=viewApi.getMethod("setNoNeedStartEmbedded",boolean.class);for(Slot s:slots)if(s.surface!=null)method.invoke(s.surface,false);}catch(Throwable ignored){}
  layoutCaption();
  clearArrivalFace("completed".equals(result)?"frame_committed":"cancelled");
  scheduleLayoutEvidence("arrival_"+result);
  if(locked)Log.i(TAG,"arrive_unlocked");
 }
 private boolean prepareArrivalFace(Slot slot,Runnable resume){
  clearArrivalFace("superseded");
  if(slot==null||slot.released||slot.taskId<0||slot.surface==null)return false;
  try{
   if(hangSourceTask==slot.taskId&&hangSourceFrame!=null&&!hangSourceFrame.isRecycled()){
    Bitmap bitmap=hangSourceFrame;hangSourceFrame=null;hangSourceTask=-1;
    attachArrivalFace(slot,bitmap,"pre_reparent_task",0);handler.post(resume);return true;
   }
   SurfaceControl leash=(SurfaceControl)taskLeash.get(slot.surface);
   final int layer=LiveTaskContent.layerId(leash),task=slot.taskId,generation=arrivalFaceGeneration;
   final int width=slot.renderBounds.width(),height=slot.renderBounds.height();
   final SurfaceControl copied=(SurfaceControl)de.robv.android.xposed.XposedHelpers.newInstance(SurfaceControl.class,leash,"WindowDeckArrivalContent");
   if(contentThread==null){contentThread=new HandlerThread("WindowDeckLiveContent");contentThread.start();contentWorker=new Handler(contentThread.getLooper());}
   final long began=SystemClock.uptimeMillis();
   contentWorker.post(()->{
    Bitmap frame=null;
    try{frame=LiveTaskContent.captureFrame(copied,width,height,1440);}catch(Throwable e){Log.w(TAG,"arrive_content_capture_failed",e);}finally{copied.release();}
    final Bitmap bitmap=frame;
    handler.post(()->{
     boolean valid=false;
     try{valid=generation==arrivalFaceGeneration&&screenArrival&&!closing&&!stopped&&!slot.released&&slot.taskId==task&&slot.renderBounds.width()==width&&slot.renderBounds.height()==height&&LiveTaskContent.layerId((SurfaceControl)taskLeash.get(slot.surface))==layer;}catch(Throwable ignored){}
     if(valid&&bitmap!=null){
      attachArrivalFace(slot,bitmap,"live_child_layers",SystemClock.uptimeMillis()-began);
     }else{if(bitmap!=null)bitmap.recycle();Log.w(TAG,"arrive_content_cover_unavailable task="+task+" current="+valid);}
     if(generation==arrivalFaceGeneration&&screenArrival&&!closing&&!stopped&&!slot.released)handler.post(resume);
    });
   });return true;
  }catch(Throwable e){Log.w(TAG,"arrive_content_cover_unavailable",e);return false;}
 }
 private void attachArrivalFace(Slot slot,Bitmap bitmap,String source,long captureMs){
  RecoveryCover cover=new RecoveryCover(this);cover.setBitmap(bitmap);
  slot.card.addView(cover,1,new FrameLayout.LayoutParams(slot.surface.getWidth(),slot.surface.getHeight()));
  int[] src=presentationSize(slot);cover.fit(SurfaceFit.sourceQuad(src[0],new int[]{0,0,src[0],src[1]},rotatePresentation(slot)),displayQuad(slot,slot.surface.getWidth(),slot.surface.getHeight()),slot.renderBounds.width(),slot.renderBounds.height());
  arrivalFaceSlot=slot;arrivalFace=cover;arrivalFaceBitmap=bitmap;
  Log.i(TAG,"arrive_content_cover task="+slot.taskId+" source="+source+" size="+bitmap.getWidth()+"x"+bitmap.getHeight()+" capture_ms="+captureMs);
 }
 /** Animator end only starts settling. Confirm live fit, then remove the face and
  * traverse input-region changes before reporting success or focusing the task. */
 private void settleArrivalFrame(int token,boolean reveal){
  if(!arrivalRun.settling(token)||!screenArrival||closing||stopped||backgrounded)return;
  if(arrivalRun.remaining(SystemClock.uptimeMillis())==0){checkArrivalDeadline();return;}
  String key=entranceSurfaceKey();
  if(key==null){handler.postDelayed(()->settleArrivalFrame(token,reveal),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(Slot s:slots)if(!writeLeashFit(s,fit))throw new IllegalStateException("Unfitted final arrival slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!arrivalRun.settling(token)||!screenArrival)return;
    stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(!arrivalRun.settling(token)||!screenArrival)return;
     if(!key.equals(entranceSurfaceKey())){settleArrivalFrame(token,reveal);return;}
     if(reveal){
      clearArrivalFace("frame_committed");entranceInputBlocked=false;
      for(int i=0;i<slots.size();i++)inputRole(slots.get(i),i==primary);
      handler.post(()->settleArrivalFrame(token,false));
     }else if(arrivalRun.complete(token,SystemClock.uptimeMillis())){
      finishArrival("completed");handler.post(()->focusPrimary("arrive"));
     }else checkArrivalDeadline();
    }));stage.invalidate();
   });fit.apply();
  }catch(Throwable e){Log.w(TAG,"arrival_final_commit_failed",e);handler.postDelayed(()->settleArrivalFrame(token,reveal),MotionSpec.SURFACE_RETRY_MS);}
 }
 private void clearArrivalFace(String reason){
  arrivalFaceGeneration++;
  if(arrivalFace==null)return;
  RecoveryCover cover=arrivalFace;Slot slot=arrivalFaceSlot;arrivalFace=null;arrivalFaceSlot=null;
  Bitmap bitmap=arrivalFaceBitmap;arrivalFaceBitmap=null;cover.setBitmap(null);if(bitmap!=null)handler.postDelayed(()->{if(!bitmap.isRecycled())bitmap.recycle();},1000);if(cover.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)cover.getParent()).removeView(cover);
  Log.i(TAG,"arrive_content_reveal result="+reason+" task="+(slot==null?-1:slot.taskId)+" waiting_for="+entranceWaitReason);
 }
 /** Unsupported ingress has no trustworthy source frame. Show the committed live task
  *  rather than inventing a 112 dp launch point or reporting a snapshot animation as live. */
 private void animateAddedCard(Slot slot){
  Log.w(TAG,"add_card_result result=degraded_source_unavailable task="+slot.taskId);
  releaseEntrance();raiseMain();layoutCaption();
  if(!closing&&!stopped&&!backgrounded)focusPrimary("add_without_source");
 }
 private void hangForReplace(){
  if(closing||!initialized||recovering||dragging!=null||hanging||slots.isEmpty())return;
  if(slots.get(primary).pinned){Toast.makeText(this,"请先取消固定",Toast.LENGTH_SHORT).show();return;}
  hangReplace=true;hangForAdd();if(!hanging)hangReplace=false;
 }
 private void hangForAdd(){
  if(closing||!initialized||recovering||dragging!=null||hanging)return;
  if(!hangReplace&&slots.size()>=Caps.MAX_TASKS){Toast.makeText(this,"最多同时打开五个应用",Toast.LENGTH_SHORT).show();return;}
  cancelSwitchPreparation("new_picker");
  ArrayList<HangShelf.Card> cards=new ArrayList<>();
  Slot main=slots.get(primary);cards.add(new HangShelf.Card(hangFace(main),main.component.getPackageName(),screenRect(main.card),true));
  for(Slot s:slots)if(s!=main&&s.card!=null)cards.add(new HangShelf.Card(hangFace(s),s.component.getPackageName(),screenRect(s.card),false));
  try{hangShelf.show(this,cards,effectiveMode()==PaneLayout.TOP_BOTTOM,this::restoreFromHang);}
  catch(Throwable e){Log.w(TAG,"hang_show_failed",e);hangShelf.hide();if(hangReplace){hangReplace=false;chooseApp(slots.get(primary));}else chooseApp(null);return;}
  hanging=true;hangSawHome=false;hangStarted=SystemClock.uptimeMillis();hangTask=-1;hangUser=-1;hangNote=null;hangStableId=-1;hangStableHits=0;handler.removeCallbacks(hangConsume);
  publishState(slots.size());
  if(!backgroundWorkbench(hangReplace?"hang_replace":"hang_add")){hanging=false;hangReplace=false;hangShelf.hide();publishState(slots.size());return;}
  handler.removeCallbacks(hangWatch);handler.postDelayed(hangWatch,400);
  Log.i(TAG,"hang_add container="+getTaskId()+" count="+slots.size()+" replace="+hangReplace);
 }
 private Bitmap hangFace(Slot s){
  try{Method capture=viewApi.getDeclaredMethod("getSnapBitMap",boolean.class);capture.setAccessible(true);Bitmap bitmap=(Bitmap)capture.invoke(s.surface,false);if(bitmap!=null&&bitmap.getConfig()==Bitmap.Config.HARDWARE)bitmap=bitmap.copy(Bitmap.Config.ARGB_8888,false);return bitmap;}
  catch(Throwable e){Log.w(TAG,"hang_snapshot_failed slot="+s.id,e);return null;}
 }
 private int lastHangFocus=-1;
 private void watchHang(){
  if(!hanging||closing)return;
  try{
   Object top=focusedRoot();
   if(top==null||taskTop(top)==null){handler.postDelayed(hangWatch,300);return;}
   int id=top.getClass().getField("taskId").getInt(top);
   ComponentName topActivity=taskTop(top);
   if(id!=lastHangFocus){lastHangFocus=id;Log.i(TAG,"hang_focus task="+id+" top="+topActivity.flattenToShortString()+" type="+top.getClass().getMethod("getActivityType").invoke(top)+" mode="+top.getClass().getMethod("getWindowingMode").invoke(top));}
   boolean home=isHomeTask(top)||id==getTaskId();
   if(home)hangSawHome=isHomeTask(top)||hangSawHome;
   // A child of ours stays focused while the group is still leaving. Counting that as
   // "the user re-opened this app" would cancel the add before the launcher is even up,
   // so a focused child only counts once the workbench has provably gone to the back.
   Slot focused=null;for(Slot s:slots)if(s.taskId==id)focused=s;
   if(!hangSawHome){long waited=SystemClock.uptimeMillis()-hangStarted;if(waited<=(focused!=null?1200:800)){hangStableId=-1;hangStableHits=0;handler.postDelayed(hangWatch,300);return;}hangSawHome=true;}
   if(home){hangStableId=-1;hangStableHits=0;handler.postDelayed(hangWatch,300);return;}
   String pkg=topActivity.getPackageName();
   // The original keeps exactly one window per app: re-opening an app that is already in
   // the workbench raises the window it holds instead of adding a second one, and only
   // speaks up when that window is already the main one. It never rejects into a dead end.
   Slot existing=focused;if(existing==null)for(Slot s:slots)if(s.component.getPackageName().equals(pkg)){existing=s;break;}
   if(existing!=null){
    finishExistingHang(existing,false);return;
   }
   String block=embedBlock(top);
   if(block!=null){hangStableId=-1;hangStableHits=0;Log.i(TAG,"hang_wait task="+id+" pkg="+pkg+" reason="+block);handler.postDelayed(hangWatch,300);return;}
   if(id!=hangStableId){hangStableId=id;hangStableHits=1;handler.postDelayed(hangWatch,300);return;}
   if(++hangStableHits<2){handler.postDelayed(hangWatch,300);return;}
   acceptHang(id,top.getClass().getField("userId").getInt(top),pkg);
  }catch(Throwable e){Log.w(TAG,"hang_watch_failed",e);handler.postDelayed(hangWatch,300);}
 }
 private Object focusedRoot() throws Exception {
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  return Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo").invoke(service);
 }
 private ComponentName taskTop(Object task) throws Exception {return (ComponentName)task.getClass().getField("topActivity").get(task);}
 private ComponentName taskBase(Object task) throws Exception {return (ComponentName)task.getClass().getField("baseActivity").get(task);}
 private boolean isHomeTask(Object task) throws Exception {
  ComponentName top=taskTop(task);
  if(top!=null&&"com.android.launcher".equals(top.getPackageName()))return true;
  Object type=task.getClass().getMethod("getActivityType").invoke(task);
  return type instanceof Integer&&((Integer)type)==2;
 }
 private String embedBlock(Object task) throws Exception {
  ComponentName base=taskBase(task),top=taskTop(task);
  if(base==null||top==null)return "no_activity";
  if(!base.getPackageName().equals(top.getPackageName()))return "starting";
  int mode=((Integer)task.getClass().getMethod("getWindowingMode").invoke(task));
  if(mode!=1)return "mode="+mode;
  int id=task.getClass().getField("taskId").getInt(task);
  Intent launcher=getPackageManager().getLaunchIntentForPackage(top.getPackageName());
  if(launcher==null||launcher.getComponent()==null)return "no_launcher";
  if(!Boolean.TRUE.equals(managerApi.getMethod("isAppSupportPocketStudio",Intent.class,int.class).invoke(manager,launcher,id)))return "unsupported";
  return null;
 }
 private Rect screenRect(View view){
  int[] loc=new int[2];view.getLocationOnScreen(loc);
  int w=Math.max(1,Math.round(view.getWidth()*view.getScaleX())),h=Math.max(1,Math.round(view.getHeight()*view.getScaleY()));
  return new Rect(loc[0],loc[1],loc[0]+w,loc[1]+h);
 }
 private void acceptHang(int task,int user,String pkg){
  for(Slot s:slots)if(s.component.getPackageName().equals(pkg)){
   finishExistingHang(s,false);return;
  }
  hanging=false;handler.removeCallbacks(hangWatch);handler.removeCallbacks(hangConsume);hangShelf.hide();publishState(slots.size());
  hangTask=task;hangUser=user;hangConsumeTries=0;Log.i(TAG,"hang_pick task="+task+" pkg="+pkg);
  final int generation=++hangSourceGeneration;final boolean[] finished={false};final long began=SystemClock.uptimeMillis();
  if(hangSourceFrame!=null){hangSourceFrame.recycle();hangSourceFrame=null;}hangSourceTask=-1;
  if(contentThread==null){contentThread=new HandlerThread("WindowDeckLiveContent");contentThread.start();contentWorker=new Handler(contentThread.getLooper());}
  final Runnable[] attempt={null};
  attempt[0]=()->{
   if(finished[0]||generation!=hangSourceGeneration||closing||hangTask!=task)return;
   contentWorker.post(()->{
   Bitmap frame=null;
   try{
    Object wm=de.robv.android.xposed.XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),"getWindowManagerService");
    frame=(Bitmap)de.robv.android.xposed.XposedHelpers.callMethod(wm,"snapshotTaskForRecents",task);
   }catch(Throwable e){Log.w(TAG,"hang_source_capture_failed task="+task,e);}
   final Bitmap captured=frame;
   final boolean presented=HandoffCover.visibleTaskFrame(captured);
   handler.post(()->{
    if(finished[0]||generation!=hangSourceGeneration||closing||hangTask!=task){if(captured!=null)captured.recycle();return;}
    if(!presented){
     HandoffCover.recycle(captured);
     handler.postDelayed(attempt[0],80);return;
    }
    finished[0]=true;hangSourceFrame=captured;hangSourceTask=task;
    Log.i(TAG,"hang_source_capture task="+task+" available=true presented=true ms="+(SystemClock.uptimeMillis()-began));ensureHangConsumed();
   });
   });
  };
  attempt[0].run();
  handler.postDelayed(()->{if(!finished[0]&&generation==hangSourceGeneration&&!closing&&hangTask==task){finished[0]=true;Log.w(TAG,"hang_source_capture_timeout task="+task+" pixels_unavailable=true");ensureHangConsumed();}},1200);
 }
 private void ensureHangConsumed(){
  if(closing||hangTask<0)return;
  if(stopped||backgrounded){bringWorkbenchForward();scheduleHangRetry();return;}
  hangQuiet=true;consumeHangAdd();hangQuiet=false;
  if(hangTask>=0)scheduleHangRetry();
 }
 private void scheduleHangRetry(){
  if(++hangConsumeTries>10){Log.w(TAG,"hang_consume_gave_up task="+hangTask);hangTask=-1;hangUser=-1;pendingHangFront=-1;hangReplace=false;Toast.makeText(this,"未能加入，请再试一次",Toast.LENGTH_SHORT).show();bringWorkbenchForward();return;}
  handler.postDelayed(hangConsume,250);
 }
 private void restoreFromHang(){
  if(!hanging)return;
  hanging=false;hangReplace=false;handler.removeCallbacks(hangWatch);hangShelf.hide();publishState(slots.size());
  Log.i(TAG,"hang_restored");bringWorkbenchForward(this::releaseEntranceCoverWhenDrawn);
 }
 private void bringWorkbenchForward(){bringWorkbenchForward(null);}
 private void bringWorkbenchForward(Runnable afterRaised){
  withEntranceCover("hang_front",()->{
   try{reorderTask(getTaskId(),true);Log.i(TAG,"hang_front container="+getTaskId());}
   catch(Throwable e){
    Log.w(TAG,"hang_front_failed",e);
    try{settleHangContainer();}catch(Throwable fallback){Log.w(TAG,"hang_settle_failed",fallback);closeEntranceCover("raise_failed");return;}
   }
   if(afterRaised!=null)afterRaised.run();
  });
 }
 private boolean selectExistingFromLauncher(Intent request){
  if(closing||!initialized||request.getIntExtra("containerTaskId",-1)!=getTaskId()||request.getLongExtra("instanceToken",0)!=stateToken)return false;
  long session=request.getLongExtra("selectionSession",0);int task=request.getIntExtra("selectedTask",-1);
  // Two queued clicks from one picker session must never replay a fullscreen start.
  if(session!=0&&session==lastExistingSession&&lastExistingTask>=0)return true;
  if(!hanging||session!=hangStarted)return false;
  String pkg=request.getStringExtra("selectedPackage");
  for(Slot s:slots)if(!s.released&&s.taskId==task&&s.component.getPackageName().equals(pkg)){
   if(recovering||dragging!=null){Toast.makeText(this,"请等待窗口恢复后再选择",Toast.LENGTH_SHORT).show();return true;}
   lastExistingSession=session;lastExistingTask=task;finishExistingHang(s,true);return true;
  }
  return false;
 }
 private void finishExistingHang(Slot selected,boolean beforeLaunch){
  boolean replacing=hangReplace;hanging=false;hangReplace=false;
  handler.removeCallbacks(hangWatch);handler.removeCallbacks(hangConsume);hangShelf.hide();
  int index=slots.indexOf(selected);boolean promote=!replacing&&index>=0&&index!=primary&&!recovering&&dragging==null&&selected.taskId>=0;
  if(promote)queueExistingSwitch(selected,hangStarted);else hangNote=selected.label+" 已在前台启动";
  publishState(slots.size());
  Log.i(TAG,"hang_existing task="+selected.taskId+" slot="+selected.id+" before_launch="+beforeLaunch+" replace="+replacing+" promote_requested="+promote);
  if(beforeLaunch){
   // The task never received a fullscreen launch: retain its existing embedding.
   withEntranceCover("hang_raise",()->{raiseHangContainerNow();releaseEntranceCoverWhenDrawn();});
  }else{
   // Non-launcher entrances may already have detached the task. Rebind the SAME
   // task with scenario/container options; Surface reparent alone cannot do that.
   selected.pendingTask=selected.taskId;pendingHangFront=selected.taskId;
   bringWorkbenchForward(()->handler.post(()->{restorePendingTasks();releaseEntranceCoverWhenDrawn();}));
  }
 }
 // Focusing this container makes ColorOS mark each embedded task always-on-top and move it to the front.
 // After the new task is in, drop that flag and raise only the container. Block the resume restart that would undo it.
 private void raiseHangContainer(){
  withEntranceCover("hang_raise",this::raiseHangContainerNow);
 }
 private void raiseHangContainerNow(){
  if(closing||isFinishing())return;
  try{Method method=viewApi.getMethod("setNoNeedStartEmbedded",boolean.class);for(Slot s:slots)if(s.surface!=null)method.invoke(s.surface,true);}
  catch(Throwable e){Log.w(TAG,"hang_suppress_failed",e);}
  try{settleHangContainer();}catch(Throwable e){Log.w(TAG,"hang_settle_failed",e);closeEntranceCover("raise_failed");}
  if(clearHangSuppress!=null)handler.removeCallbacks(clearHangSuppress);
  clearHangSuppress=()->{clearHangSuppress=null;if(pendingHangFront>=0)return;try{Method method=viewApi.getMethod("setNoNeedStartEmbedded",boolean.class);for(Slot s:slots)if(s.surface!=null)method.invoke(s.surface,false);}catch(Throwable e){Log.w(TAG,"hang_suppress_failed",e);}Log.i(TAG,"hang_suppress_cleared");};
  handler.postDelayed(clearHangSuppress,900);
 }
 private void settleHangContainer() throws Exception {
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  java.util.List<?> tasks=(java.util.List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,false,0);
  Class<?> token=Class.forName("android.window.WindowContainerToken"),wct=Class.forName("android.window.WindowContainerTransaction");
  Object tx=wct.getConstructor().newInstance();int container=getTaskId();boolean raised=false;
  for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;boolean child=false;for(Slot s:slots)if(!s.released&&s.taskId==task.taskId)child=true;if(!child&&task.taskId!=container)continue;Object t=task.getClass().getField("token").get(task);if(child)wct.getMethod("setAlwaysOnTop",token,boolean.class).invoke(tx,t,false);if(task.taskId==container){wct.getMethod("setHidden",token,boolean.class).invoke(tx,t,false);wct.getMethod("reorder",token,boolean.class).invoke(tx,t,true);raised=true;}}
  if(!raised)throw new IllegalStateException("Missing container "+container);
  Class<?> organizer=Class.forName("android.window.WindowOrganizer");organizer.getMethod("applyTransaction",wct).invoke(organizer.getConstructor().newInstance(),tx);
  Log.i(TAG,"hang_settle container="+container);
 }
 private void dismissHang(){hanging=false;hangReplace=false;hangTask=-1;hangUser=-1;hangNote=null;handler.removeCallbacks(hangWatch);handler.removeCallbacks(hangConsume);hangShelf.hide();}
 private void consumeHangAdd(){
  if(closing)return;
  if(hangNote!=null){Toast.makeText(this,hangNote,Toast.LENGTH_SHORT).show();hangNote=null;}
  if(hangTask<0||stopped||backgrounded)return;
  int id=hangTask,user=hangUser;boolean replace=hangReplace;pendingHangFront=id;
  Intent request=new Intent();request.putExtra("windowdeck_add_task_id",id);request.putExtra("windowdeck_add_user_id",user);request.putExtra("windowdeck_container_task_id",getTaskId());request.putExtra("windowdeck_hang_place",!replace);request.putExtra("windowdeck_hang_replace",replace);
  addExistingTask(request);
  if(pendingEntrance!=null&&pendingEntrance.sourceTaskId==id){hangTask=-1;hangUser=-1;hangReplace=false;Log.i(TAG,"hang_consumed task="+id+" replace="+replace);}
  else pendingHangFront=-1;
 }
 private void chooseApp(Slot replacing){
  if(closing||!initialized||recovering||dragging!=null)return;
  if(replacing!=null&&replacing.pinned){Toast.makeText(this,"请先取消固定",Toast.LENGTH_SHORT).show();return;}
  if(replacing==null&&slots.size()>=Caps.MAX_TASKS){Toast.makeText(this,"最多同时打开五个应用",Toast.LENGTH_SHORT).show();return;}
  Set<String> excluded=new HashSet<>();for(Slot s:slots)excluded.add(s.component.getPackageName());
  AppPicker.show(this,excluded,(c,label)->{
   if(closing||recovering||dragging!=null||(replacing!=null&&replacing.pinned))return;
   try{
    Slot fresh=validate(c,replacing);int index=replacing==null?slots.size():slots.indexOf(replacing);if(index<0)return;
    cancelAnimation();
    if(replacing==null){
     slots.add(fresh);publishState(slots.size());updateNaturalBounds();createWindow(fresh);layoutCards(false);refreshStatus();
     Log.i(TAG,"add slot="+fresh.id+" count="+slots.size());
    }else prepareRecovery(replacing,()->replaceSlotNow(index,replacing,fresh));
   }catch(Throwable e){fail(e);}
  });
 }
 private void replaceSlotNow(int index,Slot replacing,Slot fresh){
  if(closing||replacing.released||slots.indexOf(replacing)!=index)return;
  slots.set(index,fresh);createWindow(fresh);transferCover(replacing,fresh);updateNaturalBounds();layoutCards(false);refreshStatus();
  stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   if(closing||replacing.released)return;
   releaseSlot(replacing);restoreContainerFocus();
   Log.i(TAG,"replace slot="+fresh.id+" was="+replacing.id+" count="+slots.size());
  }));stage.invalidate();
 }
 private void transferCover(Slot from,Slot to){
  if(from.recoveryCover==null||to.card==null)return;
  RecoveryCover cover=from.recoveryCover;from.card.removeView(cover);from.recoveryCover=null;to.recoveryCover=cover;
  to.card.addView(cover,Math.min(1,to.card.getChildCount()),new FrameLayout.LayoutParams(to.renderBounds.width(),to.renderBounds.height()));
  fitSurface(to,to.card.getWidth(),to.card.getHeight());
 }
 private void menu(Slot s){
  if(closing||s.released||recovering||dragging!=null)return;
  showWorkbenchDialog(new AlertDialog.Builder(this).setTitle(s.label).setItems(new String[]{"切换为主应用",s.pinned?"取消固定":"固定此应用","替换应用","从工作台移出"},(d,which)->{
   if(s.released||recovering||dragging!=null)return;
   if(which==0)promote(slots.indexOf(s));else if(which==1)togglePin(s);else if(which==2)chooseApp(s);else removeSlot(s);
  }).setNegativeButton("取消",null).create());
 }
 private void togglePin(Slot s){
  if(!s.pinned)for(Slot other:slots)if(other.pinned){Toast.makeText(this,"请先取消固定 "+other.label,Toast.LENGTH_SHORT).show();return;}
  cancelAnimation();s.pinned=!s.pinned;layoutCards(false);Log.i(TAG,"pin slot="+s.id+" pinned="+s.pinned);
 }
 private void removeSlot(Slot s){removeSlot(s,false);}
 private void removeSlot(Slot s,boolean taskGone){
  int index=slots.indexOf(s);if(index<0||closing||s.released)return;
  if(!taskGone&&(recovering||dragging!=null))return;
  if(!taskGone&&s.pinned){Toast.makeText(this,"请先取消固定",Toast.LENGTH_SHORT).show();return;}
  cancelDrag();
  if(slots.size()==1){closeWorkbench();return;}
  prepareRecovery(s,()->removeSlotNow(s));
 }
 private void removeSlotNow(Slot s){
  int index=slots.indexOf(s);if(index<0||closing||s.released)return;
  cancelAnimation();Slot main=slots.get(primary);slots.remove(index);publishState(slots.size());primary=main==s?Math.min(index,slots.size()-1):slots.indexOf(main);
  releaseSlot(s);updateNaturalBounds();layoutCards(false);refreshStatus();restoreContainerFocus();Log.i(TAG,"remove slot="+s.id+" task="+s.taskId+" count="+slots.size());
 }
 private void prepareRecovery(Slot excluded,Runnable mutation){
  cancelAnimation();recovering=true;
  for(int i=0;i<slots.size();i++){
   Slot active=slots.get(i);inputRole(active,i==primary);
   if(active.released||active.surface==null)continue;
   if(active.recoveryCover==null){
    RecoveryCover cover=new RecoveryCover(this);

    try{Method snapshot=viewApi.getDeclaredMethod("getSnapBitMap",boolean.class);snapshot.setAccessible(true);
     Bitmap bitmap=(Bitmap)snapshot.invoke(active.surface,false);if(bitmap!=null)cover.setBitmap(bitmap);
     Log.i(TAG,"recovery_cover slot="+active.id+" snapshot="+(bitmap!=null));
    }catch(Throwable e){Log.w(TAG,"recovery_snapshot_unavailable slot="+active.id,e);}
    active.recoveryCover=cover;active.card.addView(cover,1,new FrameLayout.LayoutParams(active.renderBounds.width(),active.renderBounds.height()));
    fitSurface(active,active.card.getWidth(),active.card.getHeight());
   }
  }
  // Submit the opaque covers before moveTaskToBack can hide sibling task leashes.
  stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{if(!closing)mutation.run();}));stage.invalidate();
 }
 private void clearRecoveryCovers(){
  for(Slot active:slots)if(active.recoveryCover!=null){active.recoveryCover.setBitmap(null);active.card.removeView(active.recoveryCover);active.recoveryCover=null;}
 }
 private void cancelDrag(){if(dragging!=null){Slot old=dragging;dragging=null;if(old.card!=null)old.card.resetGesture();}}
 private boolean beginDrag(Slot s){
  if(closing||recovering||switching()||dragging!=null||s.released||s.pinned||slots.indexOf(s)==primary||s.taskId<0)return false;
  dragging=s;Log.i(TAG,"drag_start slot="+s.id);return true;
 }
 private void finishDrag(Slot s,boolean commit){
  if(dragging!=s)return;dragging=null;
  Log.i(TAG,"drag_end slot="+s.id+" commit="+commit);
  if(commit)removeSlot(s);
 }
 private void createWindow(Slot slot){
  if(waitingForSourceRelease()&&slot==pendingEntrance){Log.i(TAG,"defer_embed_wait task="+slot.sourceTaskId);return;}
  try{
   updateRenderBounds(slot);
   slot.card=new PreviewCard(this);applyCardRadius(slot,slots.indexOf(slot)==primary);
   slot.card.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
    if(!closing&&!slot.released&&r>l&&b>t&&(l!=ol||t!=ot||r!=or||b!=ob)){fitRecoveryCover(slot,r-l,b-t);scheduleLeashFit(slot,r-l,b-t);}
   });
   slot.card.setOnClickListener(v->promote(slots.indexOf(slot)));
   slot.card.beginDrag=()->beginDrag(slot);slot.card.cancelDrag=()->finishDrag(slot,false);slot.card.dismiss=()->finishDrag(slot,true);
   slot.card.setOnLongClickListener(v->{if(!switching())menu(slot);return true;});
   slot.surface=(View)viewApi.getConstructor(Context.class).newInstance(this);
   EmbeddedTaskGuard.watch(slot.surface,getTaskId(),embedded->{
    slot.embedded=embedded;
    if(!embedded){if(taskRotationRun!=null&&taskRotationRun.slot==slot)finishTaskRotation(taskRotationRun,"detached");slot.drawEvidence.invalidate();slot.resizedTask=-1;slot.fittedPose=null;clearProjection(slot);Log.i(TAG,"task_transform_released slot="+slot.id+" task="+slot.taskId);}
   });
   ((SurfaceView)slot.surface).getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
   ((SurfaceView)slot.surface).getHolder().addCallback(new SurfaceHolder.Callback(){
    public void surfaceCreated(SurfaceHolder holder){restorePlate();}
    public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){restorePlate();}
    public void surfaceDestroyed(SurfaceHolder holder){if(taskRotationRun!=null&&taskRotationRun.slot==slot)finishTaskRotation(taskRotationRun,"surface_destroyed");slot.drawEvidence.invalidate();slot.windowDrawn=false;slot.contentProbeUnavailable=false;slot.fittedPose=null;slot.resizedTask=-1;}
    private void restorePlate(){handler.post(()->{
     if(closing||slot.released)return;
     if(backdrop!=null)backdrop.restoreSurface();
     syncSurface(slot);
     Log.i(TAG,"surface_restored slot="+slot.id+" task="+slot.taskId);
    });}
   });
   try{Field disable=SurfaceView.class.getDeclaredField("mDisableBackgroundLayer");disable.setAccessible(true);disable.setBoolean(slot.surface,true);}catch(Throwable e){Log.w(TAG,"surface_background_disable_failed",e);}
   slot.surface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(!closing&&!slot.released&&r>l&&b>t&&(l!=ol||t!=ot||r!=or||b!=ob))resizeSurface(slot);});
   Class<?> listener=Class.forName("com.oplus.flexiblewindow.FlexibleTaskView$Listener");
   Object proxy=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(p,m,args)->{
    if(m.getDeclaringClass()==Object.class){if(m.getName().equals("toString"))return "WorkbenchListener";if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==args[0];}
    if(closing||slot.released)return null;
    if(m.getName().equals("onTaskCreated")||m.getName().equals("onTaskChanged")){
     ComponentName top=(ComponentName)args[1];
     int id=(Integer)args[0];if(slot.sourceTaskId>=0&&slot.taskId<0&&id!=slot.sourceTaskId){slot.failed=true;Log.e(TAG,"add_existing_identity_mismatch expected="+slot.sourceTaskId+" actual="+id);handler.post(()->removeSlot(slot,true));return null;}
     boolean firstTask=slot.taskId<0,taskChanged=slot.taskId!=id;if(taskChanged)Log.i(TAG,"task_ready slot="+slot.id+" task="+id);slot.taskId=id;
     if(taskChanged)publishState(slots.size());
     applyRotationIdentity(slot,id,null,true);
     if(!slot.taskOrientation.bound(id)){
      Log.i(TAG,"rotation_activity_callback_fallback slot="+slot.id+" task="+id+" component="+flatten(top));
      commitRotation(slot,id,RotationIdentity.choose(flatten(top),null,null,null,slot.component.getPackageName()));
     }
     handler.removeCallbacks(immersivePoll);handler.post(immersivePoll);
     updateDirectionEnvironment(slot);int nextAxis=slot.taskOrientation.axis();if(slot.taskOrientation.bound()&&firstTask&&slot.orientationAxis!=nextAxis){slot.orientationAxis=nextAxis;handler.post(this::scheduleLayout);}else queueTaskDirections();
     slot.failed=false;refreshStatus();handler.post(()->{syncSurface(slot);focusPrimary("task_ready");maybeAnimateAddedCard();if(deferPortrait&&id==slot.sourceTaskId)requestCoverPose(slot);if(id==pendingHangFront){pendingHangFront=-1;raiseHangContainer();}});
    }else if(m.getName().equals("onTaskRectOrientationChanged")){
     ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)args[0];Rect requested=args[1] instanceof Rect?new Rect((Rect)args[1]):null;
     if(info!=null&&requested!=null&&info.taskId==slot.taskId){
      RotationIdentity callbackIdentity=readIdentity(info);
      applyRotationIdentity(slot,info.taskId,info,true);
      int rotation=taskRotation(info);
      if(slot.taskOrientation.update(info.taskId,callbackIdentity.rotationClass,requested.left,requested.top,requested.right,requested.bottom,rotation)){
       int axis=slot.taskOrientation.axis();
       Log.i(TAG,"task_orientation slot="+slot.id+" task="+info.taskId+" component="+slot.taskOrientation.rotationClass()+" axis="+axis+" bounds="+requested+" rotation="+slot.taskOrientation.rotation()+" previous_rotation="+slot.taskOrientation.previousRotation()+" source="+slot.taskOrientation.source()+" "+slot.taskOrientation.record());
      }else if(!java.util.Objects.equals(callbackIdentity.rotationClass,slot.taskOrientation.rotationClass())){
       Log.i(TAG,"task_orientation stale_activity slot="+slot.id+" task="+info.taskId+" callback="+callbackIdentity.rotationClass+" current="+slot.taskOrientation.rotationClass());
      }
      // Rebinding can change the direction even when the rect is stale or invalid.
      // Claim the native transaction before FlexibleTaskView can apply its final
      // orientation. Posting this work lets a target pose escape for one frame.
      applyTaskDirections();
     }
    }else if(m.getName().equals("onInitialized")&&Boolean.FALSE.equals(args[0])){slot.failed=true;Log.e(TAG,"task_start_failed slot="+slot.id);handler.post(()->abandonEntrance(slot));refreshStatus();}
    else if(m.getName().equals("onTaskWindowDraw")&&((Integer)args[0])==slot.taskId){boolean drawn=Boolean.TRUE.equals(args[1]);Log.i(TAG,"task_draw slot="+slot.id+" drawn="+drawn);if(!drawn){slot.windowDrawn=false;slot.drawEvidence.invalidate();}else if(gestureHandoff&&slot.orientationAxis==2&&!slot.drawEvidence.hasContent())handler.post(()->{syncSurface(slot);maybeAnimateAddedCard();});else{slot.windowDrawn=true;handler.post(()->{syncSurface(slot);maybeAnimateAddedCard();});}}
    else if(m.getName().equals("onBackPressedOnTaskRoot")){int task=(Integer)args[0];handler.post(()->handleTaskRootBack(slot,task));}
    else if(m.getName().equals("onTaskRemovalStarted")){int task=(Integer)args[0];handler.post(()->onEmbeddedTaskVanished(slot,task));}
    return null;
   });
   viewApi.getMethod("setListener",Executor.class,listener).invoke(slot.surface,(Executor)this::runOnUiThread,proxy);
   Bundle config=new Bundle();config.putInt("scenario",2);config.putParcelable("launchBounds",new Rect(slot.renderBounds));
   config.putBoolean("flexible_embeed_task_config_with_container",false);
   // reparentRotate otherwise adds a ColorDrawable which our transparent plate
   // immediately clears. That requests layout, and the layout callback reparents
   // again. Our wallpaper and content covers already own the plate background.
   config.putBoolean("use_default_background_color",false);
   config.putBoolean("need_rotate_task_leash",deferPortrait&&slot.sourceTaskId>=0&&slot.sourceTaskId==getIntent().getIntExtra("windowdeck_create_source_task",-1)?false:rotatePresentation(slot));
   // Native rotation otherwise promotes the Surface above the host window,
   // bypassing preview interception and recovery covers. Keep host controls on top.
   config.putBoolean("zorder_on_top",false);
   if(slot.sourceTaskId>=0)config.putInt("taskId",slot.sourceTaskId);
   else config.putParcelable("intent",new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(slot.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   config.putInt("userId",slot.sourceTaskId>=0?slot.sourceUserId:android.os.Process.myUid()/100000);config.putFloat("cornerRadius",cardRadius(slots.indexOf(slot)==primary));config.putInt("reparent_align",slots.indexOf(slot)==primary?0:2);
   config.putBoolean("intercept_input_event",true);config.putBoolean("allow_task_detach_from_embedding",true);config.putBoolean("key_intercept_back_key",true);config.putBoolean("flexible_key_remove_task_detach",false);config.putInt("use_view_snapshot",1);
   viewApi.getMethod("init",Bundle.class).invoke(slot.surface,config);viewApi.getMethod("setEnforceStart",boolean.class).invoke(slot.surface,true);
   if(viewTransaction!=null){
    slot.transaction=(SurfaceControl.Transaction)viewTransaction.get(slot.surface);
    LeashTransactions.register(slot.transaction,t->{
     if(taskRotationRun==null&&slot.orientationAxis!=slot.taskOrientation.axis()
       &&Looper.myLooper()==handler.getLooper())applyTaskDirections();
     boolean fitted=writeLeashFit(slot,t);
     // The native reparent applies this same transaction. During this entrance,
     // consume it into ViewRoot's pending transaction rather than applying it
     // before the new SurfaceView layout. C17 ViewRoot.merge empties the source.
     boolean rotationOwns=(taskRotationRun!=null&&taskRotationRun.slot==slot&&taskRotationRun.changingLayout)
       ||(rotationTransactions!=null&&rotationTransactions.pending());
     if(fitted&&((deferPortrait&&slot==poseSlot)||rotationOwns)&&!closing&&!stopped&&!backgrounded
       &&Looper.myLooper()==handler.getLooper()&&stage.isAttachedToWindow()&&stage.isHardwareAccelerated()){
      submitMotionFit(t);stage.invalidate();return true;
     }
     return false;
    });
   }
   int[] presentation=presentationSize(slot);
   slot.card.addView(slot.surface,new FrameLayout.LayoutParams(presentation[0],presentation[1]));
   slot.icon=new ImageView(this);slot.icon.setImageDrawable(getPackageManager().getActivityIcon(slot.component));slot.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);Ui.round(slot.icon,Ui.dp(this,6));
   FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(Ui.dp(this,22),Ui.dp(this,22),Gravity.BOTTOM|Gravity.LEFT);badge.leftMargin=Ui.dp(this,6);badge.bottomMargin=Ui.dp(this,6);slot.card.addView(slot.icon,badge);
   slot.pin=Ui.text(this,"钉",9,0xffffffff);slot.pin.setGravity(Gravity.CENTER);slot.pin.setBackground(Ui.bg(0xff365db5,Ui.dp(this,8)));slot.pin.setContentDescription("已固定，先取消固定才能替换或移出");
   FrameLayout.LayoutParams pinLp=new FrameLayout.LayoutParams(Ui.dp(this,16),Ui.dp(this,16),Gravity.BOTTOM|Gravity.LEFT);pinLp.leftMargin=Ui.dp(this,32);pinLp.bottomMargin=Ui.dp(this,8);slot.card.addView(slot.pin,pinLp);
   stage.addView(slot.card);layoutCaption();status.bringToFront();
   handler.postDelayed(()->{if(!closing&&!slot.released&&slot.taskId<0){slot.failed=true;abandonEntrance(slot);refreshStatus();Log.w(TAG,"startup_timeout slot="+slot.id);}},10000);
  }catch(Throwable e){slot.failed=true;fail(e);}
 }
 @Override public void dump(String prefix,java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args){
  super.dump(prefix,fd,writer,args);
  writer.println(prefix+"WindowDeck IME: "+CanvasImeBridge.diagnostic());
 }
 private void dismissPrimaryMenu(){if(primaryPopup!=null&&primaryPopup.isShowing())primaryPopup.dismiss();primaryPopup=null;}
 private void primaryMenu(){
  if(taskRotationRun!=null||closing||recovering||dragging!=null||slots.isEmpty()||more==null||more.getVisibility()!=View.VISIBLE||stage==null||stage.getWidth()<=0)return;
  dismissPrimaryMenu();
  Slot main=slots.get(primary);
  CardLayout.Result geometry=cardGeometry();
  if(geometry.cards.length==0)return;
  int[] card=geometry.cards[Math.min(primary,geometry.cards.length-1)];
  boolean night=MenuChrome.night(this);
  int color=night?ControlBarMetrics.MENU_TEXT_NIGHT:ControlBarMetrics.MENU_TEXT;
  int width=ControlBarMetrics.menuWidth(getResources().getDisplayMetrics().density);
  LinearLayout panel=new LinearLayout(MenuChrome.themed(this,night));panel.setOrientation(LinearLayout.VERTICAL);
  String[] items={"全屏","切换布局","替换应用","关闭","悬浮键盘："+(CanvasImeBridge.floating()?"开":"关")};
  int[] glyphs={MenuChrome.FULLSCREEN,MenuChrome.LAYOUT,MenuChrome.REPLACE,MenuChrome.CLOSE,MenuChrome.KEYBOARD};
  for(int i=0;i<items.length;i++){
   final int which=i;
   panel.addView(MenuChrome.row(panel.getContext(),items[i],glyphs[i],color,layoutMode==PaneLayout.TOP_BOTTOM,v->{
    dismissPrimaryMenu();
    if(closing||recovering||dragging!=null||slots.isEmpty()||slots.get(primary)!=main)return;
    if(which==0)exitToFullscreen();else if(which==1)toggleLayout();else if(which==2)hangForReplace();else if(which==3)removeSlot(main);else CanvasImeBridge.toggleFloating(this);
   }),new LinearLayout.LayoutParams(-1,-2));
  }
  View chrome=MenuChrome.panel(panel,night);
  primaryPopup=new PopupWindow(chrome,width,-2,true);primaryPopup.setOutsideTouchable(true);primaryPopup.setElevation(0);
  primaryPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
  primaryPopup.setBackgroundDrawable(new ColorDrawable(0));
  primaryPopup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
  primaryPopup.setOnDismissListener(()->{primaryPopup=null;modalWindows=Math.max(0,modalWindows-1);handler.post(()->focusPrimary("menu_dismiss"));});
  modalWindows++;
  int[] loc=new int[2];stage.getLocationInWindow(loc);
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  int x=loc[0]+card[0]+card[2]/2-width/2;
  int y=ControlBarMetrics.menuTop(loc[1]+card[1],dm.density);
  int left=loc[0],span=stage.getWidth();
  x=Math.max(left,Math.min(x,Math.max(left,left+span-width)));
  try{primaryPopup.showAtLocation(getWindow().getDecorView(),Gravity.TOP|Gravity.LEFT,x,y);Log.i(TAG,"primary_menu_open x="+x+" y="+y+" night="+night);}
  catch(Throwable e){modalWindows=Math.max(0,modalWindows-1);primaryPopup=null;Log.w(TAG,"primary_menu_failed",e);}
 }
 void showWorkbenchDialog(AlertDialog dialog){modalWindows++;dialog.setOnDismissListener(d->{modalWindows=Math.max(0,modalWindows-1);handler.post(()->focusPrimary("dialog_dismiss"));});dialog.show();}
 private String layoutLabel(){return layoutMode==PaneLayout.TOP_BOTTOM?"上下":"左右";}
 private void toggleLayout(){
  if(closing||!initialized||recovering||dragging!=null)return;
  cancelAnimation();layoutMode=layoutMode==PaneLayout.LEFT_RIGHT?PaneLayout.TOP_BOTTOM:PaneLayout.LEFT_RIGHT;
  queueTaskDirections();scheduleLayout();
  Log.i(TAG,"layout_mode="+layoutMode+" tasks="+taskIds());
 }
 private int effectiveMode(){return layoutMode;}
 boolean rotatePrimaryIme(){return !closing&&!stopped&&!backgrounded&&!slots.isEmpty()&&primary>=0&&primary<slots.size()&&!slots.get(primary).released&&rotatePresentation(slots.get(primary));}
 private boolean rotatePresentation(Slot s){
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  return s.orientationAxis==2&&OrientationPolicy.rotatePresentation(s.renderBounds.width(),s.renderBounds.height(),dm.widthPixels,dm.heightPixels);
 }
 private int[] presentationSize(Slot s){return OrientationPolicy.presentationSize(s.renderBounds.width(),s.renderBounds.height(),rotatePresentation(s));}
 private int layoutGap(){return Math.max(1,Ui.dp(this,1));}
 private int sideWidth(){
  return effectiveMode()==PaneLayout.LEFT_RIGHT?PaneLayout.leftRightSideW(stage.getWidth(),layoutGap()):PaneLayout.topBottomSideW(stage.getWidth(),layoutGap());
 }
 private int previewHeight(){
  return PaneLayout.previewHeight(stage.getWidth(),stage.getHeight(),layoutGap(),sideWidth(),0,effectiveMode());
 }
 private int[][] allocation(){return PaneLayout.compute(stage.getWidth(),stage.getHeight(),slots.size(),primary,layoutGap(),sideWidth(),previewHeight(),effectiveMode());}
 private CardLayout.Result cardGeometry(){
  int[][] sizes=new int[slots.size()][2];
  for(int i=0;i<slots.size();i++)sizes[i]=presentationSize(slots.get(i));
  return CardLayout.compute(stage.getWidth(),stage.getHeight(),primary,layoutGap(),sideWidth(),previewHeight(),effectiveMode(),0,Ui.dp(this,48),sizes);
 }
 private boolean primaryLightContent(){
  if(!slots.isEmpty()&&primary>=0&&primary<slots.size()){
   Boolean known=contentLight.get(slots.get(primary).taskId);
   if(known!=null)return known;
  }
  return !MenuChrome.night(this);
 }
 private void onContentLight(int taskId,boolean light){
  contentLight.put(taskId,light);
  Log.i(TAG,"control_bar_light task="+taskId+" light="+light);
  if(!slots.isEmpty()&&primary>=0&&primary<slots.size()&&slots.get(primary).taskId==taskId)layoutCaption();
 }
 private void registerControlBarLight(){
  if(controlBarCallback!=null)return;
  try{
   Class<?> mgrCls=Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager");
   Object mgr=mgrCls.getMethod("getInstance").invoke(null);
   Class<?> face=Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager$IEmbeddedWindowCallback");
   Object callback=Proxy.newProxyInstance(face.getClassLoader(),new Class[]{face},(proxy,method,args)->{
    if(method.getDeclaringClass()==Object.class){
     if("equals".equals(method.getName()))return proxy==args[0];
     if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);
     if("toString".equals(method.getName()))return "WindowDeckControlBar";
    }
    if("notifyCanvasUpdateControlBar".equals(method.getName())&&args!=null&&args.length>=2&&args[0]!=null){
     int taskId=args[0].getClass().getField("taskId").getInt(args[0]);
     boolean light=Boolean.TRUE.equals(args[1]);
     handler.post(()->onContentLight(taskId,light));
    }
    return null;
   });
   mgrCls.getMethod("registerEmbeddedWindowContainerCallback",face,int.class).invoke(mgr,callback,getTaskId());
   controlBarCallback=callback;
   Log.i(TAG,"control_bar_callback task="+getTaskId());
  }catch(Throwable e){Log.w(TAG,"control_bar_callback_unavailable",e);}
 }
 private void unregisterControlBarLight(){
  Object callback=controlBarCallback;controlBarCallback=null;
  if(callback==null)return;
  try{
   Class<?> mgrCls=Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager");
   Object mgr=mgrCls.getMethod("getInstance").invoke(null);
   Class<?> face=Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager$IEmbeddedWindowCallback");
   mgrCls.getMethod("unregisterEmbeddedWindowContainerCallback",face,int.class).invoke(mgr,callback,getTaskId());
  }catch(Throwable e){Log.w(TAG,"control_bar_callback_release_failed",e);}
 }
 private void detachCaption(){
  if(!captionAttached||more==null)return;
  try{getWindowManager().removeViewImmediate(more);}catch(Throwable ignored){}
  captionAttached=false;
 }
 private void layoutCaption(){
  if(more==null||slots.isEmpty()||closing||stage==null||stage.getWidth()<=0){detachCaption();return;}
  CardLayout.Result geometry=cardGeometry();
  if(geometry.cards.length==0){detachCaption();return;}
  int[] main=geometry.cards[Math.min(primary,geometry.cards.length-1)];
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  float scale=ControlBarMetrics.scale(main[2],main[3],dm.widthPixels,dm.heightPixels);
  more.setLightContent(primaryLightContent());
  int w=ControlBarMetrics.hit(ControlBarMetrics.ASSET_W,dm.density,scale);
  int h=ControlBarMetrics.hit(ControlBarMetrics.ASSET_H,dm.density,scale);
  int[] loc=new int[2];stage.getLocationInWindow(loc);
  int x=loc[0]+main[0]+Math.max(0,(main[2]-w)/2);
  int y=loc[1]+main[1];
  WindowManager.LayoutParams lp;
  if(!captionAttached){
   android.os.IBinder token=getWindow().getDecorView().getWindowToken();
   if(token==null){stage.post(this::layoutCaption);return;}
   lp=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,android.graphics.PixelFormat.TRANSLUCENT);
   lp.token=token;lp.gravity=Gravity.TOP|Gravity.LEFT;lp.setTitle("WindowDeckCaption");lp.x=x;lp.y=y;
   try{getWindowManager().addView(more,lp);captionAttached=true;Log.i(TAG,"caption_attached x="+x+" y="+y+" w="+w+" h="+h+" scale="+scale);}
   catch(Throwable e){Log.w(TAG,"caption_attach_failed",e);return;}
  }else{
   lp=(WindowManager.LayoutParams)more.getLayoutParams();
   if(lp.x==x&&lp.y==y&&lp.width==w&&lp.height==h)return;
   lp.x=x;lp.y=y;lp.width=w;lp.height=h;
   try{getWindowManager().updateViewLayout(more,lp);}catch(Throwable e){Log.w(TAG,"caption_update_failed",e);}
  }
 }
 private void layoutAddCard(int[] box){
  addCard.setVisibility(box==null?View.GONE:View.VISIBLE);
  if(box!=null){
   FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(box[2],box[3]);lp.leftMargin=box[0];lp.topMargin=box[1];addCard.setLayoutParams(lp);
   clipSide(addCard,box[2],box[3],true);
  }
  layoutCaption();
 }
 private void inputRole(Slot s,boolean isPrimary){
  // The entrance must not hand the main card's touches to its task while the plate is still
  // moving — but it has no reason to hold the whole rail, because side cards are previews
  // either way. Both flags now drop at the first committed entrance frame rather than at the
  // end of the animation (TODO D3); finishArrival() keeps the 1500 ms escape hatch.
  boolean blocked=taskRotationRun!=null||switching()||switchPreparation.pending()||recovering||handoffPresentation;
  boolean entranceRunning=entranceInputBlocked||animation!=null||addAnimation!=null;
  boolean preview=!isPrimary||blocked||entranceRunning;s.card.preview=preview;
  s.card.gesturesEnabled=taskRotationRun==null&&!handoffPresentation&&TransitionPolicy.gesturesEnabled(isPrimary,recovering,entranceRunning,entranceInputBlocked);s.card.vertical=effectiveMode()==PaneLayout.TOP_BOTTOM;s.pin.setVisibility(s.pinned?View.VISIBLE:View.GONE);
  s.card.setContentDescription(s.label+(isPrimary?"主应用，顶部菜单":"，点击切换，长按管理"));
  s.icon.setVisibility(isPrimary?View.GONE:View.VISIBLE);
  applyCardRadius(s,isPrimary);
  try{if(interceptInput.getBoolean(s.surface)!=preview){interceptInput.setBoolean(s.surface,preview);s.surface.requestLayout();}}catch(Throwable e){fail(e);}
 }
 private int cardRadius(boolean isPrimary){return Ui.dp(this,isPrimary?Ui.MAIN_RADIUS:Ui.SIDE_RADIUS);}
 private void applyCardRadius(Slot s,boolean isPrimary){
  if(s.card==null)return;
  if(s.motionPose!=null){motionOutline(s);return;}

  int radius=cardRadius(isPrimary);
  s.renderRadius=radius;
  if(isPrimary){
   if(s.card.getBackground() instanceof android.graphics.drawable.GradientDrawable)((android.graphics.drawable.GradientDrawable)s.card.getBackground()).setCornerRadius(radius);
   else s.card.setBackground(Ui.bg(Ui.CARD,radius));
  }else if(s.card.getBackground()!=null)s.card.setBackground(null);
  clipSide(s.card,s.card.getWidth(),s.card.getHeight(),!isPrimary);
  if(s.surface==null||cornerRadius==null)return;
  Ui.round(s.surface,radius);
  try{
   if(Math.abs(cornerRadius.getFloat(s.surface)-radius)<0.5f)return;
   cornerRadius.setFloat(s.surface,radius);
   viewApi.getMethod("setCornerRadius",float.class).invoke(s.surface,(float)radius);
  }catch(Throwable e){Log.w(TAG,"corner_radius_failed slot="+s.id,e);}
 }
 private void updateNaturalBounds(){
  if(slots.isEmpty()||stage.getWidth()<100||stage.getHeight()<100)return;
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  int shortEdge=Math.max(1,Math.min(dm.widthPixels,dm.heightPixels));
  int longEdge=Math.max(1,Math.max(dm.widthPixels,dm.heightPixels));
  naturalBounds.set(0,0,shortEdge,longEdge);
  for(Slot s:slots)updateRenderBounds(s);
  CanvasImeBridge.refresh(this);
 }
 private void updateRenderBounds(Slot s){
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  int[] size=OrientationPolicy.bounds(naturalBounds.width(),naturalBounds.height(),dm.widthPixels,dm.heightPixels,s.orientationAxis);
  if(s.renderBounds.width()!=size[0]||s.renderBounds.height()!=size[1]){s.renderBounds.set(0,0,size[0],size[1]);Log.i(TAG,"render_bounds slot="+s.id+" axis="+s.orientationAxis+" size="+size[0]+"x"+size[1]);}
 }
 private void resizeSurface(Slot s){
  if(s.surface==null||s.released||s.renderBounds.isEmpty())return;
  boolean measure=switching();long start=measure?System.nanoTime():0;
  if(s.resizedTask!=s.taskId||!s.resizedBounds.equals(s.renderBounds)){
   try{resizeMethod.invoke(s.surface,new Rect(s.renderBounds));s.resizedBounds.set(s.renderBounds);s.resizedTask=s.taskId;}
   catch(Throwable e){fail(e);}
   finally{if(measure){resizeCalls++;resizeNanos+=System.nanoTime()-start;}}
  }
  int[] plate=surfacePlate(s);scheduleLeashFit(s,plate[0],plate[1]);
 }
 private void reparentSurface(Slot s){
  if(s.surface==null||s.released||!s.embedded||stopped||backgrounded)return;
  try{Method method=viewApi.getDeclaredMethod("reparent");method.setAccessible(true);method.invoke(s.surface);s.surface.setBackground(null);
   // reparent may return early while a task is being restored. Reapply our
   // display transform explicitly, including when the plate was recreated.
   try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){if(writeLeashFit(s,fit)){if(s.motionPose!=null||(poseHeld&&s==poseSlot)||(taskRotationRun!=null&&taskRotationRun.slot==s&&taskRotationRun.changingLayout)||(rotationTransactions!=null&&rotationTransactions.pending())){submitMotionFit(fit);stage.invalidate();}else fit.apply();}}}
  catch(Throwable e){Log.w(TAG,"surface_sync_failed slot="+s.id,e);}
 }
 private void syncSurface(Slot s){
  if(closing||s.released||s.surface==null||s.taskId<0)return;
  reparentSurface(s);
  int[] plate=surfacePlate(s);scheduleLeashFit(s,plate[0],plate[1]);
 }
 private int[] surfacePlate(Slot s){
  ViewGroup.LayoutParams lp=s.surface.getLayoutParams();
  int w=lp!=null&&lp.width>0?lp.width:s.surface.getWidth();
  int h=lp!=null&&lp.height>0?lp.height:s.surface.getHeight();
  return new int[]{Math.max(1,w),Math.max(1,h)};
 }
 // A transient pause while the task takes focus must not cancel the live rotation pose.
 // A real background transition still fails and clears the handoff in onStop().
 @Override protected void onPause(){immersiveForeground=false;handler.removeCallbacks(immersivePoll);if(systemRotationObserver!=null)systemRotationObserver.close();dismissPrimaryMenu();cancelDrag();if(!screenArrival&&!deferPortrait&&taskRotationRun==null)cancelAnimation();if(initialized&&!closing&&!screenArrival&&!deferPortrait&&taskRotationRun==null)layoutCards(false);super.onPause();}
 @Override protected void onResume(){super.onResume();if(rotationTransactions!=null)rotationTransactions.connect();CanvasImeBridge.start(this);immersiveForeground=true;if(systemRotationObserver==null)systemRotationObserver=new SystemRotationObserver(this,handler,this::queueTaskDirections);systemRotationObserver.start();if(orientationObserver==null){orientationObserver=new TaskOrientationObserver(handler,this::onTaskOrientationRequested);orientationObserver.start();}foreground=true;stopped=false;backgrounded=false;if(initialized&&!closing)publishState(slots.size());if(createdFromGesture&&pendingEntrance!=null)entranceFrontReady=true;if(backdrop!=null)backdrop.onResume();handler.post(this::restorePendingTasks);handler.post(this::consumeHangAdd);handler.post(this::beginHangEntrance);handler.post(this::maybeAnimateAddedCard);handler.post(this::queueTaskDirections);handler.post(immersivePoll);handler.postDelayed(()->{if(stopped||backgrounded||closing)return;for(Slot s:slots)syncSurface(s);focusPrimary("resume");maybeAnimateAddedCard();},350);}
 @Override protected void onStop(){CanvasImeBridge.stop(this);clearArrivalFace("host_stopped");foreground=false;stopped=true;if(taskRotationRun!=null)finishTaskRotation(taskRotationRun,"host_stopped");if(screenArrival||handoffPresentation)cancelAnimation();if(gestureHandoff&&pendingEntrance!=null)failHandoff(pendingEntrance,"host_stopped");for(Slot s:slots){s.drawEvidence.invalidate();clearProjection(s);}backGeneration++;forwardingBack=false;super.onStop();}
 // Do not refocus on every host touch: doing so cancels a preview's click or
 // long-press stream. Resume, settled promotion and dialog dismissal own focus.
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);backgrounded=false;stopped=false;if(intent.hasExtra("windowdeck_add_task_id"))handler.post(()->addExistingTask(intent));Log.i(TAG,"workbench_resume container="+getTaskId()+" primary="+primary+" layout="+layoutMode+" tasks="+taskIds());handler.post(()->{for(Slot s:slots)syncSurface(s);focusPrimary("entry");});}
 private void scheduleLayout(){if(layoutPosted)return;layoutPosted=true;stage.post(()->{layoutPosted=false;if(!closing){cancelDrag();updateNaturalBounds();if(deferPortrait&&poseHeld){if(!poseSettling)expandPosePlate(poseSlot);}else if(screenArrival)rebaseArrivalLayout();else{if(taskRotationRun==null||!taskRotationRun.changingLayout||taskRotationRun.started)cancelAnimation();layoutCards(false);}for(Slot s:slots)resizeSurface(s);requestEntranceFrame();Log.i(TAG,"layout width="+stage.getWidth()+" height="+stage.getHeight()+" ime="+imeBottom+" count="+slots.size());scheduleLayoutEvidence("layout");}});}
 private void fitSurface(Slot s,int width,int height){
  if(s.surface==null||s.renderBounds.isEmpty())return;
  boolean rotate=rotatePresentation(s);
  boolean preview=slots.indexOf(s)!=primary;
  // Size the SurfaceView to the plate and let the task leash map into it.
  // Scaling the full-size SurfaceView overflows; FlexibleTaskView's parent
  // surface cannot clip that. Use the ROM's rotate-leash flag for games.
  try{
   if(rotateTaskLeash.getBoolean(s.surface)!=rotate){rotateTaskLeash.setBoolean(s.surface,rotate);s.surface.post(()->syncSurface(s));}
   if(reparentAlign!=null)reparentAlign.setInt(s.surface,preview&&!rotate?2:0);
  }catch(Throwable e){fail(e);return;}
  int w=Math.max(1,width),h=Math.max(1,height);
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)s.surface.getLayoutParams();
  if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;s.surface.setLayoutParams(lp);}
  s.surface.setPivotX(0);s.surface.setPivotY(0);s.surface.setScaleX(1);s.surface.setScaleY(1);
  s.surface.setTranslationX(0);s.surface.setTranslationY(0);
  s.surface.setClipBounds(s.motionPose==null&&preview&&effectiveMode()!=PaneLayout.LEFT_RIGHT?new Rect(0,0,w,h):null);
  fitRecoveryCover(s,w,h);
  scheduleLeashFit(s,w,h);
 }
 /** Target quad for a side plate. Without the switch, or without the ROM's
  *  projective leash API, the plate stays a plain rectangle. With it, the
  *  left-right rail shares one vertical vanishing point across the column, and
  *  the top-bottom rail tapers its bottom edge toward the main window below. */
 private float[] displayQuad(Slot s,int w,int h){
  if(s.motionPose!=null)return CardMotion.localQuad(s.motionPose,w,h);
  return roleQuad(w,h,s.card.getTop(),slots.indexOf(s)!=primary);
 }
 private float[] roleQuad(int w,int h,float top,boolean preview){
  if(!AtomicPresentation.perspective(atomicRotate,preview)||leashMatrix4==null)
   return new float[]{0,0,w,0,w,h,0,h};
  float density=getResources().getDisplayMetrics().density;
  if(effectiveMode()==PaneLayout.LEFT_RIGHT)
   return CardPerspective.columnQuad(w,h,density,stage.getHeight()/2f-top);
  return CardPerspective.quad(w,h,effectiveMode(),density);
 }
 private CardMotion.Pose rolePose(Slot s,float[] box,boolean preview){
  int[] src=presentationSize(s),crop=preview?SurfaceFit.coverCrop(src[0],src[1],Math.round(box[2]),Math.round(box[3])):new int[]{0,0,src[0],src[1]};
  return CardMotion.at(box,roleQuad(Math.round(box[2]),Math.round(box[3]),box[1],preview),
   new float[]{crop[0],crop[1],crop[2],crop[3]},cardRadius(!preview));
 }
 private float[] cardBox(Slot s){return new float[]{s.card.getX(),s.card.getY(),Math.max(1,s.card.getWidth()*s.card.getScaleX()),Math.max(1,s.card.getHeight()*s.card.getScaleY())};}
 private CardMotion.Pose currentPose(Slot s){
  if(s.released||!s.embedded||s.surface==null)return null;
  float[] box=cardBox(s);
  CardMotion.Pose pose=s.motionPose!=null?s.motionPose:s.fittedPose;
  if(s.motionPose==null){
   try{if(s.fittedTask!=s.taskId||s.fittedAxis!=s.orientationAxis||!s.fittedBounds.equals(s.renderBounds)||s.fittedLayer!=LiveTaskContent.layerId((SurfaceControl)taskLeash.get(s.surface)))pose=null;}
   catch(Throwable e){pose=null;}
  }
  return pose==null?null:CardMotion.rebox(pose,box);
 }
 private CardMotion.Pose surfacePose(Slot s,int w,int h){
  if(s.motionPose!=null)return s.motionPose;
  boolean preview=slots.indexOf(s)!=primary;
  float[] box=cardBox(s),quad=displayQuad(s,w,h);int[] src=presentationSize(s);
  int[] crop=preview?SurfaceFit.coverCrop(src[0],src[1],w,h):new int[]{0,0,src[0],src[1]};
  for(int i=0;i<8;i+=2){quad[i]*=box[2]/Math.max(1,w);quad[i+1]*=box[3]/Math.max(1,h);}
  float radius=(s.renderRadius<0?cardRadius(!preview):s.renderRadius)*Math.min(s.card.getScaleX(),s.card.getScaleY());
  return CardMotion.at(box,quad,new float[]{crop[0],crop[1],crop[2],crop[3]},radius);
 }
 private void fitCover(Slot s,RecoveryCover cover,int w,int h){
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)cover.getLayoutParams();
  if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;cover.setLayoutParams(lp);}
  int[] src=presentationSize(s);
  CardMotion.Pose pose=surfacePose(s,w,h);
  cover.fit(CardMotion.sourceQuad(pose,src[0],rotatePresentation(s)),displayQuad(s,w,h),s.renderBounds.width(),s.renderBounds.height(),CardMotion.sourceRadius(pose));
 }
 private void fitRecoveryCover(Slot s,int w,int h){
  if(s.recoveryCover!=null)fitCover(s,s.recoveryCover,w,h);
  if(arrivalFaceSlot==s&&arrivalFace!=null)fitCover(s,arrivalFace,w,h);
 }
 private void scheduleLeashFit(Slot s,int w,int h){
  if(s.surface==null||s.fitPosted)return;
  s.fitPosted=true;
  s.surface.post(()->{s.fitPosted=false;if(!closing&&!s.released&&s.surface!=null)reparentSurface(s);});
 }
 private static float[] identity4(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
 private void clearProjection(Slot s){
  SurfaceControl leash=s.projectedLeash;
  if(leash==null||!leash.isValid()||leashMatrix4==null)return;
  try(SurfaceControl.Transaction reset=new SurfaceControl.Transaction()){
   // Only reset our projective matrix. The system owns fullscreen scale/position.
   leashMatrix4.invoke(reset,leash,(Object)identity4());reset.apply();s.projectedLeash=null;s.perspectiveApplied=false;
  }catch(Throwable e){Log.w(TAG,"projection_clear_failed slot="+s.id,e);}
 }
 /** Bootstrap the destination's first frame from the release snapshot. This is visual
  *  continuity only: live content is still required by awaitCoverPoseCommit. */
 private void requestCoverPose(Slot slot){
  if(!deferPortrait||poseQuerySent||portraitAfterPose||handoffReady==null||slot==null||slot.released||slot.sourceTaskId<0)return;
  // Multiple task callbacks may ask; reserve this wait before posting it.
  poseQuerySent=true;
  Runnable wait=new Runnable(){public void run(){
   if(!deferPortrait||portraitAfterPose||closing||slot.released)return;
   if(SystemClock.uptimeMillis()>=handoffDeadline){failHandoff(slot,"pose_content_timeout");return;}
   poseSlot=slot;
   Log.i(TAG,"defer_snapshot_pose_query task="+slot.sourceTaskId+" before_live=true");
   poseReply=new android.os.ResultReceiver(handler){
    @Override protected void onReceiveResult(int code,Bundle data){
     if(!deferPortrait||closing||slot.released)return;
     if(code==HandoffProtocol.POSE_QUERY){if(!poseHeld&&!portraitAfterPose)applyCoverPose(slot,data);}
     else if(code==HandoffProtocol.SOURCE_RELEASED){
      if(data==null||data.getInt(HandoffProtocol.TASK,-1)!=slot.sourceTaskId||sourceRelease==null||!sourceRelease.completeRelease())return;
      Log.i(TAG,"defer_source_released task="+slot.sourceTaskId+" bootstrap_committed=true");
      if(!initialized){failHandoff(slot,"source_release_before_layout");return;}
      for(Slot s:new ArrayList<>(slots))if(s.card==null)createWindow(s);
      expandPosePlate(slot);if(slot.card!=null)motionOutline(slot);scheduleLayout();awaitCoverPoseCommit(slot);
     }
     else if(code==HandoffProtocol.OWNERSHIP)requestPortraitAfterPose("ownership");
     else failHandoff(slot,"pose_rejected_"+code);
    }
   };
   Bundle ask=new Bundle();ask.putInt(HandoffProtocol.TASK,slot.sourceTaskId);
   ask.putParcelable(HandoffProtocol.POSE_REPLY,HandoffProtocol.transport(poseReply));
   try{handoffReady.send(HandoffProtocol.POSE_QUERY,ask);}
   catch(Throwable e){Log.w(TAG,"defer_pose_query_failed",e);failHandoff(slot,"pose_query_failed");}
  }};
  handler.post(wait);
 }
 private void applyCoverPose(Slot slot,Bundle data){
  // The first frame is a snapshot only. Constructing FlexibleTaskView here would
  // let it steal the task while Launcher still owns the recents leash.
  if(stage==null||getWindow().getDecorView().getWidth()<2||getWindow().getDecorView().getHeight()<2){
   if(SystemClock.uptimeMillis()>=handoffDeadline){failHandoff(slot,"pose_bootstrap_timeout");return;}
   handler.postDelayed(()->{if(deferPortrait&&!closing&&!slot.released&&!poseHeld)applyCoverPose(slot,data);},MotionSpec.SURFACE_RETRY_MS);return;
  }
  float[] corners=data==null?null:data.getFloatArray(HandoffProtocol.CORNERS);
  float[] crop=data==null?null:data.getFloatArray(HandoffProtocol.CROP);
  int[] space=data==null?null:data.getIntArray(HandoffProtocol.POSE_STAGE);
  float radius=data==null?Float.NaN:data.getFloat(HandoffProtocol.RADIUS,Float.NaN);
  if(data==null||data.getInt(HandoffProtocol.TASK,-1)!=slot.sourceTaskId||corners==null||corners.length!=8
    ||crop==null||crop.length!=4||space==null||space.length!=3||space[0]<2||space[1]<=space[0]
    ||space[2]!=deferSourceRotation||!Float.isFinite(radius)||radius<0){failHandoff(slot,"pose_malformed");return;}
  for(float point:corners)if(!Float.isFinite(point)){failHandoff(slot,"pose_nonfinite");return;}
  Bitmap snapshot=null;
  android.hardware.HardwareBuffer shared=data.getParcelable(HandoffProtocol.SNAPSHOT,android.hardware.HardwareBuffer.class);
  try{
   if(shared!=null&&(shared.getUsage()&android.hardware.HardwareBuffer.USAGE_PROTECTED_CONTENT)==0){
    android.graphics.ParcelableColorSpace color=data.getParcelable(HandoffProtocol.SNAPSHOT_COLOR_SPACE,android.graphics.ParcelableColorSpace.class);
    snapshot=Bitmap.wrapHardwareBuffer(shared,color==null?android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB):color.getColorSpace());
   }
  }catch(Throwable e){Log.w(TAG,"defer_snapshot_buffer_failed",e);}
  finally{if(shared!=null)shared.close();}
  float[] pixels=data.getFloatArray(HandoffProtocol.SNAPSHOT_CROP);
  float[] snapshotSource=snapshot==null||snapshot.isRecycled()?null:DisplayGeometry.snapshotQuad(pixels,snapshot.getWidth(),snapshot.getHeight());
  if(snapshotSource==null){if(snapshot!=null&&!snapshot.isRecycled())snapshot.recycle();failHandoff(slot,"pose_snapshot_invalid");return;}
  heldCorners=corners.clone();heldCrop=crop.clone();heldStage=space.clone();heldRadius=radius;
  poseHeld=true;poseSlot=slot;
  poseBridgeBitmap=snapshot;poseBridgeSource=snapshotSource;
  poseBridge=new RecoveryCover(this);poseBridge.setBitmap(snapshot);
  ((ViewGroup)getWindow().getDecorView()).getOverlay().add(poseBridge);
  fitPoseBridge();
  Log.i(TAG,"defer_snapshot_bridge task="+slot.sourceTaskId+" source=launcher_release live=false size="+snapshot.getWidth()+"x"+snapshot.getHeight());
  expandPosePlate(slot);if(slot.card!=null)motionOutline(slot);
  poseFitListener=()->{
   if(poseHeld&&!closing&&!stopped&&!backgrounded){
    fitPoseBridge();
    if(slot.surface!=null)try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
     if(writeHeldPose(slot,fit))submitMotionFit(fit);
    }catch(Throwable e){Log.w(TAG,"defer_pose_frame_fit_failed",e);}
   }
   return true;
  };
  stage.getViewTreeObserver().addOnPreDrawListener(poseFitListener);
  Log.i(TAG,"defer_pose_received stage="+Arrays.toString(heldStage)+" quad="+Arrays.toString(heldCorners)
    +" capture_crop="+Arrays.toString(heldCrop)+" task_buffer="+taskBuffer(slot));
  if(waitingForSourceRelease())awaitSourceBootstrap(slot);else awaitCoverPoseCommit(slot);
 }
 private boolean waitingForSourceRelease(){return deferPortrait&&sourceRelease!=null&&!sourceRelease.mayEmbed();}
 private void awaitSourceBootstrap(Slot slot){
  if(!waitingForSourceRelease()||!poseHeld||closing||stopped||slot.released||sourceBootstrapFramePending)return;
  if(SystemClock.uptimeMillis()>=handoffDeadline){failHandoff(slot,"source_bootstrap_timeout");return;}
  fitPoseBridge();
  if(!poseBridgeFitted||backdrop==null||!backdrop.ready||stage.isLayoutRequested()){
   handler.postDelayed(()->awaitSourceBootstrap(slot),MotionSpec.SURFACE_RETRY_MS);return;
  }
  sourceBootstrapFramePending=true;
  stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   sourceBootstrapFramePending=false;
   if(!waitingForSourceRelease()||closing||stopped||backgrounded||slot.released)return;
   if(!poseBridgeFitted||poseBridge==null||!backdrop.ready||stage.isLayoutRequested()){
    awaitSourceBootstrap(slot);return;
   }
   if(!sourceRelease.commitBootstrap()||!sourceRelease.requestRelease())return;
   Bundle done=new Bundle();done.putInt(HandoffProtocol.TASK,slot.sourceTaskId);
   done.putParcelable(HandoffProtocol.POSE_REPLY,HandoffProtocol.transport(poseReply));
   Log.i(TAG,"defer_bootstrap_committed task="+slot.sourceTaskId+" live=false embed_started=false");
   if(handoffReady==null||poseReply==null){failHandoff(slot,"source_release_reply_missing");return;}
   handoffReady.send(HandoffProtocol.BOOTSTRAP_COMMITTED,done);
  }));getWindow().getDecorView().invalidate();stage.invalidate();
 }
 private void fitPoseBridge(){
  poseBridgeFitted=false;
  if(poseBridge==null||poseBridgeBitmap==null||heldStage==null)return;
  View decor=getWindow().getDecorView();int[] loc=new int[2];decor.getLocationOnScreen(loc);
  float[] local=DisplayGeometry.localQuad(heldCorners,heldStage[0],heldStage[1],heldStage[2],decor.getWidth(),decor.getHeight(),loc[0],loc[1]);
  if(local==null)return;
  poseBridge.layout(0,0,decor.getWidth(),decor.getHeight());
  // SurfaceView's native background may exist beyond the fitted task during
  // its first resize. Keep the whole bootstrap scene above that background.
  poseBridge.setBackdrop(backdrop==null?null:backdrop.currentBitmap());
  Matrix map=new Matrix();if(!map.setPolyToPoly(poseBridgeSource,0,local,0,4))return;
  float[] m=new float[9];map.getValues(m);
  float scale=(float)Math.max(0.01d,Math.max(Math.hypot(m[0],m[3]),Math.hypot(m[1],m[4])));
  poseBridge.fit(poseBridgeSource,local,poseBridgeBitmap.getWidth(),poseBridgeBitmap.getHeight(),heldRadius/scale);
  poseBridgeFitted=true;
 }
 private void clearPoseBridge(String reason){
  poseBridgeFitted=false;
  if(poseBootstrapListener!=null&&stage!=null){stage.getViewTreeObserver().removeOnPreDrawListener(poseBootstrapListener);poseBootstrapListener=null;}
  if(poseBridge==null)return;
  RecoveryCover cover=poseBridge;poseBridge=null;
  ((ViewGroup)getWindow().getDecorView()).getOverlay().remove(cover);cover.setBitmap(null);
  Bitmap bitmap=poseBridgeBitmap;poseBridgeBitmap=null;poseBridgeSource=null;
  if(bitmap!=null)handler.postDelayed(()->{if(!bitmap.isRecycled())bitmap.recycle();},1000);
  Log.i(TAG,"defer_snapshot_reveal result="+reason+" task="+(poseSlot==null?-1:poseSlot.taskId));
 }
 /** Full display plate: the inverse portrait pose can lie above the stage's status-bar
  *  inset. Expanding only to content bounds would clip it before rotation. */
 private void expandPosePlate(Slot slot){
  if(slot==null||stage==null||slot.card==null||slot.surface==null)return;
  View decor=getWindow().getDecorView();int w=decor.getWidth(),h=decor.getHeight();
  if(w<2||h<2)return;
  int[] stageLocation=new int[2],windowLocation=new int[2];
  stage.getLocationOnScreen(stageLocation);decor.getLocationOnScreen(windowLocation);
  stage.setClipChildren(false);stage.setClipToPadding(false);
  if(stage.getParent() instanceof ViewGroup){ViewGroup parent=(ViewGroup)stage.getParent();parent.setClipChildren(false);parent.setClipToPadding(false);}
  FrameLayout.LayoutParams card=(FrameLayout.LayoutParams)slot.card.getLayoutParams();
  if(card==null)card=new FrameLayout.LayoutParams(w,h);
  int x=windowLocation[0]-stageLocation[0],y=windowLocation[1]-stageLocation[1];
  if(card.width!=w||card.height!=h||card.leftMargin!=x||card.topMargin!=y){
   card.width=w;card.height=h;card.leftMargin=x;card.topMargin=y;slot.card.setLayoutParams(card);
  }
  FrameLayout.LayoutParams plate=(FrameLayout.LayoutParams)slot.surface.getLayoutParams();
  if(plate==null)plate=new FrameLayout.LayoutParams(w,h);
  if(plate.width!=w||plate.height!=h){plate.width=w;plate.height=h;slot.surface.setLayoutParams(plate);}
  slot.card.setClipToOutline(false);slot.card.setClipChildren(false);
 }
 /** Identity includes current buffer, viewport, plate location and actual child content.
  *  No capture-space crop is used as a substitute for a task buffer. */
 private String poseSurfaceKey(Slot slot){
  if(closing||stopped||backgrounded||slot==null||slot.released||slot.taskId!=slot.sourceTaskId
    ||slot.surface==null||slot.card==null||slot.surface.isLayoutRequested()||slot.card.isLayoutRequested()
    ||stage==null||stage.isLayoutRequested()||layoutPosted||(backdrop!=null&&!backdrop.ready))return null;
  Rect buffer=taskBuffer(slot);
  if(buffer==null||buffer.width()!=slot.renderBounds.width()||buffer.height()!=slot.renderBounds.height())return null;
  try{
   SurfaceControl leash=(SurfaceControl)taskLeash.get(slot.surface),plate=((SurfaceView)slot.surface).getSurfaceControl();
   if(leash==null||!leash.isValid()||plate==null||!plate.isValid())return null;
   taskDrawState(slot);
   if(!slot.drawEvidence.hasContent()){probeLandscapePresented(slot,leash);return null;}
   View decor=getWindow().getDecorView();int[] loc=new int[2];slot.surface.getLocationOnScreen(loc);
   return slot.taskId+":"+LiveTaskContent.layerId(leash)+":"+LiveTaskContent.layerId(plate)+":"+buffer
     +":"+slot.surface.getWidth()+"x"+slot.surface.getHeight()+":"+Arrays.toString(loc)
     +":"+decor.getWidth()+"x"+decor.getHeight()+":"+(backdrop==null?0:backdrop.visualGeneration());
  }catch(Throwable e){Log.w(TAG,"defer_live_pose_unavailable",e);return null;}
 }
 private void awaitCoverPoseCommit(Slot slot){
  if(!deferPortrait||!poseHeld||poseReported||closing||slot.released)return;
  if(SystemClock.uptimeMillis()>=handoffDeadline){failHandoff(slot,"pose_frame_timeout");return;}
  View decor=getWindow().getDecorView();
  String key=poseSurfaceKey(slot);
  if(key==null||slot.surface.getWidth()!=decor.getWidth()||slot.surface.getHeight()!=decor.getHeight()){
   handler.postDelayed(()->awaitCoverPoseCommit(slot),MotionSpec.SURFACE_RETRY_MS);return;
  }
  try(SurfaceControl.Transaction committed=new SurfaceControl.Transaction()){
   if(!writeHeldPose(slot,committed))throw new IllegalStateException("No live pose fit");
   committed.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!deferPortrait||!poseHeld||poseReported||closing||slot.released)return;
    stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->
     stage.postOnAnimation(()->stage.postOnAnimation(()->{
      if(!deferPortrait||!poseHeld||poseReported||closing||slot.released)return;
      if(!key.equals(poseSurfaceKey(slot))){awaitCoverPoseCommit(slot);return;}
      poseReported=true;logHeldLayer("defer_live_pose_committed");
      Bundle done=new Bundle();done.putInt(HandoffProtocol.TASK,slot.sourceTaskId);
      done.putParcelable(HandoffProtocol.POSE_REPLY,HandoffProtocol.transport(poseReply));
      if(handoffReady==null||poseReply==null){failHandoff(slot,"pose_reply_missing");return;}
      handoffReady.send(HandoffProtocol.POSE_COMMITTED,done);
     }))));stage.invalidate();
   });submitMotionFit(committed);stage.invalidate();
  }catch(Throwable e){Log.w(TAG,"defer_pose_apply_failed",e);failHandoff(slot,"pose_apply_failed");}
 }
 private void requestPortraitAfterPose(String reason){
  if(portraitAfterPose||!deferPortrait)return;
  if(!poseHeld||!poseReported||poseSlot==null){failHandoff(pendingEntrance,"pose_ownership_not_ready");return;}
  portraitAfterPose=true;
  Log.i(TAG,"canvas_orientation retained=portrait after="+reason+" layer="+heldLayer);
  awaitPortraitPose(poseSlot);
 }
 private void awaitPortraitPose(Slot slot){
  if(!deferPortrait||!poseHeld||closing||slot.released)return;
  if(SystemClock.uptimeMillis()>=handoffDeadline){failHandoff(slot,"pose_rotation_timeout");return;}
  View decor=getWindow().getDecorView();
  boolean portrait=getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT
    &&decor.getWidth()==heldStage[0]&&decor.getHeight()==heldStage[1]
    &&stage.getWidth()>0&&stage.getWidth()<stage.getHeight();
  if(!portrait){handler.postDelayed(()->awaitPortraitPose(slot),MotionSpec.SURFACE_RETRY_MS);return;}
  if(!poseSettling){
   poseSettling=true;layoutCards(false);
   Log.i(TAG,"defer_portrait_layout task="+slot.taskId+" quad="+Arrays.toString(heldCorners));
  }
  String key=handoffLayoutKey();
  if(key==null||poseSurfaceKey(slot)==null){handler.postDelayed(()->awaitPortraitPose(slot),MotionSpec.SURFACE_RETRY_MS);return;}
  // The normal final card now has the same display-space quad as the held pose.
  // Exchange the writer and fit with this host draw, without cancelling the handoff.
  poseHeld=false;removePoseFitListener();
  commitPortraitPose(slot);
 }
 private void commitPortraitPose(Slot slot){
  if(!deferPortrait||closing||stopped||slot.released)return;
  if(SystemClock.uptimeMillis()>=handoffDeadline){failHandoff(slot,"pose_final_frame_timeout");return;}
  String key=handoffLayoutKey();
  if(key==null||poseSurfaceKey(slot)==null){handler.postDelayed(()->commitPortraitPose(slot),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   if(!writeLeashFit(slot,fit))throw new IllegalStateException("No final portrait fit");
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!deferPortrait||closing||slot.released)return;
    stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->
     stage.postOnAnimation(()->stage.postOnAnimation(()->{
      if(!deferPortrait||closing||slot.released)return;
      if(!key.equals(handoffLayoutKey())||poseSurfaceKey(slot)==null){commitPortraitPose(slot);return;}
      revealPortraitPose(slot,key);
     }))));stage.invalidate();
   });submitMotionFit(fit);stage.invalidate();
  }catch(Throwable e){Log.w(TAG,"defer_portrait_fit_failed",e);failHandoff(slot,"pose_final_fit_failed");}
 }
 private void revealPortraitPose(Slot slot,String key){
  if(poseBridge==null){failHandoff(slot,"pose_bridge_missing");return;}
  poseBridge.setVisibility(View.INVISIBLE);
  stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   if(!deferPortrait||closing||stopped||slot.released)return;
   if(!key.equals(handoffLayoutKey())||poseSurfaceKey(slot)==null){
    if(poseBridge!=null)poseBridge.setVisibility(View.VISIBLE);
    commitPortraitPose(slot);return;
   }
   clearPoseBridge("live_frame_committed");deferPortrait=false;restorePoseClipping();logHeldLayer("defer_portrait_live_committed");
   slot.windowDrawn=true;entranceFramePending=false;entranceFrameReady=true;
   Log.i(TAG,"handoff_layout_committed task="+slot.taskId+" cards="+slots.size()+" axis="+slot.orientationAxis+" pose_rotation=true");
   maybeAnimateAddedCard();
  }));getWindow().getDecorView().invalidate();stage.invalidate();
 }
 private void removePoseFitListener(){
  if(poseFitListener!=null&&stage!=null){stage.getViewTreeObserver().removeOnPreDrawListener(poseFitListener);poseFitListener=null;}
 }
 private void restorePoseClipping(){
  if(stage!=null){stage.setClipChildren(true);stage.setClipToPadding(true);
   if(stage.getParent() instanceof ViewGroup){ViewGroup parent=(ViewGroup)stage.getParent();parent.setClipChildren(true);parent.setClipToPadding(true);}}
  if(poseSlot!=null&&poseSlot.card!=null){applyCardRadius(poseSlot,slots.indexOf(poseSlot)==primary);clipSide(poseSlot.card,poseSlot.card.getWidth(),poseSlot.card.getHeight(),slots.indexOf(poseSlot)!=primary);}
 }
 private void clearHeldPose(){if(sourceRelease!=null)sourceRelease.cancel();sourceBootstrapFramePending=false;poseHeld=false;removePoseFitListener();clearPoseBridge("cancelled");restorePoseClipping();}
 private Rect taskBuffer(Slot slot){
  try{
   Field info=viewApi.getDeclaredField("mTaskInfo");info.setAccessible(true);
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
 private boolean writeHeldPose(Slot s,SurfaceControl.Transaction t){
  try{
   SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface),plate=((SurfaceView)s.surface).getSurfaceControl();
   Rect buffer=taskBuffer(s);View decor=getWindow().getDecorView();
   if(leash==null||!leash.isValid()||plate==null||!plate.isValid()||buffer==null||heldStage==null
     ||buffer.width()!=s.renderBounds.width()||buffer.height()!=s.renderBounds.height())return false;
   int[] loc=new int[2];s.surface.getLocationOnScreen(loc);
   float[] local=DisplayGeometry.localQuad(heldCorners,heldStage[0],heldStage[1],heldStage[2],decor.getWidth(),decor.getHeight(),loc[0],loc[1]);
   if(local==null)return false;
   float[] src={0,0,buffer.width(),0,buffer.width(),buffer.height(),0,buffer.height()};
   Matrix map=new Matrix();if(!map.setPolyToPoly(src,0,local,0,4))return false;
   float[] m=new float[9];map.getValues(m);
   try{Field bg=SurfaceView.class.getDeclaredField("mBackgroundControl");bg.setAccessible(true);SurfaceControl background=(SurfaceControl)bg.get(s.surface);if(background!=null&&background.isValid())t.setVisibility(background,false);}catch(Throwable ignored){}
   if(leashRadius!=null)leashRadius.invoke(t,plate,0f);
   t.setCrop(plate,new Rect(0,0,Math.max(1,s.surface.getWidth()),Math.max(1,s.surface.getHeight())));
   t.setCrop(leash,buffer);
   if(leashMatrix==null)return false;
   leashMatrix.invoke(t,leash,m[0],m[3],m[1],m[4]);t.setPosition(leash,m[2],m[5]);
   float scale=(float)Math.max(0.01d,Math.max(Math.hypot(m[0],m[3]),Math.hypot(m[1],m[4])));
   if(leashRadius!=null)leashRadius.invoke(t,leash,heldRadius/scale);
   t.setAlpha(leash,1f);t.setVisibility(leash,true);t.setAlpha(plate,1f);t.setVisibility(plate,true);
   heldLayer=LiveTaskContent.layerId(leash);s.fittedLayer=heldLayer;s.fittedTask=s.taskId;
   return true;
  }catch(Throwable e){Log.w(TAG,"defer_pose_fit_failed slot="+s.id,e);return false;}
 }
 private void logHeldLayer(String event){
  if(poseSlot==null||poseSlot.surface==null||taskLeash==null){Log.i(TAG,event+" leash=missing");return;}
  try{
   SurfaceControl leash=(SurfaceControl)taskLeash.get(poseSlot.surface);
   SurfaceControl plate=((SurfaceView)poseSlot.surface).getSurfaceControl();
   logLayer(event,poseSlot,leash,plate);
  }catch(Throwable e){Log.w(TAG,event+"_failed",e);}
 }
 private void logLayer(String event,Slot slot,SurfaceControl leash,SurfaceControl plate){
  int leashId=LiveTaskContent.layerId(leash),plateId=LiveTaskContent.layerId(plate);
  if(leashId>=0)heldLayer=leashId;
  Log.i(TAG,event+" task="+(slot==null?-1:slot.taskId)+" leash="+leashId+" name="+leash+" plate="+plateId+" plate_name="+plate+" leash_valid="+(leash!=null&&leash.isValid())+" plate_valid="+(plate!=null&&plate.isValid()));
 }
 private boolean writeLeashFit(Slot s,SurfaceControl.Transaction t){
  if(poseHeld&&s==poseSlot)return writeHeldPose(s,t);
  if(closing||stopped||backgrounded||!s.embedded||s.released||s.surface==null||s.card==null||taskLeash==null)return false;
  int w=s.surface.getWidth(),h=s.surface.getHeight();if(w<2||h<2)return false;
  boolean preview=slots.indexOf(s)!=primary,rotate=rotatePresentation(s);
  try{
   SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface);
   SurfaceControl plate=((SurfaceView)s.surface).getSurfaceControl();
   if(leash==null||!leash.isValid()||plate==null||!plate.isValid())return false;
   t.setCrop(plate,new Rect(0,0,w,h));
   try{Field bg=SurfaceView.class.getDeclaredField("mBackgroundControl");bg.setAccessible(true);SurfaceControl background=(SurfaceControl)bg.get(s.surface);if(background!=null&&background.isValid())t.setVisibility(background,false);}catch(Throwable ignored){}
   float[] target=displayQuad(s,w,h);
   RotationRun rotation=taskRotationRun;
   if(s.renderBounds.isEmpty())return false;
   int[] src=presentationSize(s);
   CardMotion.Pose pose=surfacePose(s,w,h);
   // FlexibleTaskView also rounds the parent SurfaceView plate. During a main→side
   // exchange the retained plate is initially scaled about 5x; its fixed radius
   // would clip the correctly rounded task into an excessively round rectangle.
   // The task leash alone owns the moving outline. Restore the plate's local
   // radius when the pose settles, inside this same atomic fitting transaction.
   if(leashRadius!=null)leashRadius.invoke(t,plate,s.motionPose!=null?0f:
     (float)(s.renderRadius<0?cardRadius(!preview):s.renderRadius));
   float x=pose.crop[0],y=pose.crop[1],r=x+pose.crop[2],b=y+pose.crop[3];
   float[] source=CardMotion.sourceQuad(pose,src[0],rotate);
   Rect rect=rotate?new Rect((int)Math.floor(y),(int)Math.floor(src[0]-r),(int)Math.ceil(b),(int)Math.ceil(src[0]-x)):new Rect((int)Math.floor(x),(int)Math.floor(y),(int)Math.ceil(r),(int)Math.ceil(b));
   t.setCrop(plate,new Rect(0,(int)Math.floor(Math.min(0,target[3])),w,(int)Math.ceil(Math.max(h,target[5]))));
   if(rotation!=null&&rotation.slot==s&&rotation.changingLayout){
    ensureRotationMotion(rotation);
    target=rotation.nativeMotion.target(source,target,rotation.fraction);
    // Official enter does not shrink. The plate crop clips the overflow, the same way the screen does.
    t.setCrop(plate,new Rect(0,0,w,h));
   }
   Matrix map=new Matrix();if(!map.setPolyToPoly(source,0,target,0,4))return false;
   float[] m=new float[9];map.getValues(m);
   boolean perspective=SurfaceFit.hasPerspective(m,source)&&leashMatrix4!=null;
   if(!perspective&&leashMatrix4!=null){leashMatrix4.invoke(t,leash,(Object)identity4());s.projectedLeash=null;}
   s.perspectiveApplied=perspective;
   t.setCrop(leash,rect);
   if(leashRadius!=null)leashRadius.invoke(t,leash,CardMotion.sourceRadius(pose));
   // A native wallpaper underlay fills the SurfaceView hole. No rectangular
   // card-colored layer may remain outside the projective task outline.
   if(perspective){
    Matrix base=new Matrix();base.setPolyToPoly(source,0,new float[]{0,0,w,0,w,h,0,h},0,4);
    float[] affine=new float[9];base.getValues(affine);
    leashMatrix.invoke(t,leash,affine[0],affine[3],affine[1],affine[4]);t.setPosition(leash,affine[2],affine[5]);
    Matrix inverse=new Matrix();if(!base.invert(inverse))return false;
    Matrix tilt=new Matrix();tilt.setConcat(inverse,map);float[] tiltValues=new float[9];tilt.getValues(tiltValues);
    float[] m4={tiltValues[0],tiltValues[1],0,tiltValues[2],tiltValues[3],tiltValues[4],0,tiltValues[5],0,0,1,0,tiltValues[6],tiltValues[7],0,tiltValues[8]};
    leashMatrix4.invoke(t,leash,(Object)m4);s.projectedLeash=leash;
   }else if(leashMatrix!=null){
    leashMatrix.invoke(t,leash,m[0],m[3],m[1],m[4]);t.setPosition(leash,m[2],m[5]);
   }
   s.fittedPose=pose;
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
 private void apply(Slot s,int[] r){
  if(s.card==null)return;
  fitSurface(s,r[2],r[3]);
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)s.card.getLayoutParams();
  if(lp==null)lp=new FrameLayout.LayoutParams(r[2],r[3]);
  if(lp.width!=r[2]||lp.height!=r[3]||lp.leftMargin!=r[0]||lp.topMargin!=r[1]){
   lp.width=r[2];lp.height=r[3];lp.leftMargin=r[0];lp.topMargin=r[1];s.card.setLayoutParams(lp);
  }
  clipSide(s.card,r[2],r[3],slots.indexOf(s)!=primary);
  fitRecoveryCover(s,r[2],r[3]);
 }
 private void clipSide(View card,int w,int h,boolean side){
  if(card!=null){
   Ui.round(card,Ui.dp(this,side?Ui.SIDE_RADIUS:Ui.MAIN_RADIUS));
   boolean projective=AtomicPresentation.perspective(atomicRotate,side);
   for(Slot s:slots)if(s.card==card&&s.motionPose!=null){projective=true;break;}
   card.setClipToOutline(!projective);
   if(card instanceof android.view.ViewGroup)((android.view.ViewGroup)card).setClipChildren(!projective);
  }
 }
 private void layoutCards(boolean animate){
  if(slots.isEmpty()||closing)return;
  CardLayout.Result geometry=cardGeometry();layoutAddCard(geometry.add);int[][] target=geometry.cards;
  final ArrayList<Slot> current=new ArrayList<>(slots);
  if(!animate){for(int i=0;i<current.size();i++){Slot s=current.get(i);if(s.card==null)return;s.motionPose=null;resetCardTransform(s);inputRole(s,i==primary);apply(s,target[i]);}raiseMain();layoutCaption();scheduleLayoutEvidence("settled");return;}
  final CardMotion.Pose[] from=new CardMotion.Pose[current.size()],to=new CardMotion.Pose[current.size()];
  for(int i=0;i<current.size();i++){
   Slot s=current.get(i);if(s.card==null)return;
   from[i]=currentPose(s);
   if(from[i]==null){Log.w(TAG,"switch_degraded reason=missing_source_pose slot="+s.id+" task="+s.taskId);layoutCards(false);return;}
   int[] r=target[i];to[i]=rolePose(s,new float[]{r[0],r[1],r[2],r[3]},i!=primary);
  }
  final int generation=++switchGeneration;
  switchStarted=SystemClock.uptimeMillis();lastAnimationFrame=0;maxFrameGap=0;animationFrames=0;resizeCalls=0;resizeNanos=0;motionFitFailures=0;
  animation=ValueAnimator.ofFloat(0,1);animation.setDuration(MotionSpec.SWITCH_MS);animation.setInterpolator(MotionSpec::geometry);
  // Capture before role changes. Keep render bounds fixed; interpolate the buffer
  // window and all four stage-space corners along with the View's screen box.
  for(int i=0;i<current.size();i++){
   Slot s=current.get(i);s.motionPose=from[i];inputRole(s,i==primary);apply(s,target[i]);poseCard(s,from[i],to[i]);
  }
  raiseMain();
  motionFitListener=()->{
   if(generation==switchGeneration&&!closing&&!stopped&&!backgrounded){
    try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
     boolean ready=true;for(Slot s:current){fitRecoveryCover(s,s.surface.getWidth(),s.surface.getHeight());ready&=writeLeashFit(s,fit);}
     if(ready)submitMotionFit(fit);else motionFitFailures++;
    }catch(Throwable e){motionFitFailures++;Log.w(TAG,"switch_frame_fit_failed generation="+generation,e);}
   }
   return true;
  };
  stage.getViewTreeObserver().addOnPreDrawListener(motionFitListener);
  animation.addUpdateListener(a->{
   long now=SystemClock.uptimeMillis();if(lastAnimationFrame!=0)maxFrameGap=Math.max(maxFrameGap,now-lastAnimationFrame);lastAnimationFrame=now;animationFrames++;
   float f=(Float)a.getAnimatedValue();for(int i=0;i<current.size();i++)poseCard(current.get(i),CardMotion.between(from[i],to[i],f),to[i]);stage.invalidate();
  });
  animation.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){if(animation!=a)return;
   animation=null;settlingInput=true;removeMotionFitListener();
   settleSwitch(generation,SystemClock.uptimeMillis()+MotionSpec.ARRIVAL_UNLOCK_MS);
  }});Log.i(TAG,"switch_animation_start generation="+generation+" prepare_token="+switchOriginPrepareToken+" tasks="+taskIds()+" duration_ms="+MotionSpec.SWITCH_MS+" geometry=quad_crop_radius");animation.start();
 }
 private void motionOutline(Slot s){
  // The rounded task leash supplies the animated outline. A final-role View clip
  // or rectangular backdrop would cut off the old trapezoid at the first frame.
  s.card.setClipToOutline(false);s.card.setClipChildren(false);
  if(s.card.getBackground()!=null)s.card.setBackground(null);
  if(s.surface!=null){
   s.surface.setClipBounds(null);
   s.surface.setClipToOutline(false);
   // SurfaceView also punches a rounded hole in the host Canvas. That radius is
   // multiplied by the card scale, independently of the SurfaceControl plate.
   // Keep both the ROM's retained radius and the Canvas hole square while the
   // task leash supplies the animated outline; applyCardRadius restores them.
   try{
    if(cornerRadius!=null&&cornerRadius.getFloat(s.surface)!=0f)cornerRadius.setFloat(s.surface,0f);
    float hole=((Number)viewApi.getMethod("getCornerRadius").invoke(s.surface)).floatValue();
    if(hole!=0f)viewApi.getMethod("setCornerRadius",float.class).invoke(s.surface,0f);
   }catch(Throwable e){Log.w(TAG,"motion_corner_radius_failed slot="+s.id,e);}
  }
 }
 private void poseCard(Slot s,CardMotion.Pose pose,CardMotion.Pose target){
  s.motionPose=pose;s.card.setPivotX(0);s.card.setPivotY(0);
  s.card.setTranslationX(pose.box[0]-target.box[0]);s.card.setTranslationY(pose.box[1]-target.box[1]);
  s.card.setScaleX(pose.box[2]/target.box[2]);s.card.setScaleY(pose.box[3]/target.box[3]);motionOutline(s);
 }
 private void submitMotionFit(SurfaceControl.Transaction fit){
  RotationRun run=taskRotationRun;
  if(run!=null&&run.changingLayout){rotationSubmitTask=run.task;rotationSubmitGeneration=run.generation;rotationSubmitSlot=run.slot;}
  if(rotationTransactions!=null&&((run!=null&&run.changingLayout)||rotationTransactions.pending())){
   // A native transaction for another slot may arrive in the same host draw.
   // Always include the owned task's latest angle in the privileged final fit.
   if(rotationSubmitSlot==null||!writeLeashFit(rotationSubmitSlot,fit))throw new IllegalStateException("rotation fit unavailable");
   try{rotationTransactions.submit(stage,fit,(SurfaceControl)taskLeash.get(rotationSubmitSlot.surface),rotationSubmitTask,rotationSubmitGeneration);}
   catch(ReflectiveOperationException e){throw new IllegalStateException("rotation leash unavailable",e);}return;
  }
  AttachedSurfaceControl root=stage.getRootSurfaceControl();
  if(root==null||!root.applyTransactionOnDraw(fit))throw new IllegalStateException("Missing host draw transaction");
 }
 private void removeMotionFitListener(){
  if(motionFitListener!=null){stage.getViewTreeObserver().removeOnPreDrawListener(motionFitListener);motionFitListener=null;}
 }
 private String switchSurfaceKey(){
  String key=entranceSurfaceKey();if(key==null)return null;
  StringBuilder out=new StringBuilder(key);
  for(Slot s:slots){
   if(s.motionPose==null)return null;
   out.append(':').append(Arrays.toString(cardBox(s))).append(':').append(Arrays.toString(s.motionPose.quad)).append(':').append(Arrays.toString(s.motionPose.crop));
  }
  return out.toString();
 }
 private void settleSwitch(int generation,long deadline){
  if(generation!=switchGeneration||!settlingInput||closing||stopped||backgrounded)return;
  if(SystemClock.uptimeMillis()>=deadline){
   Log.w(TAG,"switch_settle_timeout generation="+generation+" waiting_for="+entranceWaitReason);
   logSwitch("failed_settle_timeout");cancelAnimation();layoutCards(false);return;
  }
  String key=switchSurfaceKey();
  if(key==null){handler.postDelayed(()->settleSwitch(generation,deadline),MotionSpec.SURFACE_RETRY_MS);return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(Slot s:slots)if(!writeLeashFit(s,fit))throw new IllegalStateException("Unfitted switch slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(generation!=switchGeneration||!settlingInput)return;
    stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(generation!=switchGeneration||!settlingInput||closing||stopped||backgrounded)return;
     if(SystemClock.uptimeMillis()>=deadline){settleSwitch(generation,deadline);return;}
     if(!key.equals(switchSurfaceKey())){settleSwitch(generation,deadline);return;}
     settlingInput=false;
     for(int i=0;i<slots.size();i++){Slot s=slots.get(i);s.motionPose=null;resetCardTransform(s);inputRole(s,i==primary);int[] plate=surfacePlate(s);fitRecoveryCover(s,plate[0],plate[1]);}
     layoutCaption();logSwitch(motionFitFailures==0?"settled":"failed_motion_fit");scheduleLayoutEvidence("switch");handler.post(()->focusPrimary("switch"));
    }));stage.invalidate();
   });submitMotionFit(fit);stage.invalidate();
   // A missing compositor or host-frame callback is a failure, too.
   handler.postDelayed(()->{if(generation==switchGeneration&&settlingInput)settleSwitch(generation,deadline);},Math.max(1,deadline-SystemClock.uptimeMillis()));
  }catch(Throwable e){Log.w(TAG,"switch_final_fit_failed generation="+generation,e);handler.postDelayed(()->settleSwitch(generation,deadline),MotionSpec.SURFACE_RETRY_MS);}
 }
 /** Read-only evidence of the actual settled View rectangles, not just planned geometry. */
 private void scheduleLayoutEvidence(String reason){
  final int generation=++layoutEvidenceGeneration;
  final long deadline=SystemClock.uptimeMillis()+1500;
  Runnable check=new Runnable(){public void run(){
   if(generation!=layoutEvidenceGeneration||closing||stopped||backgrounded||stage==null)return;
   boolean ready=taskRotationRun==null&&!layoutPosted&&!stage.isLayoutRequested()&&!screenArrival&&!handoffPresentation&&animation==null&&addAnimation==null;
   for(Slot s:slots)ready&=s.card!=null&&s.surface!=null&&!s.card.isLayoutRequested()&&!s.surface.isLayoutRequested()&&s.taskId>=0;
   if(!ready){if(SystemClock.uptimeMillis()<deadline)stage.postOnAnimation(this);else{
    StringBuilder detail=new StringBuilder().append("host_layout=").append(stage.isLayoutRequested()).append(" posted=").append(layoutPosted).append(" arrival=").append(screenArrival).append(" handoff=").append(handoffPresentation).append(" rotation=").append(taskRotationRun!=null).append(" animation=").append(animation!=null).append(" add=").append(addAnimation!=null);
    for(Slot s:slots)detail.append(" slot=").append(s.id).append(" task=").append(s.taskId).append(" card_layout=").append(s.card!=null&&s.card.isLayoutRequested()).append(" surface_layout=").append(s.surface!=null&&s.surface.isLayoutRequested());
    Log.w(TAG,"layout_evidence_timeout "+detail);
   }return;}
   try{
    org.json.JSONObject data=new org.json.JSONObject();int[] loc=new int[2];stage.getLocationOnScreen(loc);
    data.put("container",getTaskId()).put("reason",reason).put("width",stage.getWidth()).put("height",stage.getHeight())
     .put("x",loc[0]).put("y",loc[1]).put("mode",effectiveMode()).put("primary",primary).put("count",slots.size())
     .put("rotation",getDisplay().getRotation()).put("requested_orientation",getRequestedOrientation());
    org.json.JSONArray cards=new org.json.JSONArray();
    for(Slot s:slots){org.json.JSONObject card=new org.json.JSONObject();card.put("slot",s.id).put("task",s.taskId).put("pkg",s.component.getPackageName())
     .put("axis",s.orientationAxis).put("task_rotation",s.taskOrientation.rotation()).put("previous_task_rotation",s.taskOrientation.previousRotation())
     .put("requested_visible_types",s.lastRequestedVisibleTypes).put("direction_eligibility",s.taskOrientation.eligibility()).put("effective_direction_rotation",s.taskOrientation.effectiveRotation())
     .put("rotation_class",s.taskOrientation.rotationClass()).put("rotation_unavailable",s.taskOrientation.unavailable()==null?"":s.taskOrientation.unavailable())
     .put("top_activity",s.taskOrientation.topActivity()).put("orig_activity",s.taskOrientation.origActivity()).put("real_activity",s.taskOrientation.realActivity()).put("top_activity_info",s.taskOrientation.topActivityInfo())
     .put("requested_task_orientation",s.taskOrientation.requested()).put("orientation_source",s.taskOrientation.source()).put("orientation_bounds",new org.json.JSONArray(s.taskOrientation.bounds()))
     .put("render",new org.json.JSONArray(new int[]{s.renderBounds.width(),s.renderBounds.height()}))
     .put("box",new org.json.JSONArray(new int[]{s.card.getLeft(),s.card.getTop(),s.card.getWidth(),s.card.getHeight()}))
     .put("rotate",rotatePresentation(s)).put("perspective",s.perspectiveApplied);cards.put(card);}
    data.put("cards",cards);if(addCard.getVisibility()==View.VISIBLE)data.put("add",new org.json.JSONArray(new int[]{addCard.getLeft(),addCard.getTop(),addCard.getWidth(),addCard.getHeight()}));
    Log.i(TAG,"layout_evidence "+data);
   }catch(Throwable e){Log.w(TAG,"layout_evidence_failed",e);}
  }};stage.postOnAnimation(check);
 }
 private void raiseMain(){
  if(primary<0||primary>=slots.size())return;
  Slot s=slots.get(primary);if(s.card!=null)s.card.bringToFront();
  if(status!=null)status.bringToFront();
 }
private void resetCardTransform(Slot s){s.card.setTranslationX(0);s.card.setTranslationY(0);s.card.setScaleX(1);s.card.setScaleY(1);s.motionFromRadius=-1f;s.renderRadius=-1;applyCardRadius(s,slots.indexOf(s)==primary);}
private void transformCard(Slot s,int[] from,int[] target,float fraction){
  s.card.setPivotX(0);s.card.setPivotY(0);float remaining=1-fraction;
  s.card.setTranslationX((from[0]-target[0])*remaining);s.card.setTranslationY((from[1]-target[1])*remaining);
  float scaleX=1+(from[2]/(float)Math.max(1,target[2])-1)*remaining;
  float scaleY=1+(from[3]/(float)Math.max(1,target[3])-1)*remaining;
  s.card.setScaleX(scaleX);s.card.setScaleY(scaleY);
  animateCardRadius(s,fraction,scaleX,scaleY);
}
/** Geometry and screen-space rounding share the spring sample. Divide by the View scale
 *  when writing its outline; otherwise the radius is scaled twice (TODO D2).
 *  Projective side plates retain their leash outline. */
private void animateCardRadius(Slot s,float fraction,float scaleX,float scaleY){
  if(s.card==null)return;
  boolean isPrimary=slots.indexOf(s)==primary;
  if(AtomicPresentation.perspective(atomicRotate,!isPrimary))return;
  float target=cardRadius(isPrimary),from=s.motionFromRadius<0?target:s.motionFromRadius;
  int radius=Math.round(MotionSpec.viewRadius(from,target,fraction,scaleX,scaleY));
  if(radius==s.renderRadius)return;
  s.renderRadius=radius;Ui.round(s.card,radius);
  if(s.card.getBackground() instanceof android.graphics.drawable.GradientDrawable)
   ((android.graphics.drawable.GradientDrawable)s.card.getBackground()).setCornerRadius(radius);
}
 private boolean switching(){return animation!=null||settlingInput;}
 private void logSwitch(String result){Log.i(TAG,"switch_metrics result="+result+" generation="+switchGeneration+" primary="+primary+" elapsed_ms="+(SystemClock.uptimeMillis()-switchStarted)+" frames="+animationFrames+" max_frame_gap_ms="+maxFrameGap+" resize_calls="+resizeCalls+" resize_wall_us="+(resizeNanos/1000)+" fit_failures="+motionFitFailures);}
 private void cancelAnimation(){
  cancelAnimation(false);
 }
 private void cancelAnimation(boolean preservePose){
  if(taskRotationRun!=null)finishTaskRotation(taskRotationRun,"cancelled");
  clearArrivalFace("animation_preempted");
  cancelHandoffPresentation();
  if(addAnimation!=null){ValueAnimator old=addAnimation;addAnimation=null;old.cancel();releaseEntrance();}
  if(switching())logSwitch("cancelled");switchGeneration++;removeMotionFitListener();
  settlingInput=false;
  if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}
  if(!preservePose)for(Slot s:slots)s.motionPose=null;
  if(screenArrival){arrivalRun.cancel();stage.stopWatching();Log.i(TAG,"arrive_result result=cancelled token="+arrivalToken);handler.removeCallbacks(arrivalUnlock);entranceInputBlocked=false;closeEntranceCover("arrival_cancelled");screenArrival=false;hangEntranceDue=false;arrivalSlot=null;arrivalFrom=null;if(!preservePose)for(Slot s:slots)if(s.card!=null)resetCardTransform(s);}
 }
 private void queueExistingSwitch(Slot selected,long session){
  cancelSwitchPreparation("superseded");
  switchPrepareTarget=selected;switchPrepareSession=session;switchPreparePrimary=primary;
  switchPrepareSlots=new ArrayList<>(slots);switchPrepareTasks=new int[slots.size()];
  for(int i=0;i<slots.size();i++){Slot slot=slots.get(i);switchPrepareTasks[i]=slot.taskId;}
  final int token=switchPrepareToken=switchPreparation.begin(SystemClock.uptimeMillis(),MotionSpec.ARRIVAL_UNLOCK_MS);
  // Do not read a destroyed background Surface or reorder Slots before restoration.
  for(int i=0;i<slots.size();i++)inputRole(slots.get(i),i==primary);
  Log.i(TAG,"switch_prepare_wait token="+token+" task="+selected.taskId+" session="+session+" container="+getTaskId()+" tasks="+taskIds());
  handler.post(()->prepareExistingSwitch(token));
  handler.postDelayed(()->{if(switchPreparation.current(token))failSwitchPreparation(token,"timeout");},MotionSpec.ARRIVAL_UNLOCK_MS);
 }
 private boolean sameSwitchParticipants(){
  if(switchPrepareSlots==null||switchPrepareSlots.size()!=slots.size()||primary!=switchPreparePrimary)return false;
  for(int i=0;i<slots.size();i++){
   Slot s=slots.get(i);if(s!=switchPrepareSlots.get(i)||s.released||switchPrepareTasks[i]<0)return false;
   // A rebind can temporarily publish -1; readiness will wait. A different real
   // task never inherits this request, even if its package name is the same.
   if(s.taskId>=0&&s.taskId!=switchPrepareTasks[i])return false;
  }
  return switchPrepareTarget!=null&&slots.contains(switchPrepareTarget);
 }
 private void prepareExistingSwitch(int token){
  if(!switchPreparation.waiting(token))return;
  if(closing||isFinishing()||!sameSwitchParticipants()){failSwitchPreparation(token,"identity_changed");return;}
  if(switchPreparation.remaining(SystemClock.uptimeMillis())==0){failSwitchPreparation(token,"timeout");return;}
  if(stopped||backgrounded||recovering||dragging!=null||screenArrival||handoffPresentation||animation!=null||settlingInput||entranceCover!=null||coverReleasePending){
   handler.postDelayed(()->prepareExistingSwitch(token),MotionSpec.SURFACE_RETRY_MS);return;
  }
  String key=entranceSurfaceKey();
  if(key==null){handler.postDelayed(()->prepareExistingSwitch(token),MotionSpec.SURFACE_RETRY_MS);return;}
  final int ticket=switchPreparation.commit(token,SystemClock.uptimeMillis());
  if(ticket<0){failSwitchPreparation(token,"timeout");return;}
  try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){
   for(Slot s:slots)if(!writeLeashFit(s,fit))throw new IllegalStateException("Unfitted existing-switch slot "+s.id);
   fit.addTransactionCommittedListener((Executor)handler::post,()->{
    if(!switchPreparation.committing(token,ticket))return;
    stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
     if(!switchPreparation.committing(token,ticket))return;
     if(closing||isFinishing()||!sameSwitchParticipants()){failSwitchPreparation(token,"identity_changed");return;}
     boolean ready=!stopped&&!backgrounded&&!recovering&&dragging==null&&!screenArrival&&!handoffPresentation&&animation==null&&!settlingInput&&entranceCover==null&&!coverReleasePending&&key.equals(entranceSurfaceKey());
     for(Slot s:slots)ready&=s.taskId>=0&&currentPose(s)!=null;
     if(!ready){retrySwitchPreparation(token,ticket);return;}
     Slot selected=switchPrepareTarget;long session=switchPrepareSession;int task=selected.taskId,index=slots.indexOf(selected);
     if(!switchPreparation.ready(token,ticket,SystemClock.uptimeMillis())){failSwitchPreparation(token,"timeout");return;}
     clearSwitchPreparation();
     Log.i(TAG,"switch_prepare_result result=ready token="+token+" task="+task+" session="+session+" container="+getTaskId());
     // promote samples the restored, committed pose immediately before changing roles.
     switchOriginPrepareToken=token;
     try{promote(index);}finally{switchOriginPrepareToken=0;}
    }));stage.invalidate();
   });submitMotionFit(fit);stage.invalidate();
  }catch(Throwable e){Log.w(TAG,"switch_prepare_fit_failed token="+token+" ticket="+ticket,e);retrySwitchPreparation(token,ticket);}
 }
 private void retrySwitchPreparation(int token,int ticket){
  if(switchPreparation.retry(token,ticket,SystemClock.uptimeMillis()))handler.postDelayed(()->prepareExistingSwitch(token),MotionSpec.SURFACE_RETRY_MS);
  else if(switchPreparation.current(token))failSwitchPreparation(token,"timeout");
 }
 private void clearSwitchPreparation(){switchPrepareTarget=null;switchPrepareSlots=null;switchPrepareTasks=null;switchPrepareSession=0;}
 private void cancelSwitchPreparation(String reason){
  if(!switchPreparation.pending())return;
  int task=switchPrepareTarget==null?-1:switchPrepareTarget.taskId;
  switchPreparation.cancel();clearSwitchPreparation();
  Log.i(TAG,"switch_prepare_result result=cancelled token="+switchPrepareToken+" task="+task+" reason="+reason);
  if(!closing)for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
 }
 private void failSwitchPreparation(int token,String reason){
  if(!switchPreparation.current(token))return;
  int task=switchPrepareTarget==null?-1:switchPrepareTarget.taskId;
  if(!switchPreparation.timeout(token,SystemClock.uptimeMillis()))switchPreparation.cancel();
  clearSwitchPreparation();
  Log.w(TAG,"switch_prepare_result result=failed token="+token+" task="+task+" reason="+reason+" waiting_for="+entranceWaitReason);
  closeEntranceCover("switch_prepare_"+reason);
  if(!closing){for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);Toast.makeText(this,"窗口尚未恢复，请再试一次",Toast.LENGTH_SHORT).show();}
 }
 private void promote(int index){if(closing||recovering||dragging!=null||index<0||index>=slots.size()||index==primary)return;cancelSwitchPreparation("direct_switch");for(Slot s:slots)if(s.taskId<0||s.card==null||currentPose(s)==null){Log.w(TAG,"switch_rejected reason=source_not_ready slot="+s.id+" task="+s.taskId);Toast.makeText(this,"请等待应用窗口就绪",Toast.LENGTH_SHORT).show();return;}cancelAnimation(true);CardOrder.promote(slots,primary,index);layoutCards(true);refreshStatus();Log.i(TAG,"switch primary="+primary+" tasks="+taskIds());}
 private String taskIds(){StringBuilder b=new StringBuilder();for(Slot s:slots){if(b.length()>0)b.append(',');b.append(s.taskId);}return b.toString();}
 private void refreshStatus(){runOnUiThread(()->{if(closing||slots.isEmpty())return;boolean ready=true,failed=false;for(Slot s:slots){ready&=s.taskId>=0;failed|=s.failed;}status.setVisibility(ready&&!failed?View.GONE:View.VISIBLE);status.setText(failed?"部分窗口未就绪，可长按卡片替换或移出":ready?slots.size()+" 个实时窗口 · 主应用："+slots.get(primary).label:"正在等待应用窗口…");if(status.getVisibility()==View.VISIBLE)status.bringToFront();});}
 private void fail(Throwable e){while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();Log.e(TAG,"workbench_error",e);if(status!=null){status.setVisibility(View.VISIBLE);status.setText("无法启动："+e.getClass().getSimpleName()+" · "+e.getMessage());}}
 private void exitToFullscreen(){
  if(closing||slots.isEmpty())return;
  Slot main=slots.get(primary);
  int keep=main.taskId;
  ComponentName component=main.component;
  dismissPrimaryMenu();detachCaption();
  cancelSwitchPreparation("fullscreen");CanvasImeBridge.stop(this);closing=true;publishState(0);clearRecoveryCovers();cancelDrag();cancelAnimation();closeEntranceCover("exit_fullscreen");handler.removeCallbacksAndMessages(null);
  // Unlink every task. Keep the main task from being moved to back or restored
  // behind this container; that was snapping fullscreen back to the workbench.
  for(Slot s:new ArrayList<>(slots))releaseSlot(s,s==main);
  boolean launched=bringTaskFullscreen(keep,component);
  Log.i(TAG,"workbench_fullscreen task="+keep+" launched="+launched+" container="+getTaskId());
  try{
   Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
   atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),getTaskId(),true);
  }catch(Throwable e){Log.w(TAG,"container_to_back_failed",e);}
  handler.post(()->{if(!isFinishing())finishAndRemoveTask();});
 }
 private boolean bringTaskFullscreen(int taskId,ComponentName component){
  if(taskId>=0){
   // No ActivityManager.moveTaskToFront here on purpose. uid 10243 holds no REORDER_TASKS, so the
   // call was refused on every attempt (9/9 in logs/logcat_cap2.txt) and only added a Permission
   // Denial before falling through to the branch below. Calling an API that is known to fail
   // cannot make the transition more correct, and it kept the acceptance grep non-zero (TODO B1).
   try{
    Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    Object result=Class.forName("android.app.IActivityTaskManager").getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(service,taskId,null);
    int code=result instanceof Integer?(Integer)result:Integer.MIN_VALUE;
    boolean accepted=TransitionPolicy.startAccepted(code);
    Log.i(TAG,"fullscreen_recents task="+taskId+" result="+result+" accepted="+accepted);
    // Reporting success on the call alone is what made this silent: START_CANCELED (-96) and
    // START_NOT_ACTIVITY (-95) fell outside the band but were swallowed by a hard-coded
    // return true (TODO B1-1). Anything rejected now falls through to the intent fallback.
    if(accepted)return true;
   }catch(Throwable e){Log.w(TAG,"fullscreen_recents_failed",e);}
  }
  if(component==null)return false;
  try{
   startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
   Log.i(TAG,"fullscreen_intent component="+component.flattenToShortString());
   return true;
  }catch(Throwable e){Log.w(TAG,"fullscreen_intent_failed",e);return false;}
 }
 /** Unhooks this slot's task leash from our plate so the ROM's zoom transition is the only holder
 *  of the task surface (TODO B2-1). The view's own surface-destroyed guard is already held down
 *  by {@link #releaseSlot}, which is the 300 ms fallback the hand-off notes call for. */
private void detachLeashForTransition(Slot s){
  if(s.surface==null||taskLeash==null)return;
  try{
   SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface);
   if(leash==null||!leash.isValid())return;
   try(SurfaceControl.Transaction detach=new SurfaceControl.Transaction()){
    detach.reparent(leash,null);detach.apply();
   }
   Log.i(TAG,"transition_leash_detached slot="+s.id+" task="+s.taskId);
  }catch(Throwable e){Log.w(TAG,"transition_leash_detach_failed slot="+s.id,e);}
}
private void releaseSlot(Slot s){releaseSlot(s,false);}
 private void releaseSlot(Slot s,boolean toFront){
  if(taskRotationRun!=null&&taskRotationRun.slot==s)finishTaskRotation(taskRotationRun,"released");
  if(s.released)return;
  s.drawEvidence.invalidate();
  EmbeddedTaskGuard.forget(s.surface);s.embedded=false;clearProjection(s);
  if(s.transaction!=null){
   LeashTransactions.unregister(s.transaction);
   try{
    SurfaceControl leash=taskLeash==null?null:(SurfaceControl)taskLeash.get(s.surface);
    if(leash!=null&&leash.isValid()){
     if(leashMatrix4!=null)leashMatrix4.invoke(s.transaction,leash,(Object)identity4());
     if(leashRadius!=null)leashRadius.invoke(s.transaction,leash,0f);
    }
    s.transaction.apply();
   }catch(Throwable e){Log.w(TAG,"leash_reset_failed",e);}
   s.perspectiveApplied=false;s.transaction=null;
  }
  s.released=true;
  if(!closing&&!s.quietDetach)recovering=true;
  View released=s.surface;
  if(released!=null){
   try{
    // TODO B2-1: during the hand-off the ROM's zoom transition (startZoomWindowFromSplit, the
    // third layer) and our own plate can both hold this task surface, which is what draws the
    // same card in two places for four frames (t=2.54–2.62). Detach ours first so only the ROM
    // is drawing. Behind a switch: it still needs a device pass to prove the task keeps drawing
    // once the plate is gone.
    if(detachLeashBeforeTransition&&toFront)detachLeashForTransition(s);
    // Unlink first: moving a still-linked task can background the whole group.
    // The ROM's move-to-back path resets it without the unsafe direct
    // fullscreen transition used by resetFlexibleTask.
    if(s.taskId>=0){
     viewApi.getMethod("interceptBackPressedOnTaskRoot",boolean.class).invoke(released,false);
     managerApi.getMethod("removeEmbeddedContainerTask",int.class,int.class).invoke(manager,s.taskId,getTaskId());
     if(!toFront){
      Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
      atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),s.taskId,true);
     }
    }
    // moveTaskToBack owns the server-side transition. Do not race it with
    // detachFromTaskView's second resetFlexibleTask / fullscreen transition.
    Method extra=viewApi.getDeclaredMethod("releaseExtraView");extra.setAccessible(true);extra.invoke(released);
   }catch(Throwable e){Log.w(TAG,"detach_failed slot="+s.id,e);}
   try{viewApi.getMethod("release").invoke(released);}catch(Throwable e){Log.w(TAG,"release_failed slot="+s.id,e);}
   s.surface=null;
  }
  if(pendingEntrance==s){pendingEntrance=null;entranceFrameReady=false;entranceFramePending=false;}
  if(s.card!=null){stage.removeView(s.card);s.card.resetGesture();}
  // The view reports task visibility from its surface-destroyed callback, and it
  // posts that callback to the same executor release() just used to clear the
  // view's own task token. The callback therefore runs with a null token: the
  // server takes its null-token branch, walks the container's embedded children
  // and moves every one of them to back -- the whole workbench drops to the
  // launcher -- and throws inside updateTaskVisibility, taking the pcanvas process
  // with it. mSuperLocked is the first thing that callback checks, so hold it down
  // once the view is detached and nothing will read it again.
  if(released!=null&&superLocked!=null){
   try{superLocked.setBoolean(released,true);}catch(Throwable e){Log.w(TAG,"super_lock_failed slot="+s.id,e);}
  }
  if(s.recoveryCover!=null){s.recoveryCover.setBitmap(null);s.recoveryCover=null;}
  if(s.taskId>=0&&!toFront&&!closing&&!s.quietDetach){
   Runnable restore=()->{
    if(closing)return;
    for(Slot active:slots)if(!active.released&&active.taskId==s.taskId)return;
    try{reorderTask(s.taskId,false);restoreContainerFocus();Log.i(TAG,"detached_task_restored task="+s.taskId);}
    catch(Throwable e){Log.w(TAG,"task_restore_failed id="+s.taskId,e);}
   };
   restore.run();handler.postDelayed(restore,600);
  }
  if(!closing&&!s.quietDetach){
   if(recoveryFinish!=null)handler.removeCallbacks(recoveryFinish);
   recoveryFinish=()->{recoveryFinish=null;if(closing)return;resumeRemainingTasks();
    // Allow resumed task layers to join a submitted frame before uncovering them.
    stage.postOnAnimation(()->stage.postOnAnimation(()->{if(closing)return;
     for(Slot active:slots)syncSurface(active);
     stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{if(closing)return;clearRecoveryCovers();recovering=false;layoutCards(false);Log.i(TAG,"recovery_ready tasks="+taskIds());}));stage.invalidate();
    }));
   };
   handler.postDelayed(recoveryFinish,650);
   handler.postDelayed(()->{if(!recovering||closing)return;Log.w(TAG,"recovery_forced");clearRecoveryCovers();recovering=false;layoutCards(false);},1800);
  }
 }
 private void resumeRemainingTasks(){
  if(closing||isFinishing())return;
  for(Slot active:new ArrayList<>(slots)){
   if(active.released||active.surface==null||active.taskId<0)continue;
   try{
    Method resume=viewApi.getDeclaredMethod("startActivityAndReparent");resume.setAccessible(true);resume.invoke(active.surface);
    Log.i(TAG,"embedded_resume task="+active.taskId);
   }catch(Throwable e){Log.w(TAG,"task_resume_failed task="+active.taskId,e);}
  }
 }
 private void restoreContainerFocus(){
  if(closing||isFinishing())return;
  try{reorderTask(getTaskId(),true);for(Slot s:slots)if(!s.released&&s.taskId>=0){reorderTask(s.taskId,true);syncSurface(s);}Log.i(TAG,"container_reordered task="+getTaskId());}catch(Throwable e){fail(e);}
 }
 private void reorderTask(int id,boolean front) throws Exception {
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  java.util.List<?> tasks=(java.util.List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,false,0);
  for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;if(task.taskId!=id)continue;
   Class<?> token=Class.forName("android.window.WindowContainerToken"),wct=Class.forName("android.window.WindowContainerTransaction");
   Object tx=wct.getConstructor().newInstance(),t=task.getClass().getField("token").get(task);
   if(front)wct.getMethod("setHidden",token,boolean.class).invoke(tx,t,false);
   if(!front)wct.getMethod("setAlwaysOnTop",token,boolean.class).invoke(tx,t,false);
   wct.getMethod("reorder",token,boolean.class).invoke(tx,t,front);
   Class<?> organizer=Class.forName("android.window.WindowOrganizer");organizer.getMethod("applyTransaction",wct).invoke(organizer.getConstructor().newInstance(),tx);return;
  }
  throw new IllegalStateException("Missing task "+id);
 }
 private void releaseWindows(){cancelSwitchPreparation("release");clearRecoveryCovers();cancelDrag();cancelAnimation();handler.removeCallbacksAndMessages(null);for(Slot s:new ArrayList<>(slots))releaseSlot(s);}
 private boolean backReady(){return taskRotationRun==null&&!switchPreparation.pending()&&!closing&&!backgrounded&&!stopped&&modalWindows==0&&!recovering&&!switching()&&!entranceInputBlocked&&!handoffPresentation&&dragging==null&&!slots.isEmpty();}
 private boolean focusPrimary(String reason){
  if(!backReady()||stage==null||!stage.isShown()||screenArrival)return false;
  Slot main=slots.get(primary);if(main.released||main.taskId<0)return false;
  CanvasImeBridge.refresh(this);
  try{managerApi.getMethod("setFocusAppForEmbeddedTask",int.class).invoke(manager,main.taskId);Log.i(TAG,"back_focus reason="+reason+" task="+main.taskId);return true;}
  catch(Throwable e){Log.w(TAG,"back_focus_failed",e);return false;}
 }
 private void onEmbeddedTaskVanished(Slot s,int task){
  if(closing||s.released||slots.isEmpty())return;
  // Root back detaches the primary task. Keep the group and show the launcher.
  if(slots.get(primary)==s){
   int kept=task>=0?task:s.taskId;
   if(kept<0){removeSlot(s,true);return;}
   Log.i(TAG,"back_keep_group slot="+s.id+" task="+kept+" container="+getTaskId());
   if(backgrounded||stopped){s.pendingTask=kept;return;}
   if(backgroundWorkbench("primary_vanished")){s.pendingTask=kept;return;}
   if(taskExists(kept))rebindEmbedded(s,kept);else removeSlot(s,true);
   return;
  }
  removeSlot(s,true);
 }
 private void restorePendingTasks(){
  if(closing||stopped||backgrounded||hangTask>=0)return;
  for(Slot s:new ArrayList<>(slots)){
   if(s.pendingTask<0||s.released)continue;
   int id=s.pendingTask;s.pendingTask=-1;
   if(taskExists(id))rebindEmbedded(s,id);else removeSlot(s,true);
  }
 }
 private boolean taskExists(int id){
  if(id<0)return false;
  try{
   Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
   java.util.List<?> tasks=(java.util.List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,100,false,false,0);
   for(Object value:tasks)if(((ActivityManager.RunningTaskInfo)value).taskId==id)return true;
  }catch(Throwable e){Log.w(TAG,"task_exists_failed id="+id,e);}
  return false;
 }
 private void rebindEmbedded(Slot s,int task){
  if(s.surface==null||s.released||closing||s.renderBounds.isEmpty())return;
  s.sourceTaskId=task;s.taskId=-1;s.failed=false;s.windowDrawn=false;
  try{
   Bundle config=new Bundle();config.putInt("scenario",2);config.putParcelable("launchBounds",new Rect(s.renderBounds));
   config.putBoolean("flexible_embeed_task_config_with_container",false);config.putBoolean("use_default_background_color",false);
   config.putBoolean("need_rotate_task_leash",rotatePresentation(s));config.putBoolean("zorder_on_top",false);config.putInt("taskId",task);
   config.putInt("userId",s.sourceUserId>=0?s.sourceUserId:android.os.Process.myUid()/100000);config.putFloat("cornerRadius",cardRadius(slots.indexOf(s)==primary));config.putInt("reparent_align",slots.indexOf(s)==primary?0:2);
   config.putBoolean("intercept_input_event",false);config.putBoolean("allow_task_detach_from_embedding",true);config.putBoolean("key_intercept_back_key",true);config.putBoolean("flexible_key_remove_task_detach",false);config.putInt("use_view_snapshot",1);
   viewApi.getMethod("init",Bundle.class).invoke(s.surface,config);viewApi.getMethod("setEnforceStart",boolean.class).invoke(s.surface,true);
   Method start=viewApi.getDeclaredMethod("startActivityAndReparent");start.setAccessible(true);start.invoke(s.surface);
   Log.i(TAG,"back_rebind slot="+s.id+" task="+task);
  }catch(Throwable e){Log.w(TAG,"back_rebind_failed slot="+s.id,e);}
 }
 private boolean keepGroup(){return !closing&&initialized&&!slots.isEmpty();}
 private boolean retainInsteadOfFinish(String reason){
  if(!keepGroup())return false;
  // A forwarded key is still navigating inside the main app. Do not leave or destroy the group.
  if(forwardingBack){Log.i(TAG,"finish_swallowed reason="+reason);return true;}
  backgroundWorkbench(reason);return true;
 }
 @Override public void finish(){if(retainInsteadOfFinish("finish"))return;super.finish();}
 @Override public void finishAndRemoveTask(){if(retainInsteadOfFinish("finish_remove"))return;super.finishAndRemoveTask();}
 @Override public void finishAfterTransition(){if(retainInsteadOfFinish("finish_transition"))return;super.finishAfterTransition();}
 private void handleTaskRootBack(Slot s,int task){
  if(!backReady()||slots.get(primary)!=s||s.released||s.taskId!=task){Log.i(TAG,"back_root_ignored task="+task);return;}
  Log.i(TAG,"back_root task="+task+" container="+getTaskId());backgroundWorkbench("task_root");
 }
private boolean backgroundWorkbench(String reason){
  if(!backReady())return false;
  backgrounded=true;backGeneration++;forwardingBack=false;cancelDrag();cancelAnimation();
  if(keepSurfaceOnBackground){
   // Experimental alternative for TODO A1-3. Moving the container back can still stop its
   // Activity and destroy its surface; only a device trace can establish whether it helps.
   try{
    Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
    atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),getTaskId(),true);
    Log.i(TAG,"workbench_background_surface_kept reason="+reason+" container="+getTaskId());return true;
   }catch(Throwable e){Log.w(TAG,"back_surface_keep_failed",e);}
  }
  // Home preserves the embedded group. moveTaskToBack on a linked child can
  // detach it through the ROM fullscreen transition, so do not use release here.
  try{startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Log.i(TAG,"workbench_background reason="+reason+" container="+getTaskId()+" primary="+primary+" layout="+layoutMode+" tasks="+taskIds());return true;}
  catch(Throwable e){backgrounded=false;Log.w(TAG,"back_home_failed",e);return false;}
}
 private boolean embeddedImeVisible(){
  if(imeBottom>0)return true;
  // Embedded IME insets need not reach the container. Read the display's actual
  // IME visibility before the gesture, including an IME owned by a child task.
  try{Object im=getSystemService(INPUT_METHOD_SERVICE);return (Integer)android.view.inputmethod.InputMethodManager.class.getMethod("getInputMethodWindowVisibleHeight").invoke(im)>0;}
  catch(Throwable e){Log.w(TAG,"back_ime_visibility_failed",e);return false;}
 }
 private void handleHostBack(){
  if(primaryPopup!=null&&primaryPopup.isShowing()){dismissPrimaryMenu();return;}
  boolean keyboard=backStartedWithIme||embeddedImeVisible();backStartedWithIme=false;
  if(forwardingBack||!backReady())return;
  if(keyboard){
   try{android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);im.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(),0);getWindow().getInsetsController().hide(WindowInsets.Type.ime());Log.i(TAG,"back_ime_hidden");}
   catch(Throwable e){getWindow().getInsetsController().hide(WindowInsets.Type.ime());Log.w(TAG,"back_ime_hide_fallback",e);}
   return;
  }
  // Normally focus is already in the main app, so Android handles IME, dialogs,
  // in-app navigation and predictive cancellation itself. Forward only a
  // committed host back, after checking focus, with a recursion guard.
  Slot target=slots.get(primary);if(!focusPrimary("host_back"))return;
  forwardingBack=true;final int generation=++backGeneration;
  handler.postDelayed(()->{
   if(generation!=backGeneration||!backReady()||slots.get(primary)!=target){forwardingBack=false;return;}
   try{
    Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    Object focused=Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo").invoke(service);
    int focusedId=focused==null?-1:focused.getClass().getField("taskId").getInt(focused);
    if(focusedId!=target.taskId){Log.w(TAG,"back_forward_focus_mismatch expected="+target.taskId+" actual="+focusedId);return;}
    Class<?> input=Class.forName("android.hardware.input.InputManager");Object im=input.getMethod("getInstance").invoke(null);Method inject=input.getMethod("injectInputEvent",InputEvent.class,int.class);
    long now=SystemClock.uptimeMillis();
    inject.invoke(im,new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD),0);
    inject.invoke(im,new KeyEvent(now,now+1,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_FROM_SYSTEM,InputDevice.SOURCE_KEYBOARD),0);
    Log.i(TAG,"back_forward task="+target.taskId);
   }catch(Throwable e){Log.w(TAG,"back_forward_failed",e);}
   finally{handler.postDelayed(()->{if(generation==backGeneration)forwardingBack=false;},200);}
  },80);
 }
 private void closeWorkbench(){dismissHang();dismissPrimaryMenu();detachCaption();closeEntranceCover("close_workbench");if(closing)return;CanvasImeBridge.stop(this);closing=true;publishState(0);releaseWindows();Log.i(TAG,"workbench_exit");finishAndRemoveTask();}
 @Override public void onBackPressed(){handleHostBack();}
 @Override protected void onDestroy(){if(rotationTransactions!=null)rotationTransactions.close();CanvasImeBridge.stop(this);handler.removeCallbacks(immersivePoll);if(systemRotationObserver!=null){systemRotationObserver.close();systemRotationObserver=null;}handler.removeCallbacks(applyDirections);directionPosted=false;if(orientationObserver!=null){orientationObserver.close();orientationObserver=null;}clearHeldPose();hangSourceGeneration++;if(hangSourceFrame!=null){hangSourceFrame.recycle();hangSourceFrame=null;}dismissHang();dismissPrimaryMenu();unregisterControlBarLight();detachCaption();closeEntranceCover("destroy");if(contentThread!=null){contentThread.quitSafely();contentThread=null;contentWorker=null;}if(addReceiverRegistered){unregisterReceiver(addReceiver);addReceiverRegistered=false;}if(launcherReceiverRegistered){unregisterReceiver(launcherReceiver);launcherReceiverRegistered=false;}if(backdrop!=null){backdrop.detach();backdrop=null;}getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(hostBack);if(!closing){if(!isChangingConfigurations())publishState(0);closing=true;releaseWindows();}super.onDestroy();}
 private void publishState(int count){publishState(count,++statePublishGeneration,0);}
 private Bundle liveState(int count){
  Bundle state=new Bundle();state.putInt("containerTaskId",getTaskId());state.putInt("count",count);state.putLong("instanceToken",stateToken);
  state.putBinder(VideoWindowCompat.HOST_BINDER,videoCompatHost);
  state.putBoolean("hanging",hanging&&count>0);state.putLong("selectionSession",hangStarted);state.putInt("userId",android.os.Process.myUid()/100000);
  String[] packages=new String[slots.size()];int[] tasks=new int[slots.size()];
  for(int i=0;i<slots.size();i++){packages[i]=slots.get(i).component.getPackageName();tasks[i]=slots.get(i).taskId;}
  state.putStringArray("packages",packages);state.putIntArray("tasks",tasks);return state;
 }
 private void publishState(int count,int generation,int attempt){
  if(generation!=statePublishGeneration)return;
  Bundle state=liveState(count);
  if(attempt==0)try{LiveWorkbenchState.publish(this,state);}catch(Throwable e){Log.w(TAG,"live_state_publish_failed",e);}
  try{if(getContentResolver().call(android.net.Uri.parse("content://io.github.xitc.windowdeck.state"),"publish",null,state)!=null)return;}
  catch(Throwable e){Log.w(TAG,"publish_provider_failed attempt="+attempt,e);}
  if(attempt==0){
   Intent message=new Intent(LauncherIngressReceiver.STATE).setComponent(new ComponentName("io.github.xitc.windowdeck","io.github.xitc.windowdeck.LauncherIngressReceiver"));
   message.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES|Intent.FLAG_RECEIVER_FOREGROUND);message.putExtras(state);
   // Receiver checks getSentFromUid(). Without identity sharing a fallback can
   // be rejected even though the originating host is trusted.
   BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
   try{sendBroadcast(message,null,options.toBundle());Log.i(TAG,"publish_broadcast_sent container="+getTaskId()+" count="+count);}
   catch(Throwable e){Log.w(TAG,"publish_state_failed",e);}
  }
  // Package replacement can temporarily make the provider unavailable. Retry
  // the current publication only; an older count must never overwrite an add/exit.
  if(attempt<3)handler.postDelayed(()->publishState(count,generation,attempt+1),250L*(attempt+1));
 }
 @Override protected void onSaveInstanceState(Bundle b){String[] components=new String[slots.size()];int[] sourceTaskIds=new int[slots.size()];for(int i=0;i<slots.size();i++){Slot s=slots.get(i);components[i]=s.component.flattenToString();sourceTaskIds[i]=s.sourceTaskId>=0?(s.taskId>=0?s.taskId:s.sourceTaskId):-1;}b.putStringArray("components",components);b.putIntArray("sourceTaskIds",sourceTaskIds);b.putInt("primary",primary);b.putInt("layoutMode",layoutMode);for(Slot s:slots)if(s.pinned)b.putString("pinned",s.component.flattenToString());super.onSaveInstanceState(b);}
 @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);Log.i(TAG,"configuration orientation="+c.orientation+" tasks="+taskIds()+" defer="+deferPortrait+" pose="+poseHeld);if(poseHeld)logHeldLayer("defer_rotation_config");if(backdrop!=null)backdrop.onConfigurationChanged();if(stage!=null)scheduleLayout();}
}
