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

/** Owns menu state for the workbench. The activity is only the nested Host. */
final class WorkbenchMenuController {
 interface Host {
  Activity activity();
  ValueAnimator addAnimation();
  boolean backReady();
  boolean backgrounded();
  void cancelAnimation();
  void abortSideSort(String reason);
  boolean sideSortActive();
  void cancelAnimation(boolean preservePose);
  CardLayout.Result cardGeometry();
  void chooseApp(WorkbenchSlot replacing);
  void closeWorkbench();
  boolean closing();
  Context context();
  void exitToFullscreen();
  void fitSurface(WorkbenchSlot s,int width,int height);
  boolean focusPrimary(String reason);
  void hangForReplace();
  boolean initialized();
  void inputRole(WorkbenchSlot s,boolean isPrimary);
  void layoutCards(boolean animate);
  void layoutCards(boolean animate,SwitchMotion<WorkbenchSlot> source);
  int layoutMode();
  LayoutSwitchVisuals layoutSwitchVisuals();
  int mainWindowPercent();
  ControlDots more();
  WorkbenchSlot pendingEntrance();
  void promote(int index);
  void publishState(int count);
  void publishState(int count,int generation,int attempt);
  void refreshStatus();
  void releaseSlot(WorkbenchSlot s);
  void releaseSlot(WorkbenchSlot s,boolean toFront);
  void releaseSlot(WorkbenchSlot s,boolean toFront,boolean retainPlate);
  Resources resources();
  void restoreContainerFocus();
  void scheduleLayout();
  boolean screenArrival();
  void setMainWindowPercent(int value);
  CanvasStage stage();
  boolean stopped();
  boolean switching();
  int taskId();
  TaskIdentityController.RotationRun taskRotationRun();
  void toggleImeFloating();
  void toggleLayout();
  void updateNaturalBounds();
  Window window();
  WindowManager windowManager();
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 WorkbenchMenuController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 final java.util.HashMap<Integer,Boolean> contentLight=new java.util.HashMap<>();
 Object controlBarCallback;
 boolean captionAttached;
 PopupWindow primaryPopup;
 int modalWindows;
 WorkbenchSlot dragging;
 boolean recovering;
 Runnable recoveryFinish;


