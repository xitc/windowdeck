package dev.windowdeck.app;

final class SwipePanelPolicy {
 static boolean selected(float progress,float trigger,float offset,float halfGap){
  return progress>=trigger&&Math.abs(offset)<=halfGap;
 }
 static boolean landscapeStack(int rotation){return rotation==1||rotation==3;}
 static float chipRotation(int rotation){return rotation==1?90f:rotation==3?270f:0f;}
 /** Shorten two stacked [top, bottom] spans toward their outer ends. */
 static void separateVertical(float[] upper,float[] lower,float length){
  upper[1]=upper[0]+length;lower[0]=lower[1]-length;
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
