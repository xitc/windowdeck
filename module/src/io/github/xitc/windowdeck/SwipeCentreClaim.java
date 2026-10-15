package io.github.xitc.windowdeck;

import android.util.Log;
import android.view.View;
import java.util.Map;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hands the launcher's empty centre column to the card for the one retire call that needs it.
 *
 * <p>The two-capsule layout registers columns 2 and 4 and answers {@code NONE} for column 3, so
 * {@code MultiTriggerAnimController.e(int)} finds nothing to retire while the finger is in the
 * middle. Answering {@code CAPSULE} is raised only around that call. A standing answer would make
 * {@code MultiTriggerPanelView.k()} bail, because {@code g(CAPSULE)} is false on a two-capsule
 * layout. See {@link #claimCentre}.
 */
final class SwipeCentreClaim {
 private static final String TAG="WindowDeck";
 /**
  * True while the card is actually in the panel. It is what lets the hook answer {@code CAPSULE} for
  * the launcher's empty centre column: that claim is only honest while there is a card standing in
  * the column, otherwise the launcher would retire both capsules for nothing.
  */
 private static volatile boolean cardShown;
 /**
  * True only for the duration of {@link #retire}'s own call into the launcher's animator.
  */
 private static volatile boolean claimCentre;
 /** Cached {@link RomSymbols#claimProblem} result, so the skip is reported once per install. */
 private static volatile String claimProblem;
 private static volatile boolean claimProbed,tracedClaimAnswered;

 /** Per-panel edge latch. The drive is edge triggered; the level follows both edges. */
 static final class Latch {
  boolean claimed;
  boolean tracedStop;
 }

 static void clearShown() { cardShown=false; }

 static void showCard() { cardShown=true; }

 /** Clears the edge latch at the gesture boundary, together with the card alpha reset. */
 static void reset(Latch latch) {
  latch.claimed=false;
  latch.tracedStop=false;
 }

 /**
  * Drops the centre claim because the card left the panel.
  *
  * <p>The two travel together: the claim only makes sense while there is a card in the column, and
  * leaving it up after the card is gone would have the launcher retire both capsules for nothing.
  */
 static void cardHidden(Latch latch) {
  cardShown=false;
  if(latch!=null) latch.claimed=false;
 }

 /**
  * Hooks {@code selectionOf(int)} on the params class the first time that class is seen.
  *
  * <p>The answer is raised only while {@link #retire} is inside its animator call, not while the
  * launcher is deciding whether it may select at all.
  */
 static void hook(Class<?> type) {
  final Class<?> optionType=returnType(type,RomSymbols.PARAMS_SELECTION_OF,int.class);
  if(optionType==null||!optionType.isEnum()) {
   LauncherSwipeHook.log("centre claim skipped: "+RomSymbols.PARAMS_SELECTION_OF+"(int) not found");
   return;
  }
  final Object none=enumConstant(optionType,"NONE"),capsule=enumConstant(optionType,"CAPSULE");
  if(none==null||capsule==null) {
   LauncherSwipeHook.log("centre claim skipped: NONE/CAPSULE missing on "+optionType.getName());
   return;
  }
  XposedHelpers.findAndHookMethod(type,RomSymbols.PARAMS_SELECTION_OF,int.class,new XC_MethodHook(){
   @Override protected void afterHookedMethod(MethodHookParam p) {
    try {
     if(!claimCentre||!cardShown||p.getResult()!=none)return;
     if(!SwipePanelPolicy.claimsCentre((Integer)p.args[0]))return;
     p.setResult(capsule);
     // Proves the override reached the launcher's own read of the column, once per process. The
     // claim can be reported as driven and still not retire anything if this never fires, and the
     // two failures look identical from the outside.
     if(!tracedClaimAnswered) { tracedClaimAnswered=true; LauncherSwipeHook.log("centre claim answered axis="+p.args[0]+" -> CAPSULE"); }
    } catch(Throwable e) { Log.w(TAG,"swipe_centre_claim_failed",e); }
   }
  });
 }

 /**
  * Pushes the launcher's two capsules out of the card's column, on the frame the card gets there.
  *
  * <p>Nothing in the launcher will do this on its own. Both doors into
  * {@code MultiTriggerAnimController.e(int)} are shut while the centre column reads {@code NONE}:
  * {@code MultiTriggerPanelView.k()} leaves at its first-select branch because
  * {@code f(51).d()} is false for an option this layout does not offer, and the other caller in
  * {@code i(float)} only runs for a zone below pickable. So the hook calls the animator itself,
  * with the same two calls the launcher makes on every axis change.
  *
  * <p>Edge triggered, like the launcher's own: see {@link SwipePanelPolicy#claimStep}. The launcher
  * takes over again the moment the finger leaves the column -- {@code mCurSelectAxisCode} is written
  * back to the card's column here precisely so that {@code k()}'s comparison sees a change and runs
  * its own restore, with its own callback and its own haptic.
  *
  * <p>Failing to find the animator is not fatal. The card then draws over the capsules as it did
  * before this existed, and the reason is logged once.
  */
 static void retire(View panel,Latch latch,int axis,float progress) {
  boolean wanted=cardShown&&SwipePanelPolicy.claimsCentre(axis);
  int step=SwipePanelPolicy.claimStep(wanted,latch.claimed);
  if(step==SwipePanelPolicy.CLAIM_NONE)return;
  // The latch follows the level on both edges. Only the drive is edge triggered; recording a stop
  // is what lets the next entry into the column be read as an entry again.
  latch.claimed=wanted;
  if(step==SwipePanelPolicy.CLAIM_STOP) {
   if(!latch.tracedStop) { latch.tracedStop=true; LauncherSwipeHook.log("centre claim dropped axis="+axis+" progress="+progress); }
   return;
  }
  Object controller;
  try { controller=XposedHelpers.getObjectField(panel,RomSymbols.PANEL_CONTROLLER); }
  catch(Throwable e) { Log.w(TAG,"swipe_controller_read_failed",e); return; }
  if(controller==null)return;
  if(problem(controller.getClass())!=null)return;
  claimCentre=true;
  try {
   XposedHelpers.callMethod(controller,RomSymbols.CONTROLLER_CANCEL);
   XposedHelpers.callMethod(controller,RomSymbols.CONTROLLER_RETIRE,axis);
   XposedHelpers.setIntField(controller,RomSymbols.CONTROLLER_AXIS_CODE,axis);
   LauncherSwipeHook.log("centre claim axis="+axis+" progress="+progress+" helpers="+helperCount(controller));
  } catch(Throwable e) { Log.w(TAG,"swipe_centre_retire_failed",e); }
  finally { claimCentre=false; }
 }

 /**
  * How many content helpers the controller has registered, for the claim log.
  *
  * <p>Reads the helper map directly rather than through a symbol: it is diagnostics only, and the
  * count is what says whether the claim had anything to retire. A layout that registers only the
  * card's own column would leave this at one and the claim would have nothing to do.
  */
 private static String helperCount(Object controller) {
  try {
   Object map=XposedHelpers.getObjectField(controller,"h");
   return map instanceof Map?Integer.toString(((Map<?,?>)map).size()):"?";
  } catch(Throwable ignored) { return "?"; }
 }

 /**
  * The controller's suitability for the claim, resolved once and reported once.
  *
  * @return {@code null} when the claim can run, otherwise {@link RomSymbols#claimProblem}'s reason
  */
 static String problem(Class<?> controller) {
  if(!claimProbed) {
   claimProbed=true;
   claimProblem=RomSymbols.claimProblem(controller);
   if(claimProblem!=null) LauncherSwipeHook.log("centre claim disabled: "+claimProblem);
  }
  return claimProblem;
 }

 /** The declared return type of a method, wherever it sits in the hierarchy. */
 private static Class<?> returnType(Class<?> owner,String name,Class<?>... args) {
  for(Class<?> c=owner;c!=null;c=c.getSuperclass()) {
   try { return c.getDeclaredMethod(name,args).getReturnType(); }
   catch(NoSuchMethodException ignored) {}
  }
  return null;
 }

 /** An enum constant by name, or {@code null} if this build renamed or dropped it. */
 private static Object enumConstant(Class<?> type,String name) {
  Object[] constants=type.getEnumConstants();
  if(constants==null)return null;
  for(Object c:constants) if(((Enum<?>)c).name().equals(name))return c;
  return null;
 }
}
