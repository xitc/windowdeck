package io.github.xitc.windowdeck;

/** Identity and capacity rules for launcher starts during a host-owned picker. */
final class PickerLaunchPolicy {
 static final String EMBED_BEFORE_LAUNCH="embedPickerLaunch";
 static final String PICKER_SESSIONS="pickerSessions", PICKER_COMPONENTS="pickerComponents";
 static final class Retry {
  final int container,user;
  final long instance,selection;
  final String component;
  private Retry(int container,long instance,long selection,int user,String component){
   this.container=container;this.instance=instance;this.selection=selection;this.user=user;this.component=component;
  }
  int consumedTask(int currentContainer,long currentInstance,int count,int[] tasks,int[] users,long[] selections,String[] components){
   if(currentContainer!=container||currentInstance!=instance||count<1||count>Caps.MAX_TASKS
     ||tasks==null||users==null||selections==null||components==null||tasks.length!=count||users.length!=count
     ||selections.length!=count||components.length!=count)return -1;
   for(int i=0;i<count;i++)
    if(tasks[i]>=0&&users[i]==user&&selections[i]==selection&&component.equals(components[i]))return tasks[i];
   return -1;
  }
 }
 static Retry pickerRetry(boolean hanging,int container,long instance,long selection,int user,String component){
  return hanging&&container>=0&&instance!=0&&selection>0&&user>=0&&component!=null&&!component.isEmpty()
    ?new Retry(container,instance,selection,user,component):null;
 }
 /** Ordinary ADD keeps its visible fullscreen launch before the group contracts.
  * Only add-to-side and replacement use the container as the launch destination. */
 static boolean embedBeforeLaunch(boolean replace,boolean originalPlus){return replace||originalPlus;}
 enum Decision { REJECT, ACCEPT, ALREADY_ACCEPTED }
 static Decision session(int container,long instance,long active,long accepted,boolean hanging,int ownerUser,
   int requestedContainer,long requestedInstance,long requestedSession,int requestedUser){
  if(container<0||instance==0||ownerUser<0||requestedUser<0||requestedContainer!=container
    ||requestedInstance!=instance||requestedSession<=0)return Decision.REJECT;
  // A queued click may arrive after the first one has closed the picker. It must
  // neither add twice nor fall through to a fullscreen launch.
  if(requestedSession==accepted)return Decision.ALREADY_ACCEPTED;
  return hanging&&requestedSession==active?Decision.ACCEPT:Decision.REJECT;
 }
 static boolean newApp(boolean hanging,int ownerUser,int requestedUser,int count,boolean replace,String pkg,String[] packages){
  if(!hanging||ownerUser<0||requestedUser<0||count<1||count>Caps.MAX_TASKS
    ||(!replace&&count==Caps.MAX_TASKS)||pkg==null||pkg.isEmpty()
    ||ModulePackages.isModule(pkg)||"com.oplus.pscanvas".equals(pkg)||"com.android.launcher".equals(pkg)
    ||packages==null||packages.length!=count)return false;
  for(String existing:packages)if(existing==null||pkg.equals(existing))return false;
  return true;
 }
 static boolean newAppForInstance(boolean hanging,int ownerUser,int requestedUser,int count,boolean replace,String pkg,String[] packages,int[] users){
  if(!hanging||ownerUser<0||requestedUser<0||count<1||count>Caps.MAX_TASKS
    ||(!replace&&count==Caps.MAX_TASKS)||pkg==null||pkg.isEmpty()
    ||ModulePackages.isModule(pkg)||"com.oplus.pscanvas".equals(pkg)||"com.android.launcher".equals(pkg)
    ||packages==null||users==null||packages.length!=count||users.length!=count)return false;
  for(int i=0;i<count;i++)if(packages[i]==null||(pkg.equals(packages[i])&&users[i]==requestedUser))return false;
  return true;
 }
 private PickerLaunchPolicy(){}
}
