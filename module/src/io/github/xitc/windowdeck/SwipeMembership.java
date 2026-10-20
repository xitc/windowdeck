package io.github.xitc.windowdeck;

/** Host-published membership is only a hint; the host rechecks live tasks on receipt. */
final class SwipeMembership {
 final int container,user;
 private final String[] packages;
 private final int[] tasks;
 SwipeMembership(int container,int user,String[] packages,int[] tasks){
  this.container=container;this.user=user;
  this.packages=packages==null?null:packages.clone();this.tasks=tasks==null?null:tasks.clone();
 }
 int task(int expectedContainer,int sourceUser,String base,String top){
  if(container<0||container!=expectedContainer||user!=0||user!=sourceUser||base==null||!base.equals(top)
    ||packages==null||tasks==null||packages.length!=tasks.length)return -1;
  for(int i=0;i<packages.length;i++)if(base.equals(packages[i])&&tasks[i]>=0)return tasks[i];
  return -1;
 }
}
