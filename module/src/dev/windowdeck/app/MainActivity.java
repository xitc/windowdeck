package dev.windowdeck.app;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.util.*;
public final class MainActivity extends Activity {
 private final String[] components={"com.coloros.calculator/com.android.calculator2.Calculator","com.android.settings/.Settings",""};
 private final String[] labels={"计算器","设置","未添加"};
 private final Button[] picks=new Button[3]; private TextView status; private Button start;
 protected void onCreate(Bundle state){super.onCreate(state);
  for(int i=0;i<3;i++){components[i]=getPreferences(0).getString("component"+i,components[i]);labels[i]=getPreferences(0).getString("label"+i,labels[i]);}
  getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xfff4f6fa);Ui.insets(root);setContentView(root);
  ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
  LinearLayout body=new LinearLayout(this);body.setOrientation(1);int pad=Ui.dp(this,24);body.setPadding(pad,pad,pad,pad);scroll.addView(body);
  body.addView(Ui.text(this,"多窗工作台 · Beta",28,0xff172033));
  TextView intro=Ui.text(this,"最多三个实时应用窗口，支持运行中添加、替换和移出。",16,0xff536178);intro.setPadding(0,pad,0,pad);body.addView(intro);
  body.addView(Ui.text(this,"首次使用：在 LSPosed 启用本模块，仅勾选“多窗口”（com.oplus.pscanvas），然后重新启动该应用进程。启动时需要 Root，会替换当前多窗口容器。",14,0xff536178));
  for(int i=0;i<3;i++){final int slot=i;picks[i]=Ui.button(this,prefix(i)+labels[i]);body.addView(picks[i]);picks[i].setOnClickListener(v->pick(slot));}
  start=Ui.button(this,"启动工作台");body.addView(start);start.setOnClickListener(v->launch());
  status=Ui.text(this,"适配 PJZ110 · ColorOS 16.0.10.501\n实验版：退出时解除窗口嵌入，不清除应用数据。",13,0xff536178);status.setPadding(0,pad,0,0);body.addView(status);
 }
 private String prefix(int i){return i==0?"主应用：":"侧窗 "+i+"：";}
 private void pick(int slot){
  if(slot==2&&!components[2].isEmpty()){
   new AlertDialog.Builder(this).setTitle("第三个应用").setItems(new String[]{"更换应用","不添加第三个应用"},(d,n)->{if(n==0)selectApp(slot);else savePick(slot,"","未添加");}).show();
  }else selectApp(slot);
 }
 private void selectApp(int slot){
  Set<String> excluded=new HashSet<>();for(int i=0;i<3;i++)if(i!=slot&&!components[i].isEmpty()){ComponentName c=ComponentName.unflattenFromString(components[i]);if(c!=null)excluded.add(c.getPackageName());}
  AppPicker.show(this,excluded,(c,label)->savePick(slot,c.flattenToString(),label));
 }
 private void savePick(int slot,String component,String label){components[slot]=component;labels[slot]=label;picks[slot].setText(prefix(slot)+label);getPreferences(0).edit().putString("component"+slot,component).putString("label"+slot,label).apply();}
 private static String quote(String s){return "'"+s.replace("'","'\\''")+"'";}
 private void launch(){
  ComponentName first=ComponentName.unflattenFromString(components[0]),second=ComponentName.unflattenFromString(components[1]);
  if(first==null||second==null||first.getPackageName().equals(second.getPackageName())){status.setText("请选择两个不同的应用。");return;}
  start.setEnabled(false);status.setText("正在请求 Root 并启动…");
  String cmd="am start --user 0 -f 0x10008000 -n com.oplus.pscanvas/.canvasmode.canvas.ContainerActivity --ez windowdeck_workbench_v1 true --es windowdeck_app_a "+quote(components[0])+" --es windowdeck_app_b "+quote(components[1])+(components[2].isEmpty()?"":" --es windowdeck_app_c "+quote(components[2]));
  new Thread(()->{try{
   Process p=new ProcessBuilder("su","-c",cmd).redirectErrorStream(true).start();
   if(!p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)){p.destroy();throw new IOException("Root 授权超时，请在管理器中检查本应用权限");}
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[1024];int n;while((n=p.getInputStream().read(buf))!=-1)out.write(buf,0,n);
   String result=out.toString("UTF-8");int exit=p.exitValue();runOnUiThread(()->{start.setEnabled(true);status.setText(exit==0&&!result.contains("Error")?"启动请求已发送。若未出现工作台，请确认模块已启用并重启 OplusFlexibleWindowUI。":"启动失败："+result);});
  }catch(Exception e){runOnUiThread(()->{start.setEnabled(true);status.setText(e instanceof IOException?"无法调用 Root。请在 KernelSU / Magisk 中给“多窗工作台”授予超级用户权限，再点击启动。\n"+e.getMessage():"启动失败："+e.getMessage());});}},"WorkbenchLaunch").start();
 }
}
