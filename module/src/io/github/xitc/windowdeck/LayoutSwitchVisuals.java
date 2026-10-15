package io.github.xitc.windowdeck;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Owns only temporary old-rail pixels and a main-card background-blur effect layer. */
final class LayoutSwitchVisuals implements AutoCloseable {
 private final FrameLayout stage,ghosts;
 private final ArrayList<Bitmap> owned=new ArrayList<>();
 private final int oldMode;
 private final float oldExtent;
 private SurfaceControl blur,parent;
 private Method setBlur;
 private boolean blurUnavailable,closed;
 private int radius=LayoutSwitchMotion.BLUR_RADIUS;
 LayoutSwitchVisuals(FrameLayout stage,int oldMode,float oldExtent){
  this.stage=stage;this.oldMode=oldMode;this.oldExtent=oldExtent;
  ghosts=new FrameLayout(stage.getContext());ghosts.setClipChildren(false);ghosts.setClipToPadding(false);
  ghosts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
 }
 void side(Bitmap bitmap,CardMotion.Pose pose,int taskWidth,int taskHeight,int presentedWidth,boolean rotate){
  if(bitmap==null||bitmap.isRecycled())throw new IllegalStateException("Missing side snapshot");
  // getSnapBitMap's ownership is unspecified. Keep a private copy and never recycle its result.
  Bitmap copy=bitmap.copy(Bitmap.Config.ARGB_8888,false);
  if(copy==null)throw new IllegalStateException("Uncopyable side snapshot");
  owned.add(copy);
  RecoveryCover cover=new RecoveryCover(stage.getContext());cover.setBitmap(copy);
  cover.fit(CardMotion.sourceQuad(pose,presentedWidth,rotate),pose.quad,taskWidth,taskHeight,CardMotion.sourceRadius(pose));
  ghosts.addView(cover,new FrameLayout.LayoutParams(stage.getWidth(),stage.getHeight()));
 }
 void chrome(View source,int left,int top){
  if(source==null||source.getVisibility()!=View.VISIBLE||source.getWidth()<1||source.getHeight()<1)return;
  Bitmap bitmap=Bitmap.createBitmap(source.getWidth(),source.getHeight(),Bitmap.Config.ARGB_8888);
  owned.add(bitmap);source.draw(new Canvas(bitmap));
  ImageView copy=new ImageView(stage.getContext());copy.setImageBitmap(bitmap);
  FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(bitmap.getWidth(),bitmap.getHeight());
  lp.leftMargin=left;lp.topMargin=top;ghosts.addView(copy,lp);
 }
 void attach(){
  stage.getOverlay().add(ghosts);
  ghosts.measure(View.MeasureSpec.makeMeasureSpec(stage.getWidth(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(stage.getHeight(),View.MeasureSpec.EXACTLY));
  ghosts.layout(0,0,stage.getWidth(),stage.getHeight());
 }
 void frame(float time){
  float offset=LayoutSwitchMotion.offset(oldExtent,LayoutSwitchMotion.progress(time),false);
  ghosts.setTranslationX(oldMode==PaneLayout.LEFT_RIGHT?offset:0);
  ghosts.setTranslationY(oldMode==PaneLayout.TOP_BOTTOM?offset:0);
  radius=LayoutSwitchMotion.blurRadius(time);
 }
 void fitBlur(SurfaceControl.Transaction transaction,SurfaceControl plate,int width,int height,float corner){
  if(closed||blurUnavailable||plate==null||!plate.isValid())return;
  try{
   if(blur==null){
    if(radius==0)return;
    setBlur=SurfaceControl.Transaction.class.getMethod("setBackgroundBlurRadius",SurfaceControl.class,int.class);
    SurfaceControl.Builder builder=SurfaceControl.Builder.class.getConstructor().newInstance();
    SurfaceControl.Builder.class.getMethod("setName",String.class).invoke(builder,"WindowDeck-LayoutSwitchMainBlur");
    SurfaceControl.Builder.class.getMethod("setParent",SurfaceControl.class).invoke(builder,plate);
    SurfaceControl.Builder.class.getMethod("setEffectLayer").invoke(builder);
    blur=(SurfaceControl)SurfaceControl.Builder.class.getMethod("build").invoke(builder);parent=plate;
    transaction.setLayer(blur,Integer.MAX_VALUE);transaction.setAlpha(blur,0f);transaction.setVisibility(blur,true);
    Log.i("WindowDeck","layout_switch_blur_created radius="+radius);
   }
   if(parent!=plate)throw new IllegalStateException("Main plate replaced during layout switch");
   transaction.setCrop(blur,new Rect(0,0,width,height));
   SurfaceControl.Transaction.class.getMethod("setCornerRadius",SurfaceControl.class,float.class).invoke(transaction,blur,corner);
   setBlur.invoke(transaction,blur,radius);
  }catch(Throwable e){blurUnavailable=true;removeBlur();Log.w("WindowDeck","layout_switch_blur_degraded",e);}
 }
 private void removeBlur(){
  if(blur==null)return;
  try(SurfaceControl.Transaction clear=new SurfaceControl.Transaction()){
   if(blur.isValid()){SurfaceControl.Transaction.class.getMethod("remove",SurfaceControl.class).invoke(clear,blur);clear.apply();}
  }catch(Throwable e){Log.w("WindowDeck","layout_switch_blur_cleanup_failed",e);}
  finally{blur.release();blur=null;parent=null;}
 }
 @Override public void close(){
  if(closed)return;closed=true;stage.getOverlay().remove(ghosts);ghosts.removeAllViews();removeBlur();
  // Detached display lists can still reference their bitmaps. Drop ownership and let GC
  // reclaim them after RenderThread releases the last frame, rather than recycling early.
  owned.clear();
 }
}
