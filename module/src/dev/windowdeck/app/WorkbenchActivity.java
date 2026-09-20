package dev.windowdeck.app;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.os.*;
import android.text.TextUtils;
import android.util.Log;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

public final class WorkbenchActivity extends Activity {
 private static final String TAG="WindowDeck";
 private final ArrayList<Slot> slots=new ArrayList<>();
 private FrameLayout stage; private TextView status; private Button addCard, controls;
 private Field interceptInput; private Method resizeMethod;
 private boolean settlingInput; private int switchGeneration;
 private ViewTreeObserver.OnPreDrawListener settleListener;
 private long switchStarted, lastAnimationFrame, maxFrameGap, resizeNanos; private int animationFrames, resizeCalls;
 private int layoutMode=PaneLayout.LEFT_RIGHT;
 private boolean initialized,closing,layoutPosted; private int primary,nextId,imeBottom;
 private Class<?> viewApi,managerApi; private Object manager; private ValueAnimator animation;
 private Slot dragging; private boolean recovering; private Runnable recoveryFinish;
 private final Rect naturalBounds=new Rect();
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final class Slot {
  final int id; final ComponentName component; final String label; int taskId=-1,orientationAxis; ComponentName activeComponent; final Rect renderBounds=new Rect(); boolean failed,released,pinned;
  PreviewCard card; ImageView icon, recoveryCover; TextView pin; View surface;
  Slot(ComponentName c,String l){id=nextId++;component=c;label=l;}
 }
 @Override protected void onCreate(Bundle state){
  setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state);
  getWindow().setStatusBarColor(0xffeef2f8);getWindow().setNavigationBarColor(0xffeef2f8);
  getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xffeef2f8);setContentView(root);
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
  addCard=Ui.button(this,"＋");addCard.setTextSize(28);addCard.setContentDescription("添加应用");addCard.setBackground(Ui.bg(0xffe1e8f2,Ui.dp(this,16)));addCard.setOnClickListener(v->chooseApp(null));stage.addView(addCard);
  controls=Ui.button(this,"•••");controls.setContentDescription("工作台菜单");controls.setBackground(Ui.bg(0xffdbe3ee,Ui.dp(this,24)));controls.setOnClickListener(v->workbenchMenu());stage.addView(controls);
  status=Ui.text(this,"正在准备窗口…",12,0xff52637b);status.setGravity(Gravity.CENTER);root.addView(status,new LinearLayout.LayoutParams(-1,Ui.dp(this,24)));
  try{
   if(!"com.oplus.pscanvas".equals(getPackageName()))throw new IllegalStateException("需要从系统容器启动");
   viewApi=Class.forName("com.oplus.flexiblewindow.FlexibleTaskView");resizeMethod=viewApi.getMethod("resize",Rect.class);interceptInput=viewApi.getDeclaredField("mInterceptInputEvent");interceptInput.setAccessible(true);managerApi=Class.forName("com.oplus.flexiblewindow.FlexibleWindowManager");manager=managerApi.getMethod("getInstance").invoke(null);
   String[] input=state!=null?state.getStringArray("components"):null;
   if(input==null)input=new String[]{getIntent().getStringExtra("windowdeck_app_a"),getIntent().getStringExtra("windowdeck_app_b"),getIntent().getStringExtra("windowdeck_app_c")};
   for(String component:input)if(component!=null&&!component.isEmpty()){if(slots.size()==3)break;slots.add(validate(ComponentName.unflattenFromString(component),null));}
   if(slots.isEmpty())throw new IllegalArgumentException("请至少选择一个应用");
   if(state!=null){String pinned=state.getString("pinned");for(Slot slot:slots)slot.pinned=slot.component.flattenToString().equals(pinned);}
   primary=state==null?0:Math.max(0,Math.min(slots.size()-1,state.getInt("primary",0)));
   stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
    if(closing||r-l<100||b-t<100)return;
    if(initialized&&r-l==or-ol&&b-t==ob-ot)return;
    if(!initialized){initialized=true;updateNaturalBounds();for(Slot s:new ArrayList<>(slots))createWindow(s);}
    scheduleLayout();
   });
   Log.i(TAG,"workbench_created version=0.4.3-beta.1 container="+getTaskId()+" count="+slots.size());
  }catch(Throwable e){fail(e);}
 }
 private Slot validate(ComponentName c,Slot replacing) throws Exception {
  if(c==null||c.getPackageName().equals(getPackageName())||c.getPackageName().equals("dev.windowdeck.app"))throw new IllegalArgumentException("应用参数无效");
  for(Slot s:slots)if(s!=replacing&&s.component.getPackageName().equals(c.getPackageName()))throw new IllegalArgumentException("该应用已在工作台中");
  ActivityInfo ai=getPackageManager().getActivityInfo(c,0);
  Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c);
  if(!Boolean.TRUE.equals(managerApi.getMethod("isAppSupportPocketStudio",Intent.class,int.class).invoke(manager,intent,-1)))throw new IllegalArgumentException(ai.loadLabel(getPackageManager())+" 不支持此窗口模式");
  Slot slot=new Slot(c,ai.loadLabel(getPackageManager()).toString());slot.activeComponent=c;slot.orientationAxis=OrientationPolicy.axis(ai.screenOrientation);Log.i(TAG,"app_orientation component="+c.flattenToShortString()+" requested="+ai.screenOrientation+" axis="+slot.orientationAxis);return slot;
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
    if(replacing!=null){slots.set(index,fresh);releaseSlot(replacing);}else slots.add(fresh);
    updateNaturalBounds();createWindow(fresh);layoutCards(false);refreshStatus();if(replacing!=null)restoreContainerFocus();
    Log.i(TAG,(replacing==null?"add":"replace")+" slot="+fresh.id+" count="+slots.size());
   }catch(Throwable e){fail(e);}
  });
 }
 private void menu(Slot s){
  if(closing||s.released||recovering||dragging!=null)return;
  new AlertDialog.Builder(this).setTitle(s.label).setItems(new String[]{"切换为主应用",s.pinned?"取消固定":"固定此应用","替换应用","从工作台移出"},(d,which)->{
   if(s.released||recovering||dragging!=null)return;
   if(which==0)promote(slots.indexOf(s));else if(which==1)togglePin(s);else if(which==2)chooseApp(s);else removeSlot(s);
  }).setNegativeButton("取消",null).show();
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
  cancelAnimation();Slot main=slots.get(primary);slots.remove(index);primary=main==s?Math.min(index,slots.size()-1):slots.indexOf(main);
  releaseSlot(s);updateNaturalBounds();layoutCards(false);refreshStatus();restoreContainerFocus();Log.i(TAG,"remove slot="+s.id+" task="+s.taskId+" count="+slots.size());
 }
 private void prepareRecovery(Slot excluded,Runnable mutation){
  cancelAnimation();recovering=true;
  for(int i=0;i<slots.size();i++){
   Slot active=slots.get(i);inputRole(active,i==primary);
   if(active.released||active.surface==null)continue;
   if(active.recoveryCover==null){
    ImageView cover=new ImageView(this);cover.setScaleType(ImageView.ScaleType.FIT_CENTER);
    cover.setBackgroundColor(0xffeef2f8);cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    try{Method snapshot=viewApi.getDeclaredMethod("getSnapBitMap",boolean.class);snapshot.setAccessible(true);
     Bitmap bitmap=(Bitmap)snapshot.invoke(active.surface,false);if(bitmap!=null)cover.setImageBitmap(bitmap);
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
  for(Slot active:slots)if(active.recoveryCover!=null){active.recoveryCover.setImageDrawable(null);active.card.removeView(active.recoveryCover);active.recoveryCover=null;}
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
   slot.card=new PreviewCard(this);slot.card.setBackground(Ui.bg(0xffe1e8f2,Ui.dp(this,16)));
   slot.card.setOnClickListener(v->promote(slots.indexOf(slot)));
   slot.card.beginDrag=()->beginDrag(slot);slot.card.cancelDrag=()->finishDrag(slot,false);slot.card.dismiss=()->finishDrag(slot,true);
   slot.card.setOnLongClickListener(v->{if(!switching())menu(slot);return true;});
   slot.surface=(View)viewApi.getConstructor(Context.class).newInstance(this);
   slot.surface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(!closing&&!slot.released&&r>l&&b>t&&(l!=ol||t!=ot||r!=or||b!=ob))resizeSurface(slot);});
   Class<?> listener=Class.forName("com.oplus.flexiblewindow.FlexibleTaskView$Listener");
   Object proxy=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(p,m,args)->{
    if(m.getDeclaringClass()==Object.class){if(m.getName().equals("toString"))return "WorkbenchListener";if(m.getName().equals("hashCode"))return System.identityHashCode(p);if(m.getName().equals("equals"))return p==args[0];}
    if(closing||slot.released)return null;
    if(m.getName().equals("onTaskCreated")||m.getName().equals("onTaskChanged")){
     ComponentName top=(ComponentName)args[1];if(top!=null&&!top.equals(slot.activeComponent)){slot.activeComponent=top;try{slot.orientationAxis=OrientationPolicy.axis(getPackageManager().getActivityInfo(top,0).screenOrientation);handler.post(this::scheduleLayout);}catch(PackageManager.NameNotFoundException ignored){}}
     int id=(Integer)args[0];if(slot.taskId!=id)Log.i(TAG,"task_ready slot="+slot.id+" task="+id);slot.taskId=id;slot.failed=false;refreshStatus();handler.post(()->syncSurface(slot));
    }else if(m.getName().equals("onTaskRectOrientationChanged")){
     ActivityManager.RunningTaskInfo info=(ActivityManager.RunningTaskInfo)args[0];Rect requested=args[1] instanceof Rect?new Rect((Rect)args[1]):null;
     if(info.taskId==slot.taskId&&requested!=null&&!requested.isEmpty()&&requested.width()!=requested.height()){
      int axis=requested.width()>requested.height()?2:1;
      if(slot.orientationAxis!=axis){slot.orientationAxis=axis;Log.i(TAG,"task_orientation slot="+slot.id+" axis="+axis);handler.post(this::scheduleLayout);}
     }
    }else if(m.getName().equals("onInitialized")&&Boolean.FALSE.equals(args[0])){slot.failed=true;Log.e(TAG,"task_start_failed slot="+slot.id);refreshStatus();}
    else if(m.getName().equals("onTaskWindowDraw")){Log.i(TAG,"task_draw slot="+slot.id+" drawn="+args[1]);if(Boolean.TRUE.equals(args[1]))handler.post(()->syncSurface(slot));}
    else if(m.getName().equals("onBackPressedOnTaskRoot"))handler.post(()->removeSlot(slot));
    else if(m.getName().equals("onTaskRemovalStarted"))handler.post(()->removeSlot(slot,true));
    return null;
   });
   viewApi.getMethod("setListener",Executor.class,listener).invoke(slot.surface,(Executor)this::runOnUiThread,proxy);
   Bundle config=new Bundle();config.putInt("scenario",2);config.putParcelable("launchBounds",new Rect(slot.renderBounds));
   config.putParcelable("intent",new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(slot.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   config.putInt("userId",android.os.Process.myUid()/100000);config.putFloat("cornerRadius",Ui.dp(this,10));config.putInt("reparent_align",0);
   config.putBoolean("intercept_input_event",true);config.putBoolean("allow_task_detach_from_embedding",true);config.putBoolean("key_intercept_back_key",true);config.putBoolean("flexible_key_remove_task_detach",false);config.putInt("use_view_snapshot",1);
   viewApi.getMethod("init",Bundle.class).invoke(slot.surface,config);viewApi.getMethod("setEnforceStart",boolean.class).invoke(slot.surface,true);
   slot.card.addView(slot.surface,new FrameLayout.LayoutParams(slot.renderBounds.width(),slot.renderBounds.height()));
   slot.icon=new ImageView(this);slot.icon.setImageDrawable(getPackageManager().getActivityIcon(slot.component));slot.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);slot.icon.setPadding(Ui.dp(this,3),Ui.dp(this,3),Ui.dp(this,3),Ui.dp(this,3));slot.icon.setBackground(Ui.bg(0xfff7f9fc,Ui.dp(this,12)));
   FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(Ui.dp(this,32),Ui.dp(this,32),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);slot.card.addView(slot.icon,badge);slot.pin=Ui.text(this,"钉",12,0xffffffff);slot.pin.setGravity(Gravity.CENTER);slot.pin.setBackground(Ui.bg(0xff365db5,Ui.dp(this,12)));slot.pin.setContentDescription("已固定，先取消固定才能替换或移出");
   slot.card.addView(slot.pin,new FrameLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24),Gravity.TOP|Gravity.RIGHT));
   stage.addView(slot.card);controls.bringToFront();
   handler.postDelayed(()->{if(!closing&&!slot.released&&slot.taskId<0){slot.failed=true;refreshStatus();Log.w(TAG,"startup_timeout slot="+slot.id);}},10000);
  }catch(Throwable e){slot.failed=true;fail(e);}
 }
 private void workbenchMenu(){
  if(closing||recovering||dragging!=null)return;
  new AlertDialog.Builder(this).setTitle("工作台 · "+layoutLabel()).setItems(new String[]{"添加应用","切换左右 / 上下布局","管理主应用","退出工作台"},(d,n)->{if(n==0)chooseApp(null);else if(n==1)toggleLayout();else if(n==2&&!slots.isEmpty())menu(slots.get(primary));else if(n==3)closeWorkbench();}).setNegativeButton("取消",null).show();
 }
 private String layoutLabel(){return layoutMode==PaneLayout.TOP_BOTTOM?"上下":"左右";}
 private void toggleLayout(){
  if(closing||!initialized||recovering||dragging!=null)return;
  cancelAnimation();layoutMode=layoutMode==PaneLayout.LEFT_RIGHT?PaneLayout.TOP_BOTTOM:PaneLayout.LEFT_RIGHT;
  scheduleLayout();
  Log.i(TAG,"layout_mode="+layoutMode+" tasks="+taskIds());
 }
 private int effectiveMode(){return stage.getWidth()>stage.getHeight()?PaneLayout.LEFT_RIGHT:layoutMode;}
 private int previewHeight(){
  return PaneLayout.previewHeight(stage.getWidth(),stage.getHeight(),Ui.dp(this,6),Ui.dp(this,84),Ui.dp(this,22),effectiveMode());
 }
 private int[][] geometry(){return PaneLayout.compute(stage.getWidth(),stage.getHeight(),slots.size(),primary,Ui.dp(this,6),Ui.dp(this,84),previewHeight(),effectiveMode());}
 private void layoutAddCard(){
  int[] box=PaneLayout.addBox(stage.getWidth(),stage.getHeight(),slots.size(),Ui.dp(this,6),Ui.dp(this,84),previewHeight(),effectiveMode());
  addCard.setVisibility(box==null?View.GONE:View.VISIBLE);
  layoutControls();
  if(box!=null){box[3]=Math.min(box[2],box[3]);FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(box[2],box[3]);lp.leftMargin=box[0];lp.topMargin=box[1];addCard.setLayoutParams(lp);}
 }
 private void layoutControls(){
  int edge=Ui.dp(this,48),gap=Ui.dp(this,6);
  FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(edge,edge);
  lp.leftMargin=Math.max(gap,stage.getWidth()-edge-gap);
  lp.topMargin=effectiveMode()==PaneLayout.TOP_BOTTOM?gap:Math.max(gap,stage.getHeight()-edge-gap);
  controls.setLayoutParams(lp);controls.bringToFront();
 }
 private void inputRole(Slot s,boolean isPrimary){
  boolean preview=!isPrimary||switching()||recovering;s.card.preview=preview;
  s.card.gesturesEnabled=!recovering;s.card.vertical=effectiveMode()==PaneLayout.TOP_BOTTOM;s.pin.setVisibility(s.pinned?View.VISIBLE:View.GONE);
  s.card.setContentDescription(s.label+(isPrimary?"主应用":"，点击切换，长按管理"));
  s.icon.setVisibility(isPrimary?View.GONE:View.VISIBLE);

  try{if(interceptInput.getBoolean(s.surface)!=preview){interceptInput.setBoolean(s.surface,preview);s.surface.requestLayout();}}catch(Throwable e){fail(e);}
 }
 private void updateNaturalBounds(){
  if(slots.isEmpty()||stage.getWidth()<100||stage.getHeight()<100)return;
  int[] main=geometry()[primary];int cw=Math.max(100,main[2]),ch=Math.max(100,main[3]);
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();int nw=Math.max(cw,Math.min(dm.widthPixels,dm.heightPixels));
  naturalBounds.set(0,0,nw,Math.max(Ui.dp(this,120),Math.round(ch*(nw/(float)cw))));
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
 }
 private void syncSurface(Slot s){
  if(closing||s.released||s.surface==null||s.taskId<0)return;
  try{Method method=viewApi.getDeclaredMethod("reparent");method.setAccessible(true);method.invoke(s.surface);s.surface.setBackground(null);}
  catch(Throwable e){Log.w(TAG,"surface_sync_failed slot="+s.id,e);}
 }
 @Override protected void onPause(){cancelDrag();cancelAnimation();if(initialized&&!closing)layoutCards(false);super.onPause();}
 @Override protected void onResume(){super.onResume();handler.postDelayed(()->{for(Slot s:slots)syncSurface(s);},350);}
 private void scheduleLayout(){if(layoutPosted)return;layoutPosted=true;stage.post(()->{layoutPosted=false;if(!closing){cancelDrag();cancelAnimation();updateNaturalBounds();layoutCards(false);for(Slot s:slots)resizeSurface(s);Log.i(TAG,"layout width="+stage.getWidth()+" height="+stage.getHeight()+" ime="+imeBottom+" count="+slots.size());}});}
 private void fitSurface(Slot s,int width,int height){
  if(s.surface==null||s.renderBounds.isEmpty())return;
  FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)s.surface.getLayoutParams();
  if(lp.width!=s.renderBounds.width()||lp.height!=s.renderBounds.height()){
   lp.width=s.renderBounds.width();lp.height=s.renderBounds.height();s.surface.setLayoutParams(lp);
  }
  int availableHeight=Math.max(1,height-(slots.indexOf(s)==primary?0:Ui.dp(this,22)));
  float scale=Math.min(width/(float)s.renderBounds.width(),availableHeight/(float)s.renderBounds.height());
  s.surface.setPivotX(0);s.surface.setPivotY(0);s.surface.setScaleX(scale);s.surface.setScaleY(scale);
  s.surface.setTranslationX((width-s.renderBounds.width()*scale)/2f);
  s.surface.setTranslationY((availableHeight-s.renderBounds.height()*scale)/2f);
  if(s.recoveryCover!=null){
   ImageView cover=s.recoveryCover;FrameLayout.LayoutParams cp=(FrameLayout.LayoutParams)cover.getLayoutParams();
   if(cp.width!=lp.width||cp.height!=lp.height){cp.width=lp.width;cp.height=lp.height;cover.setLayoutParams(cp);}
   cover.setPivotX(0);cover.setPivotY(0);cover.setScaleX(scale);cover.setScaleY(scale);
   cover.setTranslationX(s.surface.getTranslationX());cover.setTranslationY(s.surface.getTranslationY());
  }
 }
 private void apply(Slot s,int[] r){
  if(s.card==null)return;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)s.card.getLayoutParams();
  fitSurface(s,r[2],r[3]);
  if(lp!=null&&lp.width==r[2]&&lp.height==r[3]&&lp.leftMargin==r[0]&&lp.topMargin==r[1])return;
  if(lp==null)lp=new FrameLayout.LayoutParams(r[2],r[3]);
  lp.width=r[2];lp.height=r[3];lp.leftMargin=r[0];lp.topMargin=r[1];s.card.setLayoutParams(lp);
 }
 private void layoutCards(boolean animate){
  if(slots.isEmpty()||closing)return;layoutAddCard();int[][] target=geometry();final ArrayList<Slot> current=new ArrayList<>(slots);int[][] from=new int[current.size()][4];
  for(int i=0;i<current.size();i++){Slot s=current.get(i);if(s.card==null)return;from[i]=new int[]{Math.round(s.card.getX()),Math.round(s.card.getY()),Math.round(s.card.getWidth()*s.card.getScaleX()),Math.round(s.card.getHeight()*s.card.getScaleY())};inputRole(s,i==primary);}
  if(!animate){for(int i=0;i<current.size();i++){resetCardTransform(current.get(i));apply(current.get(i),target[i]);}return;}
  final int generation=++switchGeneration;
  switchStarted=SystemClock.uptimeMillis();lastAnimationFrame=0;maxFrameGap=0;animationFrames=0;resizeCalls=0;resizeNanos=0;
  animation=ValueAnimator.ofFloat(0,1);for(int i=0;i<current.size();i++)inputRole(current.get(i),i==primary);animation.setDuration(360);animation.setInterpolator(GesturePolicy::spring);
  // Keep SurfaceView dimensions fixed throughout the transition. RenderThread
  // transforms the card and its child Surface together; no per-frame task resize.
  for(int i=0;i<current.size();i++){apply(current.get(i),target[i]);transformCard(current.get(i),from[i],target[i],0);}
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
    logSwitch("settled");return true;
   }};stage.getViewTreeObserver().addOnPreDrawListener(settleListener);stage.invalidate();
  }});animation.start();
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
  if(switching())logSwitch("cancelled");switchGeneration++;
  if(settleListener!=null){stage.getViewTreeObserver().removeOnPreDrawListener(settleListener);settleListener=null;}
  settlingInput=false;
  if(animation!=null){ValueAnimator old=animation;animation=null;old.cancel();}
 }
 private void promote(int index){if(closing||recovering||dragging!=null||index<0||index>=slots.size()||index==primary)return;if(slots.get(index).taskId<0){Toast.makeText(this,"请等待应用窗口就绪",Toast.LENGTH_SHORT).show();return;}cancelAnimation();primary=index;layoutCards(true);refreshStatus();Log.i(TAG,"switch primary="+primary+" tasks="+taskIds());}
 private String taskIds(){StringBuilder b=new StringBuilder();for(Slot s:slots){if(b.length()>0)b.append(',');b.append(s.taskId);}return b.toString();}
 private void refreshStatus(){runOnUiThread(()->{if(closing||slots.isEmpty())return;boolean ready=true,failed=false;for(Slot s:slots){ready&=s.taskId>=0;failed|=s.failed;}status.setVisibility(ready&&!failed?View.GONE:View.VISIBLE);status.setText(failed?"部分窗口未就绪，可通过 ⋮ 替换或移出":ready?slots.size()+" 个实时窗口 · 主应用："+slots.get(primary).label:"正在等待应用窗口…");});}
 private void fail(Throwable e){while(e.getCause()!=null&&e.getCause()!=e)e=e.getCause();Log.e(TAG,"workbench_error",e);if(status!=null){status.setVisibility(View.VISIBLE);status.setText("无法启动："+e.getClass().getSimpleName()+" · "+e.getMessage());}}
 private void releaseSlot(Slot s){
  if(s.released)return;
  s.released=true;
  if(!closing)recovering=true;
  if(s.surface!=null){
   try{
    // Unlink first: moving a still-linked task can background the whole group.
    // The ROM's move-to-back path resets it without the unsafe direct
    // fullscreen transition used by resetFlexibleTask.
    if(s.taskId>=0){
     viewApi.getMethod("interceptBackPressedOnTaskRoot",boolean.class).invoke(s.surface,false);
     managerApi.getMethod("removeEmbeddedContainerTask",int.class,int.class).invoke(manager,s.taskId,getTaskId());
     Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
     atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),s.taskId,true);
    }
    // moveTaskToBack owns the server-side transition. Do not race it with
    // detachFromTaskView's second resetFlexibleTask / fullscreen transition.
    Method extra=viewApi.getDeclaredMethod("releaseExtraView");extra.setAccessible(true);extra.invoke(s.surface);
   }catch(Throwable e){Log.w(TAG,"detach_failed slot="+s.id,e);}
   try{viewApi.getMethod("release").invoke(s.surface);}catch(Throwable e){Log.w(TAG,"release_failed slot="+s.id,e);}
   s.surface=null;
  }
  if(s.card!=null){stage.removeView(s.card);s.card.resetGesture();}
  if(s.recoveryCover!=null){s.recoveryCover.setImageDrawable(null);s.recoveryCover=null;}
  if(s.taskId>=0){
   Runnable restore=()->{
    for(Slot active:slots)if(!active.released&&active.taskId==s.taskId)return;
    try{reorderTask(s.taskId,false);if(!closing)restoreContainerFocus();Log.i(TAG,"detached_task_restored task="+s.taskId);}
    catch(Throwable e){Log.w(TAG,"task_restore_failed id="+s.taskId,e);}
   };
   restore.run();handler.postDelayed(restore,600);
  }
  if(!closing){
   if(recoveryFinish!=null)handler.removeCallbacks(recoveryFinish);
   recoveryFinish=()->{recoveryFinish=null;if(closing)return;resumeRemainingTasks();
    // Allow resumed task layers to join a submitted frame before uncovering them.
    stage.postOnAnimation(()->stage.postOnAnimation(()->{if(closing)return;
     for(Slot active:slots)syncSurface(active);
     stage.getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{if(closing)return;clearRecoveryCovers();recovering=false;layoutCards(false);Log.i(TAG,"recovery_ready tasks="+taskIds());}));stage.invalidate();
    }));
   };
   handler.postDelayed(recoveryFinish,650);
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
 private void closeWorkbench(){if(closing)return;closing=true;releaseWindows();Log.i(TAG,"workbench_exit");finishAndRemoveTask();}
 @Override public void onBackPressed(){if(imeBottom>0){getWindow().getInsetsController().hide(WindowInsets.Type.ime());return;}closeWorkbench();}
 @Override protected void onDestroy(){if(!closing){closing=true;releaseWindows();}super.onDestroy();}
 @Override protected void onSaveInstanceState(Bundle b){String[] components=new String[slots.size()];for(int i=0;i<slots.size();i++)components[i]=slots.get(i).component.flattenToString();b.putStringArray("components",components);b.putInt("primary",primary);b.putInt("layoutMode",layoutMode);for(Slot s:slots)if(s.pinned)b.putString("pinned",s.component.flattenToString());super.onSaveInstanceState(b);}
 @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);Log.i(TAG,"configuration orientation="+c.orientation+" tasks="+taskIds());if(stage!=null)scheduleLayout();}
}
