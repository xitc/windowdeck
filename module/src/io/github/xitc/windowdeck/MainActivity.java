package io.github.xitc.windowdeck;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.util.*;
public final class MainActivity extends Activity {
 private static final int SLOTS=Caps.MAX_TASKS;
 private static final String[] DEFAULT_COMPONENTS={"com.coloros.calculator/com.android.calculator2.Calculator","com.android.settings/.Settings","","",""};
 private static final String[] DEFAULT_LABELS={"计算器","设置","未添加","未添加","未添加"};
 private final String[] components=new String[SLOTS];
 private final String[] labels=new String[SLOTS];
 private final SettingsRow[] picks=new SettingsRow[SLOTS];
 private LinearLayout body; private TextView status; private SettingsRow start;
 private boolean atomicRotate=AtomicPresentation.DEFAULT,originalPlus=HangPlace.DEFAULT;
 protected void onCreate(Bundle state){super.onCreate(state);
  for(int i=0;i<SLOTS;i++){components[i]=getPreferences(0).getString("component"+i,DEFAULT_COMPONENTS[i]);labels[i]=getPreferences(0).getString("label"+i,DEFAULT_LABELS[i]);}
  atomicRotate=SideWindowSettings.local(this);
  originalPlus=getPreferences(0).getBoolean(HangPlace.PREF,HangPlace.DEFAULT);
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
  root.setBackgroundColor(SettingsTheme.background(this));Ui.insets(root);setContentView(root);SettingsTheme.systemBars(this);
  ScrollView scroll=new ScrollView(this);scroll.setId(0x574401);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
  body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
  body.setPadding(0,0,0,dp(28));scroll.addView(body);
  header();
  workGroup();
  sideGroup();
  appGroup();
  compatGroup();
  footer();
 }
 private void header(){
  TextView heading=SettingsTheme.title(this,"多窗工作台");heading.setTextSize(28);
  LinearLayout.LayoutParams headParams=new LinearLayout.LayoutParams(-2,-2);
  headParams.leftMargin=dp(16);headParams.rightMargin=dp(16);headParams.topMargin=dp(16);
  body.addView(heading,headParams);
  TextView intro=SettingsTheme.summary(this,"最多五个实时应用窗口，支持运行中添加、替换和移出。");
  LinearLayout.LayoutParams introParams=new LinearLayout.LayoutParams(-1,-2);
  introParams.leftMargin=dp(16);introParams.rightMargin=dp(16);introParams.topMargin=dp(6);
  body.addView(intro,introParams);
  LinearLayout meta=new LinearLayout(this);meta.setOrientation(LinearLayout.HORIZONTAL);meta.setGravity(Gravity.CENTER_VERTICAL);
  TextView chip=new TextView(this);chip.setText("Beta");chip.setTextSize(11);chip.setTextColor(SettingsTheme.accent(this));
  chip.setTypeface(SettingsTheme.medium());chip.setBackground(SettingsTheme.pill(this,((SettingsTheme.accent(this)&0x00ffffff)|0x20000000),6));
  chip.setPadding(dp(8),dp(2),dp(8),dp(2));meta.addView(chip);
  TextView target=SettingsTheme.summary(this,"PJZ110 · ColorOS 17");
  LinearLayout.LayoutParams targetParams=new LinearLayout.LayoutParams(-2,-2);targetParams.leftMargin=dp(8);
  meta.addView(target,targetParams);
  LinearLayout.LayoutParams metaParams=new LinearLayout.LayoutParams(-1,-2);
  metaParams.leftMargin=dp(16);metaParams.rightMargin=dp(16);metaParams.topMargin=dp(10);
  body.addView(meta,metaParams);
 }
 private void workGroup(){
  LinearLayout card=group("工作台");
  SettingsRow size=SettingsRow.action(this,SettingsGlyph.SIZE,SettingsTheme.blue(this),"主窗口大小","按比例缩放主窗，侧窗自动让位",MainWindowSettings.local(this)+"%");
  size.view.setOnClickListener(v->MainWindowSettings.show(this,size.value));
  card.addView(size.view);
  SettingsRow resume=SettingsRow.action(this,SettingsGlyph.RESUME,SettingsTheme.blue(this),"打开 / 恢复当前工作台","回到已保存的窗口组合",null);
  resume.view.setOnClickListener(v->launch(true));
  card.addView(resume.view);
  start=SettingsRow.action(this,SettingsGlyph.NEW,SettingsTheme.green(this),"用所选应用新建工作台","会替换当前多窗口容器",null);
  start.view.setOnClickListener(v->launch(false));
  card.addView(start.view);
 }
 private void sideGroup(){
  LinearLayout card=group("侧窗");
  SettingsRow trapezoid=SettingsRow.switchRow(this,SettingsGlyph.SIDE,SettingsTheme.orange(this),"侧窗梯形",
   "左右布局右边、上下布局底边朝整排中线收缩，主窗保持矩形",atomicRotate,wanted->{
   if(!SideWindowSettings.save(this,wanted)){status.setText("侧窗梯形设置保存失败，请重试。");return false;}
   atomicRotate=wanted;return true;
  });
  card.addView(trapezoid.view);
  SettingsRow plus=SettingsRow.switchRow(this,SettingsGlyph.PLUS_MODE,SettingsTheme.purple(this),"原版添加",
   "点侧栏「＋」后从桌面打开的应用进入侧窗，当前主窗保持",originalPlus,wanted->{
   originalPlus=wanted;getPreferences(0).edit().putBoolean(HangPlace.PREF,wanted).apply();return true;
  });
  card.addView(plus.view);
 }
 private void appGroup(){
  int[] slotColors={SettingsTheme.blue(this),SettingsTheme.green(this),SettingsTheme.orange(this),SettingsTheme.purple(this),SettingsTheme.red(this)};
  LinearLayout card=group("应用");
  for(int i=0;i<SLOTS;i++){
   final int slot=i;
   picks[i]=SettingsRow.action(this,SettingsGlyph.APP,slotColors[i%slotColors.length],slotTitle(i),null,labels[i]);
   picks[i].view.setOnClickListener(v->pick(slot));
   card.addView(picks[i].view);
  }
 }
 private void compatGroup(){
  LinearLayout card=group("兼容");
  SettingsRow video=SettingsRow.action(this,SettingsGlyph.VIDEO,SettingsTheme.red(this),"视频全屏兼容","按包名决定哪些应用跟随系统方向全屏",null);
  video.view.setOnClickListener(v->NativeVideoSettings.show(this));
  card.addView(video.view);
 }
 private void footer(){
  TextView hint=SettingsTheme.summary(this,"首次使用：在 LSPosed 勾选“多窗口”（com.oplus.pscanvas）与“系统桌面”（com.android.launcher），再重新启动这两个进程。启动及上滑加入已有任务需要 Root；新建会替换当前多窗口容器。");
  hint.setPadding(dp(32),dp(12),dp(32),0);body.addView(hint);
  status=SettingsTheme.summary(this,"适配 PJZ110 · ColorOS 17\n实验版：退出时解除窗口嵌入，不清除应用数据。");
  status.setPadding(dp(32),dp(12),dp(32),0);body.addView(status);
 }
 private LinearLayout group(String title){
  TextView header=SettingsTheme.category(this,title);
  header.setPadding(dp(32),dp(8),dp(32),dp(6));body.addView(header);
  LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
  card.setBackground(SettingsTheme.card(this));Ui.round(card,dp(SettingsTheme.CARD_RADIUS));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
  lp.leftMargin=dp(SettingsTheme.CARD_MARGIN);lp.rightMargin=dp(SettingsTheme.CARD_MARGIN);lp.topMargin=dp(2);
  body.addView(card,lp);
  return card;
 }
 private int dp(int value){return Ui.dp(this,value);}
 private String slotTitle(int i){return i==0?"主应用":"侧窗 "+i;}
 /** Slots past the two required ones can be cleared again, so 2..4 all offer the choice. */
 private void pick(int slot){
  if(slot>=2&&!components[slot].isEmpty()){
   new AlertDialog.Builder(this).setTitle("第 "+(slot+1)+" 个应用").setItems(new String[]{"更换应用","不添加这个应用"},(d,n)->{if(n==0)selectApp(slot);else savePick(slot,"","未添加");}).show();
  }else selectApp(slot);
 }
 private void selectApp(int slot){
  Set<String> excluded=new HashSet<>();for(int i=0;i<SLOTS;i++)if(i!=slot&&!components[i].isEmpty()){ComponentName c=ComponentName.unflattenFromString(components[i]);if(c!=null)excluded.add(c.getPackageName());}
  AppPicker.show(this,excluded,(c,label)->savePick(slot,c.flattenToString(),label));
 }
 private void savePick(int slot,String component,String label){components[slot]=component;labels[slot]=label;picks[slot].setValue(label);getPreferences(0).edit().putString("component"+slot,component).putString("label"+slot,label).apply();}
 private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
 private void launch(boolean resume){
  ComponentName first=ComponentName.unflattenFromString(components[0]),second=ComponentName.unflattenFromString(components[1]);
  if(first==null||second==null||first.getPackageName().equals(second.getPackageName())){status.setText("请选择两个不同的应用。");return;}
  start.setEnabled(false);status.setText("正在请求 Root 并启动…");
  String[] extra={"c","d","e"};
  StringBuilder cmd=new StringBuilder("am start --user 0 -f ").append(resume?"0x30020000":"0x10008000")
    .append(" -n com.oplus.pscanvas/.canvasmode.canvas.ContainerActivity --ez windowdeck_workbench_v1 true")
    .append(" --ez ").append(AtomicPresentation.EXTRA).append(" ").append(atomicRotate)
    .append(" --es windowdeck_app_a ").append(quote(components[0]))
    .append(" --es windowdeck_app_b ").append(quote(components[1]));
  for(int i=2;i<SLOTS;i++)if(!components[i].isEmpty())cmd.append(" --es windowdeck_app_").append(extra[i-2]).append(" ").append(quote(components[i]));
  final String command=cmd.toString();
  new Thread(()->{try{
   Process p=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
   if(!p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){p.destroy();throw new IOException("Root 授权超时，请在管理器中检查本应用权限");}
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=p.getInputStream().read(buf))!=-1)out.write(buf,0,n);
   String result=out.toString("UTF-8");int exit=p.exitValue();runOnUiThread(()->{start.setEnabled(true);status.setText(exit==0&&!result.contains("Error")?"启动请求已发送。若未出现工作台，请确认模块已启用并重启 OplusFlexibleWindowUI。":"启动失败："+result);});
  }catch(Exception e){runOnUiThread(()->{start.setEnabled(true);status.setText(e instanceof IOException?"无法调用 Root。请在 KernelSU / Magisk 中给“多窗工作台”授予超级用户权限，再点击启动。\n"+e.getMessage():"启动失败："+e.getMessage());});}},"WorkbenchLaunch").start();
 }
}
