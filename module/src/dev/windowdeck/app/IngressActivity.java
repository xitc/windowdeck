package dev.windowdeck.app;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Explicit, no-UI cold entry; ColorOS blocks provider/broadcast delivery to stopped apps. */
public final class IngressActivity extends Activity {
 @Override protected void onCreate(Bundle state){
  super.onCreate(state);
  try{
   int uid=getLaunchedFromUid();boolean launcher=false;
   String[] packages=getPackageManager().getPackagesForUid(uid);
   if(packages!=null)for(String pkg:packages)if("com.android.launcher".equals(pkg))launcher=true;
   if(!launcher)throw new SecurityException("caller is not launcher: "+uid);
   int task=getIntent().getIntExtra("taskId",-1),user=getIntent().getIntExtra("userId",-1),container=getIntent().getIntExtra("containerTaskId",-1);
   if(task<0||user!=0||container < -1||task==container)throw new IllegalArgumentException("invalid handoff");
   LauncherIngressReceiver.startTask(this,task,user,container,null);
   Log.i("WindowDeck","cold_ingress source="+task+" caller="+uid);
  }catch(Throwable e){Log.e("WindowDeck","cold_ingress_failed",e);}
  finally{finish();}
 }
}
