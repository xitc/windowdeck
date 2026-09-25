package dev.windowdeck.app;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Matrix;
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
 private final ArrayList<Slot> slots=new ArrayList<>();
 private FrameLayout stage; private TextView status; private Button addCard, more; private PopupWindow primaryPopup; private boolean captionAttached;
 private Field interceptInput,rotateTaskLeash,cornerRadius,taskLeash,reparentAlign; private Method resizeMethod;
 private Method leashMatrix,leashMatrix4,leashRadius; private Field viewTransaction;
 private boolean settlingInput; private int switchGeneration;
 private ViewTreeObserver.OnPreDrawListener settleListener;
 private long switchStarted, lastAnimationFrame, maxFrameGap, resizeNanos; private int animationFrames, resizeCalls;
 private int layoutMode=PaneLayout.LEFT_RIGHT;
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
 private final Runnable hangConsume=this::ensureHangConsumed;
 private final Runnable arrivalUnlock=this::finishArrival;
 private View entranceFlying;
 private Slot entranceSlot;
 private String hangNote;
 private long hangStarted;
 private Runnable clearHangSuppress;
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
 private final long stateToken=SystemClock.elapsedRealtimeNanos();
 private static final String ADD_TO_LIVE_WORKBENCH="dev.windowdeck.app.ADD_TO_LIVE_WORKBENCH";
 private static final String REVEAL_ADDED_CARD="dev.windowdeck.app.REVEAL_ADDED_CARD";
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
 private Slot pendingEntrance;
 private boolean entranceFrontReady,entranceFrameReady,entranceFramePending,createdFromGesture;
 private final class Slot {
  final int id; final ComponentName component; final String label; int taskId=-1,sourceTaskId=-1,sourceUserId=-1,orientationAxis; ComponentName activeComponent; final Rect renderBounds=new Rect(); boolean failed,released,pinned,windowDrawn,entranceWaitScheduled,entranceDrawTimedOut;
  SurfaceControl.Transaction transaction; volatile boolean embedded=true; SurfaceControl projectedLeash; boolean perspectiveApplied; int leashWrites;
  PreviewCard card; ImageView icon; RecoveryCover recoveryCover; TextView pin; View surface;
  int pendingTask=-1; boolean quietDetach;
  Slot(ComponentName c,String l){id=nextId++;component=c;label=l;}
 }
 @Override protected void onCreate(Bundle state){
  setTheme(android.R.style.Theme_Material_NoActionBar);super.onCreate(state);
  // A stale launcher request must not create an empty replacement container.
  if(state==null&&getIntent().hasExtra("windowdeck_add_task_id")){Log.w(TAG,"add_task_no_container");finish();return;}
  // ActivityManager checks DUMP before delivering this exported receiver.
  // Root am broadcast may report an unknown sender UID (-1) in onReceive.
  IntentFilter addFilter=new IntentFilter(ADD_TO_LIVE_WORKBENCH);addFilter.addAction(REVEAL_ADDED_CARD);
  registerReceiver(addReceiver,addFilter,android.Manifest.permission.DUMP,handler,Context.RECEIVER_EXPORTED);addReceiverRegistered=true;
  getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,hostBack);
  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0);setContentView(root);getWindow().setBackgroundDrawable(new ColorDrawable(Ui.CHROME));Ui.overlaySystemBars(this,false);
  root.setOnApplyWindowInsetsListener((v,insets)->{
   android.graphics.Insets sys=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
   int keyboard=insets.getInsets(WindowInsets.Type.ime()).bottom;
   v.setPadding(sys.left,sys.top,sys.right,Math.max(sys.bottom,keyboard));
   if(imeBottom!=keyboard){imeBottom=keyboard;Log.i(TAG,"ime bottom="+keyboard);}
   return insets;
  });
  layoutMode=state==null?PaneLayout.LEFT_RIGHT:state.getInt("layoutMode",PaneLayout.LEFT_RIGHT);
  if(layoutMode!=PaneLayout.TOP_BOTTOM)layoutMode=PaneLayout.LEFT_RIGHT;
  stage=new FrameLayout(this);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
  addCard=Ui.button(this,"＋");addCard.setTextSize(28);addCard.setTextColor(Ui.FROST_PLUS);addCard.setGravity(Gravity.CENTER);addCard.setIncludeFontPadding(false);addCard.setPadding(0,0,0,0);addCard.setContentDescription("添加应用");addCard.setBackground(Ui.frost(Ui.dp(this,Ui.SIDE_RADIUS)));Ui.round(addCard,Ui.dp(this,Ui.SIDE_RADIUS));addCard.setOnClickListener(v->{hangReplace=false;hangForAdd();});stage.addView(addCard);
  more=Ui.more(this);more.setOnClickListener(v->primaryMenu());
  status=Ui.text(this,"正在准备窗口…",12,Ui.TEXT);status.setGravity(Gravity.CENTER);status.setBackground(Ui.bg(0x99000000,Ui.dp(this,8)));status.setPadding(Ui.dp(this,12),Ui.dp(this,6),Ui.dp(this,12),Ui.dp(this,6));status.setVisibility(View.GONE);
  FrameLayout.LayoutParams statusLp=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);statusLp.bottomMargin=Ui.dp(this,16);stage.addView(status,statusLp);
  try{
   if(!"com.oplus.pscanvas".equals(getPackageName()))throw new IllegalStateException("需要从系统容器启动");
   viewApi=Class.forName("com.oplus.flexiblewindow.FlexibleTaskView");resizeMethod=viewApi.getMethod("resize",Rect.class);interceptInput=viewApi.getDeclaredField("mInterceptInputEvent");interceptInput.setAccessible(true);managerApi=Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager");manager=managerApi.getMethod("getInstance").invoke(null);
   rotateTaskLeash=viewApi.getDeclaredField("mNeedRotateTaskLeash");rotateTaskLeash.setAccessible(true);cornerRadius=viewApi.getDeclaredField("mCornerRadius");cornerRadius.setAccessible(true);
   try{taskLeash=viewApi.getDeclaredField("mTaskLeash");taskLeash.setAccessible(true);reparentAlign=viewApi.getDeclaredField("mReparentAlign");reparentAlign.setAccessible(true);}
   catch(Throwable e){Log.w(TAG,"leash_fields_unavailable",e);}
   try{
    viewTransaction=viewApi.getDeclaredField("mTransaction");viewTransaction.setAccessible(true);
    leashMatrix=SurfaceControl.Transaction.class.getDeclaredMethod("setMatrix",SurfaceControl.class,float.class,float.class,float.class,float.class);leashMatrix.setAccessible(true);
    leashMatrix4=SurfaceControl.Transaction.class.getDeclaredMethod("setTransformationMatrix4x4",SurfaceControl.class,float[].class);leashMatrix4.setAccessible(true);
    leashRadius=SurfaceControl.Transaction.class.getDeclaredMethod("setCornerRadius",SurfaceControl.class,float.class);
    Log.i(TAG,"leash_perspective_api=matrix4x4");
   }catch(Throwable e){Log.w(TAG,"leash_perspective_unavailable",e);}
   backdrop=new WorkbenchBackdrop();backdrop.attach(this,manager);
   String[] input=state!=null?state.getStringArray("components"):null;
   if(input==null)input=new String[]{getIntent().getStringExtra("windowdeck_app_a"),getIntent().getStringExtra("windowdeck_app_b"),getIntent().getStringExtra("windowdeck_app_c")};
   int initialSource=state==null?getIntent().getIntExtra("windowdeck_create_source_task",-1):-1;
   if(initialSource>=0){
    Slot fresh=validateSourceTask(initialSource,getIntent().getIntExtra("windowdeck_create_source_user",-1));
    slots.add(fresh);pendingEntrance=fresh;createdFromGesture=true;
    input=new String[0];
   }
   int[] savedSources=state==null?null:state.getIntArray("sourceTaskIds");
   for(String component:input)if(component!=null&&!component.isEmpty()){
    if(slots.size()==3)break;Slot restored=validate(ComponentName.unflattenFromString(component),null);
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
    if(!initialized){initialized=true;updateNaturalBounds();for(Slot s:new ArrayList<>(slots))createWindow(s);if(pendingEntrance!=null&&pendingEntrance.card!=null)pendingEntrance.card.setAlpha(0f);}
    scheduleLayout();
   });
   Log.i(TAG,"workbench_created version=0.4.8-beta.2 container="+getTaskId()+" count="+slots.size());
  }catch(Throwable e){fail(e);if(getIntent().hasExtra("windowdeck_create_source_task"))finish();}
 }
 private Slot validate(ComponentName c,Slot replacing) throws Exception {
  if(c==null||c.getPackageName().equals(getPackageName())||c.getPackageName().equals("dev.windowdeck.app"))throw new IllegalArgumentException("应用参数无效");
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
   if(task==null||taskUser!=user||taskUser!=android.os.Process.myUid()/100000||task.baseActivity==null||task.topActivity==null||!task.baseActivity.getPackageName().equals(task.topActivity.getPackageName()))throw new IllegalArgumentException("任务身份或用户不匹配");
   if(((Integer)task.getClass().getMethod("getWindowingMode").invoke(task))!=1)throw new IllegalArgumentException("仅接收全屏任务");
   String pkg=task.topActivity.getPackageName();
   Intent launcher=getPackageManager().getLaunchIntentForPackage(pkg);
   if(launcher==null||launcher.getComponent()==null)throw new IllegalArgumentException("任务应用没有桌面入口");
   if(!Boolean.TRUE.equals(managerApi.getMethod("isAppSupportPocketStudio",Intent.class,int.class).invoke(manager,launcher,id)))throw new IllegalArgumentException("任务不支持嵌入");
   Slot fresh=validate(launcher.getComponent(),null);fresh.sourceTaskId=id;fresh.sourceUserId=user;fresh.activeComponent=task.topActivity;
   fresh.orientationAxis=OrientationPolicy.axis(getPackageManager().getActivityInfo(task.topActivity,0).screenOrientation);
   return fresh;
 }
 private void addExistingTask(Intent request){
  int id=request.getIntExtra("windowdeck_add_task_id",-1),user=request.getIntExtra("windowdeck_add_user_id",-1);
  int expectedContainer=request.getIntExtra("windowdeck_container_task_id",-1);
  if((pendingEntrance!=null&&pendingEntrance.taskId>=0)||addAnimation!=null){Log.i(TAG,"add_entrance_preempted slot="+(pendingEntrance==null?-1:pendingEntrance.id));if(addAnimation!=null){ValueAnimator old=addAnimation;addAnimation=null;old.cancel();}releaseEntrance();}
  boolean replace=request.getBooleanExtra("windowdeck_hang_replace",false);
  if(id<0||user<0||expectedContainer!=getTaskId()||closing||recovering||!initialized||pendingEntrance!=null||addAnimation!=null||(!replace&&slots.size()>=3)){Log.w(TAG,"add_task_rejected id="+id+" user="+user+" container="+expectedContainer+" closing="+closing+" recovering="+recovering+" initialized="+initialized+" entrance="+(pendingEntrance!=null)+" anim="+(addAnimation!=null)+" count="+slots.size()+" replace="+replace);return;}
  try{
   Slot fresh=validateSourceTask(id,user);
   cancelAnimation();
   boolean hang=request.getBooleanExtra("windowdeck_hang_place",false);
   Slot replaced=null;
   if(replace){
    replaced=slots.get(primary);if(replaced.pinned)throw new IllegalArgumentException("请先取消固定");
    slots.set(primary,fresh);
   }else if(hang&&!slots.isEmpty()){
    Slot oldMain=slots.remove(primary);slots.add(0,fresh);slots.add(oldMain);primary=0;
   }else{slots.add(fresh);primary=slots.size()-1;}
   pendingEntrance=fresh;entranceFrontReady=false;entranceFrameReady=false;
   publishState(slots.size());updateNaturalBounds();createWindow(fresh);layoutCards(false);refreshStatus();
   if(replaced!=null){replaced.quietDetach=true;releaseSlot(replaced);}
   if(fresh.card!=null)fresh.card.setAlpha(1f);
   boolean edge=(hang||replace)&&slots.size()>1,hungMain=hang&&!replace;
   if(!poseScreenArrival(fresh,edge,hungMain)){Slot arriving=fresh;stage.post(()->poseScreenArrival(arriving,edge,hungMain));}
   Log.i(TAG,"add_existing_requested slot="+fresh.id+" task="+id+" user="+user+" container="+getTaskId()+" replace="+replace);
  }catch(Throwable e){Log.w(TAG,"add_existing_failed task="+id,e);if(!hangQuiet)Toast.makeText(this,"未能加入："+(e.getMessage()==null?"原应用保持不变":e.getMessage()),Toast.LENGTH_LONG).show();}
 }
 private void revealAddedCard(Intent request){
  Slot slot=pendingEntrance;
  if(slot==null||request.getIntExtra("windowdeck_container_task_id",-1)!=getTaskId()||request.getIntExtra("windowdeck_add_task_id",-1)!=slot.sourceTaskId)return;
  entranceFrontReady=true;Log.i(TAG,"add_card_front_ready slot="+slot.id+" task="+slot.sourceTaskId);
  requestEntranceFrame();
 }
 private void abandonEntrance(Slot slot){
  if(pendingEntrance!=slot)return;
  pendingEntrance=null;entranceFrameReady=false;entranceFramePending=false;createdFromGesture=false;
  if(slot.card!=null)slot.card.setAlpha(1f);
  if(!closing)layoutCards(false);
 }
 private void requestEntranceFrame(){
  if(pendingEntrance==null||!entranceFrontReady||stopped||backgrounded||closing||stage.getWidth()==0||entranceFramePending)return;
  Slot expected=pendingEntrance;entranceFramePending=true;
  stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{
   entranceFramePending=false;
   if(pendingEntrance!=expected||stopped||backgrounded||closing)return;
   entranceFrameReady=true;maybeAnimateAddedCard();
  }));stage.invalidate();
 }
 private void maybeAnimateAddedCard(){
  Slot slot=pendingEntrance;
  if(screenArrival&&slot!=null&&entranceFrontReady&&!stopped&&!backgrounded&&!closing){pendingEntrance=null;createdFromGesture=false;entranceFrontReady=false;beginHangEntrance();return;}
  if(slot==null||!entranceFrontReady||stopped||backgrounded||closing||slot.released||slot.taskId<0)return;
  if(!entranceFrameReady){requestEntranceFrame();return;}
  if(!slot.windowDrawn&&!slot.entranceDrawTimedOut){
   if(!slot.entranceWaitScheduled){slot.entranceWaitScheduled=true;handler.postDelayed(()->{if(pendingEntrance==slot){slot.entranceDrawTimedOut=true;maybeAnimateAddedCard();}},120);}
   return;
  }
  pendingEntrance=null;createdFromGesture=false;animateAddedCard(slot);
 }
 private void releaseEntrance(){
  hangEntranceGeneration++;hangEntranceDue=false;entranceFrontReady=false;entranceFrameReady=false;entranceFramePending=false;
  Slot shown=entranceSlot!=null?entranceSlot:pendingEntrance;pendingEntrance=null;entranceSlot=null;
  if(entranceFlying!=null){if(entranceFlying.getParent()==stage)stage.removeView(entranceFlying);entranceFlying=null;}
  if(shown!=null&&shown.card!=null)shown.card.setAlpha(1f);
  for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
 }
 private void beginHangEntrance(){
  if(!hangEntranceDue||closing||!screenArrival||stopped||backgrounded||animation!=null)return;
  hangEntranceDue=false;playScreenArrival();
 }
 private boolean poseScreenArrival(Slot fresh,boolean fromEdge,boolean hungMain){
  if(fresh==null||fresh.card==null||stage.getWidth()==0||slots.isEmpty())return false;
  fresh.card.setAlpha(1f);
  int[] loc=new int[2];stage.getLocationOnScreen(loc);
  Rect screen=getWindowManager().getCurrentWindowMetrics().getBounds();
  int[][] target=cardGeometry().cards;if(target.length!=slots.size())return false;
  java.util.HashMap<Slot,Rect> edge=fromEdge?edgeRects(fresh,target,loc,screen,hungMain):new java.util.HashMap<>();
  int[][] from=new int[slots.size()][4];
  for(int i=0;i<slots.size();i++){
   Slot s=slots.get(i);Rect placed=edge.get(s);
   if(s==fresh)from[i]=new int[]{-loc[0],-loc[1],Math.max(1,screen.width()),Math.max(1,screen.height())};
   else if(placed!=null)from[i]=new int[]{placed.left-loc[0],placed.top-loc[1],Math.max(1,placed.width()),Math.max(1,placed.height())};
   else from[i]=new int[]{target[i][0],target[i][1],target[i][2],target[i][3]};
   apply(s,target[i]);transformCard(s,from[i],target[i],0f);
  }
  raiseMain();arrivalSlot=fresh;arrivalFrom=from;screenArrival=true;hangEntranceDue=true;
  handler.removeCallbacks(arrivalUnlock);handler.postDelayed(arrivalUnlock,1500);
  if(!stopped&&!backgrounded)handler.post(this::beginHangEntrance);
  Log.i(TAG,"arrive_posed edge="+fromEdge+" screen="+screen.width()+"x"+screen.height());
  return true;
 }
 private java.util.HashMap<Slot,Rect> edgeRects(Slot fresh,int[][] target,int[] loc,Rect screen,boolean hungMain){
  java.util.HashMap<Slot,Rect> map=new java.util.HashMap<>();
  if(slots.size()<2)return map;
  ArrayList<Slot> order=new ArrayList<>();ArrayList<HangShelf.Card> cards=new ArrayList<>();
  Slot previous=null;
  if(hungMain){previous=slots.get(slots.size()-1);if(previous==fresh&&slots.size()>2)previous=slots.get(slots.size()-2);if(previous!=null&&previous!=fresh){order.add(previous);cards.add(new HangShelf.Card(null,previous.component.getPackageName(),placed(previous,target,loc),true));}}
  for(Slot s:slots)if(s!=fresh&&s!=previous){order.add(s);cards.add(new HangShelf.Card(null,s.component.getPackageName(),placed(s,target,loc),false));}
  if(cards.isEmpty())return map;
  Rect[] ends=HangShelf.ends(cards,screen,effectiveMode()==PaneLayout.TOP_BOTTOM,screen.height()>screen.width(),Ui.dp(this,20),Ui.dp(this,48));
  for(int i=0;i<order.size()&&i<ends.length;i++)map.put(order.get(i),ends[i]);
  return map;
 }
 private Rect placed(Slot s,int[][] target,int[] loc){int i=slots.indexOf(s);int[] r=target[Math.max(0,i)];return new Rect(r[0]+loc[0],r[1]+loc[1],r[0]+loc[0]+r[2],r[1]+loc[1]+r[3]);}
 private void playScreenArrival(){
  if(!screenArrival||arrivalFrom==null||animation!=null||closing)return;
  // The entrance flag swallows every card touch. Drop it before the spring so the end state can receive input.
  pendingEntrance=null;entranceFrontReady=false;entranceFrameReady=false;entranceFramePending=false;createdFromGesture=false;
  final int[][] from=arrivalFrom;final int[][] target=cardGeometry().cards;
  final ArrayList<Slot> current=new ArrayList<>(slots);
  animation=ValueAnimator.ofFloat(0f,1f);animation.setDuration(600);animation.setInterpolator(HangShelf::settle);
  for(int i=0;i<current.size();i++)inputRole(current.get(i),i==primary);
  animation.addUpdateListener(a->{float f=(Float)a.getAnimatedValue();for(int i=0;i<current.size()&&i<from.length&&i<target.length;i++)if(current.get(i).card!=null)transformCard(current.get(i),from[i],target[i],f);});
  animation.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){if(animation!=a)return;animation=null;finishArrival();handler.post(()->focusPrimary("arrive"));}});
  animation.start();Log.i(TAG,"arrive_animation_start slots="+current.size());
 }
 private void finishArrival(){
  handler.removeCallbacks(arrivalUnlock);
  if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}
  boolean locked=screenArrival||pendingEntrance!=null||settlingInput;
  screenArrival=false;hangEntranceDue=false;arrivalSlot=null;arrivalFrom=null;
  pendingEntrance=null;entranceFrontReady=false;entranceFrameReady=false;entranceFramePending=false;createdFromGesture=false;settlingInput=false;
  if(settleListener!=null&&stage!=null){stage.getViewTreeObserver().removeOnPreDrawListener(settleListener);settleListener=null;}
  for(Slot s:slots)if(s.card!=null)resetCardTransform(s);
  for(int i=0;i<slots.size();i++)if(slots.get(i).card!=null)inputRole(slots.get(i),i==primary);
  try{Method method=viewApi.getMethod("setNoNeedStartEmbedded",boolean.class);for(Slot s:slots)if(s.surface!=null)method.invoke(s.surface,false);}catch(Throwable ignored){}
  layoutCaption();
  if(locked)Log.i(TAG,"arrive_unlocked");
 }
 private void animateAddedCard(Slot slot){
  if(slot.card==null)return;
  int index=slots.indexOf(slot);if(index<0||stage.getWidth()==0||stage.getHeight()==0)return;
  int[] target=cardGeometry().cards[index];
  int width=target[2],height=target[3];
  float startScale=Math.min(0.45f,Ui.dp(this,112)/(float)Math.max(1,width));
  float startX=(stage.getWidth()-width*startScale)/2f,startY=Ui.dp(this,40);
  if(addAnimation!=null){addAnimation.cancel();addAnimation=null;}
  Bitmap snapshot=null;
  try{Method capture=viewApi.getDeclaredMethod("getSnapBitMap",boolean.class);capture.setAccessible(true);snapshot=(Bitmap)capture.invoke(slot.surface,false);}
  catch(Throwable e){Log.w(TAG,"add_snapshot_unavailable slot="+slot.id,e);}
  ImageView flying=new ImageView(this);flying.setBackgroundColor(0xff202838);flying.setClickable(false);
  if(snapshot!=null){flying.setImageBitmap(snapshot);flying.setScaleType(ImageView.ScaleType.FIT_XY);}
  else {try{flying.setImageDrawable(getPackageManager().getActivityIcon(slot.component));}catch(Throwable ignored){}flying.setScaleType(ImageView.ScaleType.CENTER_INSIDE);}
  flying.setPivotX(0f);flying.setPivotY(0f);flying.setElevation(Ui.dp(this,24));
  entranceFlying=flying;entranceSlot=slot;stage.addView(flying,new FrameLayout.LayoutParams(width,height));flying.bringToFront();
  flying.setTranslationX(startX);flying.setTranslationY(startY);flying.setScaleX(startScale);flying.setScaleY(startScale);
  Ui.round(flying,Ui.dp(this,Ui.MAIN_RADIUS));
  if(slot.card!=null)slot.card.setAlpha(0f);
  ValueAnimator entrance=ValueAnimator.ofFloat(0f,1f);addAnimation=entrance;
  for(int i=0;i<slots.size();i++)inputRole(slots.get(i),i==primary);
  final int[] frames={0};
  entrance.setDuration(650);entrance.setInterpolator(f->1f-(1f-f)*(1f-f)*(1f-f));
  entrance.addUpdateListener(a->{if(addAnimation!=a)return;frames[0]++;float f=(Float)a.getAnimatedValue();
   flying.setTranslationX(startX+(target[0]-startX)*f);flying.setTranslationY(startY+(target[1]-startY)*f);
   flying.setScaleX(startScale+(1f-startScale)*f);flying.setScaleY(startScale+(1f-startScale)*f);
  });
  entrance.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){
   if(addAnimation!=a)return;addAnimation=null;if(entranceFlying==flying)entranceFlying=null;if(entranceSlot==slot)entranceSlot=null;stage.removeView(flying);if(slot.card!=null)slot.card.setAlpha(1f);raiseMain();layoutCaption();
   for(int i=0;i<slots.size();i++)inputRole(slots.get(i),i==primary);
   if(!closing&&!stopped&&!backgrounded)focusPrimary("add_animation_end");
   Log.i(TAG,"add_card_animation_end slot="+slot.id+" frames="+frames[0]);
  }});
  entrance.start();
  Log.i(TAG,"add_card_animation slot="+slot.id+" snapshot="+(snapshot!=null)+" from="+width+"x"+height+" target="+target[2]+"x"+target[3]);
 }
 private void hangForReplace(){
  if(closing||!initialized||recovering||dragging!=null||hanging||slots.isEmpty())return;
  if(slots.get(primary).pinned){Toast.makeText(this,"请先取消固定",Toast.LENGTH_SHORT).show();return;}
  hangReplace=true;hangForAdd();if(!hanging)hangReplace=false;
 }
 private void hangForAdd(){
  if(closing||!initialized||recovering||dragging!=null||hanging)return;
  if(!hangReplace&&slots.size()>=3){Toast.makeText(this,"最多同时打开三个应用",Toast.LENGTH_SHORT).show();return;}
  ArrayList<HangShelf.Card> cards=new ArrayList<>();
  Slot main=slots.get(primary);cards.add(new HangShelf.Card(hangFace(main),main.component.getPackageName(),screenRect(main.card),true));
  for(Slot s:slots)if(s!=main&&s.card!=null)cards.add(new HangShelf.Card(hangFace(s),s.component.getPackageName(),screenRect(s.card),false));
  try{hangShelf.show(this,cards,effectiveMode()==PaneLayout.TOP_BOTTOM,this::restoreFromHang);}
  catch(Throwable e){Log.w(TAG,"hang_show_failed",e);hangShelf.hide();if(hangReplace){hangReplace=false;chooseApp(slots.get(primary));}else chooseApp(null);return;}
  hanging=true;hangSawHome=false;hangStarted=SystemClock.uptimeMillis();hangTask=-1;hangUser=-1;hangNote=null;hangStableId=-1;hangStableHits=0;handler.removeCallbacks(hangConsume);
  if(!backgroundWorkbench(hangReplace?"hang_replace":"hang_add")){hanging=false;hangReplace=false;hangShelf.hide();return;}
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
   if(!hangSawHome&&SystemClock.uptimeMillis()-hangStarted>800)hangSawHome=true;
   boolean embedded=false;for(Slot s:slots)if(s.taskId==id)embedded=true;
   if(home||embedded||!hangSawHome){hangStableId=-1;hangStableHits=0;handler.postDelayed(hangWatch,300);return;}
   String pkg=topActivity.getPackageName();
   for(Slot s:slots)if(s.component.getPackageName().equals(pkg)){hanging=false;hangReplace=false;handler.removeCallbacks(hangWatch);hangShelf.hide();hangNote="已在工作台";Log.i(TAG,"hang_existing pkg="+pkg+" task="+id);bringWorkbenchForward();return;}
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
  hanging=false;handler.removeCallbacks(hangWatch);hangShelf.hide();
  for(Slot s:slots)if(s.component.getPackageName().equals(pkg)){hangReplace=false;hangNote="已在工作台";Log.i(TAG,"hang_existing pkg="+pkg);bringWorkbenchForward();return;}
  hangTask=task;hangUser=user;hangConsumeTries=0;Log.i(TAG,"hang_pick task="+task+" pkg="+pkg);ensureHangConsumed();
 }
 private void ensureHangConsumed(){
  if(closing||hangTask<0)return;
  if(stopped||backgrounded){bringWorkbenchForward();moveHangContainerFront();scheduleHangRetry();return;}
  hangQuiet=true;consumeHangAdd();hangQuiet=false;
  if(hangTask>=0)scheduleHangRetry();
 }
 private void scheduleHangRetry(){
  if(++hangConsumeTries>10){Log.w(TAG,"hang_consume_gave_up task="+hangTask);hangTask=-1;hangUser=-1;pendingHangFront=-1;hangReplace=false;Toast.makeText(this,"未能加入，请再试一次",Toast.LENGTH_SHORT).show();bringWorkbenchForward();moveHangContainerFront();return;}
  handler.postDelayed(hangConsume,250);
 }
 private void moveHangContainerFront(){
  try{ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);am.getClass().getMethod("moveTaskToFront",int.class,int.class).invoke(am,getTaskId(),0);Log.i(TAG,"hang_move_front container="+getTaskId());}
  catch(Throwable e){Log.w(TAG,"hang_move_front_failed",e);}
 }
 private void restoreFromHang(){
  if(!hanging)return;
  hanging=false;hangReplace=false;handler.removeCallbacks(hangWatch);hangShelf.hide();
  Log.i(TAG,"hang_restored");bringWorkbenchForward();
 }
 private void bringWorkbenchForward(){try{reorderTask(getTaskId(),true);Log.i(TAG,"hang_front container="+getTaskId());}catch(Throwable e){Log.w(TAG,"hang_front_failed",e);}}
 // Focusing this container makes ColorOS mark each embedded task always-on-top and move it to the front.
 // After the new task is in, drop that flag and raise only the container. Block the resume restart that would undo it.
 private void raiseHangContainer(){
  if(closing||isFinishing())return;
  try{Method method=viewApi.getMethod("setNoNeedStartEmbedded",boolean.class);for(Slot s:slots)if(s.surface!=null)method.invoke(s.surface,true);}
  catch(Throwable e){Log.w(TAG,"hang_suppress_failed",e);}
  try{settleHangContainer();}catch(Throwable e){Log.w(TAG,"hang_settle_failed",e);bringWorkbenchForward();}
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
  if(!raised){bringWorkbenchForward();return;}
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
  if(replacing==null&&slots.size()>=3){Toast.makeText(this,"最多同时打开三个应用",Toast.LENGTH_SHORT).show();return;}
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
  try{
   updateRenderBounds(slot);
   slot.card=new PreviewCard(this);applyCardRadius(slot,slots.indexOf(slot)==primary);
   slot.card.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
    if(!closing&&!slot.released&&r>l&&b>t){fitRecoveryCover(slot,r-l,b-t);scheduleLeashFit(slot,r-l,b-t);}
   });
   slot.card.setOnClickListener(v->promote(slots.indexOf(slot)));
   slot.card.beginDrag=()->beginDrag(slot);slot.card.cancelDrag=()->finishDrag(slot,false);slot.card.dismiss=()->finishDrag(slot,true);
   slot.card.setOnLongClickListener(v->{if(!switching())menu(slot);return true;});
   slot.surface=(View)viewApi.getConstructor(Context.class).newInstance(this);
   EmbeddedTaskGuard.watch(slot.surface,getTaskId(),embedded->{
    slot.embedded=embedded;
    if(!embedded){clearProjection(slot);Log.i(TAG,"task_transform_released slot="+slot.id+" task="+slot.taskId);}
   });
   ((SurfaceView)slot.surface).getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
   ((SurfaceView)slot.surface).getHolder().addCallback(new SurfaceHolder.Callback(){
    public void surfaceCreated(SurfaceHolder holder){restorePlate();}
    public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){restorePlate();}
    public void surfaceDestroyed(SurfaceHolder holder){}
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
     ComponentName top=(ComponentName)args[1];if(top!=null&&!top.equals(slot.activeComponent)){slot.activeComponent=top;try{slot.orientationAxis=OrientationPolicy.axis(getPackageManager().getActivityInfo(top,0).screenOrientation);handler.post(this::scheduleLayout);}catch(PackageManager.NameNotFoundException ignored){}}
     int id=(Integer)args[0];if(slot.sourceTaskId>=0&&slot.taskId<0&&id!=slot.sourceTaskId){slot.failed=true;Log.e(TAG,"add_existing_identity_mismatch expected="+slot.sourceTaskId+" actual="+id);handler.post(()->removeSlot(slot,true));return null;}
     if(slot.taskId!=id)Log.i(TAG,"task_ready slot="+slot.id+" task="+id);slot.taskId=id;slot.failed=false;refreshStatus();handler.post(()->{syncSurface(slot);focusPrimary("task_ready");maybeAnimateAddedCard();if(id==pendingHangFront){pendingHangFront=-1;raiseHangContainer();handler.postDelayed(this::raiseHangContainer,220);handler.postDelayed(this::raiseHangContainer,480);}});
    }else if(m.getName().equals("onTaskRectOrientationChanged")){
     ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)args[0];Rect requested=args[1] instanceof Rect?new Rect((Rect)args[1]):null;
     if(info.taskId==slot.taskId&&requested!=null&&!requested.isEmpty()&&requested.width()!=requested.height()){
      int axis=requested.width()>requested.height()?2:1;
      if(slot.orientationAxis!=axis){slot.orientationAxis=axis;Log.i(TAG,"task_orientation slot="+slot.id+" axis="+axis);handler.post(this::scheduleLayout);}
     }
    }else if(m.getName().equals("onInitialized")&&Boolean.FALSE.equals(args[0])){slot.failed=true;Log.e(TAG,"task_start_failed slot="+slot.id);handler.post(()->abandonEntrance(slot));refreshStatus();}
    else if(m.getName().equals("onTaskWindowDraw")){Log.i(TAG,"task_draw slot="+slot.id+" drawn="+args[1]);if(Boolean.TRUE.equals(args[1])){slot.windowDrawn=true;handler.post(()->{syncSurface(slot);maybeAnimateAddedCard();});}}
    else if(m.getName().equals("onBackPressedOnTaskRoot")){int task=(Integer)args[0];handler.post(()->handleTaskRootBack(slot,task));}
    else if(m.getName().equals("onTaskRemovalStarted")){int task=(Integer)args[0];handler.post(()->onEmbeddedTaskVanished(slot,task));}
    return null;
   });
   viewApi.getMethod("setListener",Executor.class,listener).invoke(slot.surface,(Executor)this::runOnUiThread,proxy);
   Bundle config=new Bundle();config.putInt("scenario",2);config.putParcelable("launchBounds",new Rect(slot.renderBounds));
   config.putBoolean("need_rotate_task_leash",rotatePresentation(slot));
   // Native rotation otherwise promotes the Surface above the host window,
   // bypassing preview interception and recovery covers. Keep host controls on top.
   config.putBoolean("zorder_on_top",false);
   if(slot.sourceTaskId>=0)config.putInt("taskId",slot.sourceTaskId);
   else config.putParcelable("intent",new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(slot.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   config.putInt("userId",slot.sourceTaskId>=0?slot.sourceUserId:android.os.Process.myUid()/100000);config.putFloat("cornerRadius",cardRadius(slots.indexOf(slot)==primary));config.putInt("reparent_align",slots.indexOf(slot)==primary?0:2);
   config.putBoolean("intercept_input_event",true);config.putBoolean("allow_task_detach_from_embedding",true);config.putBoolean("key_intercept_back_key",true);config.putBoolean("flexible_key_remove_task_detach",false);config.putInt("use_view_snapshot",1);
   viewApi.getMethod("init",Bundle.class).invoke(slot.surface,config);viewApi.getMethod("setEnforceStart",boolean.class).invoke(slot.surface,true);
   if(viewTransaction!=null){slot.transaction=(SurfaceControl.Transaction)viewTransaction.get(slot.surface);LeashTransactions.register(slot.transaction,t->writeLeashFit(slot,t));}
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
 private void dismissPrimaryMenu(){if(primaryPopup!=null&&primaryPopup.isShowing())primaryPopup.dismiss();primaryPopup=null;}
 private void primaryMenu(){
  if(closing||recovering||dragging!=null||slots.isEmpty()||more==null||more.getVisibility()!=View.VISIBLE)return;
  dismissPrimaryMenu();
  Slot main=slots.get(primary);
  LinearLayout panel=new LinearLayout(this);panel.setOrientation(1);panel.setBackground(Ui.bg(0xffffffff,Ui.dp(this,12)));panel.setElevation(Ui.dp(this,12));
  panel.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,4));
  String[] items={"切换全屏","切换布局","替换应用","关闭应用"};
  for(int i=0;i<items.length;i++){
   final int which=i;
   TextView row=Ui.text(this,items[i],16,0xff1c1c1c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);
   row.setMinHeight(Ui.dp(this,48));row.setClickable(true);row.setBackground(Ui.bg(0xffffffff,0));
   row.setOnClickListener(v->{dismissPrimaryMenu();
    if(closing||recovering||dragging!=null||slots.isEmpty()||slots.get(primary)!=main)return;
    if(which==0)exitToFullscreen();else if(which==1)toggleLayout();else if(which==2)hangForReplace();else removeSlot(main);
   });
   panel.addView(row,new LinearLayout.LayoutParams(Ui.dp(this,168),Ui.dp(this,48)));
  }
  primaryPopup=new PopupWindow(panel,Ui.dp(this,168),-2,true);primaryPopup.setOutsideTouchable(true);primaryPopup.setElevation(Ui.dp(this,12));
  primaryPopup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
  primaryPopup.setOnDismissListener(()->{primaryPopup=null;modalWindows=Math.max(0,modalWindows-1);handler.post(()->focusPrimary("menu_dismiss"));});
  modalWindows++;
  panel.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(this,168),View.MeasureSpec.EXACTLY),View.MeasureSpec.UNSPECIFIED);
  int xoff=(more.getWidth()-panel.getMeasuredWidth())/2;
  try{primaryPopup.showAsDropDown(more,xoff,Ui.dp(this,4));Log.i(TAG,"primary_menu_open");}
  catch(Throwable e){modalWindows=Math.max(0,modalWindows-1);primaryPopup=null;Log.w(TAG,"primary_menu_failed",e);}
 }
 void showWorkbenchDialog(AlertDialog dialog){modalWindows++;dialog.setOnDismissListener(d->{modalWindows=Math.max(0,modalWindows-1);handler.post(()->focusPrimary("dialog_dismiss"));});dialog.show();}
 private String layoutLabel(){return layoutMode==PaneLayout.TOP_BOTTOM?"上下":"左右";}
 private void toggleLayout(){
  if(closing||!initialized||recovering||dragging!=null)return;
  cancelAnimation();layoutMode=layoutMode==PaneLayout.LEFT_RIGHT?PaneLayout.TOP_BOTTOM:PaneLayout.LEFT_RIGHT;
  scheduleLayout();
  Log.i(TAG,"layout_mode="+layoutMode+" tasks="+taskIds());
 }
 private int effectiveMode(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE?PaneLayout.LEFT_RIGHT:layoutMode;}
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
 private int captionHeight(){return Ui.dp(this,36);}
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
  int w=Ui.dp(this,64),h=captionHeight();
  int[] loc=new int[2];stage.getLocationInWindow(loc);
  int x=loc[0]+main[0]+Math.max(0,(main[2]-w)/2);
  int y=loc[1]+main[1];
  WindowManager.LayoutParams lp;
  if(!captionAttached){
   android.os.IBinder token=getWindow().getDecorView().getWindowToken();
   if(token==null){stage.post(this::layoutCaption);return;}
   lp=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,android.graphics.PixelFormat.TRANSLUCENT);
   lp.token=token;lp.gravity=Gravity.TOP|Gravity.LEFT;lp.setTitle("WindowDeckCaption");lp.x=x;lp.y=y;
   try{getWindowManager().addView(more,lp);captionAttached=true;Log.i(TAG,"caption_attached x="+x+" y="+y);}
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
  boolean preview=!isPrimary||switching()||recovering||pendingEntrance!=null||addAnimation!=null;s.card.preview=preview;
  s.card.gesturesEnabled=!recovering&&pendingEntrance==null&&addAnimation==null;s.card.vertical=effectiveMode()==PaneLayout.TOP_BOTTOM;s.pin.setVisibility(s.pinned?View.VISIBLE:View.GONE);
  s.card.setContentDescription(s.label+(isPrimary?"主应用，顶部菜单":"，点击切换，长按管理"));
  s.icon.setVisibility(isPrimary?View.GONE:View.VISIBLE);
  applyCardRadius(s,isPrimary);
  try{if(interceptInput.getBoolean(s.surface)!=preview){interceptInput.setBoolean(s.surface,preview);s.surface.requestLayout();}}catch(Throwable e){fail(e);}
 }
 private int cardRadius(boolean isPrimary){return Ui.dp(this,isPrimary?Ui.MAIN_RADIUS:Ui.SIDE_RADIUS);}
 private void applyCardRadius(Slot s,boolean isPrimary){
  if(s.card==null)return;
  int radius=cardRadius(isPrimary);
  s.card.setBackground(isPrimary?Ui.bg(Ui.CARD,radius):null);clipSide(s.card,s.card.getWidth(),s.card.getHeight(),!isPrimary);
  if(s.surface==null||cornerRadius==null)return;
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
 }
 private void updateRenderBounds(Slot s){
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  int[] size=OrientationPolicy.bounds(naturalBounds.width(),naturalBounds.height(),dm.widthPixels,dm.heightPixels,s.orientationAxis);
  if(s.renderBounds.width()!=size[0]||s.renderBounds.height()!=size[1]){s.renderBounds.set(0,0,size[0],size[1]);Log.i(TAG,"render_bounds slot="+s.id+" axis="+s.orientationAxis+" size="+size[0]+"x"+size[1]);}
 }
 private void resizeSurface(Slot s){
  if(s.surface==null||s.released||s.renderBounds.isEmpty())return;
  boolean measure=switching();long start=measure?System.nanoTime():0;
  try{resizeMethod.invoke(s.surface,new Rect(s.renderBounds));}catch(Throwable e){fail(e);}
  finally{if(measure){resizeCalls++;resizeNanos+=System.nanoTime()-start;}}
  int[] plate=surfacePlate(s);scheduleLeashFit(s,plate[0],plate[1]);
 }
 private void reparentSurface(Slot s){
  if(s.surface==null||s.released||!s.embedded||stopped||backgrounded)return;
  try{Method method=viewApi.getDeclaredMethod("reparent");method.setAccessible(true);method.invoke(s.surface);s.surface.setBackground(null);
   // reparent may return early while a task is being restored. Reapply our
   // display transform explicitly, including when the plate was recreated.
   try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){writeLeashFit(s,fit);fit.apply();}}
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
 @Override protected void onPause(){dismissPrimaryMenu();cancelDrag();if(!screenArrival)cancelAnimation();if(initialized&&!closing&&!screenArrival)layoutCards(false);super.onPause();}
 @Override protected void onResume(){super.onResume();stopped=false;backgrounded=false;if(createdFromGesture&&pendingEntrance!=null)entranceFrontReady=true;if(backdrop!=null)backdrop.onResume();handler.post(this::restorePendingTasks);handler.post(this::consumeHangAdd);handler.post(this::beginHangEntrance);handler.postDelayed(()->{if(stopped||backgrounded||closing)return;for(Slot s:slots)syncSurface(s);focusPrimary("resume");maybeAnimateAddedCard();},350);}
 @Override protected void onStop(){stopped=true;for(Slot s:slots)clearProjection(s);backGeneration++;forwardingBack=false;super.onStop();}
 // Do not refocus on every host touch: doing so cancels a preview's click or
 // long-press stream. Resume, settled promotion and dialog dismissal own focus.
 @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);backgrounded=false;stopped=false;if(intent.hasExtra("windowdeck_add_task_id"))handler.post(()->addExistingTask(intent));Log.i(TAG,"workbench_resume container="+getTaskId()+" primary="+primary+" layout="+layoutMode+" tasks="+taskIds());handler.post(()->{for(Slot s:slots)syncSurface(s);focusPrimary("entry");});}
 private void scheduleLayout(){if(layoutPosted)return;layoutPosted=true;stage.post(()->{layoutPosted=false;if(!closing){cancelDrag();if(!screenArrival){cancelAnimation();updateNaturalBounds();layoutCards(false);}for(Slot s:slots)resizeSurface(s);requestEntranceFrame();Log.i(TAG,"layout width="+stage.getWidth()+" height="+stage.getHeight()+" ime="+imeBottom+" count="+slots.size());}});}
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
  s.surface.setClipBounds(preview&&effectiveMode()!=PaneLayout.LEFT_RIGHT?new Rect(0,0,w,h):null);
  fitRecoveryCover(s,w,h);
  scheduleLeashFit(s,w,h);
 }
 private float[] displayQuad(Slot s,int w,int h){
  return slots.indexOf(s)!=primary&&effectiveMode()==PaneLayout.LEFT_RIGHT&&leashMatrix4!=null
   ?CardPerspective.columnQuad(w,h,getResources().getDisplayMetrics().density,stage.getHeight()/2f-s.card.getTop())
   :new float[]{0,0,w,0,w,h,0,h};
 }
 private void fitRecoveryCover(Slot s,int w,int h){
  if(s.recoveryCover==null)return;
  RecoveryCover cover=s.recoveryCover;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)cover.getLayoutParams();
  if(lp.width!=w||lp.height!=h){lp.width=w;lp.height=h;cover.setLayoutParams(lp);}
  int[] src=presentationSize(s),crop=slots.indexOf(s)!=primary?SurfaceFit.coverCrop(src[0],src[1],w,h):new int[]{0,0,src[0],src[1]};
  cover.fit(SurfaceFit.sourceQuad(src[0],crop,rotatePresentation(s)),displayQuad(s,w,h),s.renderBounds.width(),s.renderBounds.height());
 }
 private void scheduleLeashFit(Slot s,int w,int h){
  if(s.surface==null)return;
  s.surface.post(()->{if(!closing&&!s.released&&s.surface!=null)reparentSurface(s);});
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
 private void writeLeashFit(Slot s,SurfaceControl.Transaction t){
  if(closing||stopped||backgrounded||!s.embedded||s.released||s.surface==null||s.card==null||taskLeash==null)return;
  int w=s.surface.getWidth(),h=s.surface.getHeight();if(w<2||h<2)return;
  boolean preview=slots.indexOf(s)!=primary,rotate=rotatePresentation(s);
  try{
   SurfaceControl leash=(SurfaceControl)taskLeash.get(s.surface);
   SurfaceControl plate=((SurfaceView)s.surface).getSurfaceControl();
   if(leash==null||!leash.isValid()||plate==null||!plate.isValid())return;
   t.setCrop(plate,new Rect(0,0,w,h));
   try{Field bg=SurfaceView.class.getDeclaredField("mBackgroundControl");bg.setAccessible(true);SurfaceControl background=(SurfaceControl)bg.get(s.surface);if(background!=null&&background.isValid())t.setVisibility(background,false);}catch(Throwable ignored){}
   boolean perspective=preview&&effectiveMode()==PaneLayout.LEFT_RIGHT&&leashMatrix4!=null;
   if(!perspective&&leashMatrix4!=null)leashMatrix4.invoke(t,leash,(Object)identity4());
   s.perspectiveApplied=perspective;
   if(s.renderBounds.isEmpty())return;
   int[] src=presentationSize(s),crop=preview?SurfaceFit.coverCrop(src[0],src[1],w,h):new int[]{0,0,src[0],src[1]};
   float x=crop[0],y=crop[1],r=x+crop[2],b=y+crop[3];
   float[] source=SurfaceFit.sourceQuad(src[0],crop,rotate);
   Rect rect=rotate?new Rect((int)y,src[0]-(int)r,(int)b,src[0]-(int)x):new Rect((int)x,(int)y,(int)r,(int)b);
   float[] target=displayQuad(s,w,h);
   t.setCrop(plate,new Rect(0,(int)Math.floor(Math.min(0,target[3])),w,(int)Math.ceil(Math.max(h,target[5]))));
   Matrix map=new Matrix();if(!map.setPolyToPoly(source,0,target,0,4))return;
   float[] m=new float[9];map.getValues(m);
   t.setCrop(leash,rect);
   if(leashRadius!=null)leashRadius.invoke(t,leash,preview?Ui.dp(this,Ui.SIDE_RADIUS)/SurfaceFit.coverScale(crop,w,h):0f);
   // A native wallpaper underlay fills the SurfaceView hole. No rectangular
   // card-colored layer may remain outside the projective task outline.
   if(perspective){
    Matrix base=new Matrix();base.setPolyToPoly(source,0,new float[]{0,0,w,0,w,h,0,h},0,4);
    float[] affine=new float[9];base.getValues(affine);
    leashMatrix.invoke(t,leash,affine[0],affine[3],affine[1],affine[4]);t.setPosition(leash,affine[2],affine[5]);
    Matrix inverse=new Matrix();if(!base.invert(inverse))return;
    Matrix tilt=new Matrix();tilt.setConcat(inverse,map);tilt.getValues(m);
    float[] m4={m[0],m[1],0,m[2],m[3],m[4],0,m[5],0,0,1,0,m[6],m[7],0,m[8]};
    leashMatrix4.invoke(t,leash,(Object)m4);s.projectedLeash=leash;
   }else if(leashMatrix!=null){
    leashMatrix.invoke(t,leash,m[0],m[3],m[1],m[4]);t.setPosition(leash,m[2],m[5]);
   }
   if(++s.leashWrites<=3)Log.i(TAG,"leash_atomic slot="+s.id+" perspective="+perspective+" rotate="+rotate+" plate="+w+"x"+h+" matrix="+java.util.Arrays.toString(m));
  }catch(Throwable e){Log.w(TAG,"leash_fit_failed slot="+s.id,e);}
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
   boolean projective=side&&effectiveMode()==PaneLayout.LEFT_RIGHT;
   card.setClipToOutline(!projective);
   if(card instanceof android.view.ViewGroup)((android.view.ViewGroup)card).setClipChildren(!projective);
  }
 }
 private void layoutCards(boolean animate){
  if(slots.isEmpty()||closing)return;CardLayout.Result geometry=cardGeometry();layoutAddCard(geometry.add);int[][] target=geometry.cards;final ArrayList<Slot> current=new ArrayList<>(slots);int[][] from=new int[current.size()][4];
  for(int i=0;i<current.size();i++){Slot s=current.get(i);if(s.card==null)return;from[i]=new int[]{Math.round(s.card.getX()),Math.round(s.card.getY()),Math.round(s.card.getWidth()*s.card.getScaleX()),Math.round(s.card.getHeight()*s.card.getScaleY())};inputRole(s,i==primary);}
  if(!animate){for(int i=0;i<current.size();i++){resetCardTransform(current.get(i));apply(current.get(i),target[i]);}raiseMain();layoutCaption();return;}
  final int generation=++switchGeneration;
  switchStarted=SystemClock.uptimeMillis();lastAnimationFrame=0;maxFrameGap=0;animationFrames=0;resizeCalls=0;resizeNanos=0;
  animation=ValueAnimator.ofFloat(0,1);for(int i=0;i<current.size();i++)inputRole(current.get(i),i==primary);animation.setDuration(360);animation.setInterpolator(GesturePolicy::spring);
  // Keep SurfaceView dimensions fixed throughout the transition. RenderThread
  // transforms the card and its child Surface together; no per-frame task resize.
  for(int i=0;i<current.size();i++){apply(current.get(i),target[i]);transformCard(current.get(i),from[i],target[i],0);}
  raiseMain();
  animation.addUpdateListener(a->{
   long now=SystemClock.uptimeMillis();if(lastAnimationFrame!=0)maxFrameGap=Math.max(maxFrameGap,now-lastAnimationFrame);lastAnimationFrame=now;animationFrames++;
   float f=(Float)a.getAnimatedValue();for(int i=0;i<current.size();i++)transformCard(current.get(i),from[i],target[i],f);
  });
  animation.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){if(animation!=a)return;
   animation=null;settlingInput=true;
   // Keep task input blocked until the final geometry has traversed layout.
   settleListener=new ViewTreeObserver.OnPreDrawListener(){public boolean onPreDraw(){
    stage.getViewTreeObserver().removeOnPreDrawListener(this);
    if(generation!=switchGeneration||closing)return true;
    settleListener=null;settlingInput=false;
    for(int i=0;i<slots.size();i++)inputRole(slots.get(i),i==primary);
    layoutCaption();logSwitch("settled");handler.post(()->focusPrimary("switch"));return true;
   }};stage.getViewTreeObserver().addOnPreDrawListener(settleListener);stage.invalidate();
  }});animation.start();
 }
 private void raiseMain(){
  if(primary<0||primary>=slots.size())return;
  Slot s=slots.get(primary);if(s.card!=null)s.card.bringToFront();
  if(status!=null)status.bringToFront();
 }
 private void resetCardTransform(Slot s){s.card.setTranslationX(0);s.card.setTranslationY(0);s.card.setScaleX(1);s.card.setScaleY(1);}
 private void transformCard(Slot s,int[] from,int[] target,float fraction){
  s.card.setPivotX(0);s.card.setPivotY(0);float remaining=1-fraction;
  s.card.setTranslationX((from[0]-target[0])*remaining);s.card.setTranslationY((from[1]-target[1])*remaining);
  s.card.setScaleX(1+(from[2]/(float)Math.max(1,target[2])-1)*remaining);
  s.card.setScaleY(1+(from[3]/(float)Math.max(1,target[3])-1)*remaining);
 }
 private boolean switching(){return animation!=null||settlingInput;}
 private void logSwitch(String result){Log.i(TAG,"switch_metrics result="+result+" generation="+switchGeneration+" primary="+primary+" elapsed_ms="+(SystemClock.uptimeMillis()-switchStarted)+" frames="+animationFrames+" max_frame_gap_ms="+maxFrameGap+" resize_calls="+resizeCalls+" resize_wall_us="+(resizeNanos/1000));}
 private void cancelAnimation(){
  if(addAnimation!=null){ValueAnimator old=addAnimation;addAnimation=null;old.cancel();releaseEntrance();}
  if(switching())logSwitch("cancelled");switchGeneration++;
  if(settleListener!=null){stage.getViewTreeObserver().removeOnPreDrawListener(settleListener);settleListener=null;}
  settlingInput=false;
  if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}
  if(screenArrival){screenArrival=false;hangEntranceDue=false;arrivalSlot=null;arrivalFrom=null;for(Slot s:slots)if(s.card!=null)resetCardTransform(s);}
 }
 private void promote(int index){if(closing||recovering||dragging!=null||index<0||index>=slots.size()||index==primary)return;if(slots.get(index).taskId<0){Toast.makeText(this,"请等待应用窗口就绪",Toast.LENGTH_SHORT).show();return;}cancelAnimation();primary=index;layoutCards(true);refreshStatus();Log.i(TAG,"switch primary="+primary+" tasks="+taskIds());}
 private String taskIds(){StringBuilder b=new StringBuilder();for(Slot s:slots){if(b.length()>0)b.append(',');b.append(s.taskId);}return b.toString();}
 private void refreshStatus(){runOnUiThread(()->{if(closing||slots.isEmpty())return;boolean ready=true,failed=false;for(Slot s:slots){ready&=s.taskId>=0;failed|=s.failed;}status.setVisibility(ready&&!failed?View.GONE:View.VISIBLE);status.setText(failed?"部分窗口未就绪，可长按卡片替换或移出":ready?slots.size()+" 个实时窗口 · 主应用："+slots.get(primary).label:"正在等待应用窗口…");if(status.getVisibility()==View.VISIBLE)status.bringToFront();});}
 private void fail(Throwable e){while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();Log.e(TAG,"workbench_error",e);if(status!=null){status.setVisibility(View.VISIBLE);status.setText("无法启动："+e.getClass().getSimpleName()+" · "+e.getMessage());}}
 private void exitToFullscreen(){
  if(closing||slots.isEmpty())return;
  Slot main=slots.get(primary);
  int keep=main.taskId;
  ComponentName component=main.component;
  dismissPrimaryMenu();detachCaption();
  closing=true;publishState(0);clearRecoveryCovers();cancelDrag();cancelAnimation();handler.removeCallbacksAndMessages(null);
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
   try{
    ActivityManager am=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
    am.getClass().getMethod("moveTaskToFront",int.class,int.class).invoke(am,taskId,0);
    Log.i(TAG,"fullscreen_move_front task="+taskId);
    return true;
   }catch(Throwable e){Log.w(TAG,"fullscreen_move_front_failed",e);}
   try{
    Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    Object result=Class.forName("android.app.IActivityTaskManager").getMethod("startActivityFromRecents",int.class,Bundle.class).invoke(service,taskId,null);
    Log.i(TAG,"fullscreen_recents task="+taskId+" result="+result);
    return true;
   }catch(Throwable e){Log.w(TAG,"fullscreen_recents_failed",e);}
  }
  if(component==null)return false;
  try{
   startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
   Log.i(TAG,"fullscreen_intent component="+component.flattenToShortString());
   return true;
  }catch(Throwable e){Log.w(TAG,"fullscreen_intent_failed",e);return false;}
 }
 private void releaseSlot(Slot s){releaseSlot(s,false);}
 private void releaseSlot(Slot s,boolean toFront){
  if(s.released)return;
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
  if(s.surface!=null){
   try{
    // Unlink first: moving a still-linked task can background the whole group.
    // The ROM's move-to-back path resets it without the unsafe direct
    // fullscreen transition used by resetFlexibleTask.
    if(s.taskId>=0){
     viewApi.getMethod("interceptBackPressedOnTaskRoot",boolean.class).invoke(s.surface,false);
     managerApi.getMethod("removeEmbeddedContainerTask",int.class,int.class).invoke(manager,s.taskId,getTaskId());
     if(!toFront){
      Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
      atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),s.taskId,true);
     }
    }
    // moveTaskToBack owns the server-side transition. Do not race it with
    // detachFromTaskView's second resetFlexibleTask / fullscreen transition.
    Method extra=viewApi.getDeclaredMethod("releaseExtraView");extra.setAccessible(true);extra.invoke(s.surface);
   }catch(Throwable e){Log.w(TAG,"detach_failed slot="+s.id,e);}
   try{viewApi.getMethod("release").invoke(s.surface);}catch(Throwable e){Log.w(TAG,"release_failed slot="+s.id,e);}
   s.surface=null;
  }
  if(pendingEntrance==s){pendingEntrance=null;entranceFrameReady=false;entranceFramePending=false;}
  if(s.card!=null){stage.removeView(s.card);s.card.resetGesture();}
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
 }
 private void releaseWindows(){clearRecoveryCovers();cancelDrag();cancelAnimation();handler.removeCallbacksAndMessages(null);for(Slot s:new ArrayList<>(slots))releaseSlot(s);}
 private boolean backReady(){return !closing&&!backgrounded&&!stopped&&modalWindows==0&&!recovering&&!switching()&&dragging==null&&!slots.isEmpty();}
 private boolean focusPrimary(String reason){
  if(!backReady()||stage==null||!stage.isShown())return false;
  Slot main=slots.get(primary);if(main.released||main.taskId<0)return false;
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
  if(closing||hangTask>=0)return;
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
 private void closeWorkbench(){dismissHang();dismissPrimaryMenu();detachCaption();if(closing)return;closing=true;publishState(0);releaseWindows();Log.i(TAG,"workbench_exit");finishAndRemoveTask();}
 @Override public void onBackPressed(){handleHostBack();}
 @Override protected void onDestroy(){dismissHang();dismissPrimaryMenu();detachCaption();if(addReceiverRegistered){unregisterReceiver(addReceiver);addReceiverRegistered=false;}if(backdrop!=null){backdrop.detach();backdrop=null;}getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(hostBack);if(!closing){if(!isChangingConfigurations())publishState(0);closing=true;releaseWindows();}super.onDestroy();}
 private void publishState(int count){Bundle state=new Bundle();state.putInt("containerTaskId",getTaskId());state.putInt("count",count);state.putLong("instanceToken",stateToken);try{if(getContentResolver().call(android.net.Uri.parse("content://dev.windowdeck.app.state"),"publish",null,state)!=null)return;}catch(Throwable e){Log.w(TAG,"publish_provider_failed",e);}Intent message=new Intent(LauncherIngressReceiver.STATE).setComponent(new ComponentName("dev.windowdeck.app","dev.windowdeck.app.LauncherIngressReceiver"));message.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES|Intent.FLAG_RECEIVER_FOREGROUND);message.putExtras(state);try{sendBroadcast(message);Log.i(TAG,"publish_broadcast_sent container="+getTaskId()+" count="+count);}catch(Throwable e){Log.w(TAG,"publish_state_failed",e);}}
 @Override protected void onSaveInstanceState(Bundle b){String[] components=new String[slots.size()];int[] sourceTaskIds=new int[slots.size()];for(int i=0;i<slots.size();i++){Slot s=slots.get(i);components[i]=s.component.flattenToString();sourceTaskIds[i]=s.sourceTaskId>=0?(s.taskId>=0?s.taskId:s.sourceTaskId):-1;}b.putStringArray("components",components);b.putIntArray("sourceTaskIds",sourceTaskIds);b.putInt("primary",primary);b.putInt("layoutMode",layoutMode);for(Slot s:slots)if(s.pinned)b.putString("pinned",s.component.flattenToString());super.onSaveInstanceState(b);}
 @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);Log.i(TAG,"configuration orientation="+c.orientation+" tasks="+taskIds());if(backdrop!=null)backdrop.onConfigurationChanged();if(stage!=null)scheduleLayout();}
}
