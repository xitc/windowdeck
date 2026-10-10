package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Matrix;
import android.util.Log;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;

/** Sample the complete ROM entering animation in buffer space.
  * A wide video SurfaceView rect and a pre-rotation screenshot are separate
  * inputs; neither is inferred from the card or captured after the request. */
final class NativeTaskRotation {
 final int width,height;
 final long duration;
 final String source;
 private final int turn;
 private final Animation enter;
 private final Transformation frame=new Transformation();
 NativeTaskRotation(Context host,int turn,int width,int height,int oldWidth,int oldHeight){
  this.turn=turn;this.width=width;this.height=height;
  Animation loaded=null;String selected="rigid_fallback";
  try{
   loaded=load(host,turn==1?"oplus_screen_rotate_minus_90_enter":"oplus_screen_rotate_plus_90_enter",width,height,oldWidth,oldHeight);
   selected="systemui:"+(turn==1?"oplus_screen_rotate_minus_90_enter":"oplus_screen_rotate_plus_90_enter");
  }catch(Exception e){Log.w("WindowDeck","task_rotation_resource_unavailable",e);loaded=null;}
  enter=loaded;source=selected;
  duration=enter==null?RotationMotion.DURATION_MS:Math.max(1,enter.computeDurationHint());
 }
 private static Animation load(Context host,String name,int width,int height,int parentWidth,int parentHeight) throws Exception {
  Context system=host.createPackageContext("com.android.systemui",0);
  int id=system.getResources().getIdentifier(name,"anim","com.android.systemui");
  if(id==0)throw new IllegalStateException("missing ROM animation "+name);
  Animation loaded=AnimationUtils.loadAnimation(system,id);
  loaded.initialize(width,height,parentWidth,parentHeight);
  loaded.restrictDuration(1500);
  loaded.setStartTime(0);
  return loaded;
 }
 private Matrix matrix(Animation animation,float fraction,boolean identityAtEnd){
  float f=Math.max(0,Math.min(1,fraction));
  Matrix result=new Matrix();
  if(animation==null||(identityAtEnd&&f==1))return result;
  frame.clear();animation.getTransformation(Math.round(f*duration),frame);result.set(frame.getMatrix());
  return result;
 }
 float[] target(float[] sourceQuad,float[] finalTarget,float fraction){
  if(enter==null)return RotationMotion.enterFrame(sourceQuad,width/2f,height/2f,turn,fraction,finalTarget);
  Matrix fit=new Matrix();
  if(!fit.setPolyToPoly(sourceQuad,0,finalTarget,0,4))throw new IllegalArgumentException("invalid rotation fit");
  Matrix combined=new Matrix();combined.setConcat(fit,matrix(enter,fraction,true));
  float[] target=sourceQuad.clone();combined.mapPoints(target);return target;
 }
 float[] sampledMatrix(float fraction){
  Matrix sampled;
  if(enter!=null)sampled=matrix(enter,fraction,true);
  else{
   sampled=new Matrix();sampled.setRotate(RotationMotion.enterDegrees(turn,RotationMotion.rotateProgress(fraction)),width/2f,height/2f);
  }
  float[] values=new float[9];sampled.getValues(values);return values;
 }
}
