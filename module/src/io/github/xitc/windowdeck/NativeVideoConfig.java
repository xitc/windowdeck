package io.github.xitc.windowdeck;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.util.Xml;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.xmlpull.v1.XmlPullParser;

/** Root-backed configuration management from the module APK, never an app/framework hook. */
final class NativeVideoConfig {
 static final String PATH="/data/oplus/os/config/sys_wms_split_app.xml";
 static final class Snapshot {
  final String xml;final long loaded;final Set<String> managed,all;
  Snapshot(String xml,long loaded){this.xml=xml;this.loaded=loaded;managed=NativeVideoConfigPlan.managed(xml);all=NativeVideoConfigPlan.entries(xml);}
 }
 static synchronized Snapshot read()throws Exception{
  if(!Build.DISPLAY.startsWith("PJZ110_17."))throw new IOException("当前仅验证了 PJZ110 的 ColorOS 17，未修改系统配置");
  String xml=root("cat "+quote(PATH));validate(xml);return new Snapshot(xml,loaded());
 }
 static synchronized Snapshot apply(Context context,Collection<String> requested)throws Exception{
  Snapshot before=read();NativeVideoConfigPlan plan=NativeVideoConfigPlan.edit(before.xml,requested,before.loaded);
  if(!plan.changed){Log.i("WindowDeck","native_video_config unchanged count="+requested.size());return before;}
  validate(plan.xml);
  File backups=new File(context.getFilesDir(),"native-video-config");
  File run=new File(backups,Long.toString(System.currentTimeMillis()));
  if(!run.mkdirs())throw new IOException("无法创建配置备份");
  write(new File(run,"original.xml"),before.xml);File candidate=new File(run,"candidate.xml");write(candidate,plan.xml);
  String metadata=root("stat -c '%u:%g:%a' "+quote(PATH)+"; ls -Zd "+quote(PATH)).trim();
  write(new File(run,"record.txt"),"original_sha256="+hash(before.xml)+"\ncandidate_sha256="+hash(plan.xml)+"\noriginal_version="+before.loaded+"\ncandidate_version="+plan.version+"\nmetadata="+metadata+"\n");
  root("set -e\ncurrent=$(sha256sum "+quote(PATH)+")\ntest \"${current%% *}\" = "+quote(hash(before.xml))+"\ncat "+quote(candidate.getAbsolutePath())+" > "+quote(PATH));
  write(new File(run,"status.txt"),"written; reload verification pending\n");
  String actual=root("cat "+quote(PATH));
  if(!actual.equals(plan.xml))throw new IOException("配置写入核对失败，备份已保留，请重新读取状态");
  String afterMetadata=root("stat -c '%u:%g:%a' "+quote(PATH)+"; ls -Zd "+quote(PATH)).trim();
  if(!metadata.equals(afterMetadata))throw new IOException("配置权限发生变化，备份已保留，请重新读取状态");
  root("dumpsys window splitscreen reload_xml");
  for(int i=0;i<20;i++){
   if(loaded()==plan.version){
    write(new File(run,"status.txt"),"loaded version="+plan.version+"\n");
    Log.i("WindowDeck","native_video_config applied version="+plan.version+" managed="+plan.managed.size()+" backup="+run.getName());
    return new Snapshot(plan.xml,plan.version);
   }
   Thread.sleep(250);
  }
  throw new IOException("配置已写入但系统尚未加载，请重新读取后重试，或撤销兼容");
 }
 private static void validate(String xml)throws Exception{
  XmlPullParser parser=Xml.newPullParser();parser.setInput(new StringReader(xml));
  boolean root=false,marker=false;int versions=0;
  for(int event=parser.getEventType();event!=XmlPullParser.END_DOCUMENT;event=parser.next())if(event==XmlPullParser.START_TAG){
   if(parser.getDepth()==1){if(!"settings".equals(parser.getName()))throw new IOException("不是完整的系统多窗配置");root=true;}
   if(parser.getDepth()==2&&"version".equals(parser.getName()))versions++;
   if("forceAppToResizable".equals(parser.getName()))marker=true;
  }
  if(!root||!marker||versions!=1)throw new IOException("系统配置格式不受支持");NativeVideoConfigPlan.version(xml);
 }
 private static long loaded()throws Exception{
  Matcher m=Pattern.compile("Current Version:([0-9]+)").matcher(root("dumpsys window splitscreen"));
  if(!m.find())throw new IOException("无法读取系统配置状态");return Long.parseLong(m.group(1));
 }
 private static String hash(String value)throws Exception{
  byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();
  for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
 }
 private static String quote(String value){return "'"+value.replace("'","'\\''")+"'";}
 private static void write(File file,String value)throws IOException{
  try(FileOutputStream stream=new FileOutputStream(file)){stream.write(value.getBytes(StandardCharsets.UTF_8));stream.getFD().sync();}
 }
 private static String root(String command)throws Exception{
  Process process=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
  FutureTask<String> read=new FutureTask<>(()->{
   try(InputStream stream=process.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] bytes=new byte[8192];int n;while((n=stream.read(bytes))!=-1){out.write(bytes,0,n);if(out.size()>4*1024*1024)throw new IOException("Root 输出超过限制");}
    return new String(out.toByteArray(),StandardCharsets.UTF_8);
   }
  });
  Thread reader=new Thread(read,"native-video-root-output");reader.setDaemon(true);reader.start();
  try{
   if(!process.waitFor(30,TimeUnit.SECONDS))throw new IOException("Root 请求超时，请在超级用户管理器中检查模块授权");
   String result=read.get(5,TimeUnit.SECONDS);
   if(process.exitValue()!=0)throw new IOException("Root 操作失败，请检查模块授权或重新读取配置："+result.substring(0,Math.min(180,result.length())).trim());
   return result;
  }finally{process.destroy();}
 }
}
