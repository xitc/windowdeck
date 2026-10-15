package io.github.xitc.windowdeck;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.RectF;
import android.util.Log;
import android.view.View;
import java.util.Map;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Capsule rectangles and the centre card's own springs.
 *
 * <p>C17 derives the middle gap from a {@code final} field, so the gap is opened by rewriting
 * {@code bgNormalRect} and {@code bgZoomOutRect} after the ROM computed them.
 * {@code bgExpandRect} is left alone. The card's expansion and the window-exit fade use the
 * launcher's 440ms spring and live on this object, one per panel.
 */
final class SwipeCapsuleMotion {
 private static final String TAG="WindowDeck";
 private float expansion;
 private boolean selected;
 private ValueAnimator expansionAnimator;
 private float alpha=1f;
 private boolean alphaOn=true;
 private boolean tracedRetire,tracedRestore,fadedForWindowAnim;
 private ValueAnimator alphaAnimator;

 boolean selected() { return selected; }
 float expansion() { return expansion; }

 void clearWindowFade() { fadedForWindowAnim=false; }

 void resetExpansion() {
  if(expansionAnimator!=null) { expansionAnimator.cancel(); expansionAnimator=null; }
  selected=false;
  expansion=0f;
 }

 /** Puts the card back at full alpha. The gesture boundary owns this; claim latches stay outside. */
 void resetAlpha(SwipeZoneChrome chrome) {
  if(alphaAnimator!=null) { alphaAnimator.cancel(); alphaAnimator=null; }
  alpha=1f;
  alphaOn=true;
  tracedRetire=false;
  tracedRestore=false;
  if(chrome!=null) chrome.zone().setAlpha(1f);
 }

