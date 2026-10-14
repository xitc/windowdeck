package io.github.xitc.windowdeck;

import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.text.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import de.robv.android.xposed.*;
import java.lang.reflect.*;
import java.util.*;

/** Native list resources and renderer, with module-owned selection and lifetime. */
final class NativeAppPicker {
 interface Selection {void select(Intent launch,int user);}
 private static final String CONTROL="com.oplus.pscanvas.canvasmode.canvas.v";
 private static final String UTIL="com.oplus.pscanvas.canvasmode.canvas.util.FlexibleWindowUtil";
 private static final Map<Object,Session> OWNED=Collections.synchronizedMap(new WeakHashMap<>());
 private static boolean hooked;
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static final class Session {
  volatile boolean closed;boolean selected;
  WorkbenchActivity activity;Object controller;PopupWindow popup;Selection callback;Thread worker;
  View body,all,results,loading,searchContent,empty;EditText edit;
  final List<Object> catalog=new ArrayList<>(),shown=new ArrayList<>(),searched=new ArrayList<>();
  Session(WorkbenchActivity a,Selection c){activity=a;callback=c;}
  void close(){
   if(closed)return;closed=true;if(worker!=null)worker.interrupt();
   if(controller!=null)set(controller,"L",false);
   if(edit!=null)edit.removeTextChangedListener(watcher);
   callback=null;activity=null;controller=null;popup=null;body=null;all=null;results=null;loading=null;searchContent=null;empty=null;edit=null;
   catalog.clear();shown.clear();searched.clear();
  }
  final TextWatcher watcher=new TextWatcher(){
   public void beforeTextChanged(CharSequence s,int a,int c,int f){}
   public void onTextChanged(CharSequence s,int a,int b,int c){bind(s.toString());}
   public void afterTextChanged(Editable e){}
  };
  void bind(String query){
   if(closed)return;
   try{
    String q=query.toLowerCase(Locale.ROOT).replace(" ","");boolean searching=!q.isEmpty();
    searched.clear();if(searching)for(Object entry:catalog)
     if(((Integer)call(entry,"e"))==4&&(Boolean)call(controller,"Z0",q,(String)call(entry,"c")))searched.add(entry);
    all.setVisibility(searching?View.GONE:View.VISIBLE);searchContent.setVisibility(searching?View.VISIBLE:View.GONE);
    if(searching&&searched.isEmpty()&&empty==null){
     empty=((ViewStub)body.findViewById(id(activity,"id","empty_view"))).inflate();
     Object animation=empty.findViewById(id(activity,"id","empty_animation"));
     call(animation,"setAnimation",Class.forName("com.oplus.pscanvas.i",false,activity.getClassLoader()).getField((activity.getResources().getConfiguration().uiMode&48)==32?"j1":"k1").getInt(null));
    }
    if(empty!=null)empty.setVisibility(searching&&searched.isEmpty()?View.VISIBLE:View.GONE);
    results.setVisibility(searching&&searched.isEmpty()?View.GONE:View.VISIBLE);
    notifyAdapter(searching?results:all);
   }catch(Throwable e){failed(this,e);}
  }
  void choose(List<?> list,int position){
   if(closed||selected||position<0||position>=list.size())return;
   try{
    Object entry=list.get(position);int type=(Integer)call(entry,"e");if(type!=3&&type!=4)return;
    String pkg=(String)call(entry,"d");int user=(Integer)call(entry,"f");
    if(user!=android.os.Process.myUid()/100000)throw new IllegalArgumentException("不支持的用户");
    Intent launch=(Intent)staticCall(activity.getClassLoader(),UTIL,"b0",activity,pkg,user);
    if(launch==null||launch.getComponent()==null||!pkg.equals(launch.getComponent().getPackageName()))throw new IllegalArgumentException("应用启动入口已变化");
    launch=new Intent(launch).addFlags(262144).putExtra("androidx.flexible.userId",user);
    selected=true;Selection receiver=callback;PopupWindow window=popup;
    window.dismiss();receiver.select(launch,user);
    Log.i("WindowDeck","native_picker_selected package="+pkg+" user="+user);
   }catch(Throwable e){failed(this,e);}
  }
 }
 private static synchronized void hooks(ClassLoader loader)throws Exception{
  if(hooked)return;
  Class<?> type=Class.forName(CONTROL,false,loader);
  XposedBridge.hookMethod(method(type,"R1",Context.class),new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam p){if(p.args[0] instanceof WorkbenchActivity)p.setResult(null);}
  });
  XposedBridge.hookMethod(method(type,"j1",List.class,View.class,int.class),new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam p){
    Session session=OWNED.get(p.thisObject);if(session==null)return;
    // Retired owned objects remain intercepted until GC: never call native mutation.
    p.setResult(null);session.choose((List<?>)p.args[0],(Integer)p.args[2]);
   }
  });hooked=true;
 }
 static void show(WorkbenchActivity activity,Set<String> excluded,Selection callback){
  Session s=new Session(activity,callback);
  try{
   ClassLoader loader=activity.getClassLoader();hooks(loader);
   Object control=Class.forName(CONTROL,false,loader).getConstructor(Context.class).newInstance(activity);
   s.controller=control;OWNED.put(control,s);set(control,"L",true);
   int width=activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,32);
   int height=Math.min(Ui.dp(activity,800),(int)(activity.getResources().getDisplayMetrics().heightPixels*.82f));
   set(control,"J",width);
   s.body=LayoutInflater.from(activity).inflate(id(activity,"layout","app_list_layout"),null);
   Ui.round(s.body,Ui.dp(activity,24));
   s.all=s.body.findViewById(id(activity,"id","all_app_view"));
   s.results=s.body.findViewById(id(activity,"id","search_result_view"));
   s.searchContent=s.body.findViewById(id(activity,"id","search_content"));
   s.loading=s.body.findViewById(id(activity,"id","all_app_loading"));
   Object search=s.body.findViewById(id(activity,"id","searchView"));
   s.edit=(EditText)call(search,"getSearchEditText");s.edit.addTextChangedListener(s.watcher);
   call(search,"setSearchAnimateType",0);call(search,"setInputMethodAnimationEnabled",false);
   s.edit.setFocusableInTouchMode(true);
   ((View)search).setOnClickListener(v->{try{call(search,"w",1);s.edit.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(s.edit,0);}catch(Exception e){failed(s,e);}});
   ((View)call(search,"getFunctionalButton")).setOnClickListener(v->{if(s.closed)return;try{s.edit.setText("");s.edit.clearFocus();call(search,"w",0);((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(s.edit.getWindowToken(),0);}catch(Exception e){failed(s,e);}});
   // Native V0 builds c2.a, its callback and v.h header spans. No ContainerView needed.
   call(control,"V0",s.all,s.shown,false);call(control,"V0",s.results,s.searched,true);
   // v.h reads this field when calculating full-width section titles.
   set(control,"Z",call(s.all,"getLayoutManager"));
   FrameLayout root=new FrameLayout(activity);root.addView(s.body,new FrameLayout.LayoutParams(width,height,Gravity.CENTER));
   root.setOnClickListener(v->{if(!s.closed)s.popup.dismiss();});s.body.setOnClickListener(v->{});
   PopupWindow popup=(PopupWindow)Class.forName("com.coui.appcompat.poplist.w",false,loader).getConstructor(View.class,int.class,int.class).newInstance(root,-1,-1);
   s.popup=popup;popup.setFocusable(true);popup.setOutsideTouchable(true);popup.setBackgroundDrawable(null);
   popup.setWindowLayoutType(2038);popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
   call(popup,"h",true);
   Runnable finish=activity.trackPickerPopup(popup);popup.setOnDismissListener(()->{s.close();finish.run();});
   popup.showAtLocation(activity.getWindow().getDecorView(),Gravity.CENTER,0,0);
   s.searchContent.setVisibility(View.GONE);
   s.worker=new Thread(()->load(s,control,activity,new HashSet<>(excluded)),"windowdeck-native-picker");s.worker.start();
   Log.i("WindowDeck","native_picker_open renderer="+CONTROL+" user="+(android.os.Process.myUid()/100000));
  }catch(Throwable e){failed(s,e);}
 }
 private static void load(Session s,Object control,WorkbenchActivity activity,Set<String> excluded){
  List<Object> entries=new ArrayList<>();
  try{
   ClassLoader loader=activity.getClassLoader();
   Object directory=Class.forName("i2.b",false,loader).getConstructor(Context.class).newInstance(activity);
   call(directory,"m");Map<?,?> apps=(Map<?,?>)call(directory,"o");
   Map<?,?> hidden=(Map<?,?>)call(control,"E0");
   int user=android.os.Process.myUid()/100000;
   Class<?> entryType=Class.forName("c2.a$b",false,loader);
   Constructor<?> entry=entryType.getConstructor(String.class,String.class,Bitmap.class,int.class,int.class);
   Map<String,String> labels=new HashMap<>();
   for(Map.Entry<?,?> app:apps.entrySet()){
    Pair<?,?> identity=(Pair<?,?>)staticCall(loader,UTIL,"P1",app.getKey());
    String pkg=(String)identity.first;int profile=(Integer)identity.second;
    if(profile!=user||excluded.contains(pkg)||"io.github.xitc.windowdeck".equals(pkg)||"com.oplus.pscanvas".equals(pkg)||(hidden!=null&&hidden.containsKey(pkg)))continue;
    labels.put(pkg,(String)app.getValue());entries.add(entry.newInstance(app.getValue(),pkg,null,4,profile));
   }
   @SuppressWarnings("unchecked") Comparator<Object> order=(Comparator<Object>)Class.forName("l2.a",false,loader).getConstructor().newInstance();entries.sort(order);
   List<Object> recent=new ArrayList<>();Set<String> seen=new HashSet<>();
   for(Object key:(List<?>)call(control,"u1")){
    Pair<?,?> identity=(Pair<?,?>)staticCall(loader,UTIL,"P1",key);String pkg=(String)identity.first;
    if((Integer)identity.second==user&&labels.containsKey(pkg)&&seen.add(pkg))recent.add(entry.newInstance(labels.get(pkg),pkg,null,3,user));
   }
   Constructor<?> title=entryType.getConstructor(String.class,int.class);
   entries.add(0,title.newInstance(text(activity,loader,"U"),2));
   if(!recent.isEmpty()){
    int spans=(Integer)call(control,"I0");while(recent.size()%spans!=0)recent.add(entryType.getConstructor(int.class).newInstance(1));
    entries.addAll(0,recent);entries.add(0,title.newInstance(text(activity,loader,"V"),2));
   }
   for(Object value:entries){if(s.closed||Thread.currentThread().isInterrupted())return;call(control,"l1",value);}
   MAIN.post(()->{
    if(s.closed)return;
    s.catalog.addAll(entries);s.shown.addAll(entries);s.loading.setVisibility(View.GONE);s.bind(s.edit.getText().toString());
    Log.i("WindowDeck","native_picker_loaded entries="+entries.size());
   });
  }catch(Throwable e){MAIN.post(()->failed(s,e));}
 }
 private static void failed(Session s,Throwable e){
  if(s.closed)return;WorkbenchActivity activity=s.activity;PopupWindow popup=s.popup;
  if(popup!=null&&popup.isShowing())popup.dismiss();else s.close();
  if(activity!=null&&!activity.isFinishing())Toast.makeText(activity,"原版应用列表加载失败，请重试",Toast.LENGTH_LONG).show();
  Log.e("WindowDeck","native_picker_failed",e);
 }
 private static int id(Context c,String kind,String name){int id=c.getResources().getIdentifier(name,kind,"com.oplus.pscanvas");if(id==0)throw new IllegalStateException("Missing native "+kind+"/"+name);return id;}
 private static String text(Context c,ClassLoader loader,String field)throws Exception{return c.getString(Class.forName("com.oplus.pscanvas.j",false,loader).getField(field).getInt(null));}
 private static Method method(Class<?> type,String name,Class<?>... args)throws Exception{Method m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
 private static void set(Object object,String name,Object value){try{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}catch(Exception e){throw new IllegalStateException(name,e);}}
 private static Object staticCall(ClassLoader loader,String type,String name,Object... args)throws Exception{return invoke(Class.forName(type,false,loader),null,name,args);}
 private static Object call(Object receiver,String name,Object... args)throws Exception{return invoke(receiver.getClass(),receiver,name,args);}
 private static Object invoke(Class<?> type,Object receiver,String name,Object[] args)throws Exception{
  for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods()){
   if(!m.getName().equals(name)||m.getParameterTypes().length!=args.length)continue;
   Class<?>[] types=m.getParameterTypes();boolean match=true;
   for(int i=0;i<args.length;i++)if(args[i]!=null&&!types[i].isInstance(args[i])&&!(types[i]==int.class&&args[i] instanceof Integer)&&!(types[i]==boolean.class&&args[i] instanceof Boolean)){match=false;break;}
   if(match){m.setAccessible(true);return m.invoke(receiver,args);}
  }throw new NoSuchMethodException(type.getName()+"."+name);
 }
 private static void notifyAdapter(View list)throws Exception{call(call(list,"getAdapter"),"notifyDataSetChanged");}
 private NativeAppPicker(){}
}