 void menu(WorkbenchSlot s){
  if(host.sideSortActive()||host.closing()||s.released||recovering||dragging!=null)return;
  showWorkbenchDialog(new AlertDialog.Builder(host.context()).setTitle(s.label).setItems(new String[]{"切换为主应用",s.pinned?"取消固定":"固定此应用","替换应用","从工作台移出"},(d,which)->{
   if(s.released||recovering||dragging!=null)return;
   if(which==0)host.promote(session.slots.indexOf(s));else if(which==1)togglePin(s);else if(which==2)host.chooseApp(s);else removeSlot(s);
  }).setNegativeButton("取消",null).create());
 }
 void togglePin(WorkbenchSlot s){
  if(!s.pinned)for(WorkbenchSlot other:session.slots)if(other.pinned){Toast.makeText(host.context(),"请先取消固定 "+other.label,Toast.LENGTH_SHORT).show();return;}
  host.cancelAnimation();s.pinned=!s.pinned;host.layoutCards(false);Log.i(TAG,"pin slot="+s.id+" pinned="+s.pinned);
 }
 void removeSlot(WorkbenchSlot s){removeSlot(s,false);}
 void removeSlot(WorkbenchSlot s,boolean taskGone){
  int index=session.slots.indexOf(s);if(index<0||host.closing()||s.released)return;
  if(!taskGone&&(host.sideSortActive()||recovering||dragging!=null))return;
  if(!taskGone&&s.pinned){Toast.makeText(host.context(),"请先取消固定",Toast.LENGTH_SHORT).show();return;}
  cancelDrag();
  if(session.slots.size()==1){host.closeWorkbench();return;}
  prepareRecovery(s,()->removeSlotNow(s));
 }
 void removeSlotNow(WorkbenchSlot s){
  int index=session.slots.indexOf(s);if(index<0||host.closing()||s.released)return;
  host.cancelAnimation();WorkbenchSlot main=session.slots.get(session.primary);session.slots.remove(index);host.publishState(session.slots.size());session.primary=main==s?Math.min(index,session.slots.size()-1):session.slots.indexOf(main);
  host.releaseSlot(s);host.updateNaturalBounds();host.layoutCards(false);host.refreshStatus();host.restoreContainerFocus();Log.i(TAG,"remove slot="+s.id+" task="+s.taskId+" count="+session.slots.size());
 }
 void prepareRecovery(WorkbenchSlot excluded,Runnable mutation){
  host.abortSideSort("recovery");
  host.cancelAnimation();recovering=true;
  for(int i=0;i<session.slots.size();i++){
   WorkbenchSlot active=session.slots.get(i);host.inputRole(active,i==session.primary);
   if(active.released||active.surface==null)continue;
   if(active.recoveryCover==null){
    RecoveryCover cover=new RecoveryCover(host.context());

    try{Method snapshot=rom.viewApi.getDeclaredMethod("getSnapBitMap",boolean.class);snapshot.setAccessible(true);
     Bitmap bitmap=(Bitmap)snapshot.invoke(active.surface,false);if(bitmap!=null)cover.setBitmap(bitmap);
     Log.i(TAG,"recovery_cover slot="+active.id+" snapshot="+(bitmap!=null));
    }catch(Throwable e){Log.w(TAG,"recovery_snapshot_unavailable slot="+active.id,e);}
    active.recoveryCover=cover;active.card.addView(cover,1,new FrameLayout.LayoutParams(active.renderBounds.width(),active.renderBounds.height()));
    host.fitSurface(active,active.card.getWidth(),active.card.getHeight());
   }
  }
  // Submit the opaque covers before moveTaskToBack can hide sibling task leashes.
  host.stage().getViewTreeObserver().registerFrameCommitCallback(()->handler.post(()->{if(!host.closing())mutation.run();}));host.stage().invalidate();
 }
 void clearRecoveryCovers(){
  for(WorkbenchSlot active:session.slots)if(active.recoveryCover!=null){active.recoveryCover.setBitmap(null);active.card.removeView(active.recoveryCover);active.recoveryCover=null;}
 }
 void cancelDrag(){host.abortSideSort("cancel_drag");if(dragging!=null){WorkbenchSlot old=dragging;dragging=null;if(old.card!=null)old.card.resetGesture();}}
 boolean beginDrag(WorkbenchSlot s){
  if(host.sideSortActive()||host.closing()||recovering||host.switching()||dragging!=null||s.released||s.pinned||session.slots.indexOf(s)==session.primary||s.taskId<0)return false;
  dragging=s;Log.i(TAG,"drag_start slot="+s.id);return true;
 }
 void finishDrag(WorkbenchSlot s,boolean commit){
  if(dragging!=s)return;dragging=null;
  Log.i(TAG,"drag_end slot="+s.id+" commit="+commit);
  if(commit)removeSlot(s);
 }
 void dismissPrimaryMenu(){PopupWindow popup=primaryPopup;primaryPopup=null;if(popup!=null&&popup.isShowing())popup.dismiss();}
 Runnable trackPrimaryPopup(PopupWindow popup){
  primaryPopup=popup;
  boolean[] dismissed={false};
  Runnable finish=()->{
   // COUI notifies when dismissal starts and again when its animated window is removed.
   if(dismissed[0])return;dismissed[0]=true;
   if(primaryPopup==popup)primaryPopup=null;
   modalWindows=Math.max(0,modalWindows-1);handler.post(()->host.focusPrimary("menu_dismiss"));
  };
  popup.setOnDismissListener(finish::run);modalWindows++;
  return finish;
 }
 Runnable trackPickerPopup(PopupWindow popup){
  primaryPopup=popup;
  boolean[] dismissed={false};
  Runnable finish=()->{
   if(dismissed[0])return;dismissed[0]=true;
   if(primaryPopup==popup)primaryPopup=null;
   modalWindows=Math.max(0,modalWindows-1);handler.post(()->host.focusPrimary("menu_dismiss"));
  };
  popup.setOnDismissListener(finish::run);modalWindows++;
  return finish;
 }
 void primaryMenu(){
  if(host.sideSortActive())return;
  if(host.taskRotationRun()!=null||host.closing()||recovering||dragging!=null||session.slots.isEmpty()||host.more()==null||host.more().getVisibility()!=View.VISIBLE||host.stage()==null||host.stage().getWidth()<=0)return;
  dismissPrimaryMenu();
  WorkbenchSlot main=session.slots.get(session.primary);
  CardLayout.Result geometry=host.cardGeometry();
  if(geometry.cards.length==0)return;
  int[] card=geometry.cards[Math.min(session.primary,geometry.cards.length-1)];
  boolean night=MenuChrome.night(host.context());
  int color=night?ControlBarMetrics.MENU_TEXT_NIGHT:ControlBarMetrics.MENU_TEXT;
  int width=ControlBarMetrics.menuWidth(host.resources().getDisplayMetrics().density);
  Context menuContext=MenuChrome.themed(host.context(),night);
  String[] items={"全屏","切换布局","替换应用","关闭","悬浮键盘："+(CanvasImeBridge.floating()?"开":"关")};
  int[] glyphs={MenuChrome.FULLSCREEN,MenuChrome.LAYOUT,MenuChrome.REPLACE,MenuChrome.CLOSE,MenuChrome.KEYBOARD};
  java.util.function.IntConsumer action=which->{
   if(which<0||which>=items.length)return;
   dismissPrimaryMenu();
   if(host.closing()||recovering||dragging!=null||session.slots.isEmpty()||session.slots.get(session.primary)!=main)return;
   if(which==0)host.exitToFullscreen();else if(which==1)host.toggleLayout();else if(which==2)host.hangForReplace();else if(which==3)removeSlot(main);else host.toggleImeFloating();
  };
  int[] loc=new int[2];host.stage().getLocationOnScreen(loc);
  android.util.DisplayMetrics dm=host.resources().getDisplayMetrics();
  int anchorX=loc[0]+card[0]+card[2]/2;
  int y=ControlBarMetrics.menuTop(loc[1]+card[1],dm.density);
  PopupWindow nativePopup=MenuChrome.popup(menuContext,items,glyphs,color,host.layoutMode()==PaneLayout.TOP_BOTTOM,
   (parent,view,which,id)->action.accept(which));
  if(nativePopup!=null){
   Runnable finish=trackPrimaryPopup(nativePopup);
   try{
    MenuChrome.showPopup(nativePopup,host.window().getDecorView(),anchorX,y);
    if(nativePopup.isShowing()){
     Log.i(TAG,"primary_menu_open native=true anchor_x="+anchorX+" y="+y+" night="+night);return;
    }
    Log.w(TAG,"menu_native_popup_not_shown");
   }catch(Throwable e){Log.w(TAG,"menu_native_popup_show_failed",e);}
   MenuChrome.abortPopup(nativePopup);finish.run();
  }
  LinearLayout panel=new LinearLayout(menuContext);panel.setOrientation(LinearLayout.VERTICAL);
  for(int i=0;i<items.length;i++){
   final int which=i;
   panel.addView(MenuChrome.row(panel.getContext(),items[i],glyphs[i],color,host.layoutMode()==PaneLayout.TOP_BOTTOM,v->action.accept(which)),new LinearLayout.LayoutParams(-1,-2));
  }
  View chrome=MenuChrome.panel(panel,night);
  int x=anchorX-width/2;
  int left=loc[0],span=host.stage().getWidth();
  x=Math.max(left,Math.min(x,Math.max(left,left+span-width)));
  // C17's popup root spans the window. A panel-sized window clips the native
  // elevation shadow into a gray rectangle at the rounded corners.
  FrameLayout popupRoot=new FrameLayout(panel.getContext());
  popupRoot.setClipChildren(false);popupRoot.setClipToPadding(false);
  popupRoot.setOnClickListener(v->dismissPrimaryMenu());
  chrome.setClickable(true);
  FrameLayout.LayoutParams menuBounds=new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.LEFT);
  menuBounds.leftMargin=x;menuBounds.topMargin=y;
  popupRoot.addView(chrome,menuBounds);
  PopupWindow popup=new PopupWindow(popupRoot,dm.widthPixels,dm.heightPixels,true);popup.setOutsideTouchable(true);popup.setElevation(0);
  popup.setClippingEnabled(false);popup.setIsLaidOutInScreen(true);
  popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
  popup.setBackgroundDrawable(new ColorDrawable(0));
  popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
  Runnable finish=trackPrimaryPopup(popup);
  try{popup.showAtLocation(host.window().getDecorView(),Gravity.TOP|Gravity.LEFT,0,0);Log.i(TAG,"primary_menu_open native=false x="+x+" y="+y+" night="+night);}
  catch(Throwable e){popup.dismiss();finish.run();Log.w(TAG,"primary_menu_failed",e);}
 }
 void showWorkbenchDialog(AlertDialog dialog){modalWindows++;dialog.setOnDismissListener(d->{modalWindows=Math.max(0,modalWindows-1);handler.post(()->host.focusPrimary("dialog_dismiss"));});dialog.show();}
 String layoutLabel(){return host.layoutMode()==PaneLayout.TOP_BOTTOM?"上下":"左右";}
 void primarySizeSlider(){
  if(!host.backReady()||host.pendingEntrance()!=null||host.screenArrival()||host.addAnimation()!=null||host.more()==null||host.more().getVisibility()!=View.VISIBLE||host.stage()==null||!host.stage().isShown())return;
  dismissPrimaryMenu();WorkbenchSlot main=session.slots.get(session.primary);
  final PopupWindow[] owned={null};boolean[] finished={false};
  Runnable finish=()->{
   if(finished[0])return;finished[0]=true;
   if(primaryPopup==owned[0])primaryPopup=null;
   modalWindows=Math.max(0,modalWindows-1);handler.post(()->host.focusPrimary("size_slider_dismiss"));
  };
  PopupWindow popup=MainWindowSizePopup.create(host.activity(),host.more(),host.mainWindowPercent(),percent->{
   if(host.closing()||host.stopped()||host.backgrounded()||recovering||host.switching()||host.taskRotationRun()!=null||host.screenArrival()||session.slots.isEmpty()||session.slots.get(session.primary)!=main)return false;
   if(host.mainWindowPercent()!=percent){host.setMainWindowPercent(percent);host.scheduleLayout();}
   return true;
  },percent->MainWindowSettings.write(host.context(),percent),finish);
  owned[0]=popup;primaryPopup=popup;modalWindows++;
  try{popup.showAtLocation(host.window().getDecorView(),Gravity.TOP|Gravity.LEFT,0,0);Log.i(TAG,"main_size_slider_open percent="+host.mainWindowPercent());}
  catch(Throwable e){popup.dismiss();finish.run();Log.w(TAG,"main_size_slider_failed",e);}
 }
 void refreshMainWindowSize(){
  int value=MainWindowSettings.read(host.context(),host.mainWindowPercent());
  if(value==host.mainWindowPercent())return;
  host.setMainWindowPercent(value);dismissPrimaryMenu();
  Log.i(TAG,"main_window_percent="+value);
  if(host.initialized()&&!host.closing()&&host.stage()!=null)host.scheduleLayout();
 }
 boolean primaryLightContent(){
  if(!session.slots.isEmpty()&&session.primary>=0&&session.primary<session.slots.size()){
   Boolean known=contentLight.get(session.slots.get(session.primary).taskId);
   if(known!=null)return known;
  }
  return !MenuChrome.night(host.context());
 }
 void onContentLight(int taskId,boolean light){
  contentLight.put(taskId,light);
  Log.i(TAG,"control_bar_light task="+taskId+" light="+light);
  if(!session.slots.isEmpty()&&session.primary>=0&&session.primary<session.slots.size()&&session.slots.get(session.primary).taskId==taskId)layoutCaption();
 }
 void registerControlBarLight(){
  if(controlBarCallback!=null)return;
  try{
   Class<?> mgrCls=CanvasContract.require().manager;
   Object mgr=mgrCls.getMethod("getInstance").invoke(null);
   Class<?> face=Class.forName(CanvasContract.WINDOW_MANAGER+"$IEmbeddedWindowCallback");
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
   mgrCls.getMethod("registerEmbeddedWindowContainerCallback",face,int.class).invoke(mgr,callback,host.taskId());
   controlBarCallback=callback;
   Log.i(TAG,"control_bar_callback task="+host.taskId());
  }catch(Throwable e){Log.w(TAG,"control_bar_callback_unavailable",e);}
 }
 void unregisterControlBarLight(){
  Object callback=controlBarCallback;controlBarCallback=null;
  if(callback==null)return;
  try{
   Class<?> mgrCls=CanvasContract.require().manager;
   Object mgr=mgrCls.getMethod("getInstance").invoke(null);
   Class<?> face=Class.forName(CanvasContract.WINDOW_MANAGER+"$IEmbeddedWindowCallback");
   mgrCls.getMethod("unregisterEmbeddedWindowContainerCallback",face,int.class).invoke(mgr,callback,host.taskId());
  }catch(Throwable e){Log.w(TAG,"control_bar_callback_release_failed",e);}
 }
 void detachCaption(){
  if(!captionAttached||host.more()==null)return;
  try{host.windowManager().removeViewImmediate(host.more());}catch(Throwable ignored){}
  captionAttached=false;
 }
 void layoutCaption(){
  if(host.layoutSwitchVisuals()!=null){detachCaption();return;}
  if(host.more()==null||session.slots.isEmpty()||host.closing()||host.stage()==null||host.stage().getWidth()<=0){detachCaption();return;}
  CardLayout.Result geometry=host.cardGeometry();
  if(geometry.cards.length==0){detachCaption();return;}
  int[] main=geometry.cards[Math.min(session.primary,geometry.cards.length-1)];
  android.util.DisplayMetrics dm=host.resources().getDisplayMetrics();
  float scale=ControlBarMetrics.scale(main[2],main[3],dm.widthPixels,dm.heightPixels);
  host.more().setLightContent(primaryLightContent());
  int w=ControlBarMetrics.hit(ControlBarMetrics.ASSET_W,dm.density,scale);
  int h=ControlBarMetrics.hit(ControlBarMetrics.ASSET_H,dm.density,scale);
  int[] loc=new int[2];host.stage().getLocationInWindow(loc);
  int x=loc[0]+main[0]+Math.max(0,(main[2]-w)/2);
  int y=loc[1]+main[1];
  WindowManager.LayoutParams lp;
  if(!captionAttached){
   android.os.IBinder token=host.window().getDecorView().getWindowToken();
   if(token==null){host.stage().post(this::layoutCaption);return;}
   lp=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,android.graphics.PixelFormat.TRANSLUCENT);
   lp.token=token;lp.gravity=Gravity.TOP|Gravity.LEFT;lp.setTitle("WindowDeckCaption");lp.x=x;lp.y=y;
   try{host.windowManager().addView(host.more(),lp);captionAttached=true;Log.i(TAG,"caption_attached x="+x+" y="+y+" w="+w+" h="+h+" scale="+scale);}
   catch(Throwable e){Log.w(TAG,"caption_attach_failed",e);return;}
  }else{
   lp=(WindowManager.LayoutParams)host.more().getLayoutParams();
   if(lp.x==x&&lp.y==y&&lp.width==w&&lp.height==h)return;
   lp.x=x;lp.y=y;lp.width=w;lp.height=h;
   try{host.windowManager().updateViewLayout(host.more(),lp);}catch(Throwable e){Log.w(TAG,"caption_update_failed",e);}
  }
 }
}
