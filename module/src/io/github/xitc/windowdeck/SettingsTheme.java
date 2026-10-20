package io.github.xitc.windowdeck;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.TextView;

/**
 * C17 系统设置的深浅色与版式数值。资源名与取值见设置界面 C17 风格规格，依据
 * system_ext:/priv-app/Settings/Settings.apk（F.04）。
 */
final class SettingsTheme {
 static boolean night(Context c){return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;}
 static int background(Context c){return night(c)?0xff000000:0xfff0f1f2;}
 static int cardColor(Context c){return night(c)?0xff1a1a1a:0xffffffff;}
 static int press(Context c){return night(c)?0x33ffffff:0x1a000000;}
 static int text(Context c){return night(c)?0xe6ffffff:0xe6000000;}
 static int secondary(Context c){return night(c)?0x8affffff:0x8a000000;}
 static int accent(Context c){return blue(c);}
 static int trackOff(Context c){return night(c)?0x4dffffff:0x42000000;}
 static int chevron(Context c){return night(c)?0x66ffffff:0x66000000;}
 static int blue(Context c){return night(c)?0xff1a8cff:0xff0080ff;}
 static int green(Context c){return night(c)?0xff24b232:0xff00bd13;}
 static int orange(Context c){return night(c)?0xfff08222:0xffff7700;}
 static int purple(Context c){return night(c)?0xffa539ff:0xff952af4;}
 static int red(Context c){return night(c)?0xffeb493d:0xffeb3b2f;}
 static final int CARD_RADIUS=12,CARD_MARGIN=16,ROW_PADDING=16,ROW_MIN=48,ROW_ICON_MIN=72,ICON=40,TITLE_SIZE=16,SUMMARY_SIZE=13,CATEGORY_SIZE=12;
 private SettingsTheme(){}
 static Typeface medium(){return Typeface.create("sans-serif-medium",Typeface.NORMAL);}
 static TextView title(Context c,String s){
  TextView v=new TextView(c);v.setText(s);v.setTextSize(TITLE_SIZE);v.setTextColor(text(c));
  v.setTypeface(medium());v.setLineSpacing(0,1.1f);return v;
 }
 static TextView summary(Context c,String s){
  TextView v=new TextView(c);v.setText(s);v.setTextSize(SUMMARY_SIZE);v.setTextColor(secondary(c));
  v.setLineSpacing(0,1.2f);return v;
 }
 static TextView category(Context c,String s){
  TextView v=new TextView(c);v.setText(s);v.setTextSize(CATEGORY_SIZE);v.setTextColor(secondary(c));
  v.setTypeface(medium());return v;
 }
 static GradientDrawable card(Context c){
  GradientDrawable d=new GradientDrawable();d.setColor(cardColor(c));d.setCornerRadius(Ui.dp(c,CARD_RADIUS));return d;
 }
 static GradientDrawable pill(Context c,int color,int radius){
  GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(Ui.dp(c,radius));return d;
 }
 static RippleDrawable ripple(Context c){return new RippleDrawable(ColorStateList.valueOf(press(c)),null,null);}
 /** 页面与系统栏同色，浅色页面使用深色系统栏图标。 */
 static void systemBars(Activity a){
  Window w=a.getWindow();
  w.setStatusBarColor(background(a));w.setNavigationBarColor(background(a));
  w.getDecorView().setForceDarkAllowed(false);
  WindowInsetsController bars=w.getInsetsController();
  int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
  if(bars!=null)bars.setSystemBarsAppearance(night(a)?0:light,light);
 }
}
