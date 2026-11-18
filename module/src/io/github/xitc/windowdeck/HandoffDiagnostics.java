package io.github.xitc.windowdeck;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class HandoffDiagnostics {
 static final String ID="windowdeck_diag_id", RELEASED="windowdeck_diag_released";
 static final String EVENT="diagnostic_event", START="diagnostic_start", STATUS="diagnostic_status", STOP="diagnostic_stop";
 static final String REPORT="report", RUNNING="running", PROBE_RESULT="diagnostic_probe_result";
 private static final String PROBE_ACTION=BuildIdentity.APPLICATION_ID+".DIAGNOSTIC_VERSION";
 private static final String PROBE="probe";
 private static final long CAPTURE_MS=120_000;
 private static final Uri URI=Uri.parse(BuildIdentity.STATE_URI);
 private static final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,
   new ArrayBlockingQueue<>(128),job->new Thread(job,"WindowDeckDiagnostics"),new ThreadPoolExecutor.DiscardOldestPolicy());
 private static final String VERSION=Version.NAME.replaceFirst("-.*$","")+" ("+Version.CODE+")";
 enum Event {
  RELEASE(true), PATH(true), FALLBACK(true), FIRST_MOTION(true), HOST_RECEIVED(true), HOST_OWNERSHIP(true), RESULT(true),
  SOURCE_GEOMETRY(false), TARGET_GEOMETRY(false), NATIVE_POSE_INTERCEPTED(false), MOTION_COMMITTED(false),
  MOTION_GEOMETRY(false), HOST_PREPARED(false), WALLPAPER(false),
  MAIN_SIDE_SWITCH(false,true), HOST_PAUSE(false,true), MAIN_FOCUS(false,true),
  GESTURE_TARGET(false,true), APP_TOUCH(false,true), APP_CLICK(false,true), APP_REQUEST(false,true), APP_DEFERRED(false,true), APP_CALLBACK(false,true),
  APP_START(false,true), APP_ROUTE(false,true);
  final boolean key,timeline;
  Event(boolean key){this(key,false);}
  Event(boolean key,boolean timeline){this.key=key;this.timeline=timeline;}
 }
 private static volatile Context processContext;
 private final Context context;
 final String id;
 final long released;
 private HandoffDiagnostics(Context context,String id,long released){
  this.context=context.getApplicationContext();this.id=id;this.released=released;
 }
 static HandoffDiagnostics begin(Context context,long released){
  HandoffDiagnostics trace=new HandoffDiagnostics(context,UUID.randomUUID().toString(),released);
  trace.record(Event.RELEASE,"up");
  return trace;
 }
 static boolean valid(String id,long released){
  long now=SystemClock.uptimeMillis();
  return id!=null&&id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    &&released>0&&released<=now&&now-released<=CAPTURE_MS;
 }
 static HandoffDiagnostics receive(Context context,Intent intent){
  if(intent==null)return null;
  String id=intent.getStringExtra(ID);long released=intent.getLongExtra(RELEASED,0);
  return valid(id,released)?new HandoffDiagnostics(context,id,released):null;
 }
 void put(Intent intent){intent.putExtra(ID,id);intent.putExtra(RELEASED,released);}
 void put(Bundle bundle){bundle.putString(ID,id);bundle.putLong(RELEASED,released);}
 void record(Event event,String reason){record(event,reason,null);}
 void geometry(Event event,RectF box){
  if(box!=null)record(event,"bounds",new float[]{box.left,box.top,box.right,box.bottom});
 }
 private void record(Event event,String reason,float[] geometry){
  long now=event==Event.RELEASE?released:SystemClock.uptimeMillis();
  if(event.key)Log.i("WindowDeck","diagnostic id="+id+" ms="+(now-released)+" event="+event+" reason="+reason);
  Bundle payload=payload(event,reason,now);
  put(payload);
  if(geometry!=null)payload.putFloatArray("geometry",geometry);
  send(context,EVENT,payload);
 }
 static void timeline(Event event,String reason,int task){
  timeline(event,reason,task,null);
 }
 static void timeline(Event event,String reason,int task,String launchId){
  timeline(event,reason,task,launchId,SystemClock.uptimeMillis(),SystemClock.elapsedRealtime(),null);
 }
 static void timeline(Event event,String reason,int task,String launchId,long at,long clock,String[] frames){
  Context context=processContext;
  if(context==null)return;
  Bundle payload=payload(event,reason,at);
  payload.putLong("clock",clock);
  if(task>=0)payload.putInt("task",task);
  if(launchId!=null)payload.putString("launch_id",launchId);
  if(frames!=null&&frames.length>0)payload.putStringArray("launcher_frames",frames);
  send(context,EVENT,payload);
 }
 static boolean launcherFrame(String frame){
  if(frame==null||frame.length()>180||!frame.matches("[a-zA-Z0-9_.$-]+#[a-zA-Z0-9_$<>-]+"))return false;
  return frame.startsWith("com.android.launcher.")||frame.startsWith("com.android.launcher3.")
    ||frame.startsWith("com.android.quickstep.")||frame.startsWith("com.oplus.quickstep.")
    ||frame.matches("[a-z][a-z0-9_$]{0,11}#.+");
 }
 private static Bundle payload(Event event,String reason,long at){
  Bundle payload=new Bundle();
  payload.putLong("at",at);payload.putLong("clock",SystemClock.elapsedRealtime());
  payload.putString("event",event.name());payload.putString("reason",reason);
  payload.putString("version",VERSION);payload.putString("commit",Version.COMMIT);
  return payload;
 }
 private static void send(Context context,String method,Bundle payload){
  worker.execute(()->{
   try{call(context,method,payload);}catch(RuntimeException ignored){}
  });
 }
 static void requestVersions(Context context,Bundle session){
  String probe=session.getString(PROBE);
  if(probe==null)return;
  BroadcastOptions options=BroadcastOptions.makeBasic();
  options.setShareIdentityEnabled(true);
  for(String pkg:new String[]{"com.android.launcher","com.oplus.pscanvas"}){
   Intent request=new Intent(PROBE_ACTION).setPackage(pkg).putExtra(PROBE,probe);
   try{context.sendBroadcast(request,null,options.toBundle());}catch(SecurityException ignored){}
  }
 }
 static void registerVersionProbe(Context context){
  processContext=context;
  context.registerReceiver(new BroadcastReceiver(){
   @Override public void onReceive(Context ignored,Intent intent){
    String[] senders=context.getPackageManager().getPackagesForUid(getSentFromUid());
    if(senders==null||!Arrays.asList(senders).contains(BuildIdentity.APPLICATION_ID))return;
    String probe=intent.getStringExtra(PROBE);
    if(probe==null)return;
    Bundle answer=new Bundle();
    answer.putString(PROBE,probe);answer.putString("version",VERSION);answer.putString("commit",Version.COMMIT);
    answer.putLong("clock",SystemClock.elapsedRealtime());
    send(context,PROBE_RESULT,answer);
   }
  },new IntentFilter(PROBE_ACTION),Context.RECEIVER_EXPORTED);
 }
 private static boolean buildValid(String version,String commit){
  return version!=null&&version.matches("[0-9]+\\.[0-9]+\\.[0-9]+ \\([0-9]+\\)")
    &&commit!=null&&commit.matches("[0-9a-f]{40}|[0-9a-f]{64}|unknown");
 }
 static Bundle call(Context context,String method,Bundle payload){return context.getContentResolver().call(URI,method,null,payload);}

 /** The module provider owns the bounded buffer for both Hook processes. */
 static final class Store {
  private static final class Line {
   final String role,id,reason,version,commit,launchId;
   final long released,at;
   final Event event;
   final int task;
   final float[] geometry;
   String[] frames;
   Line(String role,String id,long released,long at,Event event,String reason,String version,String commit,float[] geometry,int task,String launchId){
    this.role=role;this.id=id;this.released=released;this.at=at;this.event=event;
    this.reason=reason;this.version=version;this.commit=commit;this.geometry=geometry;this.task=task;this.launchId=launchId;
   }
  }
  private final ArrayDeque<Line> recent=new ArrayDeque<>(),captured=new ArrayDeque<>();
  private final Map<String,String> probes=new HashMap<>();
  private long started,startedClock,deadline;
  private String probe;
  private int dropped;
  synchronized Bundle control(String method){
   long now=SystemClock.elapsedRealtime();
   if(START.equals(method)&&now>=deadline){
    captured.clear();probes.clear();dropped=0;
    started=SystemClock.uptimeMillis();startedClock=now;deadline=now+CAPTURE_MS;
    probe=UUID.randomUUID().toString();
   }else if(STOP.equals(method))deadline=Math.min(now,deadline);
   Bundle status=new Bundle();
   status.putBoolean(RUNNING,now<deadline);
   if(START.equals(method))status.putString(PROBE,probe);
   if(STOP.equals(method))status.putString(REPORT,report());
   return status;
  }
  synchronized void observe(String role,Bundle data){
   if(data==null||probe==null||!probe.equals(data.getString(PROBE)))return;
   long clock=data.getLong("clock");
   String version=data.getString("version"),commit=data.getString("commit");
   if(inCapture(clock)&&buildValid(version,commit))probes.put(role,version+" · "+commit);
  }
  synchronized void add(String role,Bundle data){
   if(data==null)return;
   String id=data.getString(ID),reason=data.getString("reason"),version=data.getString("version"),commit=data.getString("commit");
   long released=data.getLong(RELEASED),at=data.getLong("at");
   if(at>SystemClock.uptimeMillis()||reason==null||!reason.matches("[a-z0-9_]{1,48}")||!buildValid(version,commit))return;
   Event event;
   try{event=Event.valueOf(data.getString("event",""));}catch(IllegalArgumentException ignored){return;}
   long clock=data.getLong("clock");
   if(event.timeline){
    if(at>=started&&inCapture(clock)){
     String launchId=data.getString("launch_id");
     if(!"Launcher".equals(role)||launchId==null||!launchId.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))launchId=null;
     Line line=new Line(role,probe,started,at,event,reason,version,commit,null,data.getInt("task",-1),launchId);
     if(launchId!=null&&event==Event.APP_REQUEST){
      String[] frames=data.getStringArray("launcher_frames");
      if(frames!=null&&frames.length<=8&&Arrays.stream(frames).allMatch(HandoffDiagnostics::launcherFrame))
       line.frames=frames;
     }
     capture(line);
    }
    return;
   }
   if(!valid(id,released)||at<released)return;
   float[] geometry=data.getFloatArray("geometry");
   if(geometry!=null){
    if(geometry.length!=4)return;
    for(float value:geometry)if(!Float.isFinite(value)||Math.abs(value)>100_000)return;
   }
   if(event.key){
    if(recent.size()==96)recent.removeFirst();
    recent.addLast(new Line(role,id,released,at,event,reason,version,commit,null,-1,null));
   }
   if(released>=started&&inCapture(clock))
    capture(new Line(role,id,released,at,event,reason,version,commit,geometry,-1,null));
  }
  private void capture(Line line){
   if(captured.size()==320){captured.removeFirst();dropped++;}
   captured.addLast(line);
  }
  private boolean inCapture(long clock){
   return started!=0&&clock>=startedClock&&clock<=deadline&&clock<=SystemClock.elapsedRealtime();
  }
  private String report(){
   ArrayList<Line> lines=new ArrayList<>(started==0?recent:captured);
   lines.sort(Comparator.comparing((Line line)->!line.event.timeline).thenComparingLong(line->line.released)
     .thenComparing(line->line.id).thenComparingLong(line->line.at));
   StringBuilder text=new StringBuilder("WindowDeck 诊断\n");
   for(String role:new String[]{"Launcher","工作台宿主"}){
    LinkedHashSet<String> builds=new LinkedHashSet<>();
    if(probes.containsKey(role))builds.add(probes.get(role));
    for(Line line:lines)if(role.equals(line.role))builds.add(line.version+" · "+line.commit);
    text.append(role).append("  ").append(builds.isEmpty()?"本次未观察到载入版本":String.join(" / ",builds)).append('\n');
   }
   if(dropped>0)text.append("已淘汰 ").append(dropped).append(" 条较早事件\n");
   if(started!=0){
    text.append("时间线以开始采集为零点，交接记录以松手为零点。提交时点不等于显示帧。\n");
    if(lines.stream().noneMatch(line->line.event==Event.GESTURE_TARGET))text.append("Launcher 手势目标本次未观测到。\n");
    if(lines.stream().noneMatch(line->line.event==Event.APP_START))text.append("Launcher 应用启动请求本次未观测到。\n");
    if(lines.stream().anyMatch(line->line.event==Event.APP_START&&line.launchId==null))
     text.append("缺少 launch# 的启动未关联到上游请求。\n");
   }else text.append("时间为相对松手的毫秒数，提交时点不等于显示帧。\n");
   Map<Integer,Integer> taskNumbers=new HashMap<>();
  Map<String,Integer> launchNumbers=new HashMap<>();
   String previous="";
   for(Line line:lines){
    if(!previous.equals(line.id)){
     previous=line.id;
     text.append('\n');
     if(line.event.timeline)text.append("采集时间线  ");
     text.append(previous).append('\n');
    }
    text.append('+').append(line.at-line.released).append(" ms  ").append(line.role).append("  ")
      .append(line.event).append("  ").append(line.reason).append("  ").append(line.version).append("  ").append(line.commit);
    if(started!=0&&!line.event.timeline)text.append("  采集 +").append(line.at-started).append(" ms");
    if(line.geometry!=null)text.append("  ").append(Arrays.toString(line.geometry));
    if(line.task>=0)text.append("  task#").append(taskNumbers.computeIfAbsent(line.task,key->taskNumbers.size()+1));
    if(line.launchId!=null)text.append("  launch#").append(launchNumbers.computeIfAbsent(line.launchId,key->launchNumbers.size()+1));
    if(line.event==Event.APP_REQUEST)
     text.append("  stack=").append(line.frames==null||line.frames.length==0?"unobserved":String.join(" <- ",line.frames));
    text.append('\n');
   }
   if(lines.isEmpty())text.append("\n没有采集到诊断事件。\n");
   return text.toString();
  }
 }
}
