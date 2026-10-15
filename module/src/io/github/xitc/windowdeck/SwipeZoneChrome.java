package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The centre card's view tree and paints.
 *
 * <p>The launcher draws 分屏 and 浮窗 as a white capsule, an icon and a two-line title. This card
 * uses that same chrome. Layout, alpha and whether the card is offered stay with the gesture.
 */
final class SwipeZoneChrome {
 private final LinearLayout zone;
 private final TextView label;
 private final WorkbenchMark mark;
 private final PanelBg background;

 private SwipeZoneChrome(LinearLayout zone, TextView label, WorkbenchMark mark, PanelBg background) {
  this.zone=zone;
  this.label=label;
  this.mark=mark;
  this.background=background;
 }

 /** Adds the card to {@code panel} once, at the launcher's 112×68dp slot. */
 static SwipeZoneChrome attach(FrameLayout panel) {
  Context c=panel.getContext();
  LinearLayout zone=new LinearLayout(c);
  zone.setOrientation(LinearLayout.VERTICAL);
  zone.setGravity(Gravity.CENTER_HORIZONTAL);
  zone.setClickable(false);
  zone.setFocusable(false);
  zone.setElevation(0);
  zone.setForceDarkAllowed(false);
  zone.setPadding(dp(c,8),launcherPx(c,"rapid_reaction_icon_margin_top_v15",12),dp(c,8),0);
  PanelBg bg=new PanelBg(launcherFloat(c,"rapid_reaction_double_rect_background_smooth_corner_radius",9));
  zone.setBackground(bg);
  ImageView icon=new ImageView(c);
  WorkbenchMark mark=new WorkbenchMark();
  icon.setImageDrawable(mark);
  icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
  icon.setBackgroundColor(0);
  icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  icon.setForceDarkAllowed(false);
  int iconPx=launcherPx(c,"icon_content_size",24);
  zone.addView(icon,new LinearLayout.LayoutParams(iconPx,iconPx));
  TextView label=new TextView(c);
  label.setText("添加到工作台");
  label.setGravity(Gravity.CENTER);
  label.setMaxLines(2);
  label.setEllipsize(TextUtils.TruncateAt.END);
  label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  label.setForceDarkAllowed(false);
  int style=c.getResources().getIdentifier("couiTextButtonS","style",c.getPackageName());
  if(style!=0)label.setTextAppearance(style);
  label.setTextSize(12);
  label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
  LinearLayout.LayoutParams textLp=new LinearLayout.LayoutParams(-1,-2);
  textLp.topMargin=launcherPx(c,"rapid_reaction_text_content_margin_top",4);
  zone.addView(label,textLp);
  zone.setContentDescription("添加到工作台");
  FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(c,112),dp(c,68),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
  lp.topMargin=dp(c,40);
  panel.addView(zone,lp);
  return new SwipeZoneChrome(zone,label,mark,bg);
 }

 LinearLayout zone() { return zone; }

 /** Glyph and capsule fill follow the expansion spring. 0 is the resting white chip. */
 void apply(float expansion) {
  background.setExpansion(expansion);
  int color=SwipePanelPolicy.glyph(expansion);
  label.setTextColor(color);
  mark.setGlyph(color);
 }

 private static int dp(Context c,int n) {
  return Math.round(n*c.getResources().getDisplayMetrics().density);
 }

 private static int launcherPx(Context c,String name,int fallbackDp) {
  int id=c.getResources().getIdentifier(name,"dimen",c.getPackageName());
  return id==0?dp(c,fallbackDp):c.getResources().getDimensionPixelSize(id);
 }

 private static float launcherFloat(Context c,String name,float fallbackDp) {
  int id=c.getResources().getIdentifier(name,"dimen",c.getPackageName());
  return id==0?fallbackDp*c.getResources().getDisplayMetrics().density:c.getResources().getDimension(id);
 }

 /** White fill, alpha 0.2–0.6, 9dp smooth corner. Same path the launcher uses for 分屏 and 浮窗. */
 private static final class PanelBg extends Drawable {
  private final float radius;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  private final RectF rect=new RectF();
  private final Object oplusPath;
  private final java.lang.reflect.Method smooth;
  private float expansion;
  private boolean smoothFailed;

  PanelBg(float radius) {
   this.radius=radius;
   paint.setStyle(Paint.Style.FILL);
   Object pathObject=null;
   java.lang.reflect.Method method=null;
   try {
    Class<?> type=Class.forName("com.oplus.graphics.OplusPath");
    pathObject=type.getConstructor(Path.class).newInstance(path);
    method=type.getMethod("addSmoothRoundRect",RectF.class,float.class,float.class,float.class,Path.Direction.class);
   } catch(Throwable ignored) {}
   oplusPath=pathObject;
   smooth=method;
  }

  void setExpansion(float expansion) {
   this.expansion=expansion;
   invalidateSelf();
  }

  public void draw(Canvas canvas) {
   android.graphics.Rect b=getBounds();
   if(b.isEmpty())return;
   paint.setColor(SwipePanelPolicy.background(expansion));
   rect.set(b);
   path.reset();
   if(smooth!=null&&!smoothFailed) {
    try { smooth.invoke(oplusPath,rect,radius,radius,0.99f,Path.Direction.CCW); }
    catch(Throwable e) { smoothFailed=true; path.reset(); path.addRoundRect(rect,radius,radius,Path.Direction.CCW); }
   } else path.addRoundRect(rect,radius,radius,Path.Direction.CCW);
   canvas.drawPath(path,paint);
  }

  public void setAlpha(int alpha) {}
  public void setColorFilter(ColorFilter filter) {}
  public int getOpacity() { return PixelFormat.TRANSLUCENT; }
 }

 /** 24dp window-plus glyph, stroke 1.6 at 90% like rapid_reaction_float_window_v15. */
 private static final class WorkbenchMark extends Drawable {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private int color=0xe6ffffff;

  WorkbenchMark() {
   paint.setStyle(Paint.Style.STROKE);
   paint.setStrokeCap(Paint.Cap.ROUND);
   paint.setStrokeJoin(Paint.Join.ROUND);
  }

  void setGlyph(int opaque) {
   color=0xe6000000|(opaque&0x00ffffff);
   invalidateSelf();
  }

  public void draw(Canvas canvas) {
   android.graphics.Rect b=getBounds();
   if(b.isEmpty())return;
   canvas.save();
   canvas.translate(b.left,b.top);
   canvas.scale(b.width()/24f,b.height()/24f);
   paint.setColor(color);
   paint.setStrokeWidth(1.6f);
   canvas.drawRoundRect(4.3f,4.3f,19.7f,19.7f,2.2f,2.2f,paint);
   canvas.drawLine(12f,8.7f,12f,15.3f,paint);
   canvas.drawLine(8.7f,12f,15.3f,12f,paint);
   canvas.restore();
  }

  public int getIntrinsicWidth() { return 24; }
  public int getIntrinsicHeight() { return 24; }
  public void setAlpha(int alpha) {}
  public void setColorFilter(ColorFilter filter) {}
  public int getOpacity() { return PixelFormat.TRANSLUCENT; }
 }
}
