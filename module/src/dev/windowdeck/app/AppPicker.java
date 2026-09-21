package dev.windowdeck.app;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.text.*;
import android.widget.*;
import java.util.*;
final class AppPicker {
 interface Selection { void select(ComponentName component,String label); }
 static void show(Activity activity,Set<String> excluded,Selection callback){
  List<ResolveInfo> all=new ArrayList<>(activity.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0));
  Set<String> seen=new HashSet<>();
  all.removeIf(r->excluded.contains(r.activityInfo.packageName)||r.activityInfo.packageName.equals("com.oplus.pscanvas")||r.activityInfo.packageName.equals("dev.windowdeck.app")||!seen.add(r.activityInfo.packageName));
  all.sort((a,b)->a.loadLabel(activity.getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(activity.getPackageManager()).toString()));
  LinearLayout body=new LinearLayout(activity);body.setOrientation(1);int pad=Ui.dp(activity,16);body.setPadding(pad,0,pad,0);
  EditText search=new EditText(activity);search.setSingleLine();search.setHint("搜索应用名称或包名");body.addView(search);
  ListView list=new ListView(activity);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
  ArrayList<ResolveInfo> shown=new ArrayList<>();ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<String>());list.setAdapter(adapter);
  Runnable filter=()->{String q=search.getText().toString().toLowerCase(Locale.ROOT);shown.clear();adapter.clear();for(ResolveInfo r:all){String name=r.loadLabel(activity.getPackageManager()).toString();if((name+" "+r.activityInfo.packageName).toLowerCase(Locale.ROOT).contains(q)){shown.add(r);adapter.add(name+"\n"+r.activityInfo.packageName);}}};filter.run();
  search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){filter.run();}public void afterTextChanged(Editable e){}});
  AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("选择应用").setView(body).setNegativeButton("取消",null).create();
  list.setOnItemClickListener((p,v,pos,id)->{ResolveInfo r=shown.get(pos);dialog.dismiss();callback.select(new ComponentName(r.activityInfo.packageName,r.activityInfo.name),r.loadLabel(activity.getPackageManager()).toString());});
  dialog.setOnShowListener(d->{android.view.Window w=dialog.getWindow();w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);w.setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.78f));body.setFocusableInTouchMode(true);body.requestFocus();});
  if(activity instanceof WorkbenchActivity)((WorkbenchActivity)activity).showWorkbenchDialog(dialog);else dialog.show();
 }
}
