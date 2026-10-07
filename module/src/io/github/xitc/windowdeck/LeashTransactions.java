package io.github.xitc.windowdeck;

import android.view.SurfaceControl;
import android.util.Log;
import de.robv.android.xposed.*;
import java.util.IdentityHashMap;

/** Fits transforms inside transactions owned by our embedded views. */
final class LeashTransactions {
 /** True means the writer consumed the native transaction into a host draw. */
 interface Writer { boolean write(SurfaceControl.Transaction transaction); }
 private static final IdentityHashMap<Object,Writer> writers=new IdentityHashMap<>();
 private static boolean installed;
 static synchronized void install(){
  if(installed)return;
  XposedHelpers.findAndHookMethod(SurfaceControl.Transaction.class,"apply",boolean.class,boolean.class,new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam param){
    Writer writer;
    synchronized(LeashTransactions.class){writer=writers.get(param.thisObject);}
    // Append our final crop/rotation to the SAME transaction. A corrective second
    // apply can expose the ROM's intermediate old-main geometry for one frame.
    if(writer!=null&&writer.write((SurfaceControl.Transaction)param.thisObject))param.setResult(null);
   }
  });
  installed=true;
  Log.i("WindowDeck","leash_atomic_hook_ready");
 }
 static synchronized void register(Object transaction,Writer writer){
  if(!installed)throw new IllegalStateException("Atomic leash hook unavailable");
  writers.put(transaction,writer);
 }
 static synchronized void unregister(Object transaction){writers.remove(transaction);}
}
