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

/** Owns back state for the workbench. The activity is only the nested Host. */
final class WorkbenchBackController {
 interface Host {
  boolean backgrounded();
  void cancelAnimation();
  void cancelAnimation(boolean preservePose);
  void cancelDrag();
  void closeEntranceCover(String why);
  boolean closing();
  void detachCaption();
  void dismissHang();
  void dismissPrimaryMenu();
  WorkbenchSlot dragging();
  boolean entranceInputBlocked();
  void finishAndRemoveTask();
  boolean handoffPresentation();
  int imeBottom();
  boolean keepSurfaceOnBackground();
  int layoutMode();
  int modalWindows();
  PopupWindow primaryPopup();
  void publishState(int count);
  void publishState(int count,int generation,int attempt);
  boolean recovering();
  void refreshIme();
  void releaseWindows();
  boolean screenArrival();
  void setBackgrounded(boolean value);
  void setClosing(boolean value);
  CanvasStage stage();
  void startActivity(Intent intent);
  void startActivity(Intent intent,Bundle options);
  void stopIme();
  boolean stopped();
  SwitchPreparation switchPreparation();
  boolean switching();
  Object systemService(String name);
  int taskId();
  String taskIds();
  TaskIdentityController.RotationRun taskRotationRun();
  Window window();
 }

 final WorkbenchSession session;
 final Handler handler;
 final CanvasBinding rom;
 final Host host;
 WorkbenchBackController(Host host,WorkbenchSession session,Handler handler,CanvasBinding rom){
  this.host=host;this.session=session;this.handler=handler;this.rom=rom;
 }

 private static final String TAG="WindowDeck";

 boolean forwardingBack;
 int backGeneration;
 boolean backStartedWithIme;


 boolean backReady(){return host.taskRotationRun()==null&&!host.switchPreparation().pending()&&!host.closing()&&!host.backgrounded()&&!host.stopped()&&host.modalWindows()==0&&!host.recovering()&&!host.switching()&&!host.entranceInputBlocked()&&!host.handoffPresentation()&&host.dragging()==null&&!session.slots.isEmpty();}
 boolean focusPrimary(String reason){
  if(!backReady()||host.stage()==null||!host.stage().isShown()||host.screenArrival())return false;
  WorkbenchSlot main=session.slots.get(session.primary);if(main.released||main.taskId<0)return false;
  host.refreshIme();
  try{rom.canvas.managerMethod("setFocusAppForEmbeddedTask").invoke(rom.manager,main.taskId);Log.i(TAG,"back_focus reason="+reason+" task="+main.taskId);return true;}
  catch(Throwable e){Log.w(TAG,"back_focus_failed",e);return false;}
 }
 void handleTaskRootBack(WorkbenchSlot s,int task){
  if(host.closing()||host.backgrounded()||host.stopped()||session.slots.isEmpty()||session.slots.get(session.primary)!=s||s.released||s.taskId!=task){Log.i(TAG,"back_root_ignored task="+task);return;}
  // C17 can deliver Back through the still-focused embedded task while the
  // native popup is visible. The popup is the current modal owner of that Back.
  if(host.primaryPopup()!=null&&host.primaryPopup().isShowing()){host.dismissPrimaryMenu();Log.i(TAG,"back_menu_dismiss task="+task);return;}
  if(!backReady()){Log.i(TAG,"back_root_ignored task="+task);return;}
  Log.i(TAG,"back_root task="+task+" container="+host.taskId());backgroundWorkbench("task_root");
 }
boolean backgroundWorkbench(String reason){
  if(!backReady())return false;
  host.setBackgrounded(true);backGeneration++;forwardingBack=false;host.cancelDrag();host.cancelAnimation();
  if(host.keepSurfaceOnBackground()){
   // Experimental alternative for TODO A1-3. Moving the container back can still stop its
   // Activity and destroy its surface; only a device trace can establish whether it helps.
   try{
    Class<?> atm=Class.forName("android.app.OplusActivityTaskManager");
    atm.getMethod("moveTaskToBack",int.class,boolean.class).invoke(atm.getMethod("getInstance").invoke(null),host.taskId(),true);
    Log.i(TAG,"workbench_background_surface_kept reason="+reason+" container="+host.taskId());return true;
   }catch(Throwable e){Log.w(TAG,"back_surface_keep_failed",e);}
  }
  // Home preserves the embedded group. moveTaskToBack on a linked child can
  // detach it through the ROM fullscreen transition, so do not use release here.
  try{host.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Log.i(TAG,"workbench_background reason="+reason+" container="+host.taskId()+" primary="+session.primary+" layout="+host.layoutMode()+" tasks="+host.taskIds());return true;}
  catch(Throwable e){host.setBackgrounded(false);Log.w(TAG,"back_home_failed",e);return false;}
}
 boolean embeddedImeVisible(){
  if(host.imeBottom()>0)return true;
  // Embedded IME insets need not reach the container. Read the display's actual
  // IME visibility before the gesture, including an IME owned by a child task.
  try{Object im=host.systemService(Context.INPUT_METHOD_SERVICE);return (Integer)android.view.inputmethod.InputMethodManager.class.getMethod("getInputMethodWindowVisibleHeight").invoke(im)>0;}
  catch(Throwable e){Log.w(TAG,"back_ime_visibility_failed",e);return false;}
 }
 void handleHostBack(){
  if(host.primaryPopup()!=null&&host.primaryPopup().isShowing()){host.dismissPrimaryMenu();return;}
  boolean keyboard=backStartedWithIme||embeddedImeVisible();backStartedWithIme=false;
  if(forwardingBack||!backReady())return;
  if(keyboard){
   try{android.view.inputmethod.InputMethodManager im=(android.view.inputmethod.InputMethodManager)host.systemService(Context.INPUT_METHOD_SERVICE);im.hideSoftInputFromWindow(host.window().getDecorView().getWindowToken(),0);host.window().getInsetsController().hide(WindowInsets.Type.ime());Log.i(TAG,"back_ime_hidden");}
   catch(Throwable e){host.window().getInsetsController().hide(WindowInsets.Type.ime());Log.w(TAG,"back_ime_hide_fallback",e);}
   return;
  }
  // Normally focus is already in the main app, so Android handles IME, dialogs,
  // in-app navigation and predictive cancellation itself. Forward only a
  // committed host back, after checking focus, with a recursion guard.
  WorkbenchSlot target=session.slots.get(session.primary);if(!focusPrimary("host_back"))return;
  forwardingBack=true;final int generation=++backGeneration;
  handler.postDelayed(()->{
   if(generation!=backGeneration||!backReady()||session.slots.get(session.primary)!=target){forwardingBack=false;return;}
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
 void closeWorkbench(){host.dismissHang();host.dismissPrimaryMenu();host.detachCaption();host.closeEntranceCover("close_workbench");if(host.closing())return;host.stopIme();host.setClosing(true);host.publishState(0);host.releaseWindows();Log.i(TAG,"workbench_exit");host.finishAndRemoveTask();}
}
