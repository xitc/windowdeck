package dev.windowdeck.app;

final class SwipePanelPolicy {
 static boolean selected(float progress,float trigger,float offset,float halfGap){
  return progress>=trigger&&Math.abs(offset)<=halfGap;
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
