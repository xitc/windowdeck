package dev.windowdeck.app;

import android.view.SurfaceControl;
import android.util.Log;
import de.robv.android.xposed.*;
import java.util.IdentityHashMap;

/** Restores transforms after transactions owned by our embedded views commit. */
final class LeashTransactions {
 interface Writer { void write(SurfaceControl.Transaction transaction); }
 private static final IdentityHashMap<Object,Writer> writers=new IdentityHashMap<>();
 private static boolean installed;
 static synchronized void install(){
  if(installed)return;
  XposedHelpers.findAndHookMethod(SurfaceControl.Transaction.class,"apply",boolean.class,boolean.class,new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam param){
    Writer writer;
    synchronized(LeashTransactions.class){writer=writers.get(param.thisObject);}
    if(writer!=null&&!param.hasThrowable()){
     // A reparent/rotation in the ROM transaction can reset perspective during
     // native apply. Submit a separate transform-only transaction afterwards.
     // It is not registered, so its apply cannot recursively invoke the writer.
     try(SurfaceControl.Transaction fit=new SurfaceControl.Transaction()){writer.write(fit);fit.apply();}
    }
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
