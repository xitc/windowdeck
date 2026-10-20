package io.github.xitc.windowdeck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Reads the module preference when the host cannot resolve its Provider. */
public final class SideWindowSettingsReceiver extends BroadcastReceiver {
 @Override public void onReceive(Context context,Intent intent){
  if(intent==null||!SideWindowSettings.QUERY.equals(intent.getAction()))return;
  int uid=getSentFromUid();
  String[] packages=context.getPackageManager().getPackagesForUid(uid);
  boolean host=false;
  if(packages!=null)for(String pkg:packages)if("com.oplus.pscanvas".equals(pkg))host=true;
  if(!host){Log.w("WindowDeck","side_perspective_sender_denied uid="+uid);return;}
  Bundle answer=new Bundle();answer.putBoolean(SideWindowSettings.PREF,SideWindowSettings.local(context));
  setResultExtras(answer);setResultCode(1);
 }
}
