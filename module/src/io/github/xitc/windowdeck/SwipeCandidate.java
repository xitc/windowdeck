package io.github.xitc.windowdeck;

/** Which fullscreen task a prepared snapshot may add. No binder calls. */
final class SwipeCandidate {
 static final class Task {
  final int id,userId,windowMode; final String basePackage,topPackage; final boolean workbench;
  Task(int id,String basePackage,String topPackage,int userId,int windowMode,boolean workbench){
   this.id=id;this.basePackage=basePackage;this.topPackage=topPackage;this.userId=userId;this.windowMode=windowMode;this.workbench=workbench;
  }
  boolean baseCanvas(){return "com.oplus.pscanvas".equals(basePackage);}
  boolean topCanvas(){return "com.oplus.pscanvas".equals(topPackage);}
 }
 static final class Match {
  final int task,container,existingTask;
  Match(int task,int container){this(task,container,-1);}
  Match(int task,int container,int existingTask){this.task=task;this.container=container;this.existingTask=existingTask;}
 }
 private SwipeCandidate(){}
 /**
  * Display is independent of whether the asynchronous task/state snapshot is ready for ingress.
  * A missing source, unknown state, or a retired container may settle during this gesture. Keep
  * the entry available, but never use this result to authorize adding a task (see {@link #match}).
  */
 static boolean showEntry(int source,Task[] tasks,boolean stateKnown,int stateContainer,int stateCount){
  return showEntry(source,tasks,stateKnown,stateContainer,stateCount,null);
 }
 static boolean showEntry(int source,Task[] tasks,boolean stateKnown,int stateContainer,int stateCount,SwipeMembership members){
  if(source<0)return false;
  if(tasks==null)return true;
  for(Task info:tasks){
   if(info==null)continue;
   if(info.id==source){
    if(info.userId!=0||info.baseCanvas()||info.topCanvas()
      ||"com.android.launcher".equals(info.topPackage)||ModulePackages.isModule(info.topPackage))return false;
   }
   if(info.workbench&&info.topCanvas()&&stateKnown&&info.id==stateContainer&&stateCount>=Caps.MAX_TASKS
     &&existing(source,tasks,members,stateContainer)<0)return false;
  }
  return true;
 }
 /**
  * @param stateKnown whether a published container and count are already in memory.
  *        A live container with no published state is refused instead of queried here.
  */
 static Match match(int source,Task[] tasks,boolean stateKnown,int stateContainer,int stateCount){
  return match(source,tasks,stateKnown,stateContainer,stateCount,null);
 }
 static Match match(int source,Task[] tasks,boolean stateKnown,int stateContainer,int stateCount,SwipeMembership members){
  if(source<0||tasks==null)return null;
  Task sourceInfo=null,containerInfo=null;boolean otherCanvas=false;
  for(Task info:tasks){
   if(info==null)continue;
   if(info.id==source)sourceInfo=info;
   if(info.baseCanvas()&&!info.workbench)otherCanvas=true;
   if(info.workbench&&info.topCanvas())containerInfo=info;
  }
  if(containerInfo==null&&otherCanvas)return null;
  if(sourceInfo==null||sourceInfo.basePackage==null||sourceInfo.topPackage==null)return null;
  int live=containerInfo==null?-1:containerInfo.id;
  int existing=live==stateContainer?existing(source,tasks,members,live):-1;
  if(live>=0&&source==live)return null;
  if(!stateKnown){if(live>=0)return null;}
  else if(!IngressPolicy.canEnter(live,stateContainer,stateCount,existing>=0))return null;
  if(!sourceInfo.basePackage.equals(sourceInfo.topPackage))return null;
  if(sourceInfo.userId!=0||(containerInfo!=null&&containerInfo.userId!=0))return null;
  if(sourceInfo.windowMode!=1)return null;
  String pkg=sourceInfo.topPackage;
  if("com.oplus.pscanvas".equals(pkg)||"com.android.launcher".equals(pkg)||ModulePackages.isModule(pkg))return null;
  return new Match(source,live,existing);
 }
 private static int existing(int source,Task[] tasks,SwipeMembership members,int container){
  if(members==null||tasks==null)return -1;
  for(Task task:tasks)if(task!=null&&task.id==source)return members.task(container,task.userId,task.basePackage,task.topPackage);
  return -1;
 }
}
