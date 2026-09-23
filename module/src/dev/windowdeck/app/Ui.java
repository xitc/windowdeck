package dev.windowdeck.app;
import android.app.Activity;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
final class Ui {
 static final int CHROME=0xff2c2c2e;
 static final int CARD=0xff3a3a3c;
 static final int TEXT=0xfff2f2f2;
 static final int MUTED=0xffb0b0b4;
 static final int FROST=0x73ffffff;
 static final int FROST_PLUS=0x8a000000;
 static final int MAIN_RADIUS=18;
 static final int SIDE_RADIUS=10;
 static int dp(Context c,float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
 static GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
 static GradientDrawable frost(int radius){
  GradientDrawable d=new GradientDrawable();d.setColor(FROST);d.setCornerRadius(radius);d.setStroke(1,0x40ffffff);return d;
 }
 static TextView text(Context c,String s,int size,int color){TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
 static Button button(Context c,String s){Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setStateListAnimator(null);b.setElevation(0);return b;}
 static void darkSystemBars(Activity a){
  Window w=a.getWindow();
  w.getDecorView();
  w.setStatusBarColor(CHROME);w.setNavigationBarColor(CHROME);
  WindowInsetsController bars=w.getInsetsController();
  if(bars!=null)bars.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
 }
 static void overlaySystemBars(Activity a,boolean lightWallpaper){
  Window w=a.getWindow();
  w.getDecorView();
  w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
  w.setStatusBarColor(0);w.setNavigationBarColor(0);
  w.setStatusBarContrastEnforced(false);w.setNavigationBarContrastEnforced(false);
  WindowInsetsController bars=w.getInsetsController();
  if(bars!=null){
   int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
   bars.setSystemBarsAppearance(lightWallpaper?light:0,light);
  }
 }
 static void insets(View v){v.setOnApplyWindowInsetsListener((view,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(b.left,b.top,b.right,b.bottom);return i;});}
 static void round(View v,int radius){
  v.setClipToOutline(true);
  if(v.getOutlineProvider() instanceof RoundOutline){
   RoundOutline o=(RoundOutline)v.getOutlineProvider();
   if(o.radius==radius)return;
   o.radius=radius;v.invalidateOutline();return;
  }
  v.setOutlineProvider(new RoundOutline(radius));
  v.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->view.invalidateOutline());
 }
 private static final class RoundOutline extends ViewOutlineProvider {
  int radius;
  RoundOutline(int radius){this.radius=radius;}
  public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,Math.max(0,view.getWidth()),Math.max(0,view.getHeight()),radius);}
 }
 static Button more(Context c){
  Button b=button(c,"•••");
  b.setTextSize(22);b.setTextColor(0xe61c1c1c);b.setGravity(Gravity.CENTER);
  b.setBackgroundColor(0);
  b.setShadowLayer(4,0,1,0x99ffffff);
  b.setContentDescription("主应用菜单");
  b.setClickable(true);b.setFocusable(true);
  b.setIncludeFontPadding(false);
  return b;
 }
}
