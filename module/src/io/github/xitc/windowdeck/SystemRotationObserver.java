package io.github.xitc.windowdeck;

import android.app.Activity;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/** C17 system-judged rotation and per-user rotation settings, without sensor guessing. */
final class SystemRotationObserver implements AutoCloseable {
 private final Activity activity;
 private final Handler handler;
 private final Runnable changed;
 private final IntConsumer consumer;
 private final ContentObserver settingsObserver;
 private Object global;
 private Class<?> globalType;
 private IBinder token;
 private boolean active,registered,settingsRegistered;
 private int generation;
 int proposed=-1,user=-1;
 boolean settingsKnown,locked;
 SystemRotationObserver(Activity activity,Handler handler,Runnable changed){
  this.activity=activity;this.handler=handler;this.changed=changed;
  consumer=value->{if(active){if(value>=0&&value<=3){proposed=value;publish("proposal");}else Log.i("WindowDeck","system_rotation ignored_unknown="+value);}};
  settingsObserver=new ContentObserver(handler){@Override public void onChange(boolean self){if(active){readSettings();publish("settings");}}};
 }
 void start(){
  if(active)return;
  active=true;int run=++generation;readSettings();
  try{
   activity.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),false,settingsObserver);
   settingsRegistered=true;
   activity.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.USER_ROTATION),false,settingsObserver);
  }catch(Throwable e){Log.w("WindowDeck","system_rotation_settings_observer registered=false",e);}
  try{
   token=(IBinder)Activity.class.getMethod("getActivityToken").invoke(activity);
   if(token==null)throw new IllegalStateException("missing Activity token");
   globalType=Class.forName("android.view.WindowManagerGlobal");global=globalType.getMethod("getInstance").invoke(null);
   // Drop already queued callbacks across a pause/resume boundary.
   Executor executor=command->handler.post(()->{if(active&&generation==run)command.run();});
   globalType.getMethod("registerProposedRotationListener",IBinder.class,Executor.class,IntConsumer.class).invoke(global,token,executor,consumer);
   registered=true;Log.i("WindowDeck","system_rotation_observer registered=true");
  }catch(Throwable e){proposed=-1;Log.w("WindowDeck","system_rotation_observer registered=false",e);}
  publish("resume");
 }
 private void readSettings(){
  try{
   int auto=Settings.System.getInt(activity.getContentResolver(),Settings.System.ACCELEROMETER_ROTATION);
   int saved=Settings.System.getInt(activity.getContentResolver(),Settings.System.USER_ROTATION);
   if((auto!=0&&auto!=1)||saved<0||saved>3)throw new IllegalStateException("invalid rotation settings");
   user=saved;locked=auto==0;settingsKnown=true;
  }catch(Throwable e){settingsKnown=false;user=-1;Log.w("WindowDeck","system_rotation_settings known=false",e);}
 }
 private void publish(String reason){
  Log.i("WindowDeck","system_rotation reason="+reason+" proposed="+proposed+" settings_known="+settingsKnown+" locked="+locked+" user="+user);
  changed.run();
 }
 @Override public void close(){
  active=false;generation++;proposed=-1;
  if(registered){registered=false;try{globalType.getMethod("unregisterProposedRotationListener",IBinder.class,IntConsumer.class).invoke(global,token,consumer);}catch(Throwable e){Log.w("WindowDeck","system_rotation_observer unregister_failed",e);}}
  if(settingsRegistered){settingsRegistered=false;activity.getContentResolver().unregisterContentObserver(settingsObserver);}
 }
}
