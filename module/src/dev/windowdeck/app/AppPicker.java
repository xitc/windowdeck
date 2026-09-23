package dev.windowdeck.app;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
final class AppPicker {
 interface Selection { void select(ComponentName component,String label); }
 static final class Entry {
  final ComponentName component; final String label,haystack; Drawable icon;
  Entry(ComponentName c,String l){component=c;label=l;haystack=(l+" "+c.getPackageName()).toLowerCase(Locale.ROOT);}
 }
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static List<Entry> catalog=Collections.emptyList();
 private static boolean loading;
 private static Runnable pendingBind;
 static void show(Activity activity,Set<String> excluded,Selection callback){
  int pad=Ui.dp(activity,16),rowH=Ui.dp(activity,56),icon=Ui.dp(activity,36),radius=Ui.dp(activity,16);
  LinearLayout body=new LinearLayout(activity);body.setOrientation(1);body.setBackground(Ui.bg(Ui.CHROME,radius));body.setPadding(pad,pad,pad,pad);
  TextView title=Ui.text(activity,"选择应用",20,Ui.TEXT);title.setPadding(0,0,0,Ui.dp(activity,12));body.addView(title);
  EditText search=new EditText(activity);search.setSingleLine();search.setHint("搜索应用");search.setHintTextColor(Ui.MUTED);search.setTextColor(Ui.TEXT);
  search.setBackground(Ui.bg(Ui.CARD,Ui.dp(activity,12)));search.setPadding(pad,Ui.dp(activity,10),pad,Ui.dp(activity,10));search.setTextSize(15);
  LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,-2);searchLp.bottomMargin=Ui.dp(activity,8);body.addView(search,searchLp);
  TextView empty=Ui.text(activity,"正在加载应用…",14,Ui.MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,Ui.dp(activity,24),0,Ui.dp(activity,24));
  ListView list=new ListView(activity);list.setDivider(null);list.setDividerHeight(0);list.setSelector(android.R.color.transparent);list.setCacheColorHint(0);
  FrameLayout pane=new FrameLayout(activity);pane.addView(list,new FrameLayout.LayoutParams(-1,-1));pane.addView(empty,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
  body.addView(pane,new LinearLayout.LayoutParams(-1,0,1));
  TextView cancel=Ui.text(activity,"取消",16,Ui.MUTED);cancel.setGravity(Gravity.CENTER);cancel.setPadding(0,Ui.dp(activity,14),0,Ui.dp(activity,4));cancel.setClickable(true);
  body.addView(cancel,new LinearLayout.LayoutParams(-1,-2));
  ArrayList<Entry> shown=new ArrayList<>();
  BaseAdapter adapter=new BaseAdapter(){
   public int getCount(){return shown.size();}
   public Entry getItem(int i){return shown.get(i);}
   public long getItemId(int i){return i;}
   public View getView(int i,View convert,ViewGroup parent){
    LinearLayout row;ImageView image;TextView name;
    if(convert instanceof LinearLayout){row=(LinearLayout)convert;image=(ImageView)row.getChildAt(0);name=(TextView)row.getChildAt(1);}
    else{
     row=new LinearLayout(activity);row.setOrientation(0);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(activity,8),0,Ui.dp(activity,8),0);row.setMinimumHeight(rowH);
     image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);Ui.round(image,Ui.dp(activity,8));
     LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(icon,icon);iconLp.rightMargin=Ui.dp(activity,12);row.addView(image,iconLp);
     name=Ui.text(activity,"",16,Ui.TEXT);name.setSingleLine();name.setEllipsize(TextUtils.TruncateAt.END);row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
    }
    Entry item=shown.get(i);name.setText(item.label);
    if(item.icon==null){
     try{item.icon=activity.getPackageManager().getApplicationIcon(item.component.getPackageName());}catch(Exception e){item.icon=activity.getDrawable(android.R.drawable.sym_def_app_icon);}
    }
    image.setImageDrawable(item.icon);return row;
   }
  };
  list.setAdapter(adapter);
  Runnable bind=()->{
   String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();
   for(Entry e:catalog)if(!excluded.contains(e.component.getPackageName())&&(q.isEmpty()||e.haystack.contains(q)))shown.add(e);
   adapter.notifyDataSetChanged();
   boolean idle=catalog.isEmpty()&&loading;
   empty.setText(idle?"正在加载应用…":shown.isEmpty()?"没有匹配的应用":"");
   empty.setVisibility(idle||shown.isEmpty()?View.VISIBLE:View.GONE);
   list.setVisibility(shown.isEmpty()?View.INVISIBLE:View.VISIBLE);
  };
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){bind.run();}public void afterTextChanged(Editable e){}});
  AlertDialog dialog=new AlertDialog.Builder(activity).setView(body).create();
  dialog.setCanceledOnTouchOutside(true);
  list.setOnItemClickListener((p,v,pos,id)->{if(pos<0||pos>=shown.size())return;Entry e=shown.get(pos);dialog.dismiss();callback.select(e.component,e.label);});
  cancel.setOnClickListener(v->dialog.dismiss());
  dialog.setOnShowListener(d->{
   android.view.Window w=dialog.getWindow();if(w==null)return;
   w.setBackgroundDrawable(Ui.bg(0,0));
   w.setDimAmount(0.45f);
   w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
   int width=Math.min(activity.getResources().getDisplayMetrics().widthPixels-Ui.dp(activity,24),Ui.dp(activity,400));
   w.setLayout(width,(int)(activity.getResources().getDisplayMetrics().heightPixels*.72f));
  });
  refresh(activity,bind);
  if(activity instanceof WorkbenchActivity)((WorkbenchActivity)activity).showWorkbenchDialog(dialog);else dialog.show();
 }
 private static void refresh(Activity activity,Runnable bind){
  if(!catalog.isEmpty())bind.run();
  if(loading){pendingBind=bind;return;}
  loading=true;PackageManager pm=activity.getPackageManager();
  new Thread(()->{
   ArrayList<Entry> next=new ArrayList<>();
   Set<String> seen=new HashSet<>();
   List<ResolveInfo> infos=pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0);
   for(ResolveInfo r:infos){
    String pkg=r.activityInfo.packageName;
    if("com.oplus.pscanvas".equals(pkg)||"dev.windowdeck.app".equals(pkg)||!seen.add(pkg))continue;
    CharSequence label=r.loadLabel(pm);next.add(new Entry(new ComponentName(pkg,r.activityInfo.name),label==null?pkg:label.toString()));
   }
   next.sort((a,b)->a.label.compareToIgnoreCase(b.label));
   MAIN.post(()->{
    catalog=next;loading=false;
    if(!activity.isFinishing())bind.run();
    Runnable extra=pendingBind;pendingBind=null;if(extra!=null&&extra!=bind)extra.run();
   });
  },"windowdeck-apps").start();
 }
}
