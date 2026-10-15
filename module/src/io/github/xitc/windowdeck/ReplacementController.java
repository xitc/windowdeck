package io.github.xitc.windowdeck;

/** Owns replacement ordering and callback lifetime; no Android or Activity dependency.
 * All events, including commit callbacks, must arrive on the host's UI thread. */
final class ReplacementController<T> {
 enum Phase { PREPARING, WAITING, COMMITTING, COMPLETED, ROLLED_BACK }
 interface Scheduler {
  void post(Runnable action,long delay);
  void remove(Runnable action);
 }
 interface Host<T> {
  void prepare(Run<T> run,Runnable ready);
  boolean install(Run<T> run);
  boolean valid(Run<T> run);
  int task(T slot);
  String surfaceKey();
  /** Invoke committed only after both Surface transaction and host frame commit. */
  void commitFrame(Runnable committed) throws Exception;
  void committed(Run<T> run);
  void rolledBack(Run<T> run,String reason);
  void failed(Throwable error);
 }
 static final class Run<T> {
  final T old,fresh;
  final int index,primary;
  boolean mainEntrance;
  private final ReplacementGate gate=new ReplacementGate();
  private Phase phase=Phase.PREPARING;
  private long attempt;
  private Runnable timeout,retry;
  Run(T old,T fresh,int index,int primary){this.old=old;this.fresh=fresh;this.index=index;this.primary=primary;}
  Phase phase(){return phase;}
 }
 private final Scheduler scheduler;
 private final Host<T> host;
 private Run<T> current;
 ReplacementController(Scheduler scheduler,Host<T> host){this.scheduler=scheduler;this.host=host;}
 Run<T> current(){return current;}
 boolean bind(Run<T> run,int task){return current==run&&run!=null&&run.gate.bind(task);}
 void initialize(Run<T> run,boolean success){if(current==run&&run!=null)run.gate.initialize(success);}
 boolean begin(T old,T fresh,int index,int primary){
  if(current!=null)return false;
  Run<T> run=new Run<>(old,fresh,index,primary);current=run;
  run.timeout=()->cancel(run,"timeout");run.retry=()->poll(run);
  scheduler.post(run.timeout,10000);
  try{host.prepare(run,()->install(run));}
  catch(Throwable error){cancel(run,"prepare_failed");host.failed(error);}
  return true;
 }
 private void install(Run<T> run){
  if(current!=run||run.phase!=Phase.PREPARING)return;
  // Invalidate duplicate preparation callbacks before calling into the host.
  run.phase=Phase.WAITING;
  try{
   if(!host.install(run)){cancel(run,"target_changed");return;}
   poll(run);
  }catch(Throwable error){cancel(run,"install_failed");host.failed(error);}
 }
 private String readyKey(Run<T> run){
  if(!host.valid(run)||!run.gate.ready(host.task(run.fresh),true))return null;
  return host.surfaceKey();
 }
 private void poll(Run<T> run){
  if(current!=run||run.phase!=Phase.WAITING)return;
  if(!host.valid(run)||run.gate.finished()){cancel(run,"new_task_failed");return;}
  String key=readyKey(run);
  if(key==null){scheduler.post(run.retry,32);return;}
  run.phase=Phase.COMMITTING;long attempt=++run.attempt;
  try{host.commitFrame(()->frameCommitted(run,attempt,key));}
  catch(Throwable error){cancel(run,"fit_failed");host.failed(error);}
 }
 private void frameCommitted(Run<T> run,long attempt,String key){
  if(current!=run||run.phase!=Phase.COMMITTING||attempt!=run.attempt)return;
  if(!key.equals(readyKey(run))){run.phase=Phase.WAITING;poll(run);return;}
  finish(run,Phase.COMPLETED);
  host.committed(run);
 }
 void cancel(Run<T> run,String reason){
  if(run==null||current!=run)return;
  // Terminal state precedes resource release, which can synchronously reenter.
  finish(run,Phase.ROLLED_BACK);
  host.rolledBack(run,reason);
 }
 private void finish(Run<T> run,Phase phase){
  run.phase=phase;run.gate.finish();current=null;
  scheduler.remove(run.timeout);scheduler.remove(run.retry);
 }
}