 /**
  * Pushes the two ROM capsules apart so the hook's card fits between them.
  *
  * Only {@code bgNormalRect} and {@code bgZoomOutRect} are touched. {@code bgExpandRect} is the
  * window the capsule morphs into once selected, so it is the one rectangle that must keep the
  * ROM's value; see {@link RomSymbols#RECT_EXPAND}.
  *
  * In landscape the two capsules are stacked, so the gap opens vertically instead.
  *
  * Absolute edges only. {@code onLayout} runs repeatedly and {@code offset()} would drift.
  */
 static void anchor(Object params,Resources res,int rotation) throws Exception {
  Map<?,?> rects=bgRects(params);
  if(rects==null||rects.isEmpty())return;
  if(SwipePanelPolicy.landscapeStack(rotation)) { openLandscapeGap(params,dp(res,RomSymbols.CAPSULE_WIDTH_DP)); return; }
  float center=res.getDisplayMetrics().widthPixels/2f;
  float screen=res.getDisplayMetrics().widthPixels;
  float gap=dp(res,RomSymbols.MID_GAP_DP);
  // The ROM pins the capsules to the screen centre, so this layout needs 2*(104+64)=336dp of
  // screen before the outer edges cross the display. On anything narrower, take the width down
  // instead of letting the capsule run off the edge.
  float width=SwipePanelPolicy.capsuleWidth(screen,gap,dp(res,RomSymbols.CAPSULE_WIDTH_DP),dp(res,RomSymbols.MIN_OUTER_MARGIN_DP));
  if(width<=0f)return;
  for(Object info:rects.values()) {
   RectF normal=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_NORMAL);
   if(normal==null||normal.isEmpty())continue;
   boolean left=normal.centerX()<center;
   float inner=SwipePanelPolicy.capsuleInner(screen,gap,left);
   anchor(normal,left,inner,width);
   // The zoom-out rectangle is only ever read as a ratio and a centre difference against the
   // normal one, so it has to shrink with it or the ROM's 0.5 zoom-out becomes 80/104. Its outer
   // edge is shared with the normal rectangle because that is the direction the ROM collapses in.
   RectF zoom=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_ZOOM_OUT);
   if(zoom!=null&&!zoom.isEmpty()) anchor(zoom,left,left?inner-width/2f:inner+width/2f,width/2f);
  }
  // Keeps the ROM's own width bookkeeping in step; the rectangles above are already correct
  // whether or not this field survived the OTA.
  try { XposedHelpers.setFloatField(params,RomSymbols.PARAMS_SIDE_WIDTH,width); }
  catch(Throwable ignored) {}
 }

 /** Pins one capsule-shaped rect: the edge facing the screen centre lands on {@code inner}. */
 private static void anchor(RectF rect,boolean left,float inner,float width) {
  if(left) { rect.right=inner; rect.left=inner-width; }
  else { rect.left=inner; rect.right=inner+width; }
 }

 private static void openLandscapeGap(Object params,float length) throws Exception {
  Map<?,?> rects=bgRects(params);
  RectF first=null,second=null;
  for(Object info:rects.values()) {
   RectF normal=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_NORMAL);
   if(normal.isEmpty())continue;
   if(first==null)first=normal;
   else second=normal;
  }
  if(first==null||second==null)return;
  RectF upper=first.centerY()<=second.centerY()?first:second,lower=upper==first?second:first;
  float[] top=new float[]{upper.top,upper.bottom},bottom=new float[]{lower.top,lower.bottom};
  SwipePanelPolicy.separateVertical(top,bottom,length);
  upper.top=top[0];
  upper.bottom=top[1];
  lower.top=bottom[0];
  lower.bottom=bottom[1];
 }

 /**
  * Takes the card off the screen the moment the window starts animating open.
  *
  * <p>{@code MultiTriggerPanelView.i(float)} calls {@code createExitAnimation()} on the rising edge
  * of {@code isWindowAnimationStarted}, ahead of every other branch, and that is what keeps the
  * panel from covering the window it just launched. The ROM fades its own content helpers and
  * background paints to alpha 0 and touches nothing else -- the card is a direct child of the panel
  * and is in neither set, so without this it stays on screen over the expanding window.
  *
  * <p>Faded rather than hidden, matching {@code createExitAnimation}'s spring. Once the window
  * animation starts, {@code i(float)} stops driving the panel root's alpha (it guards the
  * {@code setCurrentFraction} call on {@code !isWindowAnimationStarted}), so this fade is the only
  * thing left that can take the card off the screen.
  *
  * <p>The latch is this object's own rather than the ROM's {@code hasCreateExitAnim}: the ROM sets
  * that flag inside the very {@code i(float)} call this hook runs after, so by the time it is read
  * it is always already true. The gesture start clears both the latch and the faded alpha.
  *
  * @return true once the card is being retired, so the caller leaves it alone.
  */
 boolean fadeForWindow(View panel,SwipeZoneChrome chrome,float progress) {
  boolean started;
  try { started=XposedHelpers.getBooleanField(panel,RomSymbols.PANEL_WINDOW_ANIM_STARTED); }
  catch(Throwable e) { Log.w(TAG,"swipe_window_anim_read_failed",e); return false; }
  if(!started||chrome==null)return false;
  if(fadedForWindowAnim)return true;
  fadedForWindowAnim=true;
  LauncherSwipeHook.log("zone exit=window_anim progress="+progress);
  // Share ownership with selection fading so gesture reset cancels *every* writer of card alpha.
  // An untracked exit animator can otherwise finish after reset and hide the next gesture's card.
  if(alphaAnimator!=null) alphaAnimator.cancel();
  alpha=chrome.zone().getAlpha();
  ValueAnimator animator=ValueAnimator.ofFloat(alpha,0f);
  alphaAnimator=animator;
  animator.setDuration(SwipePanelPolicy.ROM_SPRING_MILLIS);
  animator.setInterpolator(f->SwipePanelPolicy.spring(f));
  animator.addUpdateListener(a->{
   alpha=(Float)a.getAnimatedValue();
   if(chrome.zone()!=null) chrome.zone().setAlpha(alpha);
  });
  animator.start();
  return true;
 }

 /**
  * Fades the card in or out as the launcher lights one option and retires the others.
  *
  * <p>Reversible on purpose, and driven by its own spring rather than hidden outright: the finger can
  * cross back into the middle column, and the launcher brings its own capsules back over the same
  * 440ms. Cancelling the running animator and re-targeting from wherever the alpha currently is
  * keeps a reversal from snapping.
  *
  * @param axis the axis code this decision was made on, logged so the two thresholds and the column
  *             split can be read back from a real gesture
  */
 void cardCurrent(SwipeZoneChrome chrome,boolean on,int axis,float progress) {
  if(chrome==null||alphaOn==on)return;
  alphaOn=on;
  if(on) { if(!tracedRestore) { tracedRestore=true; LauncherSwipeHook.log("zone restore=centre axis="+axis+" progress="+progress); } }
  else if(!tracedRetire) { tracedRetire=true; LauncherSwipeHook.log("zone retire=other_option axis="+axis+" progress="+progress); }
  if(alphaAnimator!=null) alphaAnimator.cancel();
  ValueAnimator animator=ValueAnimator.ofFloat(alpha,on?1f:0f);
  alphaAnimator=animator;
  animator.setDuration(SwipePanelPolicy.ROM_SPRING_MILLIS);
  animator.setInterpolator(f->SwipePanelPolicy.spring(f));
  animator.addUpdateListener(a->{
   alpha=(Float)a.getAnimatedValue();
   if(chrome.zone()!=null) chrome.zone().setAlpha(alpha);
  });
  animator.start();
 }

 void position(View panel,SwipeZoneChrome chrome) {
  if(chrome==null||panel.getWidth()<=0)return;
  Object params=params(panel);
  if(params==null)return;
  chrome.apply(expansion);
  View zone=chrome.zone();
  zone.setTranslationX(0);
  zone.setTranslationY(0);
  zone.setScaleX(1);
  zone.setScaleY(1);
  int rotation=rotation(panel);
  int left,top,width,height;
  if(SwipePanelPolicy.landscapeStack(rotation)) {
   float[] span=landscapeSpan(params);
   if(span==null) { zone.setRotation(0); return; }
   float[] box=SwipePanelPolicy.landscapeChip(span[0],span[1],span[2],span[3],dp(panel.getContext(),112),expansion);
   left=Math.round(box[0]);
   top=Math.round(box[1]);
   width=Math.round(box[2]);
   height=Math.round(box[3]);
  } else {
   width=dp(panel.getContext(),112);
   height=Math.round(bgHeight(params));
   top=Math.round(bgMarginTop(params));
   float[] box=SwipePanelPolicy.box(panel.getWidth(),panel.getHeight(),panel.getResources().getDisplayMetrics().density,top,height,expansion);
   left=Math.round(box[0]);
   width=Math.round(box[2]);
   height=Math.round(box[3]);
  }
  zone.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
  zone.layout(left,top,left+width,top+height);
  zone.setPivotX(width/2f);
  zone.setPivotY(height/2f);
  zone.setRotation(SwipePanelPolicy.chipRotation(rotation));
 }

 void updateSelection(View panel,SwipeZoneChrome chrome,float progress,float trigger) {
  if(chrome==null)return;
  Object params=params(panel);
  if(params==null)return;
  boolean now=chrome.zone().getVisibility()==View.VISIBLE&&SwipePanelPolicy.releaseSelected(progress,trigger,centerOffset(panel),dp(panel.getContext(),64),axisCode(panel));
  // Match the native pickable zone immediately; visual expansion never gates selection.
  if(now!=selected) animateExpansion(panel,now);
 }

 private void animateExpansion(View panel,boolean selected) {
  this.selected=selected;
  if(expansionAnimator!=null) expansionAnimator.cancel();
  ValueAnimator animator=ValueAnimator.ofFloat(expansion,selected?1f:0f);
  expansionAnimator=animator;
  // The launcher's own expand spring, not a stock easing curve. A cubic ease-out front-loads 60%
  // of the travel into the first 100ms, which is what made the card look like it snapped open;
  // this curve is still at 38% there and settles with the launcher's small 2% overshoot.
  animator.setDuration(SwipePanelPolicy.ROM_SPRING_MILLIS);
  animator.setInterpolator(f->SwipePanelPolicy.spring(f));
  animator.addUpdateListener(a->{
   expansion=(Float)a.getAnimatedValue();
   LauncherSwipeHook.positionZone(panel);
  });
  animator.start();
  XposedBridge.log("WindowDeck center_expand selected="+selected);
 }

 private static float[] landscapeSpan(Object params) {
  Map<?,?> rects=bgRects(params);
  RectF first=null,second=null;
  for(Object info:rects.values()) {
   RectF normal=(RectF)XposedHelpers.callMethod(info,RomSymbols.RECT_NORMAL);
   if(normal.isEmpty())continue;
   if(first==null)first=normal;
   else second=normal;
  }
  if(first==null||second==null)return null;
  RectF upper=first.centerY()<=second.centerY()?first:second,lower=upper==first?second:first;
  return new float[]{upper.left,upper.right,upper.bottom,lower.top};
 }

 private static Object params(View panel) {
  Object controller=XposedHelpers.getObjectField(panel,RomSymbols.PANEL_CONTROLLER);
  return controller==null?null:XposedHelpers.getObjectField(controller,RomSymbols.CONTROLLER_PARAMS);
 }

 private static int centerOffset(View panel) { return XposedHelpers.getIntField(panel,RomSymbols.PANEL_CENTER_OFFSET); }

 private static int rotation(View panel) {
  try { return XposedHelpers.getIntField(panel,RomSymbols.PANEL_ROTATION); }
  catch(Throwable ignored) { return 0; }
 }

 /** 0 is the launcher's own "nothing pickable yet" value, read as "the card stays". */
 private static int axisCode(View panel) {
  try { return XposedHelpers.getIntField(panel,RomSymbols.PANEL_AXIS_CODE); }
  catch(Throwable e) { Log.w(TAG,"swipe_axis_read_failed",e); return 0; }
 }

 private static float bgHeight(Object params) { return ((Number)XposedHelpers.callMethod(params,RomSymbols.PARAMS_BG_HEIGHT)).floatValue(); }
 private static float bgMarginTop(Object params) { return ((Number)XposedHelpers.callMethod(params,RomSymbols.PARAMS_BG_MARGIN_TOP)).floatValue(); }
 private static Map<?,?> bgRects(Object params) { return (Map<?,?>)XposedHelpers.callMethod(params,RomSymbols.PARAMS_BG_RECT_MAP); }
 private static int dp(Context c,int n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
 private static int dp(Resources r,int n) { return Math.round(n*r.getDisplayMetrics().density); }
}
