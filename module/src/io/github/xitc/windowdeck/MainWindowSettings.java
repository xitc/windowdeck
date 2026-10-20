package io.github.xitc.windowdeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Module-owned preference, also read by the embedded host and the launcher planner. */
final class MainWindowSettings {
 static final String PREF_FILE="MainActivity",PREF="main_window_percent";
 static final String WRITE="set_main_window_percent";
 static int local(Context context){
  return CardLayout.mainPercent(context.getSharedPreferences(PREF_FILE,0).getInt(PREF,CardLayout.MAIN_DEFAULT_PERCENT));
 }
 static int read(Context context,int fallback){
  try{
   Bundle answer=context.getContentResolver().call(Uri.parse(BuildIdentity.STATE_URI),PREF,null,null);
   if(answer!=null&&answer.containsKey(PREF))return CardLayout.mainPercent(answer.getInt(PREF));
  }catch(Exception e){Log.w("WindowDeck","main_window_size_unavailable",e);}
  return CardLayout.mainPercent(fallback);
 }
 static String label(int percent){return "主窗口大小："+percent+"%";}
 static boolean write(Context context,int percent){
  int value=CardLayout.mainPercent(percent);Bundle request=new Bundle();request.putInt(PREF,value);
  try{
   Bundle answer=context.getContentResolver().call(Uri.parse(BuildIdentity.STATE_URI),WRITE,null,request);
   return answer!=null&&answer.containsKey(PREF)&&answer.getInt(PREF)==value;
  }catch(Exception e){Log.w("WindowDeck","main_window_size_save_failed",e);return false;}
 }
 static void show(Activity activity,TextView entry){
  SharedPreferences prefs=activity.getSharedPreferences(PREF_FILE,0);
  LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
  int pad=Ui.dp(activity,20);body.setPadding(pad,pad,pad,pad);body.setBackgroundColor(SettingsTheme.cardColor(activity));
  TextView value=Ui.text(activity,label(local(activity)),20,SettingsTheme.text(activity));body.addView(value);
  TextView hint=Ui.text(activity,"100% 为默认大小。主窗按比例缩放，放大可覆盖部分侧窗；到达屏幕边缘或侧窗最小可操作区域时会自动限制。保存后返回工作台生效。",14,SettingsTheme.secondary(activity));
  hint.setPadding(0,pad,0,pad);body.addView(hint);
  SeekBar slider=new SeekBar(activity);slider.setMax(CardLayout.MAIN_MAX_PERCENT-CardLayout.MAIN_MIN_PERCENT);
  slider.setProgress(local(activity)-CardLayout.MAIN_MIN_PERCENT);slider.setContentDescription("主窗口大小百分比");
  body.addView(slider,new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));
  slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
   public void onProgressChanged(SeekBar bar,int progress,boolean fromUser){value.setText(label(progress+CardLayout.MAIN_MIN_PERCENT));}
   public void onStartTrackingTouch(SeekBar bar){}
   public void onStopTrackingTouch(SeekBar bar){}
  });
  AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("主窗口大小").setView(body)
   .setNegativeButton("取消",null).setNeutralButton("恢复默认",null)
   .setPositiveButton("保存",(d,which)->{
    int percent=CardLayout.mainPercent(slider.getProgress()+CardLayout.MAIN_MIN_PERCENT);
    prefs.edit().putInt(PREF,percent).apply();entry.setText(percent+"%");
   }).create();
  dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->slider.setProgress(CardLayout.MAIN_DEFAULT_PERCENT-CardLayout.MAIN_MIN_PERCENT)));
  dialog.show();
 }
 private MainWindowSettings(){}
}
