package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hides launcher UI under a landscape handoff cover, then puts the window alpha back.
 *
 * <p>First landscape entry can also veil the retained source leash. The caller stops those writes
 * at the native release callback. Logging the leash names uses the same walk with alpha writes off.
 */
final class SwipeLauncherVeil {
 private static final String TAG="WindowDeck";

 /** Names and native layer ids of the recents leashes, without changing alpha. */
 static void logLeashes(Object handler) {
  StringBuilder names=new StringBuilder();
  try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) { veilSurfaces(handler,transaction,names,false); }
  catch(Throwable e) { Log.w(TAG,"swipe_source_layer_unavailable",e); }
  Log.i(TAG,"swipe_source_layer name="+names);
 }

 /** Hide launcher UI. First landscape entry veils its exact retained source;
  *  the caller stops these writes at the native release callback. */
 static void conceal(Object handler,View root,AtomicBoolean stop,boolean veilLeash,SwipeSourceFrame.Capture source) {
  if(root==null)return;
  final int[] ticks={0};
  final boolean[] logged={false};
  Runnable step=new Runnable(){public void run(){
   if(stop.get())return;
   StringBuilder names=new StringBuilder();
   try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){
    Object vri=XposedHelpers.callMethod(root,"getViewRootImpl");
    SurfaceControl window=vri==null?null:(SurfaceControl)XposedHelpers.callMethod(vri,"getSurfaceControl");
    if(window!=null&&window.isValid())transaction.setAlpha(window,0f);
    if(source!=null)source.conceal(transaction);
    veilSurfaces(handler,transaction,names,veilLeash);
    transaction.apply();
   }catch(Throwable e){Log.w(TAG,"handoff_underlay_failed",e);}
   if(!logged[0]){logged[0]=true;Log.i(TAG,"handoff_underlay_alpha name="+names);}
   if(ticks[0]++<90)root.postDelayed(this,16);
   else restore(root);
  }};
  root.post(step);
 }

 static void restore(View root) {
  if(root==null)return;
  try {
   Object vri=XposedHelpers.callMethod(root,"getViewRootImpl");
   SurfaceControl window=vri==null?null:(SurfaceControl)XposedHelpers.callMethod(vri,"getSurfaceControl");
   if(window==null||!window.isValid())return;
   try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()){transaction.setAlpha(window,1f).apply();}
  } catch(Throwable e) { Log.w(TAG,"handoff_underlay_restore_failed",e); }
 }

 private static void veilSurfaces(Object handler,SurfaceControl.Transaction transaction,StringBuilder names,boolean apply) {
  int[] budget={8},visits={0};
  IdentityHashMap<Object,Boolean> seen=new IdentityHashMap<>();
  Object manager=null;
  try { manager=XposedHelpers.getObjectField(handler,RomSymbols.HANDLER_TASK_ANIM); }
  catch(Throwable e) { Log.w(TAG,"handoff_underlay_failed",e); }
  veilObject(manager,transaction,names,budget,visits,0,seen,apply);
  if(manager!=null) {
   try { veilObject(XposedHelpers.callMethod(manager,RomSymbols.TASK_ANIM_CONTROLLER),transaction,names,budget,visits,0,seen,apply); }
   catch(Throwable e) { Log.w(TAG,"handoff_underlay_failed",e); }
  }
  try { veilObject(XposedHelpers.getObjectField(handler,RomSymbols.SOURCE_HANDLES),transaction,names,budget,visits,0,seen,apply); }
  catch(Throwable e) { Log.w(TAG,"handoff_underlay_failed",e); }
 }

 private static void veilObject(Object node,SurfaceControl.Transaction transaction,StringBuilder names,int[] budget,int[] visits,int depth,IdentityHashMap<Object,Boolean> seen,boolean apply) {
  if(node==null||budget[0]<=0||visits[0]>=48||depth>3||seen.containsKey(node))return;
  seen.put(node,Boolean.TRUE);
  visits[0]++;
  if(node instanceof SurfaceControl) {
   SurfaceControl sc=(SurfaceControl)node;
   String name=String.valueOf(sc);
   if(skipSurface(name)||!sc.isValid())return;
   try {
    if(apply)transaction.setAlpha(sc,0f);
    budget[0]--;
    if(names.length()>0)names.append(',');
    names.append(name).append("#layer=").append(LiveTaskContent.layerId(sc));
   } catch(Throwable e) { Log.w(TAG,"handoff_underlay_failed",e); }
   return;
  }
  if(node instanceof Object[]) {
   for(Object child:(Object[])node) veilObject(child,transaction,names,budget,visits,depth+1,seen,apply);
   return;
  }
  if(skipNode(node))return;
  for(Class<?> type=node.getClass();type!=null&&type!=Object.class;type=type.getSuperclass()) {
   if(type.getName().startsWith("java."))break;
   Field[] fields;
   try { fields=type.getDeclaredFields(); }
   catch(Throwable e) { return; }
   Field[] interesting=new Field[fields.length];
   int n=0,m=0;
   for(Field field:fields) {
    if(Modifier.isStatic(field.getModifiers()))continue;
    Class<?> ft=field.getType();
    if(ft.isPrimitive()||ft==String.class)continue;
    if(ft==SurfaceControl.class||ft.isArray()) interesting[n++]=field;
    else interesting[fields.length-(++m)]=field;
   }
   for(int i=0;i<n&&budget[0]>0;i++) readField(node,interesting[i],transaction,names,budget,visits,depth,seen,apply);
   for(int i=0;i<m&&budget[0]>0&&visits[0]<48;i++) readField(node,interesting[fields.length-1-i],transaction,names,budget,visits,depth,seen,apply);
  }
 }

 private static void readField(Object node,Field field,SurfaceControl.Transaction transaction,StringBuilder names,int[] budget,int[] visits,int depth,IdentityHashMap<Object,Boolean> seen,boolean apply) {
  try { field.setAccessible(true); veilObject(field.get(node),transaction,names,budget,visits,depth+1,seen,apply); }
  catch(Throwable ignored) {}
 }

 private static boolean skipNode(Object node) {
  if(node instanceof View||node instanceof Context||node instanceof Bitmap)return true;
  String name=node.getClass().getName();
  return name.startsWith("java.")||name.startsWith("javax.")||name.startsWith("android.widget.")
   ||name.startsWith("android.graphics.")||name.startsWith("android.os.")||name.startsWith("android.util.")
   ||name.startsWith("android.content.");
 }

 private static boolean skipSurface(String name) {
  return name==null||name.contains("WindowDeck")||name.contains("Display")||name.contains("Wallpaper")
   ||name.contains("StatusBar")||name.contains("NavigationBar")||name.contains("InputMethod")||name.contains("ScreenDecor")
   ||name.contains("pscanvas");
 }
}
