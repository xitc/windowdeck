package io.github.xitc.windowdeck;

/** Rotation class from TaskInfo record fields. realActivity is logged and never chosen. */
final class RotationIdentity {
 final String topActivity,origActivity,realActivity,topActivityInfo,rotationClass,unavailable;

 private RotationIdentity(String top,String orig,String real,String info,String chosen,String unavailable){
  topActivity=top;origActivity=orig;realActivity=real;topActivityInfo=info;rotationClass=chosen;this.unavailable=unavailable;
 }
 /** Priority is topActivityInfo, then origActivity, then topActivity. An empty top still yields unavailable. */
 static RotationIdentity choose(String top,String orig,String real,String info,String basePackage){
  String chosen=usable(info);
  if(chosen==null)chosen=usable(orig);
  if(chosen==null)chosen=usable(top);
  String reason=null;
  if(usable(top)==null)reason="empty_component";
  else if(basePackage!=null&&!basePackage.equals(packageName(top)))reason="cross_package";
  if(chosen==null&&reason==null)reason="empty_component";
  return new RotationIdentity(top,orig,real,info,chosen,reason);
 }
 /** The raw class stays. Only the manifest axis is missing. */
 RotationIdentity nameNotFound(){
  if(rotationClass==null||unavailable!=null)return this;
  return new RotationIdentity(topActivity,origActivity,realActivity,topActivityInfo,rotationClass,"name_not_found");
 }
 boolean differs(){return !same(topActivity,origActivity)||!same(origActivity,realActivity)||!same(realActivity,topActivityInfo);}
 String record(){
  return "rotation_activity="+(unavailable==null?String.valueOf(rotationClass):"unavailable")
   +(unavailable==null?"":" reason="+unavailable)
   +" topActivity="+topActivity+" origActivity="+origActivity+" realActivity="+realActivity
   +" topActivityInfo="+topActivityInfo+" chosen="+rotationClass+" differ="+differs();
 }
 static String usable(String value){
  if(value==null)return null;
  int slash=value.indexOf('/');
  if(slash<=0||slash>=value.length()-1)return null;
  String cls=value.substring(slash+1);
  if(cls.isEmpty()||".".equals(cls))return null;
  return value;
 }
 private static String packageName(String value){
  String ok=usable(value);
  return ok==null?"":ok.substring(0,ok.indexOf('/'));
 }
 private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
}
