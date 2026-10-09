package io.github.xitc.windowdeck;

/** Compatibility is a task membership decision, never a package-wide rotation rule. */
final class VideoWindowPolicy {
 static boolean target(String pkg){return "tv.acfundanmaku.video".equals(pkg)||"com.google.android.youtube".equals(pkg);}
 static boolean applies(String pkg,int task,int user,int scenario,int container,int count,int ownerUser,
                        String[] packages,int[] tasks,boolean hostAlive){
  if(!target(pkg)||task<0||user!=0||ownerUser!=user||scenario!=2||container<0||task==container||count<1||count>Caps.MAX_TASKS||!hostAlive)return false;
  if(packages==null||tasks==null||packages.length!=tasks.length||packages.length!=count)return false;
  for(int i=0;i<tasks.length;i++)if(tasks[i]==task&&pkg.equals(packages[i]))return true;
  return false;
 }
}
