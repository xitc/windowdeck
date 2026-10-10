package io.github.xitc.windowdeck;

/** Keeps the current main-card scale, max(card / display). This is not C17's formula. */
final class ControlBarMetrics {
 static final float ASSET_W=100f,ASSET_H=40f,DOT_DP=5f,DOT_Y_DP=21.5f;
 static final float[] DOT_X_DP={41.5f,50.5f,59.5f};
 static final int DOT_ON_LIGHT=0x99000000,DOT_ON_DARK=0xE6FFFFFF;
 static final float MENU_ANCHOR_DP=12f,MENU_GAP_DP=8f,MENU_WIDTH_DP=196f,MENU_MIN_DP=168f,MENU_MAX_DP=256f;
 /** RoundFrameLayout corner resource, not the 12 dp content radius. */
 static final float MENU_FRAME_CORNER_DP=28f;
 static final int MENU_BLUR_RADIUS=160;
 static final int MENU_TEXT=0xE6000000,MENU_TEXT_NIGHT=0xE6FFFFFF;
 static final int MENU_FALLBACK=0xFFFFFFFF,MENU_FALLBACK_NIGHT=0xFF242424;
 static final int MENU_PRESS=0x08000000,MENU_PRESS_NIGHT=0x0DFFFFFF;
 static final int BLEND_LIGHT=0xBFEEEEEE,MIX_LIGHT=0x99CDCDCD,BLEND_DARK=0x99262626,MIX_DARK=0x99262626;
 static final int BLUR_TYPE=2,BLEND_MODE_LIGHT=3,BLEND_MODE_DARK=2;

 static float scale(int cardW,int cardH,int displayW,int displayH){
  if(cardW<=0||cardH<=0||displayW<=0||displayH<=0)return 1f;
  return Math.max(cardW/(float)displayW,cardH/(float)displayH);
 }
 static int dpPx(float dp,float density){return (int)(dp*density+.5f);}
 /** Truncate after rounding dp to px, matching C17's dimensionPixelSize * scale. */
 static int hit(float dp,float density,float scale){return Math.max(1,(int)(dpPx(dp,density)*scale));}
 static int menuWidth(float density){
  int width=dpPx(MENU_WIDTH_DP,density);
  return Math.max(dpPx(MENU_MIN_DP,density),Math.min(dpPx(MENU_MAX_DP,density),width));
 }
 /** Window y is the card top plus the unscaled 12 dp anchor. The 8 dp gap stays on the list. */
 static int menuTop(int cardTop,float density){return cardTop+dpPx(MENU_ANCHOR_DP,density);}
 /** x, y, radius for three dots, in the scaled 100×40 view. */
 static float[] dots(int viewW,int viewH){
  float sx=viewW/ASSET_W,sy=viewH/ASSET_H,y=DOT_Y_DP*sy,r=(DOT_DP/2f)*sx;
  float[] out=new float[9];
  for(int i=0;i<3;i++){out[i*3]=DOT_X_DP[i]*sx;out[i*3+1]=y;out[i*3+2]=r;}
  return out;
 }
 /** Oplus material color: red, green, blue, alpha. */
 static float[] rgba(int argb){
  return new float[]{((argb>>16)&255)/255f,((argb>>8)&255)/255f,(argb&255)/255f,((argb>>>24)&255)/255f};
 }
 private ControlBarMetrics(){}
}
