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

/** Owns hang state for the workbench. The activity is only the nested Host. */
final class HangPickController {
 interface Host {
  ValueAnimator addAnimation();
  boolean addExistingTask(Intent request);
  ValueAnimator animation();
  boolean attachTaskEntrance(WorkbenchSlot fresh,boolean replace,boolean hang,Intent request) throws Exception;
  boolean backgroundWorkbench(String reason);
  boolean backgrounded();
  void cancelAnimation();
  void cancelAnimation(boolean preservePose);
  void cancelSwitchPreparation(String reason);
  void clearRecoveryCovers();
  void closeEntranceCover(String why);
  boolean closing();
  ContentResolver contentResolver();
  Context context();
  void createWindow(WorkbenchSlot slot);
  WorkbenchSlot dragging();
  int effectiveMode();
  void fail(Throwable e);
  boolean finishing();
  void fitSurface(WorkbenchSlot s,int width,int height);
  boolean focusPrimary(String reason);
  boolean initialized();
  void layoutCaption();
  void layoutCards(boolean animate);
  void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source);
  PackageManager packageManager();
  WorkbenchSlot pendingEntrance();
  boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus);
  boolean poseScreenArrival(WorkbenchSlot fresh,boolean fromEdge,boolean hungMain,boolean originalPlus,long waitMs);
  void publishState(int count);
  void publishState(int count,int generation,int attempt);
  void queueExistingSwitch(WorkbenchSlot selected,long session);
  void raiseMain();
  boolean recovering();
  void refreshStatus();
  void releaseEntrance();
  void releaseEntranceCoverWhenDrawn();
  void releaseSlot(WorkbenchSlot s);
  void releaseSlot(WorkbenchSlot s,boolean toFront);
  void releaseSlot(WorkbenchSlot s,boolean toFront,boolean retainPlate);
  void reorderTask(int id,boolean front) throws Exception;
  ReplacementController<WorkbenchSlot> replacements();
  void restoreContainerFocus();
  void restorePendingTasks();
  void setRecovering(boolean value);
  void showNativePicker(java.util.Set<String> excluded,NativeAppPicker.Selection callback);
  long stateToken();
  boolean stopped();
  int taskId();
  void updateNaturalBounds();
  WorkbenchSlot validate(ComponentName c,WorkbenchSlot replacing) throws Exception;
  WorkbenchSlot validateSourceTask(int id,int user) throws Exception;
  WindowManager windowManager();
  void withEntranceCover(String reason,Runnable action);
  CardMotion.Pose currentPose(WorkbenchSlot s);
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final LiveContentPump pump;
 final Host host;
 HangPickController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom,LiveContentPump pump){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;this.pump=pump;
 }

 private static final String TAG="WindowDeck";

 final HangShelf hangShelf=new HangShelf();
 final IdentityHashMap<WorkbenchSlot,Rect> hangReturnRects=new IdentityHashMap<>();
 final IdentityHashMap<WorkbenchSlot,int[]> hangReturnBindings=new IdentityHashMap<>();
 SwitchMotion<WorkbenchSlot> hangReturnPoses;
 final Runnable hangWatch=this::watchHang;
 final Runnable hangConsume=this::ensureHangConsumed;
 int hangTask=-1,hangUser=-1,pendingHangFront=-1,hangEntranceGeneration,hangStableId=-1,hangStableHits,hangConsumeTries;
 void supersedeHangEntrance(){hangEntranceGeneration++;}
 boolean hanging,hangSawHome,hangQuiet,hangReplace;
 Bitmap hangSourceFrame;
 int hangSourceTask=-1,hangSourceGeneration;
 String hangNote;
 long hangStarted;
 Runnable clearHangSuppress;
 long lastSelectionSession;
 WorkbenchSlot pendingPickerLaunch,pendingPickerReplacement;
 Runnable pickerLaunchTimeout;
 int lastHangFocus=-1;


 boolean readOriginalPlus(){
  try{
   Bundle answer=host.contentResolver().call(android.net.Uri.parse("content://io.github.xitc.windowdeck.state"),HangPlace.PREF,null,null);
   boolean value=answer!=null&&answer.getBoolean(HangPlace.PREF,HangPlace.DEFAULT);
   Log.i(TAG,"original_plus="+value);return value;
  }catch(Throwable e){Log.w(TAG,"original_plus_unavailable",e);return false;}
 }
 void clearHangSourceFrame(){
  hangSourceGeneration++;hangSourceTask=-1;
  if(hangSourceFrame!=null){hangSourceFrame.recycle();hangSourceFrame=null;}
 }
 void animateAddedCard(WorkbenchSlot slot){
  Log.w(TAG,"add_card_result result=degraded_source_unavailable task="+slot.taskId);
  host.releaseEntrance();host.raiseMain();host.layoutCaption();
  if(!host.closing()&&!host.stopped()&&!host.backgrounded())host.focusPrimary("add_without_source");
 }
 void hangForReplace(){
  if(host.closing()||!host.initialized()||host.recovering()||host.dragging()!=null||hanging||pendingPickerLaunch!=null||session.slots.isEmpty())return;
  if(session.slots.get(session.primary).pinned){Toast.makeText(host.context(),"请先取消固定",Toast.LENGTH_SHORT).show();return;}
  hangReplace=true;hangForAdd();if(!hanging)hangReplace=false;
 }
 void hangForAdd(){
  if(host.closing()||!host.initialized()||host.recovering()||host.dragging()!=null||hanging||pendingPickerLaunch!=null)return;
  if(!hangReplace&&session.slots.size()>=Caps.MAX_TASKS){Toast.makeText(host.context(),"最多同时打开五个应用",Toast.LENGTH_SHORT).show();return;}
  host.cancelSwitchPreparation("new_picker");
  clearHangReturnGeometry();
  ArrayList<HangShelf.Card> cards=new ArrayList<>();
  WorkbenchSlot main=session.slots.get(session.primary);cards.add(new HangShelf.Card(hangFace(main),main.component.getPackageName(),screenRect(main.card),true));
  for(WorkbenchSlot s:session.slots)if(s!=main&&s.card!=null)cards.add(new HangShelf.Card(hangFace(s),s.component.getPackageName(),screenRect(s.card),false));
  try{hangShelf.show(host.context(),cards,host.effectiveMode()==PaneLayout.TOP_BOTTOM,this::restoreFromHang);}
  catch(Throwable e){Log.w(TAG,"hang_show_failed",e);hangShelf.hide();if(hangReplace){hangReplace=false;chooseApp(session.slots.get(session.primary));}else chooseApp(null);return;}
  Rect screen=host.windowManager().getCurrentWindowMetrics().getBounds();
  Rect[] ends=HangShelf.ends(cards,screen,host.effectiveMode()==PaneLayout.TOP_BOTTOM,screen.height()>screen.width(),Ui.dp(host.context(),20),Ui.dp(host.context(),48));
  ArrayList<WorkbenchSlot> shelfOrder=new ArrayList<>();shelfOrder.add(main);for(WorkbenchSlot s:session.slots)if(s!=main&&s.card!=null)shelfOrder.add(s);
  hangReturnPoses=SwitchMotion.capture(shelfOrder,host::currentPose);
  for(int i=0;i<shelfOrder.size();i++){
   WorkbenchSlot s=shelfOrder.get(i);hangReturnRects.put(s,new Rect(ends[i]));
   hangReturnBindings.put(s,new int[]{s.taskId,s.orientationAxis,s.renderBounds.width(),s.renderBounds.height()});
  }
  hanging=true;hangSawHome=false;hangStarted=SystemClock.uptimeMillis();hangTask=-1;hangUser=-1;hangNote=null;hangStableId=-1;hangStableHits=0;handler.removeCallbacks(hangConsume);
  host.publishState(session.slots.size());
  if(!host.backgroundWorkbench(hangReplace?"hang_replace":"hang_add")){hanging=false;hangReplace=false;hangShelf.hide();clearHangReturnGeometry();host.publishState(session.slots.size());return;}
  handler.removeCallbacks(hangWatch);handler.postDelayed(hangWatch,400);
  Log.i(TAG,"hang_add container="+host.taskId()+" count="+session.slots.size()+" replace="+hangReplace);
 }
 CardMotion.Pose hangReturnPose(WorkbenchSlot s){
  int[] binding=hangReturnBindings.get(s);
  if(hangReturnPoses==null||binding==null||binding[0]!=s.taskId||binding[1]!=s.orientationAxis
    ||binding[2]!=s.renderBounds.width()||binding[3]!=s.renderBounds.height())return null;
  return hangReturnPoses.source(s);
 }
 void clearHangReturnGeometry(){hangReturnRects.clear();hangReturnBindings.clear();hangReturnPoses=null;}
 Bitmap hangFace(WorkbenchSlot s){
  try{Method capture=rom.viewApi.getDeclaredMethod("getSnapBitMap",boolean.class);capture.setAccessible(true);Bitmap bitmap=(Bitmap)capture.invoke(s.surface,false);if(bitmap!=null&&bitmap.getConfig()==Bitmap.Config.HARDWARE)bitmap=bitmap.copy(Bitmap.Config.ARGB_8888,false);return bitmap;}
  catch(Throwable e){Log.w(TAG,"hang_snapshot_failed slot="+s.id,e);return null;}
 }
 void watchHang(){
  if(!hanging||host.closing())return;
  try{
   Object top=focusedRoot();
   if(top==null||taskTop(top)==null){handler.postDelayed(hangWatch,300);return;}
   int id=top.getClass().getField("taskId").getInt(top);
   ComponentName topActivity=taskTop(top);
   if(id!=lastHangFocus){lastHangFocus=id;Log.i(TAG,"hang_focus task="+id+" top="+topActivity.flattenToShortString()+" type="+top.getClass().getMethod("getActivityType").invoke(top)+" mode="+top.getClass().getMethod("getWindowingMode").invoke(top));}
   boolean home=isHomeTask(top)||id==host.taskId();
   if(home)hangSawHome=isHomeTask(top)||hangSawHome;
   // A child of ours stays focused while the group is still leaving. Counting that as
   // "the user re-opened this app" would cancel the add before the launcher is even up,
   // so a focused child only counts once the workbench has provably gone to the back.
   WorkbenchSlot focused=null;for(WorkbenchSlot s:session.slots)if(s.taskId==id)focused=s;
   if(!hangSawHome){long waited=SystemClock.uptimeMillis()-hangStarted;if(waited<=(focused!=null?1200:800)){hangStableId=-1;hangStableHits=0;handler.postDelayed(hangWatch,300);return;}hangSawHome=true;}
   if(home){hangStableId=-1;hangStableHits=0;handler.postDelayed(hangWatch,300);return;}
   String pkg=topActivity.getPackageName();
   // The original keeps exactly one window per app: re-opening an app that is already in
   // the workbench raises the window it holds instead of adding a second one, and only
   // speaks up when that window is already the main one. It never rejects into a dead end.
   WorkbenchSlot existing=focused;if(existing==null)for(WorkbenchSlot s:session.slots)if(s.component.getPackageName().equals(pkg)){existing=s;break;}
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
 Object focusedRoot() throws Exception {
  Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
  return Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo").invoke(service);
 }
 ComponentName taskTop(Object task) throws Exception {return (ComponentName)task.getClass().getField("topActivity").get(task);}
 ComponentName taskBase(Object task) throws Exception {return (ComponentName)task.getClass().getField("baseActivity").get(task);}
 boolean isHomeTask(Object task) throws Exception {
  ComponentName top=taskTop(task);
  if(top!=null&&"com.android.launcher".equals(top.getPackageName()))return true;
  Object type=task.getClass().getMethod("getActivityType").invoke(task);
  return type instanceof Integer&&((Integer)type)==2;
 }
 String embedBlock(Object task) throws Exception {
  ComponentName base=taskBase(task),top=taskTop(task);
  if(base==null||top==null)return "no_activity";
  if(!base.getPackageName().equals(top.getPackageName()))return "starting";
  int mode=((Integer)task.getClass().getMethod("getWindowingMode").invoke(task));
  if(mode!=1)return "mode="+mode;
  int id=task.getClass().getField("taskId").getInt(task);
  Intent launcher=host.packageManager().getLaunchIntentForPackage(top.getPackageName());
  if(launcher==null||launcher.getComponent()==null)return "no_launcher";
  if(!Boolean.TRUE.equals(rom.canvas.managerMethod("isAppSupportPocketStudio").invoke(rom.manager,launcher,id)))return "unsupported";
  return null;
 }
 Rect screenRect(View view){
  int[] loc=new int[2];view.getLocationOnScreen(loc);
  int w=Math.max(1,Math.round(view.getWidth()*view.getScaleX())),h=Math.max(1,Math.round(view.getHeight()*view.getScaleY()));
  return new Rect(loc[0],loc[1],loc[0]+w,loc[1]+h);
 }
 void acceptHang(int task,int user,String pkg){
  for(WorkbenchSlot s:session.slots)if(s.component.getPackageName().equals(pkg)){
   finishExistingHang(s,false);return;
  }
  hanging=false;handler.removeCallbacks(hangWatch);handler.removeCallbacks(hangConsume);hangShelf.hide();host.publishState(session.slots.size());
  hangTask=task;hangUser=user;hangConsumeTries=0;Log.i(TAG,"hang_pick task="+task+" pkg="+pkg);
  final int generation=++hangSourceGeneration;final boolean[] finished={false};final long began=SystemClock.uptimeMillis();
  if(hangSourceFrame!=null){hangSourceFrame.recycle();hangSourceFrame=null;}hangSourceTask=-1;

  final Runnable[] attempt={null};
  attempt[0]=()->{
   if(finished[0]||generation!=hangSourceGeneration||host.closing()||hangTask!=task)return;
   pump.worker().post(()->{
   Bitmap frame=null;
   try{
    Object wm=de.robv.android.xposed.XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),"getWindowManagerService");
    frame=(Bitmap)de.robv.android.xposed.XposedHelpers.callMethod(wm,"snapshotTaskForRecents",task);
   }catch(Throwable e){Log.w(TAG,"hang_source_capture_failed task="+task,e);}
   final Bitmap captured=frame;
   final boolean presented=HandoffCover.visibleTaskFrame(captured);
   handler.post(()->{
    if(finished[0]||generation!=hangSourceGeneration||host.closing()||hangTask!=task){if(captured!=null)captured.recycle();return;}
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
  handler.postDelayed(()->{if(!finished[0]&&generation==hangSourceGeneration&&!host.closing()&&hangTask==task){finished[0]=true;Log.w(TAG,"hang_source_capture_timeout task="+task+" pixels_unavailable=true");ensureHangConsumed();}},1200);
 }
 void ensureHangConsumed(){
  if(host.closing()||hangTask<0)return;
  if(host.stopped()||host.backgrounded()){bringWorkbenchForward();scheduleHangRetry();return;}
  hangQuiet=true;consumeHangAdd();hangQuiet=false;
  if(hangTask>=0)scheduleHangRetry();
 }
 void scheduleHangRetry(){
  if(++hangConsumeTries>10){Log.w(TAG,"hang_consume_gave_up task="+hangTask);hangTask=-1;hangUser=-1;pendingHangFront=-1;hangReplace=false;Toast.makeText(host.context(),"未能加入，请再试一次",Toast.LENGTH_SHORT).show();bringWorkbenchForward();return;}
  handler.postDelayed(hangConsume,250);
 }
 void restoreFromHang(){
  if(!hanging)return;
  clearHangReturnGeometry();
  hanging=false;hangReplace=false;handler.removeCallbacks(hangWatch);hangShelf.hide();host.publishState(session.slots.size());
  Log.i(TAG,"hang_restored");bringWorkbenchForward(host::releaseEntranceCoverWhenDrawn);
 }
 void bringWorkbenchForward(){bringWorkbenchForward(null);}
 void bringWorkbenchForward(Runnable afterRaised){
  host.withEntranceCover("hang_front",()->{
   try{host.reorderTask(host.taskId(),true);Log.i(TAG,"hang_front container="+host.taskId());}
   catch(Throwable e){
    Log.w(TAG,"hang_front_failed",e);
    try{settleHangContainer();}catch(Throwable fallback){Log.w(TAG,"hang_settle_failed",fallback);host.closeEntranceCover("raise_failed");return;}
   }
   if(afterRaised!=null)afterRaised.run();
  });
 }
 PickerLaunchPolicy.Decision pickerSession(Intent request){
  return PickerLaunchPolicy.session(host.taskId(),host.stateToken(),hangStarted,lastSelectionSession,hanging,android.os.Process.myUid()/100000,
    request.getIntExtra("containerTaskId",-1),request.getLongExtra("instanceToken",0),request.getLongExtra("selectionSession",0),request.getIntExtra("userId",-1));
 }
 boolean selectExistingFromLauncher(Intent request){
  if(host.closing()||!host.initialized())return false;
  PickerLaunchPolicy.Decision decision=pickerSession(request);
  if(decision==PickerLaunchPolicy.Decision.ALREADY_ACCEPTED)return true;
  if(decision!=PickerLaunchPolicy.Decision.ACCEPT)return false;
  long selectionSession=request.getLongExtra("selectionSession",0);int task=request.getIntExtra("selectedTask",-1);
  String pkg=request.getStringExtra("selectedPackage");
  for(WorkbenchSlot s:session.slots)if(!s.released&&s.taskId==task&&s.component.getPackageName().equals(pkg)){
   if(host.recovering()||host.dragging()!=null){Toast.makeText(host.context(),"请等待窗口恢复后再选择",Toast.LENGTH_SHORT).show();return true;}
   lastSelectionSession=selectionSession;finishExistingHang(s,true);return true;
  }
  return false;
 }
 boolean launchFromPicker(Intent request){
  if(host.closing()||!host.initialized())return false;
  PickerLaunchPolicy.Decision decision=pickerSession(request);
  if(decision==PickerLaunchPolicy.Decision.ALREADY_ACCEPTED)return true;
  if(decision!=PickerLaunchPolicy.Decision.ACCEPT||host.recovering()||host.dragging()!=null||pendingPickerLaunch!=null||host.pendingEntrance()!=null||host.animation()!=null||host.addAnimation()!=null)return false;
  Intent launch=request.getParcelableExtra("selectedIntent",Intent.class);
  String pkg=request.getStringExtra("selectedPackage");
  if(launch==null||launch.getComponent()==null||!launch.getComponent().getPackageName().equals(pkg)
    ||!Intent.ACTION_MAIN.equals(launch.getAction())||!launch.hasCategory(Intent.CATEGORY_LAUNCHER)
    ||launch.getData()!=null||launch.getSelector()!=null||launch.getClipData()!=null||launch.hasExtra(Intent.EXTRA_USER))return false;
  String[] current=new String[session.slots.size()];for(int i=0;i<session.slots.size();i++)current[i]=session.slots.get(i).component.getPackageName();
  int user=android.os.Process.myUid()/100000;
  if(!PickerLaunchPolicy.newApp(hanging,user,request.getIntExtra("userId",-1),session.slots.size(),hangReplace,pkg,current))return false;
  // Recheck stale launcher state before reserving a direct-embed selection.
  // Returning false replays the original launch once; watchHang retains ownership
  // of the later fullscreen-to-main transition and the same source task.
  if(!PickerLaunchPolicy.embedBeforeLaunch(hangReplace,readOriginalPlus())){
   Log.i(TAG,"picker_launch_fullscreen_recheck package="+pkg+" session="+hangStarted);return false;
  }
  try{
   WorkbenchSlot replaced=hangReplace?session.slots.get(session.primary):null;
   if(replaced!=null&&replaced.pinned)return false;
   WorkbenchSlot fresh=validatePickerLaunch(launch,replaced,user);
   // Reserve the first selection before raising the host. Creating a FlexibleTaskView
   // in a stopped Activity could start its task before destination geometry exists.
   lastSelectionSession=request.getLongExtra("selectionSession",0);
   pendingPickerLaunch=fresh;pendingPickerReplacement=replaced;
   hanging=false;hangReplace=false;hangTask=-1;hangUser=-1;pendingHangFront=-1;
   handler.removeCallbacks(hangWatch);handler.removeCallbacks(hangConsume);hangShelf.hide();clearHangSourceFrame();host.publishState(session.slots.size());
   pickerLaunchTimeout=()->{
    if(pendingPickerLaunch!=fresh)return;
    pendingPickerLaunch=null;pendingPickerReplacement=null;pickerLaunchTimeout=null;
    host.closeEntranceCover("picker_host_timeout");host.publishState(session.slots.size());
    Log.e(TAG,"picker_launch_failed reason=host_not_foreground package="+pkg);
    Toast.makeText(host.context(),"工作台未能恢复，请重新添加",Toast.LENGTH_LONG).show();
   };
   handler.postDelayed(pickerLaunchTimeout,5000);
   Log.i(TAG,"picker_launch_accepted package="+pkg+" task="+fresh.sourceTaskId+" session="+lastSelectionSession+" replace="+(replaced!=null));
   bringWorkbenchForward(()->handler.post(this::consumePickerLaunch));
   return true;
  }catch(Throwable e){
   Log.w(TAG,"picker_launch_rejected package="+pkg,e);
   if(pendingPickerLaunch!=null){
    // Ownership was already reserved. Do not also replay the ordinary launch.
    pendingPickerLaunch=null;pendingPickerReplacement=null;
    if(pickerLaunchTimeout!=null){handler.removeCallbacks(pickerLaunchTimeout);pickerLaunchTimeout=null;}
    host.closeEntranceCover("picker_raise_failed");host.publishState(session.slots.size());
    Toast.makeText(host.context(),"工作台未能恢复，请重新添加",Toast.LENGTH_LONG).show();return true;
   }
   return false;
  }
 }
 WorkbenchSlot validatePickerLaunch(Intent launch,WorkbenchSlot replaced,int user) throws Exception {
  ComponentName component=launch.getComponent();
  ActivityInfo info=host.packageManager().getActivityInfo(component,0);
  if(!info.enabled||!info.exported||!info.applicationInfo.enabled)throw new IllegalArgumentException("桌面入口不可用");
  WorkbenchSlot fresh=host.validate(component,replaced);
  // Keep an already running external task and its page. Cold starts retain the
  // original launcher Intent and receive scenario/container options from C17.
  java.util.List<?> tasks=rom.canvas.tasks();
  for(Object value:tasks){
   ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;
   if(task.baseActivity!=null&&task.baseActivity.getPackageName().equals(component.getPackageName())
     &&task.getClass().getField("userId").getInt(task)==user&&((Integer)task.getClass().getMethod("getWindowingMode").invoke(task))==1){fresh=host.validateSourceTask(task.taskId,user);break;}
  }
  fresh.launchIntent=new Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);return fresh;
 }
 void consumePickerLaunch(){
  WorkbenchSlot fresh=pendingPickerLaunch;if(fresh==null||host.closing()||host.stopped()||host.backgrounded())return;
  WorkbenchSlot replaced=pendingPickerReplacement;
  pendingPickerLaunch=null;pendingPickerReplacement=null;
  if(pickerLaunchTimeout!=null){handler.removeCallbacks(pickerLaunchTimeout);pickerLaunchTimeout=null;}
  try{
   if(replaced!=null&&(session.slots.isEmpty()||session.slots.get(session.primary)!=replaced||replaced.pinned))throw new IllegalStateException("替换目标已变化");
   hangReplace=replaced!=null;fresh.pickerLaunch=true;
   host.attachTaskEntrance(fresh,replaced!=null,replaced==null,new Intent());
   Log.i(TAG,"picker_launch_embedded_requested slot="+fresh.id+" task="+fresh.sourceTaskId+" container="+host.taskId()+" scenario=2");
  }catch(Throwable e){
   Log.e(TAG,"picker_launch_failed reason=attach",e);host.closeEntranceCover("picker_attach_failed");
   Toast.makeText(host.context(),"未能加入，请重新添加",Toast.LENGTH_LONG).show();
  }finally{hangReplace=false;}
 }
 void finishExistingHang(WorkbenchSlot selected,boolean beforeLaunch){
  clearHangReturnGeometry();
  boolean replacing=hangReplace;hanging=false;hangReplace=false;
  handler.removeCallbacks(hangWatch);handler.removeCallbacks(hangConsume);hangShelf.hide();
  int index=session.slots.indexOf(selected);boolean promote=!replacing&&index>=0&&index!=session.primary&&!host.recovering()&&host.dragging()==null&&selected.taskId>=0;
  if(promote)host.queueExistingSwitch(selected,hangStarted);else hangNote=selected.label+" 已在前台启动";
  host.publishState(session.slots.size());
  Log.i(TAG,"hang_existing task="+selected.taskId+" slot="+selected.id+" before_launch="+beforeLaunch+" replace="+replacing+" promote_requested="+promote);
  if(beforeLaunch){
   // The task never received a fullscreen launch: retain its existing embedding.
   host.withEntranceCover("hang_raise",()->{raiseHangContainerNow();host.releaseEntranceCoverWhenDrawn();});
  }else{
   // Non-launcher entrances may already have detached the task. Rebind the SAME
   // task with scenario/container options; Surface reparent alone cannot do that.
   selected.pendingTask=selected.taskId;pendingHangFront=selected.taskId;
   bringWorkbenchForward(()->handler.post(()->{host.restorePendingTasks();host.releaseEntranceCoverWhenDrawn();}));
  }
 }
 void raiseHangContainer(){
  host.withEntranceCover("hang_raise",this::raiseHangContainerNow);
 }
 void raiseHangContainerNow(){
  if(host.closing()||host.finishing())return;
  try{Method method=rom.canvas.viewMethod("setNoNeedStartEmbedded");for(WorkbenchSlot s:session.slots)if(s.surface!=null)method.invoke(s.surface,true);}
  catch(Throwable e){Log.w(TAG,"hang_suppress_failed",e);}
  try{settleHangContainer();}catch(Throwable e){Log.w(TAG,"hang_settle_failed",e);host.closeEntranceCover("raise_failed");}
  if(clearHangSuppress!=null)handler.removeCallbacks(clearHangSuppress);
  clearHangSuppress=()->{clearHangSuppress=null;if(pendingHangFront>=0)return;try{Method method=rom.canvas.viewMethod("setNoNeedStartEmbedded");for(WorkbenchSlot s:session.slots)if(s.surface!=null)method.invoke(s.surface,false);}catch(Throwable e){Log.w(TAG,"hang_suppress_failed",e);}Log.i(TAG,"hang_suppress_cleared");};
  handler.postDelayed(clearHangSuppress,900);
 }
 void settleHangContainer() throws Exception {
  java.util.List<?> tasks=rom.canvas.tasks();
  Class<?> token=Class.forName("android.window.WindowContainerToken"),wct=Class.forName("android.window.WindowContainerTransaction");
  Object tx=wct.getConstructor().newInstance();int container=host.taskId();boolean raised=false;
  for(Object value:tasks){ActivityManager.RunningTaskInfo task=(ActivityManager.RunningTaskInfo)value;boolean child=false;for(WorkbenchSlot s:session.slots)if(!s.released&&s.taskId==task.taskId)child=true;if(!child&&task.taskId!=container)continue;Object t=task.getClass().getField("token").get(task);if(child)wct.getMethod("setAlwaysOnTop",token,boolean.class).invoke(tx,t,false);if(task.taskId==container){wct.getMethod("setHidden",token,boolean.class).invoke(tx,t,false);wct.getMethod("reorder",token,boolean.class).invoke(tx,t,true);raised=true;}}
  if(!raised)throw new IllegalStateException("Missing container "+container);
  Class<?> organizer=Class.forName("android.window.WindowOrganizer");organizer.getMethod("applyTransaction",wct).invoke(organizer.getConstructor().newInstance(),tx);
  Log.i(TAG,"hang_settle container="+container);
 }
 void dismissHang(){if(pickerLaunchTimeout!=null){handler.removeCallbacks(pickerLaunchTimeout);pickerLaunchTimeout=null;}pendingPickerLaunch=null;pendingPickerReplacement=null;hanging=false;hangReplace=false;hangTask=-1;hangUser=-1;hangNote=null;handler.removeCallbacks(hangWatch);handler.removeCallbacks(hangConsume);hangShelf.hide();clearHangReturnGeometry();}
 void consumeHangAdd(){
  if(host.closing())return;
  if(hangNote!=null){Toast.makeText(host.context(),hangNote,Toast.LENGTH_SHORT).show();hangNote=null;}
  if(hangTask<0||host.stopped()||host.backgrounded())return;
  int id=hangTask,user=hangUser;boolean replace=hangReplace;pendingHangFront=id;
  Intent request=new Intent();request.putExtra("windowdeck_add_task_id",id);request.putExtra("windowdeck_add_user_id",user);request.putExtra("windowdeck_container_task_id",host.taskId());request.putExtra("windowdeck_hang_place",!replace);request.putExtra("windowdeck_hang_replace",replace);
  host.addExistingTask(request);
  if((host.pendingEntrance()!=null&&host.pendingEntrance().sourceTaskId==id)||(host.replacements().current()!=null&&host.replacements().current().fresh.sourceTaskId==id)){hangTask=-1;hangUser=-1;hangReplace=false;Log.i(TAG,"hang_consumed task="+id+" replace="+replace);}
  else pendingHangFront=-1;
 }
 void chooseApp(WorkbenchSlot replacing){
  if(host.closing()||!host.initialized()||host.recovering()||host.dragging()!=null)return;
  if(replacing!=null&&replacing.pinned){Toast.makeText(host.context(),"请先取消固定",Toast.LENGTH_SHORT).show();return;}
  if(replacing==null&&session.slots.size()>=Caps.MAX_TASKS){Toast.makeText(host.context(),"最多同时打开五个应用",Toast.LENGTH_SHORT).show();return;}
  Set<String> excluded=new HashSet<>();for(WorkbenchSlot s:session.slots)excluded.add(s.component.getPackageName());
  host.showNativePicker(excluded,(launch,user)->{
   if(host.closing()||host.recovering()||host.dragging()!=null||(replacing!=null&&replacing.pinned))return;
   try{
    if(host.stopped()||host.backgrounded()||user!=android.os.Process.myUid()/100000||launch.getComponent()==null)return;
    WorkbenchSlot fresh=host.validate(launch.getComponent(),replacing);fresh.launchIntent=new Intent(launch);int index=replacing==null?session.slots.size():session.slots.indexOf(replacing);if(index<0)return;
    host.cancelAnimation();
    if(replacing==null){
     session.slots.add(fresh);host.publishState(session.slots.size());host.updateNaturalBounds();host.createWindow(fresh);host.layoutCards(false);host.refreshStatus();
     Log.i(TAG,"add slot="+fresh.id+" count="+session.slots.size());
    }else prepareReplacement(index,replacing,fresh);
   }catch(Throwable e){host.fail(e);}
  });
 }
 void prepareReplacement(int index,WorkbenchSlot old,WorkbenchSlot fresh){
  if(host.recovering()||host.closing()||host.stopped()||host.backgrounded())return;
  host.replacements().begin(old,fresh,index,session.primary);
 }
 void commitReplacement(ReplacementController.Run<WorkbenchSlot> run){
 run.old.quietDetach=true;host.releaseSlot(run.old);host.clearRecoveryCovers();host.setRecovering(false);
 host.publishState(session.slots.size());host.layoutCards(false);host.restoreContainerFocus();
 if(run.mainEntrance){
  boolean previous=hangReplace;hangReplace=true;
  try{if(!host.poseScreenArrival(run.fresh,session.slots.size()>1,false,false)){host.closeEntranceCover("replace_no_pose");host.focusPrimary("replace_committed");}}
  finally{hangReplace=previous;}
 }else{host.closeEntranceCover("replace_committed");host.focusPrimary("replace_committed");}
 Log.i(TAG,"replace_committed slot="+run.fresh.id+" was="+run.old.id+" task="+run.fresh.taskId);
 }
 void restoreReplacement(ReplacementController.Run<WorkbenchSlot> run,String reason){
  int index=session.slots.indexOf(run.fresh);
  if(index>=0){session.slots.set(index,run.old);session.primary=run.primary;transferCover(run.fresh,run.old);}
  run.fresh.quietDetach=true;host.releaseSlot(run.fresh);host.clearRecoveryCovers();host.setRecovering(false);host.closeEntranceCover("replace_rollback");
  if(!run.old.released&&!run.old.embedded&&run.old.taskId>=0)run.old.pendingTask=run.old.taskId;
  if(!host.closing()){host.publishState(session.slots.size());host.updateNaturalBounds();host.layoutCards(false);host.refreshStatus();
   if(!host.stopped()&&!host.backgrounded()){host.restorePendingTasks();host.restoreContainerFocus();host.focusPrimary("replace_rollback");}}
  Log.w(TAG,"replace_rollback slot="+run.fresh.id+" was="+run.old.id+" reason="+reason);
 }
 void transferCover(WorkbenchSlot from,WorkbenchSlot to){
  if(from.recoveryCover==null||to.card==null)return;
  RecoveryCover cover=from.recoveryCover;from.card.removeView(cover);from.recoveryCover=null;to.recoveryCover=cover;
  to.card.addView(cover,Math.min(1,to.card.getChildCount()),new FrameLayout.LayoutParams(to.renderBounds.width(),to.renderBounds.height()));
  host.fitSurface(to,to.card.getWidth(),to.card.getHeight());
 }
}
