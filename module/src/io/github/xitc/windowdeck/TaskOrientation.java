package io.github.xitc.windowdeck;

/** Direction for one task. The bound component is the record-side rotation class. */
final class TaskOrientation {
 private int task=-1, fallbackAxis, axis, rotation=-1, previousRotation=-1, requested=-2;
 private String activity, landscapeSource;
 private RotationIdentity identity=RotationIdentity.choose(null,null,null,null,null);
 private int left, top, right, bottom;
 private boolean runtime, settingsKnown, locked, immersiveLandscape;
 private int proposedRotation=-1,userRotation=-1;

 void bind(int taskId,String component,int manifestAxis){
  String base=null;
  if(component!=null){int slash=component.indexOf('/');if(slash>0)base=component.substring(0,slash);}
  bind(taskId,RotationIdentity.choose(component,component,component,component,base),manifestAxis);
 }
 void bind(int taskId,RotationIdentity next,int manifestAxis){
  if(next==null)return;
  boolean keepRect=task==taskId&&next.rotationClass==null&&runtime;
  boolean sameClass=task==taskId&&same(activity,next.rotationClass);
  identity=next;activity=next.rotationClass;
  if(sameClass)return;
  task=taskId;landscapeSource=LandscapeActivities.dynamicSource(next.rotationClass);fallbackAxis=manifestAxis;axis=manifestAxis;
  immersiveLandscape=false;requested=-2;
  if(!keepRect){runtime=false;rotation=previousRotation=-1;left=top=right=bottom=0;}
  else axis=right-left>bottom-top?2:1;
 }
 boolean bound(){return task>=0;}
 boolean bound(int taskId){return taskId>=0&&task==taskId;}
 boolean needsRebind(int taskId,RotationIdentity next){
  if(next==null)return false;
  return task!=taskId||!same(activity,next.rotationClass)||!same(identity.topActivity,next.topActivity)
   ||!same(identity.origActivity,next.origActivity)||!same(identity.realActivity,next.realActivity)
   ||!same(identity.topActivityInfo,next.topActivityInfo)||!same(identity.unavailable,next.unavailable);
 }
 /** Use the callback's identity, never substitute the freshly bound class.
  * Null matches only an unavailable bound class, allowing task-scoped rect fallback. */
 boolean update(int taskId,String component,int l,int t,int r,int b,int actualRotation){
  long w=(long)r-l,h=(long)b-t;
  if(taskId<0||taskId!=task||!same(activity,component)
    ||w<=0||h<=0||w==h||w>Integer.MAX_VALUE||h>Integer.MAX_VALUE)return false;
  int nextRotation=actualRotation>=0&&actualRotation<=3?actualRotation:-1;
  if(runtime&&left==l&&top==t&&right==r&&bottom==b&&rotation==nextRotation)return false;
  if(rotation!=nextRotation){previousRotation=rotation;rotation=nextRotation;}
  left=l;top=t;right=r;bottom=b;axis=w>h?2:1;runtime=true;
  return true;
 }
 boolean request(int taskId,int orientation){
  if(taskId<0||taskId!=task||requested==orientation)return false;
  requested=orientation;return true;
 }
 void immersive(boolean landscape){immersiveLandscape=landscape&&"youtube_dynamic_compat".equals(landscapeSource);}
 int requested(){return requested;}
 boolean hasFixedRequest(){return OrientationPolicy.axis(requested)!=0;}
 void environment(int proposed,boolean known,boolean isLocked,int user){
  proposedRotation=validRotation(proposed);settingsKnown=known;locked=isLocked;userRotation=validRotation(user);
 }
 int effectiveRotation(){return settingsKnown?(locked?userRotation:proposedRotation):-1;}
 private boolean hasDynamicDirection(){return hasLandscapeActivity()&&effectiveRotation()>=0;}
 private static int validRotation(int value){return value>=0&&value<=3?value:-1;}
 int axis(){int requestAxis=OrientationPolicy.axis(requested);int effective=effectiveRotation();return requestAxis!=0?requestAxis:immersiveLandscape?2:hasDynamicDirection()?(effective==1||effective==3?2:1):runtime?axis:fallbackAxis;}
 boolean hasLandscapeActivity(){return landscapeSource!=null;}
 String eligibility(){return landscapeSource==null?"none":landscapeSource;}
 String source(){return hasFixedRequest()?"activity_request":immersiveLandscape?"youtube_immersive_compat":hasDynamicDirection()?(locked?"user_rotation":"proposed_rotation"):runtime?"rom_rect":"manifest";}
 String rotationClass(){return identity.rotationClass;}
 String topActivity(){return identity.topActivity;}
 String origActivity(){return identity.origActivity;}
 String realActivity(){return identity.realActivity;}
 String topActivityInfo(){return identity.topActivityInfo;}
 String unavailable(){return identity.unavailable;}
 String record(){return identity.record();}
 int rotation(){return rotation;}
 int previousRotation(){return previousRotation;}
 boolean hasRuntime(){return runtime;}
 int[] bounds(){return new int[]{left,top,right,bottom};}
 private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
}
