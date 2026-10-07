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
  final int task,container;
  Match(int task,int container){this.task=task;this.container=container;}
 }
 private SwipeCandidate(){}
 /**
  * @param stateKnown whether a published container and count are already in memory.
  *        A live container with no published state is refused instead of queried here.
  */
 static Match match(int source,Task[] tasks,boolean stateKnown,int stateContainer,int stateCount){
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
  if(live>=0&&source==live)return null;
  if(!stateKnown){if(live>=0)return null;}
  else if(!IngressPolicy.canEnter(live,stateContainer,stateCount))return null;
  if(!sourceInfo.basePackage.equals(sourceInfo.topPackage))return null;
  if(sourceInfo.userId!=0||(containerInfo!=null&&containerInfo.userId!=0))return null;
  if(sourceInfo.windowMode!=1)return null;
  String pkg=sourceInfo.topPackage;
  if("com.oplus.pscanvas".equals(pkg)||"com.android.launcher".equals(pkg)||"io.github.xitc.windowdeck".equals(pkg))return null;
  return new Match(source,live);
 }
}
