package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Exactly one moving representation, cropped from the release frame; live tasks stay below. */
final class SnapshotMotionView extends View {
 private final Bitmap release;
 private Bitmap sourceFrame;
 private final RectF source=new RectF(),target=new RectF(),current=new RectF();
 private final Matrix displayMap=new Matrix(),motionMap=new Matrix();
 private float fromRadius,toRadius;
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
 private final Path outline=new Path();
 private Drawable wallpaper;
 private final int width,height;
 private int turns;
 private float progress,backgroundProgress;private boolean moving;
 private final float[] sweepCapture=new float[4],sweepTarget=new float[4];
 private boolean sweep,holdBleed,releasePainted;
 private int stageW,stageH;
 private boolean stageLocked;
 private Runnable releaseListener;
 SnapshotMotionView(Context context,Bitmap release,int width,int height){
  super(context);this.release=release;this.width=width;this.height=height;
 }
 void rebase(int turns){if(stageLocked)return;this.turns=turns;invalidate();}
 /** One portrait coordinate system for the whole sweep. Later axis swaps do not rebase. */
 void lockStage(int turns,int stageW,int stageH){
  this.turns=turns;this.stageW=stageW;this.stageH=stageH;stageLocked=stageW>0&&stageH>0;invalidate();
 }
 int sweepTurns(){return turns;}
 void sourceFrame(Bitmap frame){sourceFrame=frame;invalidate();}
 void holdBleed(){holdBleed=true;invalidate();}
 /** Fires once the release bitmap is in this frame, not when only chrome was painted. */
 void whenReleasePainted(Runnable listener){releaseListener=listener;if(releasePainted&&listener!=null)post(listener);}
 private void noteRelease(){if(releasePainted)return;releasePainted=true;if(releaseListener!=null)post(releaseListener);}
 void armSweep(RectF capture,RectF target,float fromRadius,float toRadius){
  sweepCapture[0]=capture.left;sweepCapture[1]=capture.top;sweepCapture[2]=capture.right;sweepCapture[3]=capture.bottom;
  sweepTarget[0]=target.left;sweepTarget[1]=target.top;sweepTarget[2]=target.right;sweepTarget[3]=target.bottom;
  this.fromRadius=fromRadius;this.toRadius=toRadius;sweep=true;invalidate();
 }
 void retarget(RectF target,float radius){
  sweepTarget[0]=target.left;sweepTarget[1]=target.top;sweepTarget[2]=target.right;sweepTarget[3]=target.bottom;
  toRadius=radius;invalidate();
 }
 void configure(RectF source,float fromRadius,RectF target,float toRadius,Drawable wallpaper){
  this.source.set(source);this.target.set(target);
  this.fromRadius=fromRadius;this.toRadius=toRadius;this.wallpaper=wallpaper;
  progress=0;backgroundProgress=0;moving=true;invalidate();
 }
 void configureSweep(RectF capture,float fromRadius,RectF target,float toRadius,Drawable wallpaper){
  armSweep(capture,target,fromRadius,toRadius);
  this.fromRadius=fromRadius;this.toRadius=toRadius;
  if(wallpaper!=null){this.wallpaper=wallpaper;backgroundProgress=0;}
  progress=0;moving=true;invalidate();
 }
 void wallpaper(Drawable value){wallpaper=value;invalidate();}
 void progress(float value,float backgroundValue){progress=value;backgroundProgress=backgroundValue;invalidate();}
 /** Screen-space card corners, LT RT RB LB. The stage matrix is already the one this view draws with. */
 float[] sampleCorners(){
  if(!sweep||getWidth()<1||getHeight()<1)return null;
  int spaceW=stageLocked?stageW:getWidth(),spaceH=stageLocked?stageH:getHeight();
  float[] quad=DisplayGeometry.sweep(sweepCapture,sweepTarget,width,height,spaceW,spaceH,turns,progress);
  if(quad==null)return null;
  float[] stage=new float[]{1,0,0,0,1,0,0,0,1};
  if(stageLocked&&!DisplayGeometry.stageMatrix(stage,turns,getWidth(),getHeight(),stageW,stageH))return null;
  int[] loc=new int[2];getLocationOnScreen(loc);
  float[] out=new float[8];
  for(int i=0;i<8;i+=2){
   float x=quad[i],y=quad[i+1];
   out[i]=stage[0]*x+stage[1]*y+stage[2]+loc[0];
   out[i+1]=stage[3]*x+stage[4]*y+stage[5]+loc[1];
  }
  return out;
 }
 /** Capture-space crop of the bitmap this sweep maps onto those corners. */
 float[] sampleStageCorners(){
  if(!sweep||!stageLocked)return null;
  return DisplayGeometry.sweep(sweepCapture,sweepTarget,width,height,stageW,stageH,turns,progress);
 }
 int[] sampleStage(){return stageLocked?new int[]{stageW,stageH,turns}:null;}
 float[] sampleCrop(){return new float[]{sweepCapture[0],sweepCapture[1],sweepCapture[2],sweepCapture[3]};}
 float sampleRadius(){return MotionSpec.lerp(fromRadius,toRadius,progress);}
 @Override protected void onDraw(Canvas canvas){
  // Rotate the release buffer into the current logical display before cropping or moving it.
  // Scaling the old landscape buffer straight into a portrait rectangle stretches the game.
  int spaceW=stageLocked?stageW:getWidth(),spaceH=stageLocked?stageH:getHeight();
  if(spaceW<1||spaceH<1)return;
  int stageSave=canvas.save();
  try{
   if(stageLocked){
    float[] stage=new float[9];
    if(!DisplayGeometry.stageMatrix(stage,turns,getWidth(),getHeight(),stageW,stageH))return;
    Matrix viewMap=new Matrix();viewMap.setValues(stage);canvas.concat(viewMap);
   }
   float[] pixels={0,0,release.getWidth(),0,release.getWidth(),release.getHeight(),0,release.getHeight()};
   float[] mapped=new float[8];float[] corners={0,0,width,0,width,height,0,height};
   int rotatedW=(turns&1)==0?width:height,rotatedH=(turns&1)==0?height:width;
   for(int i=0;i<8;i+=2){float[] p=DisplayGeometry.point(corners[i],corners[i+1],width,height,turns);mapped[i]=p[0]*(float)spaceW/rotatedW;mapped[i+1]=p[1]*(float)spaceH/rotatedH;}
   displayMap.setPolyToPoly(pixels,0,mapped,0,4);
   if(!moving){
    if(sweep){drawSweep(canvas,0);return;}
    // A static hang-add cover must keep its release pixels while the display
    // changes axis. displayMap already applies that quarter-turn without stretch.
    // Replacing the snapshot with chrome exposes a full gray frame before live.
    if(holdBleed){canvas.drawColor(Ui.CHROME);return;}
    paint.setAlpha(255);canvas.drawBitmap(release,displayMap,paint);noteRelease();return;
   }
   if(sweep){drawSweep(canvas,progress);return;}
  // With no prepared wallpaper, the caller only permits expansion containing the
  // entire old task. Preserve the release background until the host supplies it.
  if(wallpaper==null){paint.setAlpha(255);canvas.drawBitmap(release,displayMap,paint);}
  else canvas.drawColor(Ui.CHROME);
  if(wallpaper!=null){wallpaper.setBounds(0,0,getWidth(),getHeight());wallpaper.draw(canvas);}
  // Never leave a frozen copy of the source task in the fading desktop background.
  int saved=canvas.save();canvas.clipOutRect(source);paint.setAlpha(Math.round(255*(1-backgroundProgress)));
  canvas.drawBitmap(release,displayMap,paint);canvas.restoreToCount(saved);
  current.set(MotionSpec.lerp(source.left,target.left,progress),MotionSpec.lerp(source.top,target.top,progress),
   MotionSpec.lerp(source.right,target.right,progress),MotionSpec.lerp(source.bottom,target.bottom,progress));
  float radius=MotionSpec.lerp(fromRadius,toRadius,progress);
  outline.reset();outline.addRoundRect(current,radius,radius,Path.Direction.CW);
  saved=canvas.save();canvas.clipPath(outline);paint.setAlpha(255);
  motionMap.setRectToRect(source,current,Matrix.ScaleToFit.FILL);motionMap.preConcat(displayMap);
  canvas.drawBitmap(release,motionMap,paint);canvas.restoreToCount(saved);noteRelease();
  }finally{canvas.restoreToCount(stageSave);}
 }
 private void drawSweep(Canvas canvas,float amount){
  int spaceW=stageLocked?stageW:Math.max(1,getWidth()),spaceH=stageLocked?stageH:Math.max(1,getHeight());
  // The release frame already contains the gesture blur. Chrome here is the full-screen
  // gray cut. Wallpaper replaces that frame only after the workbench backdrop arrives.
  if(release==null||release.isRecycled())canvas.drawColor(Ui.CHROME);
  else{paint.setAlpha(255);canvas.drawBitmap(release,displayMap,paint);}
  float[] quad=DisplayGeometry.sweep(sweepCapture,sweepTarget,width,height,spaceW,spaceH,turns,amount);
  if(quad==null)return;
  if(wallpaper!=null){
   wallpaper.setAlpha(Math.round(255*Math.max(0f,Math.min(1f,backgroundProgress))));
   wallpaper.setBounds(0,0,spaceW,spaceH);wallpaper.draw(canvas);
  }
  // Quarter-turns permute the corners; both ends and every interpolated frame
  // are axis-aligned rectangles. Use the same screen-space rounding as live.
  float[] box=DisplayGeometry.quadBounds(quad);
  current.set(box[0],box[1],box[2],box[3]);
  float radius=MotionSpec.lerp(fromRadius,toRadius,amount);
  outline.reset();outline.addRoundRect(current,radius,radius,Path.Direction.CW);
  int saved=canvas.save();canvas.clipPath(outline);paint.setAlpha(255);
  Bitmap content=sourceFrame==null?release:sourceFrame;
  float[] src=sourceFrame==null?new float[]{sweepCapture[0],sweepCapture[1],sweepCapture[2],sweepCapture[1],sweepCapture[2],sweepCapture[3],sweepCapture[0],sweepCapture[3]}
   :new float[]{0,0,content.getWidth(),0,content.getWidth(),content.getHeight(),0,content.getHeight()};
  motionMap.setPolyToPoly(src,0,quad,0,4);
  canvas.drawBitmap(content,motionMap,paint);canvas.restoreToCount(saved);noteRelease();
 }
}
