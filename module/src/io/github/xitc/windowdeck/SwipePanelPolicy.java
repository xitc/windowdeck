package io.github.xitc.windowdeck;

final class SwipePanelPolicy {
 /**
  * The launcher's two thresholds, deliberately kept apart.
  *
  * {@code mStartShowP} is where an option starts fading in and {@code mStartTriggerP} is where it
  * becomes selectable; between the two it is on screen but not selectable (axis code 32, see
  * {@code SplitFloatParams.calculateSelectArea}). Collapsing them into one gate is what made the
  * card appear a full 0.20 of progress after the two options it sits between -- and never at all
  * for a swipe that comes to rest in between.
  */
 static boolean visible(float progress,float startShow){return progress>=startShow;}
 static boolean selectable(float progress,float startTrigger){return progress>=startTrigger;}
 static boolean selected(float progress,float trigger,float offset,float halfGap){
  return selectable(progress,trigger)&&Math.abs(offset)<=halfGap;
 }
 /**
  * The zone bits of the launcher's axis code that mean "this option can be picked right now".
  * Below it the code carries 32 (on screen, not pickable) or 16 (off screen).
  */
 static final int AXIS_PICKABLE=48;
 /**
  * The column the card occupies. The launcher's own three-option layout puts the middle capsule
  * here ({@code SplitCapsuleFloatParams}: column 1 = split, 3 = capsule, 5 = float) and its
  * two-option layout leaves it empty (column 2 = split, 4 = float), which is the gap this card was
  * built to fill. Both readings agree that column 3 is not one of the launcher's outer options.
  */
 static final int AXIS_COLUMN_CARD=3;
 /**
  * True while the card is still the option the launcher is showing at the centre.
  *
  * <p>{@code SplitCapsuleFloatParams.calculateSelectArea} packs the whole selection into one int --
  * high nibble the zone, low nibble the column -- and {@code MultiTriggerAnimController.e(int)}
  * acts on it by retiring every option whose axis code is not the current one. For those it targets
  * alpha 0 for both the content ({@code new a(0.0f, new PointF(0.5f, 0.5f), zoomOutCentre -
  * normalCentre)}) and the background paint, while the option that was picked grows from its normal
  * rect into its expand rect. The picked capsule is moved to the centre of the screen on the way, so
  * anything still sitting there is overlapped by it.
  *
  * <p>The column is what decides it, and it is the same for both panel variants: the launcher only
  * ever writes a column for a real option, and the card's column is never one of them. Column 0 is
  * the launcher's own "not triggered yet" state -- {@code calculateSelectArea} leaves the column at
  * 0 until progress reaches that column's threshold -- and nothing is retired there.
  *
  * @param axisCode the panel's current axis code, straight off {@code mCurrentSelectedAxisCode}
  */
 static boolean stillCurrent(int axisCode){
  int column=axisCode&0x0F;
  return (axisCode&0xF0)!=AXIS_PICKABLE||column==0||column==AXIS_COLUMN_CARD;
 }
 /**
  * True when the axis code is a pickable option sitting in the card's own column.
  *
  * <p>The launcher's two-capsule layout leaves that column empty -- its {@code d(int)} answers
  * {@code NONE} there -- so {@code MultiTriggerAnimController.e(int)} finds nothing to retire and
  * the two capsules stay lit exactly where the card expands over them.
  *
  * <p>This is the condition the hook uses to answer {@code CAPSULE} for that one code, but the
  * answer alone is not enough and the hook does not rely on the launcher to ask for it. Both
  * entry points into {@code e(int)} are shut while the column reads {@code NONE}:
  * {@code MultiTriggerPanelView.k()} returns at its first-select branch because
  * {@code f(51).d()} is false for an option the layout does not offer, and the other caller in
  * {@code i(float)} only runs for a zone below pickable. So the launcher never calls
  * {@code e(int)} on this column at all, and the hook has to drive it; see
  * {@code LauncherSwipeHook.retireCapsules}. The claim is still expressed here because it is the
  * same question -- "is this the card's column, and is the card on screen" -- and the answer has
  * to stay in step with {@link #stillCurrent}.
  *
  * <p>Only ever used to upgrade {@code NONE}; a layout that already has a real option in the column
  * is left alone.
  */
 static boolean claimsCentre(int axisCode){
  return (axisCode&0xF0)==AXIS_PICKABLE&&(axisCode&0x0F)==AXIS_COLUMN_CARD;
 }
 /**
  * What {@code LauncherSwipeHook.retireCapsules} has to do about the centre claim this frame.
  *
  * <p>Two different things travel together and only one of them is an edge. The <em>drive</em> is
  * edge triggered, because the launcher's animator re-arms all five of its springs from zero on
  * every call -- driving it each frame is an animation that never arrives. The <em>latch</em> is a
  * level, and it has to be recorded even on a frame where nothing is driven.
  *
  * <p>Getting that wrong is not a cosmetic slip: a stop that is never recorded leaves the latch
  * saying "already claimed", the next entry into the column is read as a frame in the middle of a
  * claim, and the capsules are left lit under the card -- which is exactly the failure that shows
  * up when the finger goes out to 分屏 first and then comes back to 添加到工作台.
  */
 static final int CLAIM_NONE=0;
 /** The rising edge: the card has just arrived in its column, drive the launcher's animator. */
 static final int CLAIM_START=1;
 /** The falling edge: record it and leave the restore to the launcher, which does it on its own. */
 static final int CLAIM_STOP=2;
 /**
  * @param wanted  the card is on screen and the finger is in its column
  * @param claimed what the previous frame recorded
  */
 static int claimStep(boolean wanted,boolean claimed){
  if(wanted==claimed)return CLAIM_NONE;
  return wanted?CLAIM_START:CLAIM_STOP;
 }
 static boolean landscapeStack(int rotation){return rotation==1||rotation==3;}
 static float chipRotation(int rotation){return rotation==1?90f:rotation==3?270f:0f;}
 /** Shorten two stacked [top, bottom] spans toward their outer ends, never past the midpoint. */
 static void separateVertical(float[] upper,float[] lower,float length){
  float limit=(lower[1]-upper[0])/2f;
  float f=Math.max(0f,Math.min(length,limit));
  upper[1]=upper[0]+f;lower[0]=lower[1]-f;
 }
 /** Pre-rotation box. A 90° turn makes width the button thickness and height the gap length. */
 static float[] landscapeChip(float buttonLeft,float buttonRight,float gapTop,float gapBottom,float longSide,float expansion){
  float f=Math.max(0,Math.min(1,expansion));
  float thickness=buttonRight-buttonLeft;
  float gap=Math.max(0,gapBottom-gapTop);
  float length=longSide+Math.max(0,gap-longSide)*f;
  float cx=(buttonLeft+buttonRight)/2f,cy=(gapTop+gapBottom)/2f;
  return new float[]{cx-length/2f,cy-thickness/2f,length,thickness};
 }
 static float[] box(int screenWidth,int screenHeight,float density,float top,float normalHeight,float fraction){
  float f=Math.max(0,Math.min(1,fraction));
  float normalWidth=112*density;
  float width=normalWidth+(Math.min(280*density,screenWidth-24*density)-normalWidth)*f;
  float height=normalHeight+(Math.min(560*density,screenHeight-top-80*density)-normalHeight)*f;
  return new float[]{(screenWidth-width)/2,top,width,height};
 }
 /**
  * Inner edge of a capsule: the one facing the screen centre.
  *
  * This is the ROM's own rule ({@code panelparams/b.k}: left capsule right edge
  * {@code (W - gap)/2}, right capsule left edge {@code (W + gap)/2}) restated, so the hook anchors
  * to exactly the geometry the launcher would have produced had the gap been its own.
  */
 static float capsuleInner(float screenWidth,float gap,boolean left){
  return left?(screenWidth-gap)/2f:(screenWidth+gap)/2f;
 }
 /**
  * Capsule width that leaves a {@code gap}-wide middle column and at least {@code minMargin} on
  * the outside, never exceeding {@code preferred}.
  *
  * The launcher centres the capsules, so the room available to each one is
  * {@code (screenWidth - gap)/2} minus whatever has to stay clear of the edge. On a wide screen
  * the preferred width wins; on a narrow one the capsule narrows instead of crossing the display.
  */
 static float capsuleWidth(float screenWidth,float gap,float preferred,float minMargin){
  return Math.max(0f,Math.min(preferred,(screenWidth-gap)/2f-minMargin));
 }
 // ------------------------------------------------------------------ launcher motion
 /**
  * The launcher's alpha spring, straight out of {@code MultiTriggerAnimController.k}: stiffness
  * 440 with a damping ratio of 1.0, i.e. critically damped.
  *
  * The controller ships two spring families and they are not interchangeable. Geometry -- a
  * capsule's scale and offset -- runs on 158 / 0.78, which overshoots by 2%. Alpha, every brighten
  * and dim, runs on this one and does not overshoot at all. The card is a large element (up to
  * 280x560dp, so 492dp of travel), and 2% of that is 11dp of visible bounce, which is a size the
  * capsules' own overshoot never reaches.
  */
 static final float ROM_SPRING_STIFFNESS=440f;
 static final float ROM_SPRING_DAMPING=1f;
 /**
  * How long the curve runs. A critically damped spring is monotonic, so unlike an underdamped one
  * it can be cut short without landing past or short of the target: at 440ms it is at 0.9990.
  */
 static final int ROM_SPRING_MILLIS=440;
 /**
  * Step response of the launcher's alpha spring: 0 at {@code f==0}, rising monotonically to 0.999
  * at {@code f==1} and never crossing 1.
  *
  * What both of the launcher's springs have that a stock easing curve does not is zero velocity at
  * the mark -- they ease into motion instead of snapping into it, and that is what a card this size
  * needs. What this family adds on top is 90% of the travel inside 185ms with no overshoot, so the
  * card arrives briskly and then simply stops, the way the launcher's own brighten and dim do.
  */
 static float spring(float f){
  double t=Math.max(0f,Math.min(1f,f))*(ROM_SPRING_MILLIS/1000.0);
  double wn=Math.sqrt(ROM_SPRING_STIFFNESS);
  double z=ROM_SPRING_DAMPING;
  if(z>=1.0)return (float)(1.0-(1.0+wn*t)*Math.exp(-wn*t));
  double wd=wn*Math.sqrt(1.0-z*z);
  return (float)(1.0-Math.exp(-z*wn*t)*(Math.cos(wd*t)+((z*wn)/wd)*Math.sin(wd*t)));
 }
/**
 * There is no per-option entrance curve to port, and the code that used to live here was wrong.
 *
 * <p>{@code MultiTriggerAnimController}'s show animator does compute
 * {@code mapRange(fraction, 0.9f, 1.0f)} and does write it to the option's scaleX/scaleY, which
 * reads like "the contents scale in over the last tenth". It is not that. The scale range behind
 * that write is {@code new PointF(1.0f, 1.0f)} -- set in {@code ContentViewAnimHelper}'s
 * constructor and only ever re-set to {@code (currentScale, 1.0f)} by {@code e(int)} -- and
 * {@code resetViewScale()} has already put the view at 1.0 by then. Mapping anything across
 * 1.0..1.0 yields 1.0, so the icon and the title sit at full size from the first frame; the
 * {@code 0.9f} is the time domain, not the scale domain.
 *
 * <p>The only property that moves is {@code View.ALPHA} on the panel root, driven 0 to 1 by that
 * same animator, and the option's contents inherit it. A previous revision of this class carried a
 * {@code popIn(reveal)} helper that scaled the card's icon and label from nothing over the last
 * tenth of the reveal, on the strength of the misreading above. That is what put the card's text on
 * screen after the box it sits in, so it was removed rather than corrected.
 */
/** Launcher rapid-reaction fill: white at alpha 0.2, rising to 0.6 while selected. */
 static int background(float expansion){
  float f=Math.max(0f,Math.min(1f,expansion));
  return ((int)(255f*(0.2f+0.4f*f))<<24)|0x00ffffff;
 }
 /** Glyphs track that fill from white to black, same as the launcher ArgbEvaluator. */
 static int glyph(float expansion){
  float f=Math.max(0f,Math.min(1f,expansion));
  int channel=255+(int)(f*(0-255));
  return 0xff000000|(channel<<16)|(channel<<8)|channel;
 }
}
