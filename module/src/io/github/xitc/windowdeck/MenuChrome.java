package io.github.xitc.windowdeck;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.view.*;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.util.AttributeSet;
import android.util.Log;
import java.lang.reflect.Method;

/** COUI vertical list chrome. Native panels open blur with RoundFrameLayout.w; the fallback uses ViewRootManager or an opaque color. */
final class MenuChrome {
 static final int FULLSCREEN=0,LAYOUT=1,REPLACE=2,CLOSE=3,KEYBOARD=4;
 private MenuChrome(){}
 private static int resource(Context c,String type,String name){
  return c.getResources().getIdentifier(name,type,"com.oplus.pscanvas");
 }
 static Drawable drawable(Context c,String name){
  try{
   int id=resource(c,"drawable",name);
   if(id==0){Log.w("WindowDeck","menu_resource_unavailable name="+name);return null;}
   return c.getDrawable(id).mutate();
  }catch(Throwable e){Log.w("WindowDeck","menu_resource_unavailable name="+name,e);return null;}
 }
 static Context themed(Context c,boolean night){
  String name=night?"Theme.COUI.Dark":"Theme.COUI";
  int id=resource(c,"style",name);
  if(id==0){Log.w("WindowDeck","menu_resource_unavailable name="+name);return c;}
  return new ContextThemeWrapper(c,id);
 }
 /** Open blur the way C0246q.i1(true) does, before the popup is shown. */
 static View panel(LinearLayout rows,boolean night){
  Context c=rows.getContext();
  int padding=Ui.dp(c,ControlBarMetrics.MENU_GAP_DP);rows.setPadding(0,padding,0,padding);
  rows.setForceDarkAllowed(false);
  try{
   Class<?> type=Class.forName("com.coui.appcompat.poplist.RoundFrameLayout",true,c.getClassLoader());
   FrameLayout frame=(FrameLayout)type.getConstructor(Context.class,AttributeSet.class).newInstance(c,null);
   type.getMethod("setClipMode",int.class).invoke(frame,1);
   frame.setForceDarkAllowed(false);
   boolean blur=openBlur(type,frame,c);
   if(!blur)frame.setBackgroundColor(night?ControlBarMetrics.MENU_FALLBACK_NIGHT:ControlBarMetrics.MENU_FALLBACK);
   frame.addView(rows,new FrameLayout.LayoutParams(-1,-2));
   Log.i("WindowDeck","menu_chrome native=true blur_open=true blur_enabled="+blur);
   return frame;
  }catch(Throwable e){Log.w("WindowDeck","menu_native_panel_unavailable",e);}
  background(rows,night);
  return rows;
 }
 /** Package-private RoundFrameLayout.w(true, the host MID_END constant). native=true does not mean the material is applied. */
 private static boolean openBlur(Class<?> type,View frame,Context c) throws Exception {
  Class<?> holder=Class.forName(RomSymbols.MENU_BLUR_LEVEL_CLASS,true,type.getClassLoader());
  Object mid=holder.getField(RomSymbols.MENU_BLUR_LEVEL_FIELD).get(null);
  Method open=type.getDeclaredMethod("w",boolean.class,mid.getClass());
  open.setAccessible(true);
  open.invoke(frame,true,mid);
  return blurEnabled(c);
 }
 static boolean night(Context c){
  return (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
 }
 static void background(View panel,boolean night){
  int corner=frameCorner(panel.getContext());
  panel.setBackground(Ui.bg(night?ControlBarMetrics.MENU_FALLBACK_NIGHT:ControlBarMetrics.MENU_FALLBACK,corner));
  Ui.round(panel,corner);
  if(panel.isAttachedToWindow())blur(panel,night,corner,true);
  else panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
   public void onViewAttachedToWindow(View v){blur(v,night,corner,true);}
   public void onViewDetachedFromWindow(View v){}
  });
 }
 static View row(Context c,String label,int kind,int color,boolean topBottom,View.OnClickListener click){
  try{
   int id=resource(c,"layout","coui_popup_list_window_item");
   int titleId=resource(c,"id","popup_list_window_item_title");
   int iconId=resource(c,"id","popup_list_window_item_icon");
   if(id==0||titleId==0||iconId==0)Log.w("WindowDeck","menu_native_row_unavailable");
   else{
    View row=LayoutInflater.from(c).inflate(id,null);
    TextView text=row.findViewById(titleId);
    ImageView icon=row.findViewById(iconId);
    View description=row.findViewById(resource(c,"id","popup_list_window_item_description"));
    if(description!=null)description.setVisibility(View.GONE);
    text.setText(label);text.setTextColor(color);
    icon.setImageDrawable(glyph(c,kind,color,topBottom));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    decorateRow(row,color,click);
    return row;
   }
  }catch(Throwable e){Log.w("WindowDeck","menu_native_row_unavailable",e);}
  LinearLayout row=new LinearLayout(c);
  row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
  row.setMinimumHeight(Ui.dp(c,40));
  int padV=Ui.dp(c,8),padH=Ui.dp(c,20);
  row.setPadding(padH,padV,padH,padV);
  ImageView icon=new ImageView(c);
  icon.setImageDrawable(glyph(c,kind,color,topBottom));
  icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
  LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Ui.dp(c,20),Ui.dp(c,20));
  ip.setMarginEnd(Ui.dp(c,12));
  row.addView(icon,ip);
  TextView text=Ui.text(c,label,16,color);
  text.setTypeface(Typeface.create(Typeface.create("sans-serif-regular",Typeface.NORMAL),400,false));
  text.setGravity(Gravity.CENTER_VERTICAL);
  row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
  decorateRow(row,color,click);
  return row;
 }
 private static void decorateRow(View row,int color,View.OnClickListener click){
  row.setClickable(true);row.setFocusable(true);
  int pressed=(color&0x00ffffff)==0?ControlBarMetrics.MENU_PRESS:ControlBarMetrics.MENU_PRESS_NIGHT;
  row.setBackground(new RippleDrawable(ColorStateList.valueOf(pressed),null,null));
  row.setOnClickListener(click);
 }
 private static Drawable glyph(Context c,int kind,int color,boolean topBottom){
  if(kind==KEYBOARD)return new Glyph(kind,color);
  String name=kind==FULLSCREEN?"full_button_bg":kind==REPLACE?"app_list_button_bg":kind==CLOSE?"close_button_bg":
   topBottom?"split_screen_switch_orientation_horizontal":"split_screen_switch_orientation_vertical";
  Drawable icon=drawable(c,name);
  if(icon==null)return new Glyph(kind,color);
  // The vector carries its own alpha; tint only RGB to avoid multiplying it twice.
  icon.setTint(color|0xff000000);
  return icon;
 }
 private static void blur(View panel,boolean night,int corner,boolean retry){
  if(!blurEnabled(panel.getContext()))return;
  try{
   Class<?> mgrCls=Class.forName("com.oplus.view.ViewRootManager");
   Object mgr=mgrCls.getConstructor(View.class).newInstance(panel);
   Drawable drawable=(Drawable)mgrCls.getMethod("getBackgroundBlurDrawable").invoke(mgr);
   if(drawable==null){
    if(retry)panel.post(()->blur(panel,night,corner,false));
    else Log.w("WindowDeck","menu_blur_unavailable");
    return;
   }
   mgrCls.getMethod("setColor",int.class).invoke(mgr,0);
   mgrCls.getMethod("setBlurRadius",int.class).invoke(mgr,ControlBarMetrics.MENU_BLUR_RADIUS);
   mgrCls.getMethod("setCornerRadius",float.class).invoke(mgr,(float)corner);
   try{
    Class<?> paramCls=Class.forName("com.oplus.graphics.OplusBlurParam");
    Object param=paramCls.getConstructor().newInstance();
    paramCls.getMethod("setBlurType",int.class).invoke(param,ControlBarMetrics.BLUR_TYPE);
    float[] blend=ControlBarMetrics.rgba(night?ControlBarMetrics.BLEND_DARK:ControlBarMetrics.BLEND_LIGHT);
    float[] mix=ControlBarMetrics.rgba(night?ControlBarMetrics.MIX_DARK:ControlBarMetrics.MIX_LIGHT);
    int mode=night?ControlBarMetrics.BLEND_MODE_DARK:ControlBarMetrics.BLEND_MODE_LIGHT;
    paramCls.getMethod("setMaterialParams",int.class,float[].class,float[].class).invoke(param,mode,blend,mix);
    mgrCls.getMethod("setBlurParams",paramCls).invoke(mgr,param);
   }catch(Throwable material){Log.w("WindowDeck","menu_blur_material_failed",material);}
   panel.setBackground(drawable);
  }catch(Throwable e){Log.w("WindowDeck","menu_blur_unavailable",e);}
 }
 private static boolean blurEnabled(Context c){
  try{
   if(Settings.System.getInt(c.getContentResolver(),"system_material_blur_enable",0)!=1)return false;
   WindowManager wm=(WindowManager)c.getSystemService(WindowManager.class);
   return wm!=null&&wm.isCrossWindowBlurEnabled();
  }catch(Throwable e){Log.w("WindowDeck","menu_blur_unavailable",e);return false;}
 }
 private static int frameCorner(Context c){
  String name="coui_popup_round_frame_layout_corner_radius";
  int id=resource(c,"dimen",name);
  if(id==0)Log.w("WindowDeck","menu_resource_unavailable name="+name);
  else{
   try{return c.getResources().getDimensionPixelSize(id);}
   catch(Throwable e){Log.w("WindowDeck","menu_resource_unavailable name="+name,e);}
  }
  return Ui.dp(c,ControlBarMetrics.MENU_FRAME_CORNER_DP);
 }
 private static final class Glyph extends Drawable {
  private final int kind; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  Glyph(int kind,int color){
   this.kind=kind;
   paint.setColor(color);paint.setStyle(Paint.Style.STROKE);
   paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
  }
  public void draw(Canvas canvas){
   Rect b=getBounds();
   float s=Math.min(b.width(),b.height())/24f;
   if(s<=0)return;
   canvas.save();canvas.translate(b.left,b.top);canvas.scale(s,s);
   paint.setStrokeWidth(1.4f);
   if(kind==FULLSCREEN)corners(canvas);
   else if(kind==LAYOUT){canvas.drawRoundRect(3,3,9.2f,21,1.4f,1.4f,paint);canvas.drawRoundRect(12.2f,3,21,21,1.4f,1.4f,paint);}
   else if(kind==REPLACE){
    canvas.drawRoundRect(3,3,10,10,1.3f,1.3f,paint);canvas.drawRoundRect(14,3,21,10,1.3f,1.3f,paint);
    canvas.drawRoundRect(3,14,10,21,1.3f,1.3f,paint);canvas.drawRoundRect(14,14,21,21,1.3f,1.3f,paint);
   }else if(kind==KEYBOARD){
    canvas.drawRoundRect(2,5,22,19,2,2,paint);
    for(int y=9;y<=12;y+=3)for(int x=6;x<=18;x+=4)canvas.drawPoint(x,y,paint);
    canvas.drawLine(7,16,17,16,paint);
   }else{canvas.drawCircle(12,12,9.5f,paint);canvas.drawLine(8.3f,8.3f,15.7f,15.7f,paint);canvas.drawLine(15.7f,8.3f,8.3f,15.7f,paint);}
   canvas.restore();
  }
  private void corners(Canvas canvas){
   path.rewind();
   corner(9,3,3,3,3,9);corner(15,3,21,3,21,9);corner(15,21,21,21,21,15);corner(9,21,3,21,3,15);
   canvas.drawPath(path,paint);
  }
  private void corner(float x0,float y0,float x1,float y1,float x2,float y2){path.moveTo(x0,y0);path.lineTo(x1,y1);path.lineTo(x2,y2);}
  public void setAlpha(int alpha){}
  public void setColorFilter(ColorFilter filter){}
  public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 }
}
