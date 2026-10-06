package io.github.xitc.windowdeck;

/** Only an active picker session may turn a launcher click into a card selection. */
final class ExistingAppPolicy {
 static int task(boolean hanging,int ownerUser,int requestedUser,String pkg,String[] packages,int[] tasks){
  if(!hanging||ownerUser!=requestedUser||pkg==null||packages==null||tasks==null||packages.length!=tasks.length)return -1;
  for(int i=0;i<packages.length;i++)if(pkg.equals(packages[i])&&tasks[i]>=0)return tasks[i];
  return -1;
 }
}
