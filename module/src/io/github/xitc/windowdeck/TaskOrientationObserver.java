package io.github.xitc.windowdeck;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.lang.reflect.Field;

/** Receive system task requests inside the existing privileged canvas host. */
final class TaskOrientationObserver implements AutoCloseable {
 interface Listener { void requested(int taskId,int orientation); }
 private static final String DESCRIPTOR="android.app.ITaskStackListener";
 private final Handler handler;
 private final Listener listener;
 private Object service, proxy;
 private Class<?> listenerType;
 private int requestTransaction;
 private volatile boolean active;
 private final Binder binder=new Binder(){
  @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
   if(code==IBinder.INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(DESCRIPTOR);return true;}
   if(code==requestTransaction){
    data.enforceInterface(DESCRIPTOR);final int task=data.readInt(),orientation=data.readInt();
    handler.post(()->{if(active)listener.requested(task,orientation);});return true;
   }
   // Other task-stack events are one-way notifications and have no bearing on this observer.
   if(code>=IBinder.FIRST_CALL_TRANSACTION&&code<=IBinder.LAST_CALL_TRANSACTION)return true;
   return super.onTransact(code,data,reply,flags);
  }
 };
 TaskOrientationObserver(Handler handler,Listener listener){this.handler=handler;this.listener=listener;}
 boolean start(){
  if(active)return true;
  try{
   listenerType=Class.forName(DESCRIPTOR);Class<?> stub=Class.forName(DESCRIPTOR+"$Stub");
   Field transaction=stub.getDeclaredField("TRANSACTION_onActivityRequestedOrientationChanged");transaction.setAccessible(true);
   requestTransaction=transaction.getInt(null);
   proxy=stub.getMethod("asInterface",IBinder.class).invoke(null,binder);
   service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
   active=true;service.getClass().getMethod("registerTaskStackListener",listenerType).invoke(service,proxy);
   Log.i("WindowDeck","task_orientation_observer registered=true");return true;
  }catch(Throwable e){active=false;Log.w("WindowDeck","task_orientation_observer registered=false",e);return false;}
 }
 @Override public void close(){
  boolean registered=active;active=false;
  if(registered)try{service.getClass().getMethod("unregisterTaskStackListener",listenerType).invoke(service,proxy);}
  catch(Throwable e){Log.w("WindowDeck","task_orientation_observer unregister_failed",e);}
 }
}
