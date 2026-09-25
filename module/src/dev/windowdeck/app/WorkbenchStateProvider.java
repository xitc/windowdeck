package dev.windowdeck.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

/** Narrow cross-process state and handoff bridge for the host and launcher. */
public final class WorkbenchStateProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  boolean launcher=false,host=false;String[] packages=getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());
  if(packages!=null)for(String pkg:packages){if("com.android.launcher".equals(pkg))launcher=true;if("com.oplus.pscanvas".equals(pkg))host=true;}
  android.content.SharedPreferences prefs=getContext().getSharedPreferences("workbench_state",0);
  if("publish".equals(method)){
   if(!host||extras==null)return null;
   int container=extras.getInt("containerTaskId",-1),count=extras.getInt("count",-1);long token=extras.getLong("instanceToken",0);
   if(container<0||count<0||count>3)return null;
   if(count==0&&(prefs.getInt("container",-1)!=container||prefs.getLong("token",0)!=token))return null;
   prefs.edit().putInt("container",container).putInt("count",count).putLong("token",token).apply();
   Log.i("WindowDeck","state_published container="+container+" count="+count);return Bundle.EMPTY;
  }
  if(!launcher)return null;
  if("add_task".equals(method)){
   if(extras==null)return null;
   int task=extras.getInt("taskId",-1),user=extras.getInt("userId",-1),container=extras.getInt("containerTaskId",-1);
   if(task<0||user!=0||container < -1||task==container||(container>=0&&(prefs.getInt("container",-1)!=container||prefs.getInt("count",0)<1||prefs.getInt("count",0)>=3)))return null;
   LauncherIngressReceiver.startTask(getContext(),task,user,container,null);return Bundle.EMPTY;
  }
  if(!"state".equals(method))return null;
  Bundle state=new Bundle();state.putInt("container",prefs.getInt("container",-1));state.putInt("count",prefs.getInt("count",0));return state;
 }
 public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){return null;}
 public String getType(Uri uri){return null;}
 public Uri insert(Uri uri,ContentValues values){return null;}
 public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
 public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
}
