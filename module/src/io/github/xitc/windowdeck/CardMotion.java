package io.github.xitc.windowdeck;

/** Immutable presentation in stage coordinates; crop is in the presented buffer. */
final class CardMotion {
 static final class Pose {
  final float[] box,quad,crop;
  final float radius;
  Pose(float[] box,float[] quad,float[] crop,float radius){
   this.box=box.clone();this.quad=quad.clone();this.crop=crop.clone();this.radius=radius;
  }
 }
 static Pose at(float[] box,float[] localQuad,float[] crop,float radius){
  float[] quad=localQuad.clone();
  for(int i=0;i<8;i+=2){quad[i]+=box[0];quad[i+1]+=box[1];}
  return new Pose(box,quad,crop,radius);
 }
 static Pose between(Pose from,Pose to,float fraction){
  float f=Math.max(0,Math.min(1,fraction));
  return new Pose(blend(from.box,to.box,f),blend(from.quad,to.quad,f),
   blend(from.crop,to.crop,f),from.radius+(to.radius-from.radius)*f);
 }
 private static float[] blend(float[] from,float[] to,float f){
  float[] out=new float[from.length];for(int i=0;i<out.length;i++)out[i]=from[i]+(to[i]-from[i])*f;return out;
 }
 /** Retain the current shape when an interrupted View has already moved or scaled. */
 static Pose rebox(Pose pose,float[] box){
  float[] quad=localQuad(pose,box[2],box[3]);
  return at(box,quad,pose.crop,pose.radius*Math.min(box[2]/pose.box[2],box[3]/pose.box[3]));
 }
 static float[] localQuad(Pose pose,float width,float height){
  float[] out=new float[8];
  for(int i=0;i<8;i+=2){out[i]=(pose.quad[i]-pose.box[0])*width/pose.box[2];out[i+1]=(pose.quad[i+1]-pose.box[1])*height/pose.box[3];}
  return out;
 }
 static float[] sourceQuad(Pose pose,int presentedWidth,boolean rotate){
  float x=pose.crop[0],y=pose.crop[1],r=x+pose.crop[2],b=y+pose.crop[3];
  return rotate?new float[]{y,presentedWidth-x,y,presentedWidth-r,b,presentedWidth-r,b,presentedWidth-x}:new float[]{x,y,r,y,r,b,x,b};
 }
 static float sourceRadius(Pose pose){
  return pose.radius/Math.max(0.0001f,Math.min(pose.box[2]/pose.crop[2],pose.box[3]/pose.crop[3]));
 }
 static boolean projective(float[] quad,float w,float h){
  float[] rect={0,0,w,0,w,h,0,h};
  for(int i=0;i<8;i++)if(Math.abs(quad[i]-rect[i])>0.01f)return true;
  return false;
 }
 private CardMotion(){}
}
