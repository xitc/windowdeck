package io.github.xitc.windowdeck;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Module-owned preference; never query the provider on a touch callback. */
final class CornerEntrySettings {
 static final String PREF="corner_swipe_entry";
 private static volatile boolean enabled;
 private static boolean registered;
 private static final java.util.concurrent.ExecutorService READER=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
  Thread t=new Thread(r,"windowdeck-entry-setting");t.setDaemon(true);return t;
 });
 static Uri uri(){return Uri.parse(BuildIdentity.STATE_URI+"/"+PREF);}
 static boolean local(Context context){return context.getSharedPreferences("MainActivity",0).getBoolean(PREF,false);}
 static boolean save(Context context,boolean value){
  if(!context.getSharedPreferences("MainActivity",0).edit().putBoolean(PREF,value).commit())return false;
  context.getContentResolver().notifyChange(uri(),null);return true;
 }
 static boolean enabled(){return enabled;}
 static synchronized void register(Context context){
  if(registered||context==null)return;
  Context application=context.getApplicationContext();final Context app=application==null?context:application;
  Handler main=new Handler(Looper.getMainLooper());
  Runnable refresh=()->READER.execute(()->{
   try{
    Bundle answer=app.getContentResolver().call(uri(),PREF,null,null);
    if(answer==null||!answer.containsKey(PREF))return;
    boolean next=answer.getBoolean(PREF);
    main.post(()->{if(enabled!=next){enabled=next;CornerSwipeHook.settingChanged();Log.i("WindowDeck","corner_entry_setting enabled="+next);}});
   }catch(Exception e){Log.w("WindowDeck","corner_entry_setting_unavailable",e);}
  });
  app.getContentResolver().registerContentObserver(uri(),false,new ContentObserver(main){
   @Override public void onChange(boolean selfChange){refresh.run();}
  });
  registered=true;refresh.run();
 }
 private CornerEntrySettings(){}
}
