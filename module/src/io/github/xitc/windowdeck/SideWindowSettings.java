package io.github.xitc.windowdeck;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import java.util.function.Consumer;

/** Module-owned setting; the injected pscanvas host has a different preferences directory. */
final class SideWindowSettings {
 static final String PREF_FILE="MainActivity",PREF="atomic_rotate";
 static final String QUERY=BuildIdentity.APPLICATION_ID+".QUERY_SIDE_PERSPECTIVE";
 static Uri uri(){return Uri.parse(BuildIdentity.STATE_URI+"/"+PREF);}
 static boolean local(Context context){
  android.content.SharedPreferences prefs=context.getSharedPreferences(PREF_FILE,0);
  if(prefs.contains(PREF))return prefs.getBoolean(PREF,AtomicPresentation.DEFAULT);
  // getPreferences() used the full class name in prototype builds whose Java
  // namespace differs from their application ID. Keep that saved choice too.
  return context.getSharedPreferences("io.github.xitc.windowdeck.MainActivity",0).getBoolean(PREF,AtomicPresentation.DEFAULT);
 }
 static boolean save(Context context,boolean enabled){
  if(!context.getSharedPreferences(PREF_FILE,0).edit().putBoolean(PREF,enabled).commit())return false;
  context.getContentResolver().notifyChange(uri(),null);
  return true;
 }
 static Boolean stored(Context context){
  try{
   Bundle answer=context.getContentResolver().call(uri(),PREF,null,null);
   if(answer!=null&&answer.containsKey(PREF))return answer.getBoolean(PREF);
  }catch(Exception e){Log.w("WindowDeck","side_perspective_setting_unavailable",e);}
  return null;
 }
 static boolean read(Context context,boolean fallback){
  Boolean value=stored(context);return value==null?fallback:value;
 }
 static void request(Context context,Handler handler,Consumer<Boolean> accept){
  Boolean value=stored(context);
  if(value!=null){accept.accept(value);return;}
  Intent query=new Intent(QUERY).setComponent(new ComponentName(BuildIdentity.APPLICATION_ID,
    "io.github.xitc.windowdeck.SideWindowSettingsReceiver"));
  query.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES|Intent.FLAG_RECEIVER_FOREGROUND);
  BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
  try{
   context.sendOrderedBroadcast(query,null,options.toBundle(),new BroadcastReceiver(){
    @Override public void onReceive(Context ignored,Intent response){
     Bundle answer=getResultExtras(false);
     if(getResultCode()==1&&answer!=null&&answer.containsKey(PREF))accept.accept(answer.getBoolean(PREF));
    }
   },handler,0,null,null);
  }catch(Exception e){Log.w("WindowDeck","side_perspective_broadcast_unavailable",e);}
 }
 private SideWindowSettings(){}
}
