package io.github.xitc.windowdeck;

import android.app.*;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.util.*;

/** User-managed package list for the C17 native fullscreen compatibility configuration. */
final class NativeVideoSettings {
 private final Activity activity;
 private final LinkedHashSet<String> selected=new LinkedHashSet<>();
 private final Handler main=new Handler(Looper.getMainLooper());
 private final android.content.SharedPreferences prefs;
 private TextView status;
 private LinearLayout rows;
 private Button add,apply,revoke,defaults,biliTrial;
 private AlertDialog dialog;
 private boolean busy;
 private NativeVideoConfig.Snapshot snapshot;
 private NativeVideoSettings(Activity activity){this.activity=activity;prefs=activity.getSharedPreferences("native_video_compat",0);}
 static void show(Activity activity){new NativeVideoSettings(activity).open();}
 private void open(){
  selected.addAll(prefs.getStringSet("packages",new HashSet<>(Arrays.asList("tv.acfundanmaku.video","com.google.android.youtube"))));
  LinearLayout body=new LinearLayout(activity);body.setOrientation(1);int pad=Ui.dp(activity,16);body.setPadding(pad,pad,pad,pad);body.setBackgroundColor(SettingsTheme.cardColor(activity));
  TextView hint=Ui.text(activity,"B 站请使用下方单独的试验按钮。普通名单对 B 站无效；修改系统配置需要 Root。",14,SettingsTheme.secondary(activity));body.addView(hint);
  status=Ui.text(activity,"正在读取系统兼容状态…",13,SettingsTheme.secondary(activity));status.setPadding(0,pad,0,pad);body.addView(status);
  TextView biliHint=Ui.text(activity,"B 站工作台横屏试验：让系统将 B 站标记为不可调整大小，使播放器允许原生横屏请求。可能影响 B 站系统浮窗和分屏；可在此撤销。",13,SettingsTheme.secondary(activity));biliHint.setPadding(0,0,0,pad/2);body.addView(biliHint);
  biliTrial=button(body,"应用 B 站横屏试验");biliTrial.setOnClickListener(v->applyBiliTrial());
  rows=new LinearLayout(activity);rows.setOrientation(1);ScrollView scroll=new ScrollView(activity);scroll.addView(rows);body.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(activity,180)));
  add=button(body,"添加应用");add.setOnClickListener(v->{
   Set<String> excluded=new HashSet<>(selected);excluded.addAll(Arrays.asList("dev.windowdeck.app","io.github.xitc.windowdeck","com.oplus.pscanvas","com.android.launcher","com.android.systemui"));
   AppPicker.show(activity,excluded,(component,label,user)->{String pkg=component.getPackageName();if(NativeVideoConfigPlan.validPackage(pkg)){selected.add(pkg);render();status.setText("名单已更改，点击“应用名单”后生效。");}});
  });
  apply=button(body,"应用其他应用名单");apply.setOnClickListener(v->save(new LinkedHashSet<>(selected)));
  defaults=button(body,"恢复默认名单");defaults.setOnClickListener(v->{selected.clear();selected.addAll(Arrays.asList("tv.acfundanmaku.video","com.google.android.youtube"));render();status.setText("默认名单已恢复，点击“应用名单”后生效。");});
  revoke=button(body,"撤销全部兼容");revoke.setOnClickListener(v->save(Collections.emptySet()));
  dialog=new AlertDialog.Builder(activity).setTitle("视频全屏兼容").setView(body).setNegativeButton("关闭",null).create();dialog.show();
  render();setBusy(true);
  new Thread(()->{try{NativeVideoConfig.Snapshot state=NativeVideoConfig.read();main.post(()->{
   if(!active())return;snapshot=state;
   if(!prefs.contains("packages")&&!state.managed.isEmpty()){selected.clear();selected.addAll(state.managed);}
   render();setBusy(false);updateBiliTrialButton();showStatus();
  });}catch(Exception e){main.post(()->{if(active()){setBusy(false);status.setText("读取失败："+e.getMessage());}});}},"native-video-read").start();
 }
 private Button button(LinearLayout parent,String text){
  Button b=Ui.button(activity,text);b.setTextColor(SettingsTheme.text(activity));b.setBackground(Ui.bg(SettingsTheme.background(activity),Ui.dp(activity,10)));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Ui.dp(activity,44));lp.topMargin=Ui.dp(activity,8);parent.addView(b,lp);return b;
 }
 private boolean active(){return !activity.isFinishing()&&!activity.isDestroyed()&&dialog.isShowing();}
 private String label(String pkg){try{return activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception e){return pkg;}}
 private void render(){
  rows.removeAllViews();
  if(selected.isEmpty())rows.addView(Ui.text(activity,"名单为空，添加应用后点击应用。",14,SettingsTheme.secondary(activity)));
  for(String pkg:selected){
   LinearLayout row=new LinearLayout(activity);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
   TextView name=Ui.text(activity,label(pkg),15,SettingsTheme.text(activity));name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(name,new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1));
   Button remove=Ui.button(activity,"移除");remove.setTextColor(SettingsTheme.secondary(activity));remove.setEnabled(!busy);remove.setOnClickListener(v->{selected.remove(pkg);render();status.setText("名单已更改，点击“应用名单”后生效。");});row.addView(remove,new LinearLayout.LayoutParams(Ui.dp(activity,64),Ui.dp(activity,44)));rows.addView(row);
  }
 }
 private void setBusy(boolean value){busy=value;for(Button b:new Button[]{add,apply,revoke,defaults,biliTrial})b.setEnabled(!value);render();}
 private void showStatus(){
  boolean matches=snapshot!=null&&snapshot.all.containsAll(selected)&&snapshot.managed.stream().allMatch(selected::contains);
  status.setText(matches?"当前名单已应用。变更后需重新打开对应应用。":"当前名单需要应用；系统更新后也可在此重新应用。");
 }
 private void updateBiliTrialButton(){if(biliTrial!=null)biliTrial.setText(snapshot!=null&&snapshot.biliUnresizable?"撤销 B 站横屏试验":"应用 B 站横屏试验");}
 private void applyBiliTrial(){
  boolean enabled=snapshot==null||!snapshot.biliUnresizable;setBusy(true);status.setText(enabled?"正在备份并启用 B 站试验设置…":"正在备份并撤销 B 站试验设置…");
  new Thread(()->{try{
   NativeVideoConfig.Snapshot state=NativeVideoConfig.applyBiliUnresizable(activity.getApplicationContext(),enabled);
   main.post(()->{if(!active())return;snapshot=state;setBusy(false);updateBiliTrialButton();status.setText(enabled?"B 站试验设置已加载。请退出并重新打开 B 站，再从工作台测试横屏；系统浮窗／分屏回归待测。":"B 站试验条目已撤销，原系统其他配置已保留。请重新打开 B 站。");});
  }catch(Exception e){main.post(()->{if(active()){setBusy(false);status.setText("B 站试验未完成："+e.getMessage());}});}} ,"native-video-bili-trial").start();
 }
 private void save(Set<String> requested){
  setBusy(true);status.setText("正在备份并更新兼容设置…");
  new Thread(()->{try{
   NativeVideoConfig.Snapshot state=NativeVideoConfig.apply(activity.getApplicationContext(),requested);
   prefs.edit().putStringSet("packages",new HashSet<>(requested)).commit();
   main.post(()->{if(!active())return;snapshot=state;selected.clear();selected.addAll(requested);setBusy(false);status.setText(requested.isEmpty()?"模块添加的兼容已撤销，系统原有名单保留。请重新打开之前设置的应用。":"名单已应用。请退出并重新打开这些应用后测试全屏。");});
  }catch(Exception e){main.post(()->{if(active()){setBusy(false);status.setText("未完成："+e.getMessage());}});}},"native-video-apply").start();
 }
}
