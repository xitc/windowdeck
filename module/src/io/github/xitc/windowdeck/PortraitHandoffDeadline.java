package io.github.xitc.windowdeck;

/** Source/bootstrap, host readiness and presentation are separate bounded phases. */
final class PortraitHandoffDeadline {
 private final int task;
 private final long started,budget,limit;
 private long readyDeadline,reportedDeadline;
 private int container=-1;
 private boolean hostStarted,live,cancelled;
 PortraitHandoffDeadline(int task,long started,long budget){
  if(task<0||started<0||budget<=0||budget>Long.MAX_VALUE/2||started>Long.MAX_VALUE-2*budget)throw new IllegalArgumentException("deadline");
  this.task=task;this.started=started;this.budget=budget;limit=started+2*budget;readyDeadline=started+budget;
 }
 long limit(){return limit;}
 long deadline(){return live?limit:readyDeadline;}
 boolean matches(int task,int container){return !cancelled&&task==this.task&&container>=0&&(this.container<0||container==this.container);}
 boolean hostStarted(int task,int container,long hostDeadline,long now){
  if(!matches(task,container)||now<started||expired(now))return false;
  if(hostStarted)return reportedDeadline==hostDeadline;
  if(live)return true; // A valid READY can overtake the bootstrap parcel; it never restarts a wait.
  if(hostDeadline<=now||hostDeadline-now>budget)return false;
  this.container=container;hostStarted=true;reportedDeadline=hostDeadline;
  readyDeadline=Math.min(hostDeadline,limit);return true;
 }
 boolean liveReady(int task,int container,long now){
  if(!matches(task,container)||now<started||expired(now))return false;
  this.container=container;live=true;return true;
 }
 boolean expired(long now){return cancelled||now>=deadline();}
 String phase(){return cancelled?"cancelled":live?"presentation":hostStarted?"host_content":"host_start";}
 void cancel(){cancelled=true;}
}
