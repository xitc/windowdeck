package io.github.xitc.windowdeck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class DiagnosticsSettings {
 static final int EXPORT_REQUEST=0x5745;
 private static final String PENDING="diagnostic_pending_export";
 private final Activity activity;
 private String pending;

 DiagnosticsSettings(Activity activity,LinearLayout card,Bundle state){
  this.activity=activity;
  pending=state==null?null:state.getString(PENDING);
  SettingsRow start=SettingsRow.action(activity,SettingsGlyph.RESUME,SettingsTheme.blue(activity),
    "开始诊断","采集两分钟内的交接、主侧交换和桌面启动事件",null);
  start.view.setOnClickListener(view->start());
  card.addView(start.view);
  SettingsRow stop=SettingsRow.action(activity,SettingsGlyph.NEW,SettingsTheme.green(activity),
    "结束并导出","预览 Launcher 和工作台宿主的事件及版本",null);
  stop.view.setOnClickListener(view->preview());
  card.addView(stop.view);
 }
 void save(Bundle state){if(pending!=null)state.putString(PENDING,pending);}
 private void start(){
  Bundle current=HandoffDiagnostics.call(activity,HandoffDiagnostics.STATUS,null);
  if(current!=null&&current.getBoolean(HandoffDiagnostics.RUNNING)){
   show("诊断正在采集");return;
  }
  Bundle started=HandoffDiagnostics.call(activity,HandoffDiagnostics.START,null);
  if(started!=null&&started.getBoolean(HandoffDiagnostics.RUNNING)){
   HandoffDiagnostics.requestVersions(activity,started);
   show("诊断已开始，请在两分钟内复现");
  }else show("未能开始诊断");
 }
 private void preview(){
  Bundle result=HandoffDiagnostics.call(activity,HandoffDiagnostics.STOP,null);
  String report=result==null?null:result.getString(HandoffDiagnostics.REPORT);
  if(report==null){show("未能读取诊断记录");return;}
  TextView text=new TextView(activity);
  int padding=Ui.dp(activity,20);
  text.setPadding(padding,padding,padding,padding);
  text.setTextIsSelectable(true);text.setText(report);
  ScrollView scroll=new ScrollView(activity);scroll.addView(text);
  new AlertDialog.Builder(activity).setTitle("诊断预览").setView(scroll)
    .setNegativeButton("关闭",null).setPositiveButton("导出文本",(dialog,which)->export(report)).show();
 }
 private void export(String report){
  Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
    .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"WindowDeck-diagnostics.txt");
  pending=report;
  try{activity.startActivityForResult(intent,EXPORT_REQUEST);}
  catch(android.content.ActivityNotFoundException e){pending=null;show("未找到可以保存文本的应用");}
 }
 void onResult(int resultCode,Intent data){
  String report=pending;pending=null;
  if(resultCode!=Activity.RESULT_OK||data==null||data.getData()==null||report==null)return;
  android.net.Uri destination=data.getData();
  new Thread(()->{
   String message;
   try(OutputStream output=activity.getContentResolver().openOutputStream(destination,"wt")){
    if(output==null)throw new IOException("missing output");
    output.write(report.getBytes(StandardCharsets.UTF_8));
    message="诊断文本已导出";
   }catch(IOException|RuntimeException e){message="无法保存诊断文本";}
   String feedback=message;
   activity.runOnUiThread(()->show(feedback));
  },"WindowDeckDiagnosticsExport").start();
 }
 private void show(String text){Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();}
}
