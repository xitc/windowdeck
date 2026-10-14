package io.github.xitc.windowdeck;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.AdapterView;
import android.widget.PopupWindow;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native COUI popup owns motion and live material; styled local popup is the fallback. */
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
  String name=night?"Theme.COUI.Dark":"Theme.COUI.Light";
  int id=resource(c,"style",name);
  if(id==0){Log.w("WindowDeck","menu_resource_unavailable name="+name);return c;}
  return new ContextThemeWrapper(c,id);
 }
 /** C0304v.T0/I1: reuse the complete popup, including its responsive motion controller. */
 static PopupWindow popup(Context c,String[] labels,int[] kinds,int color,boolean topBottom,AdapterView.OnItemClickListener click){
  try{
   Class<?> type=Class.forName(RomSymbols.MENU_POPUP_CLASS,true,c.getClassLoader());
   Class<?> builder=Class.forName(RomSymbols.MENU_ITEM_BUILDER_CLASS,true,c.getClassLoader());
   Method title=builder.getMethod(RomSymbols.MENU_ITEM_TITLE_METHOD,String.class);
   Method icon=builder.getMethod(RomSymbols.MENU_ITEM_ICON_METHOD,Drawable.class);
   Method build=builder.getMethod(RomSymbols.MENU_ITEM_BUILD_METHOD);
   Method items=type.getMethod(RomSymbols.MENU_ITEMS_METHOD,List.class);
   Method listener=type.getMethod(RomSymbols.MENU_ITEM_CLICK_METHOD,AdapterView.OnItemClickListener.class);
   Method spacing=type.getMethod(RomSymbols.MENU_SPACING_METHOD,boolean.class,boolean.class);
   Method blur=type.getMethod(RomSymbols.MENU_BLUR_METHOD,boolean.class);
   // Validate the show/cleanup contract before creating any native popup state.
   type.getMethod(RomSymbols.MENU_SHOW_METHOD,View.class,int.class,int.class);
   type.getMethod(RomSymbols.MENU_DISMISS_IMMEDIATE_METHOD);
   List<Object> entries=new ArrayList<>();
   for(int i=0;i<labels.length;i++){
    Object entry=builder.getConstructor().newInstance();
    title.invoke(entry,labels[i]);icon.invoke(entry,glyph(c,kinds[i],color,topBottom));
    entries.add(build.invoke(entry));
   }
   PopupWindow popup=(PopupWindow)type.getConstructor(Context.class).newInstance(c);
   popup.setBackgroundDrawable(null);
   popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
   popup.setSplitTouchEnabled(false);popup.setIsLaidOutInScreen(true);
   popup.setFocusable(true);popup.setOutsideTouchable(true);
   popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
   spacing.invoke(popup,false,false);blur.invoke(popup,true);
   items.invoke(popup,entries);listener.invoke(popup,click);
   View root=popup.getContentView();root.setForceDarkAllowed(false);
   root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
    public void onViewAttachedToWindow(View v){
     v.post(()->{
      if(!v.isAttachedToWindow())return;
      View panel=v instanceof ViewGroup&&((ViewGroup)v).getChildCount()>0?((ViewGroup)v).getChildAt(0):v;
      Drawable applied=panel.getBackground();
      Log.i("WindowDeck","menu_popup_attached native=true system_blur_enabled="+blurEnabled(c)+
       " background="+(applied==null?"none":applied.getClass().getName()));
     });
    }
    public void onViewDetachedFromWindow(View v){}
   });
   Log.i("WindowDeck","menu_popup native=true blur_requested=true");
   return popup;
  }catch(Throwable e){Log.w("WindowDeck","menu_native_popup_unavailable",e);return null;}
 }
 static void showPopup(PopupWindow popup,View anchor,int x,int y) throws Exception {
  popup.getClass().getMethod(RomSymbols.MENU_SHOW_METHOD,View.class,int.class,int.class).invoke(popup,anchor,x,y);
 }
 /** Roll back a failed show immediately, before opening the local fallback window. */
 static void abortPopup(PopupWindow popup){
  try{popup.getClass().getMethod(RomSymbols.MENU_DISMISS_IMMEDIATE_METHOD).invoke(popup);}
  catch(Throwable e){
   Log.w("WindowDeck","menu_native_popup_cleanup_failed",e);
   try{popup.dismiss();}catch(Throwable dismissError){Log.w("WindowDeck","menu_native_popup_dismiss_failed",dismissError);}
  }
 }
 /** Open blur the way C0246q.i1(true) does, before the popup is shown. */
 static View panel(LinearLayout rows,boolean night){
  Context c=rows.getContext();
  int padding=Ui.dp(c,ControlBarMetrics.MENU_GAP_DP);rows.setPadding(0,padding,0,padding);
  rows.setForceDarkAllowed(false);rows.setBackground(null);
  try{
   Class<?> type=Class.forName("com.coui.appcompat.poplist.RoundFrameLayout",true,c.getClassLoader());
   int layout=resource(c,"layout","coui_popup_list_window_layout");
   if(layout==0)throw new IllegalStateException("Missing COUI menu layout");
   // XML supplies rfRadius, smooth radius/weight, clip mode and rendering flags.
   View inflated=LayoutInflater.from(c).inflate(layout,new FrameLayout(c),false);
   if(!type.isInstance(inflated))throw new IllegalStateException("Unexpected COUI menu root");
   FrameLayout frame=(FrameLayout)inflated;
   frame.removeAllViews();
   frame.setForceDarkAllowed(false);
   Drawable background=panelDrawable(c);
   if(background==null)throw new IllegalStateException("Missing COUI menu background");
   // setClipMode initializes a plain white background. C0246q replaces it before attach.
   frame.setBackground(background);
   boolean requested=false;
   try{openBlur(type,frame);requested=true;}
   catch(Throwable e){Log.w("WindowDeck","menu_native_blur_unavailable",e);}
   frame.addView(rows,new FrameLayout.LayoutParams(-1,-2));
   frame.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
    public void onViewAttachedToWindow(View v){
     Drawable applied=v.getBackground();
     Log.i("WindowDeck","menu_chrome_attached native=true hardware="+v.isHardwareAccelerated()+
      " system_blur_enabled="+blurEnabled(v.getContext())+" background="+(applied==null?"none":applied.getClass().getName()));
    }
    public void onViewDetachedFromWindow(View v){}
   });
   Log.i("WindowDeck","menu_chrome native=true blur_requested="+requested);
   return frame;
  }catch(Throwable e){
   Log.w("WindowDeck","menu_native_panel_unavailable",e);
   if(rows.getParent() instanceof ViewGroup)((ViewGroup)rows.getParent()).removeView(rows);
  }
  background(rows,night);
  return rows;
 }
 private static Drawable panelDrawable(Context c){
  int attr=resource(c,"attr","couiPopupWindowBackground");
  if(attr!=0){
   TypedArray values=c.obtainStyledAttributes(new int[]{attr});
   try{Drawable d=values.getDrawable(0);if(d!=null)return d.mutate();}
   finally{values.recycle();}
  }
  return drawable(c,"coui_popup_window_background");
 }
 /** A request only: RoundFrameLayout applies and observes the material after attachment. */
 private static void openBlur(Class<?> type,View frame) throws Exception {
  Class<?> holder=Class.forName(RomSymbols.MENU_BLUR_LEVEL_CLASS,true,type.getClassLoader());
  Object mid=holder.getField(RomSymbols.MENU_BLUR_LEVEL_FIELD).get(null);
  Method open=type.getDeclaredMethod(RomSymbols.MENU_OPEN_BLUR_METHOD,boolean.class,mid.getClass());
  open.setAccessible(true);
  open.invoke(frame,true,mid);
 }
 static boolean night(Context c){
  return (c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
 }
 static void background(View panel,boolean night){
  int corner=contentCorner(panel.getContext());
  Ui.round(panel,corner);
  FallbackMaterial material=new FallbackMaterial(panel,night,corner);
  material.opaque();panel.addOnAttachStateChangeListener(material);
  if(panel.isAttachedToWindow())material.onViewAttachedToWindow(panel);
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
 /** Only used without the native container. Never leave an unconfigured transparent drawable. */
 private static final class FallbackMaterial implements View.OnAttachStateChangeListener {
  private final View panel;
  private final boolean night;
  private final int corner,color;
  private WindowManager wm;
  private Object manager;
  private Class<?> managerClass;
  private Drawable blurDrawable;
  private boolean observing,listening;
  private final Runnable refresh=()->update(false);
  private final Consumer<Boolean> blurListener;
  private final ContentObserver observer=new ContentObserver(new Handler(Looper.getMainLooper())){
   @Override public void onChange(boolean selfChange){update(true);}
  };
  FallbackMaterial(View panel,boolean night,int corner){
   this.panel=panel;this.night=night;this.corner=corner;
   blurListener=enabled->{panel.post(refresh);};
   color=hostColor(panel.getContext(),night?"coui_list_dialog_background_color_no_blur_night":"coui_list_dialog_background_color_no_blur_light",
    night?ControlBarMetrics.MENU_FALLBACK_NIGHT:ControlBarMetrics.MENU_FALLBACK);
  }
  private void opaque(){
   if(panel.getBackground()!=null&&panel.getBackground()!=blurDrawable)return;
   panel.setBackground(Ui.bg(color,corner));
  }
  public void onViewAttachedToWindow(View v){
   try{
    panel.getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor("system_material_blur_enable"),false,observer);
    observing=true;
    wm=panel.getContext().getSystemService(WindowManager.class);
    if(wm!=null){wm.addCrossWindowBlurEnabledListener(blurListener);listening=true;}
   }catch(Throwable e){Log.w("WindowDeck","menu_blur_listener_unavailable",e);}
   update(true);
  }
  public void onViewDetachedFromWindow(View v){
   panel.removeCallbacks(refresh);
   if(observing){panel.getContext().getContentResolver().unregisterContentObserver(observer);observing=false;}
   if(listening){wm.removeCrossWindowBlurEnabledListener(blurListener);listening=false;}
   disable();manager=null;managerClass=null;blurDrawable=null;wm=null;
  }
  private void update(boolean retry){
   if(!panel.isAttachedToWindow())return;
   if(!panel.isHardwareAccelerated()||!blurEnabled(panel.getContext())){disable();return;}
   try{
    if(manager==null){
     Class<?> mgrCls=Class.forName("com.oplus.view.ViewRootManager");
     Object mgr=mgrCls.getConstructor(View.class).newInstance(panel);
     Drawable drawable=(Drawable)mgrCls.getMethod("getBackgroundBlurDrawable").invoke(mgr);
     if(drawable==null){
      if(retry)panel.post(refresh);
      else Log.w("WindowDeck","menu_blur_unavailable");
      return;
     }
     Class<?> paramCls=Class.forName("com.oplus.graphics.OplusBlurParam");
     Object param=paramCls.getConstructor().newInstance();
     paramCls.getMethod("setBlurType",int.class).invoke(param,ControlBarMetrics.BLUR_TYPE);
     Context c=panel.getContext();
     float[] blend=ControlBarMetrics.rgba(hostColor(c,night?"coui_popup_list_blend_blur_dark":"coui_popup_list_blend_blur_light",
      night?ControlBarMetrics.BLEND_DARK:ControlBarMetrics.BLEND_LIGHT));
     float[] mix=ControlBarMetrics.rgba(hostColor(c,night?"coui_popup_list_mix_blur_dark":"coui_popup_list_mix_blur_light",
      night?ControlBarMetrics.MIX_DARK:ControlBarMetrics.MIX_LIGHT));
     paramCls.getMethod("setMaterialParams",int.class,float[].class,float[].class).invoke(param,ControlBarMetrics.MENU_BLEND_MODE,blend,mix);
     mgrCls.getMethod("setBlurParams",paramCls).invoke(mgr,param);
     mgrCls.getMethod("setCornerRadius",float.class).invoke(mgr,(float)corner);
     drawable.setAlpha(255);
     managerClass=mgrCls;manager=mgr;blurDrawable=drawable;
    }
    managerClass.getMethod("setColor",int.class).invoke(manager,hostColor(panel.getContext(),"coui_list_dialog_background_color_above_blur",0));
    managerClass.getMethod("setBlurRadius",int.class).invoke(manager,ControlBarMetrics.MENU_BLUR_RADIUS);
    panel.setBackground(blurDrawable);
   }catch(Throwable e){disable();Log.w("WindowDeck","menu_blur_unavailable",e);}
  }
  private void disable(){
   if(manager!=null)try{managerClass.getMethod("setBlurRadius",int.class).invoke(manager,0);}
   catch(Throwable e){Log.w("WindowDeck","menu_blur_disable_failed",e);}
   opaque();
  }
 }
 private static int hostColor(Context c,String name,int fallback){
  int id=resource(c,"color",name);
  if(id!=0)try{return c.getResources().getColor(id,c.getTheme());}
  catch(Throwable e){Log.w("WindowDeck","menu_resource_unavailable name="+name,e);}
  return fallback;
 }
 private static boolean blurEnabled(Context c){
  try{
   if(Settings.System.getInt(c.getContentResolver(),"system_material_blur_enable",0)!=1)return false;
   WindowManager wm=(WindowManager)c.getSystemService(WindowManager.class);
   return wm!=null&&wm.isCrossWindowBlurEnabled();
  }catch(Throwable e){Log.w("WindowDeck","menu_blur_unavailable",e);return false;}
 }
 private static int contentCorner(Context c){
  String name="coui_round_corner_m";
  int id=resource(c,"dimen",name);
  if(id==0)Log.w("WindowDeck","menu_resource_unavailable name="+name);
  else{
   try{return c.getResources().getDimensionPixelSize(id);}
   catch(Throwable e){Log.w("WindowDeck","menu_resource_unavailable name="+name,e);}
  }
  return Ui.dp(c,ControlBarMetrics.MENU_CORNER_DP);
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
