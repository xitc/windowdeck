package io.github.xitc.windowdeck;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.function.IntPredicate;

/** A fixed floating control: moving the main card never moves the slider under the finger. */
final class MainWindowSizePopup {
 static PopupWindow create(Activity activity,View anchor,int initial,IntPredicate preview,
                           IntPredicate save,Runnable dismissed){
  boolean night=MenuChrome.night(activity);
  Context context=MenuChrome.themed(activity,night);
  int color=night?ControlBarMetrics.MENU_TEXT_NIGHT:ControlBarMetrics.MENU_TEXT;
  int pad=Ui.dp(context,20),margin=Ui.dp(context,8);
  Typeface font=Typeface.create(Typeface.create("sans-serif-regular",Typeface.NORMAL),400,false);
  LinearLayout rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
  LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(pad,0,pad,0);
  rows.addView(body,new LinearLayout.LayoutParams(-1,-2));
  LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
  header.setPadding(0,margin,0,0);
  TextView title=Ui.text(context,"主窗口大小",16,color);title.setTypeface(font);
  title.setGravity(Gravity.CENTER_VERTICAL);
  header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
  TextView value=Ui.text(context,CardLayout.mainPercent(initial)+"%",16,color);value.setTypeface(font);
  value.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);value.setPadding(margin,0,0,0);
  header.addView(value,new LinearLayout.LayoutParams(-2,-2));
  body.addView(header,new LinearLayout.LayoutParams(-1,-2));
  SeekBar slider=new SizeSeekBar(context,night);
  slider.setMax(CardLayout.MAIN_MAX_PERCENT-CardLayout.MAIN_MIN_PERCENT);
  slider.setKeyProgressIncrement(1);
  slider.setProgress(CardLayout.mainPercent(initial)-CardLayout.MAIN_MIN_PERCENT);
  slider.setContentDescription("主窗口大小，80%至130%");
  slider.setStateDescription(CardLayout.mainPercent(initial)+"%");
  body.addView(slider,new LinearLayout.LayoutParams(-1,Ui.dp(context,48)));
  Button reset=Ui.button(context,"恢复默认");reset.setTextColor(color);reset.setTextSize(16);
  reset.setTypeface(font);reset.setGravity(Gravity.CENTER);reset.setPadding(pad,margin,pad,margin);
  reset.setMinimumHeight(Ui.dp(context,48));
  reset.setBackground(new RippleDrawable(ColorStateList.valueOf(night?0x1FFFFFFF:0x14000000),
   Ui.bg(night?0x14FFFFFF:0x08000000,Ui.dp(context,12)),Ui.bg(0xFFFFFFFF,Ui.dp(context,12))));
  LinearLayout.LayoutParams resetBounds=new LinearLayout.LayoutParams(-1,-2);resetBounds.bottomMargin=margin;
  body.addView(reset,resetBounds);
  View chrome=MenuChrome.panel(rows,night);chrome.setClickable(true);
  Rect visible=new Rect();activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
  android.util.DisplayMetrics dm=activity.getResources().getDisplayMetrics();
  if(visible.isEmpty())visible.set(0,0,dm.widthPixels,dm.heightPixels);
  int width=Math.max(1,Math.min(Ui.dp(context,300),visible.width()-2*margin));
  chrome.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
  int height=chrome.getMeasuredHeight();int[] location=new int[2];anchor.getLocationOnScreen(location);
  int x=Math.max(visible.left+margin,Math.min(location[0]+anchor.getWidth()/2-width/2,visible.right-margin-width));
  int y=location[1]+anchor.getHeight()+margin;
  if(y+height>visible.bottom-margin)y=location[1]-height-margin;
  y=Math.max(visible.top+margin,Math.min(y,visible.bottom-margin-height));
  FrameLayout root=new FrameLayout(context);root.setClipChildren(false);root.setClipToPadding(false);
  FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.LEFT);
  bounds.leftMargin=x;bounds.topMargin=y;root.addView(chrome,bounds);
  PopupWindow popup=new PopupWindow(root,dm.widthPixels,dm.heightPixels,true);
  popup.setBackgroundDrawable(new ColorDrawable(0));popup.setOutsideTouchable(true);
  popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
  popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
  popup.setClippingEnabled(false);popup.setIsLaidOutInScreen(true);popup.setSplitTouchEnabled(false);
  root.setOnClickListener(v->popup.dismiss());
  int[] current={CardLayout.mainPercent(initial)},saved={current[0]};
  boolean[] tracking={false},closed={false},saveWarning={false};
  Runnable persist=()->{
   if(current[0]==saved[0])return;
   if(save.test(current[0])){saved[0]=current[0];saveWarning[0]=false;}
   else if(!saveWarning[0]){saveWarning[0]=true;Toast.makeText(activity,"当前大小已调整，但未能保存设置",Toast.LENGTH_SHORT).show();}
  };
  slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
   public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){
    if(closed[0])return;
    int percent=CardLayout.mainPercent(progress+CardLayout.MAIN_MIN_PERCENT);
    if(!preview.test(percent)){popup.dismiss();return;}
    current[0]=percent;value.setText(percent+"%");bar.setStateDescription(percent+"%");bar.invalidate();
    if(!tracking[0])persist.run();
   }
   public void onStartTrackingTouch(SeekBar bar){tracking[0]=true;}
   public void onStopTrackingTouch(SeekBar bar){tracking[0]=false;if(!closed[0])persist.run();}
  });
  reset.setOnClickListener(v->slider.setProgress(CardLayout.MAIN_DEFAULT_PERCENT-CardLayout.MAIN_MIN_PERCENT));
  popup.setOnDismissListener(()->{if(closed[0])return;closed[0]=true;persist.run();dismissed.run();});
  return popup;
 }
 /** C17 transparency slider geometry, with platform SeekBar input and accessibility. */
 private static final class SizeSeekBar extends SeekBar {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float radius,thumbRadius;
  private final int trackColor,progressColor;
  SizeSeekBar(Context context,boolean night){
   super(context);
   radius=Ui.dp(context,10);thumbRadius=Ui.dp(context,6);
   trackColor=themeColor(context,"couiColorContainer12",night?0x33FFFFFF:0x1F000000);
   progressColor=themeColor(context,"couiColorContainerTheme",night?0xFF999999:0xFF4D4D4D);
   setPadding((int)radius,0,(int)radius,0);setThumb(null);setProgressDrawable(new ColorDrawable(0));
   setBackground(null);setSplitTrack(false);
  }
  @Override protected void drawableStateChanged(){super.drawableStateChanged();invalidate();}
  @Override protected synchronized void onDraw(Canvas canvas){
   float left=getPaddingLeft(),right=getWidth()-getPaddingRight(),y=getHeight()/2f;
   float fraction=getMax()==0?0:getProgress()/(float)getMax();
   boolean rtl=getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
   float x=left+(right-left)*(rtl?1-fraction:fraction);
   paint.setColor(trackColor);
   canvas.drawRoundRect(left-radius,y-radius,right+radius,y+radius,radius,radius,paint);
   paint.setColor(progressColor);
   canvas.drawRoundRect(rtl?x-radius:left-radius,y-radius,rtl?right+radius:x+radius,y+radius,radius,radius,paint);
   paint.setColor(0xFFFFFFFF);canvas.drawCircle(x,y,thumbRadius,paint);
   if(isPressed()||isFocused()){
    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Ui.dp(getContext(),2));
    paint.setColor(0x80FFFFFF);canvas.drawCircle(x,y,thumbRadius+Ui.dp(getContext(),2),paint);
    paint.setStyle(Paint.Style.FILL);
   }
  }
 }
 private static int themeColor(Context context,String name,int fallback){
  int id=context.getResources().getIdentifier(name,"attr","com.oplus.pscanvas");
  if(id==0)return fallback;
  android.content.res.TypedArray values=context.obtainStyledAttributes(new int[]{id});
  try{return values.getColor(0,fallback);}finally{values.recycle();}
 }
 private MainWindowSizePopup(){}
}
