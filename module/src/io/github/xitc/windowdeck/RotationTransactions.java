package io.github.xitc.windowdeck;

import android.app.BroadcastOptions;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.SurfaceControl;
import android.view.View;
import de.robv.android.xposed.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/** C17 sanitizes arbitrary 2D rotation when the canvas UID submits to SurfaceFlinger.
 * Merge the canvas draw first, then let the already permitted launcher submit it.
 * No system permission check, unrelated transaction, or module scope is changed. */
final class RotationTransactions {
 private static final String TAG="WindowDeck",CONNECT="io.github.xitc.windowdeck.CONNECT_ROTATION_TRANSACTIONS";
 private static final String DESCRIPTOR="io.github.xitc.windowdeck.RotationTransactions";
 private static final int PING=IBinder.FIRST_CALL_TRANSACTION,APPLY=PING+1;
 private static boolean registered;
 interface Failure { void failed(int task,int generation); }

 static void installLauncher(){
  XposedHelpers.findAndHookMethod(android.app.Application.class,"attach",Context.class,new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){registerLauncher((Context)p.args[0]);}
  });
 }
 private static void registerLauncher(Context context){
  if(registered)return;
  if(context.checkSelfPermission("android.permission.ROTATE_SURFACE_FLINGER")!=PackageManager.PERMISSION_GRANTED){
   Log.e(TAG,"rotation_submitter_unavailable reason=missing_rotate_permission uid="+android.os.Process.myUid());return;
  }
  Context app=context.getApplicationContext();if(app==null)app=context;
  app.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
   int sender=getSentFromUid(),expected=-1;
   try{expected=c.getPackageManager().getApplicationInfo("com.oplus.pscanvas",0).uid;}catch(Exception ignored){}
   int container=intent.getIntExtra("container",-1);long instance=intent.getLongExtra("instance",0);
   if(sender!=expected||sender<0||sender/100000!=android.os.Process.myUid()/100000||container<0||instance==0
     ||intent.getIntExtra("version",-1)!=Version.CODE){
    Log.w(TAG,"rotation_submitter_connect_denied uid="+sender+" version="+Version.NAME);setResultCode(0);return;
   }
   Bundle result=new Bundle();result.putBinder("submitter",new Submitter(sender,container,instance));
   result.putInt("version",Version.CODE);setResultExtras(result);setResultCode(1);
  }},new IntentFilter(CONNECT),null,new Handler(Looper.getMainLooper()),Context.RECEIVER_EXPORTED);
  registered=true;
  Log.i(TAG,"rotation_submitter_ready version="+Version.NAME+" uid="+android.os.Process.myUid());
 }
 private static final class Submitter extends Binder {
  private final RotationFrameOrder order;
  private final int uid,container;
  private final long instance;
  Submitter(int uid,int container,long instance){this.uid=uid;this.container=container;this.instance=instance;order=new RotationFrameOrder(uid,container,instance);}
  @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags) throws RemoteException {
   if(code==IBinder.INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
   if(code!=PING&&code!=APPLY)return super.onTransact(code,data,reply,flags);
   data.enforceInterface(DESCRIPTOR);
   int caller=Binder.getCallingUid();
   if(caller!=uid)throw new SecurityException("rotation host uid mismatch");
   if(code==PING){data.enforceNoDataAvail();reply.writeNoException();reply.writeInt(Version.CODE);return true;}
   int requestedContainer=data.readInt();long requestedInstance=data.readLong();
   int task=data.readInt(),generation=data.readInt();long frame=data.readLong();
   synchronized(order){
    if(!order.accept(caller,requestedContainer,requestedInstance,task,generation,frame))throw new SecurityException("stale rotation draw");
    try(SurfaceControl.Transaction t=SurfaceControl.Transaction.CREATOR.createFromParcel(data);
        SurfaceControl.Transaction fit=SurfaceControl.Transaction.CREATOR.createFromParcel(data)){
     data.enforceNoDataAvail();
     long identity=Binder.clearCallingIdentity();
     try{
      // C17 also sanitizes transactions passed between local sync groups. The
      // unsanitized fit must be appended HERE, after the host draw is ready.
      t.merge(fit);
      t.addTransactionCommittedListener(Runnable::run,()->Log.i(TAG,"task_rotation_sf_commit task="+task+" generation="+generation+" frame="+frame+" owner=launcher"));
      t.apply();
     }finally{Binder.restoreCallingIdentity(identity);}
     Log.i(TAG,"task_rotation_submit task="+task+" generation="+generation+" frame="+frame+" container="+container+" instance="+instance+" owner=launcher uid="+android.os.Process.myUid());
    }
   }
   reply.writeNoException();return true;
  }
 }

 /** A sync owner is attached for each fit. SurfaceSyncGroup merges overlapping
  * owners in submission order; an older consumer receives null after delegation.
  * Never reuse an owner merely because the previous buffer is still in flight:
  * ViewRoot may already have dispatched it and started a different draw. */
 static final class Host implements AutoCloseable {
  private final Context context;
  private final Handler main;
  private final int container;
  private final long instance;
  private final Failure failure;
  private final Constructor<?> constructor;
  private final Method add,ready,resize,reparent,clear,matrix;
  private IBinder submitter;
  private boolean connecting,closed;
  private final RotationFrameOrder.Queue<Pending> pending=new RotationFrameOrder.Queue<>();
  private static final class Pending {
   final IBinder submitter;
   long frame;final int task,generation;boolean abandoned;
   Object group;
   SurfaceControl.Transaction fit,draw;
   Pending(IBinder submitter,int task,int generation){this.submitter=submitter;this.task=task;this.generation=generation;}
  }
  Host(Context context,Handler main,int container,long instance,Failure failure) throws Exception {
   this.context=context;this.main=main;this.container=container;this.instance=instance;this.failure=failure;
   Class<?> group=Class.forName("android.window.SurfaceSyncGroup");
   constructor=group.getConstructor(String.class,Consumer.class);
   add=group.getMethod("add",AttachedSurfaceControl.class,Runnable.class);ready=group.getMethod("markSyncReady");
   // These Java-side maps do not travel in the native transaction parcel. Run the
   // same bookkeeping as Transaction.apply() before transferring ownership.
   resize=method("applyResizedSurfaces");reparent=method("notifyReparentedSurfaces");clear=method("clear");
   matrix=SurfaceControl.Transaction.class.getDeclaredMethod("setMatrix",SurfaceControl.class,float.class,float.class,float.class,float.class);matrix.setAccessible(true);
  }
  private static Method method(String name) throws Exception {Method m=SurfaceControl.Transaction.class.getDeclaredMethod(name);m.setAccessible(true);return m;}
  synchronized boolean available(){return !closed&&submitter!=null&&submitter.isBinderAlive();}
  synchronized boolean pending(){return pending.pending();}
  void connect(){connect(0);}
  private synchronized void connect(int attempt){
   if(closed||connecting||available())return;connecting=true;
   BroadcastOptions options=BroadcastOptions.makeBasic();options.setShareIdentityEnabled(true);
   context.sendOrderedBroadcast(new Intent(CONNECT).setPackage("com.android.launcher").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
     .putExtra("container",container).putExtra("instance",instance).putExtra("version",Version.CODE),null,options.toBundle(),new BroadcastReceiver(){
    public void onReceive(Context c,Intent intent){
     synchronized(Host.this){
      connecting=false;if(closed)return;
      Bundle result=getResultExtras(false);IBinder candidate=result==null?null:result.getBinder("submitter");
      if(getResultCode()==1&&candidate!=null&&result.getInt("version",-1)==Version.CODE){
       Parcel data=Parcel.obtain(),reply=Parcel.obtain();
       try{
        data.writeInterfaceToken(DESCRIPTOR);if(!candidate.transact(PING,data,reply,0))throw new RemoteException("missing rotation ping");
        reply.readException();if(reply.readInt()!=Version.CODE)throw new IllegalStateException("rotation version mismatch");
        submitter=candidate;Log.i(TAG,"rotation_submitter_connected version="+Version.NAME+" container="+container);return;
       }catch(Exception e){Log.w(TAG,"rotation_submitter_connect_failed",e);}finally{data.recycle();reply.recycle();}
      }
      if(attempt<2)main.postDelayed(()->connect(attempt+1),500);
      else Log.w(TAG,"rotation_submitter_unavailable reason=launcher_not_connected version="+Version.NAME);
     }
    }
   },main,0,null,null);
  }
  synchronized void submit(View stage,SurfaceControl.Transaction fit,SurfaceControl leash,int task,int generation){
   if(Looper.myLooper()!=main.getLooper()||closed||!stage.isAttachedToWindow()||!stage.isHardwareAccelerated())throw new IllegalStateException("rotation draw unavailable");
   AttachedSurfaceControl root=stage.getRootSurfaceControl();if(root==null)throw new IllegalStateException("missing rotation root");
   if(leash==null||!leash.isValid())throw new IllegalStateException("missing rotation leash");
   if(!available())throw new IllegalStateException("missing permitted rotation submitter");
   Pending p=new Pending(submitter,task,generation);p.frame=pending.add(p);
   try{
    p.fit=copy(fit);
    // Keep native reparent/crop/position and Java bookkeeping in the host draw,
    // but let only a rect-preserving matrix enter its local sync callbacks. The
    // copy above retains the requested angle for the permitted final merge.
    matrix.invoke(fit,leash,1f,0f,0f,1f);
    p.group=constructor.newInstance("WindowDeckRotation:"+task+":"+generation+":"+p.frame,(Consumer<SurfaceControl.Transaction>)t->complete(p,t));
    if(!Boolean.TRUE.equals(add.invoke(p.group,root,null)))throw new IllegalStateException("rotation root sync failed");
    if(!root.applyTransactionOnDraw(fit))throw new IllegalStateException("rotation draw merge failed");
    ready.invoke(p.group);
   }catch(Exception e){
    p.abandoned=true;
    if(p.group!=null)try{ready.invoke(p.group);}catch(Exception ignored){}
    else complete(p,null);
    throw new IllegalStateException("rotation draw sync failed",e);
   }
  }
  private synchronized void complete(Pending p,SurfaceControl.Transaction t){
   if(!pending.ready(p.frame))return;p.draw=t;
   Pending next;while((next=pending.poll())!=null)apply(next);
  }
  private void apply(Pending p){
   SurfaceControl.Transaction t=p.draw;
   boolean acknowledged=false;
   try{
    if(t==null){Log.i(TAG,"task_rotation_draw_delegated task="+p.task+" generation="+p.generation+" frame="+p.frame);return;}
    if(p.abandoned){t.apply();return;}
    resize.invoke(t);reparent.invoke(t);
    Parcel data=Parcel.obtain(),reply=Parcel.obtain();
    try{
     data.writeInterfaceToken(DESCRIPTOR);data.writeInt(container);data.writeLong(instance);
     data.writeInt(p.task);data.writeInt(p.generation);data.writeLong(p.frame);
     // Keep the local native state until acknowledged, so a dead launcher cannot
     // strand the host buffer. Never also apply the acknowledged transaction.
     t.writeToParcel(data,0);
     p.fit.writeToParcel(data,0);
     if(!p.submitter.transact(APPLY,data,reply,0))throw new RemoteException("missing rotation apply");reply.readException();acknowledged=true;clear.invoke(t);
    }finally{data.recycle();reply.recycle();}
   }catch(Exception e){
    if(submitter==p.submitter)submitter=null;
    Log.e(TAG,"task_rotation_submit_failed task="+p.task+" generation="+p.generation+" frame="+p.frame,e);
    // Release the owned host draw, then cancel this generation. Do not keep
    // animating position when its angular matrix cannot be accepted.
    if(t!=null&&!acknowledged)try{t.apply();}catch(Exception local){Log.e(TAG,"task_rotation_draw_release_failed",local);}
    main.post(()->failure.failed(p.task,p.generation));
   }finally{if(p.fit!=null){p.fit.close();p.fit=null;}p.draw=null;p.group=null;}
  }
  private static SurfaceControl.Transaction copy(SurfaceControl.Transaction t){
   Parcel parcel=Parcel.obtain();try{t.writeToParcel(parcel,0);parcel.setDataPosition(0);return SurfaceControl.Transaction.CREATOR.createFromParcel(parcel);}
   finally{parcel.recycle();}
  }
  @Override public synchronized void close(){closed=true;submitter=null;}
 }
 private RotationTransactions(){}
}
