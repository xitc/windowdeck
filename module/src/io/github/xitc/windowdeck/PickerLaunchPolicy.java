package io.github.xitc.windowdeck;

/** Identity and capacity rules for launcher starts during a host-owned picker. */
final class PickerLaunchPolicy {
 static final String EMBED_BEFORE_LAUNCH="embedPickerLaunch";
 /** Ordinary ADD keeps its visible fullscreen launch before the group contracts.
  * Only add-to-side and replacement use the container as the launch destination. */
 static boolean embedBeforeLaunch(boolean replace,boolean originalPlus){return replace||originalPlus;}
 enum Decision { REJECT, ACCEPT, ALREADY_ACCEPTED }
 static Decision session(int container,long instance,long active,long accepted,boolean hanging,int ownerUser,
   int requestedContainer,long requestedInstance,long requestedSession,int requestedUser){
  if(container<0||instance==0||ownerUser<0||requestedUser!=ownerUser||requestedContainer!=container
    ||requestedInstance!=instance||requestedSession<=0)return Decision.REJECT;
  // A queued click may arrive after the first one has closed the picker. It must
  // neither add twice nor fall through to a fullscreen launch.
  if(requestedSession==accepted)return Decision.ALREADY_ACCEPTED;
  return hanging&&requestedSession==active?Decision.ACCEPT:Decision.REJECT;
 }
 static boolean newApp(boolean hanging,int ownerUser,int requestedUser,int count,boolean replace,String pkg,String[] packages){
  if(!hanging||ownerUser<0||ownerUser!=requestedUser||count<1||count>Caps.MAX_TASKS
    ||(!replace&&count==Caps.MAX_TASKS)||pkg==null||pkg.isEmpty()
    ||"io.github.xitc.windowdeck".equals(pkg)||"com.oplus.pscanvas".equals(pkg)||"com.android.launcher".equals(pkg)
    ||packages==null||packages.length!=count)return false;
  for(String existing:packages)if(existing==null||pkg.equals(existing))return false;
  return true;
 }
 private PickerLaunchPolicy(){}
}
