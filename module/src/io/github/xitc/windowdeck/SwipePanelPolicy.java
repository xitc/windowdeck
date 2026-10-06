package io.github.xitc.windowdeck;

final class SwipePanelPolicy {
 static boolean selected(float progress,float trigger,float offset,float halfGap){
  return progress>=trigger&&Math.abs(offset)<=halfGap;
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
 /** {@code Utilities.getProgress(progress, startShow, startTrigger)}, clamped to 0..1. */
 static float reveal(float progress,float startShow,float startTrigger){
  if(startTrigger==startShow)return progress>=startTrigger?1f:0f;
  float p=Math.abs(progress-startShow)/Math.abs(startTrigger-startShow);
  return Math.max(0f,Math.min(1f,p));
 }
 /**
  * {@code Utilities.mapRange(reveal, 0.9f, 1.0f)} -- the launcher scales an option's icon and
  * title from nothing to full size over the last tenth of the reveal, so the contents land with a
  * quick pop while the capsule outline has already faded in.
  */
 static float popIn(float reveal){
  float p=Math.max(0f,Math.min(1f,reveal));
  return Math.max(0f,Math.min(1f,(p-0.9f)/0.1f));
 }
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
