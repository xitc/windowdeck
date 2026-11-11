package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;

/** Bounded diagnostics for a canvas that requests layout but never traverses it. */
final class CanvasStage extends FrameLayout {
 private final SurfaceAperture aperture=new SurfaceAperture();
 private final Matrix addProjection=new Matrix();
 private View projectedAdd;
 private int[] projectedAddBounds;
 PreviewCard mainCard;
 PreviewCard retiringCard;
 final boolean ownsSurfaceAperture=SurfaceApertureHook.install();
 CardSpace cardSpace(){
  android.util.DisplayMetrics dm=getResources().getDisplayMetrics();
  android.view.Display display=getDisplay();
  if(!isAttachedToWindow()||display==null)
   return new CardSpace(0,0,getWidth(),getHeight(),getWidth(),getHeight(),-1,dm.density,dm.widthPixels<=dm.heightPixels);
  int[] origin=new int[2];getLocationOnScreen(origin);
  android.graphics.Point size=new android.graphics.Point();display.getRealSize(size);
  return new CardSpace(origin[0],origin[1],size.x,size.y,getWidth(),getHeight(),display.getRotation(),dm.density,size.x<=size.y);
 }
 void surfaceAperture(PreviewCard card,float[] quad){
  surfaceAperture(card,quad,0,0,0);
 }
 void setAddProjection(View add,int width,int height,float[] quad){
  projectedAdd=null;projectedAddBounds=null;
  if(quad!=null&&CardMotion.projective(quad,width,height)&&
    addProjection.setPolyToPoly(new float[]{0,0,width,0,width,height,0,height},0,quad,0,4)){
   projectedAdd=add;
   projectedAddBounds=CardPerspective.bounds(width,height,quad);
  }
  invalidate();
 }
 int[] chromeBounds(View view){
  return view==projectedAdd?projectedAddBounds:new int[]{0,0,view.getWidth(),view.getHeight()};
 }
 void drawChrome(Canvas canvas,View view){
  int save=canvas.save();
  try{
   if(view==projectedAdd)canvas.concat(addProjection);
   view.draw(canvas);
  }finally{canvas.restoreToCount(save);}
 }
 void surfaceAperture(PreviewCard card,float[] quad,float width,float height,float radius){
  if(java.util.Arrays.equals(card.surfaceAperture,quad)&&card.apertureWidth==width&&card.apertureHeight==height&&card.apertureRadius==radius)return;
  boolean ownershipChanged=(card.surfaceAperture==null)!=(quad==null);
  card.surfaceAperture=quad==null?null:quad.clone();card.apertureWidth=width;card.apertureHeight=height;card.apertureRadius=radius;
  if(ownershipChanged)for(int i=0;i<card.getChildCount();i++)if(card.getChildAt(i) instanceof android.view.SurfaceView)card.getChildAt(i).invalidate();
  invalidate();
 }
 @Override protected void dispatchDraw(Canvas canvas){
  // All holes precede all child chrome, including a neighbouring card's icon.
  for(int i=0;i<getChildCount();i++){
   View child=getChildAt(i);if(!(child instanceof PreviewCard)||child.getVisibility()!=View.VISIBLE)continue;
   PreviewCard card=(PreviewCard)child;
   float sx=card.getScaleX(),sy=card.getScaleY();
   aperture.draw(canvas,card.surfaceAperture,card.apertureWidth,card.apertureHeight,card.apertureRadius,
     card.getX()+card.getPivotX()*(1-sx),card.getY()+card.getPivotY()*(1-sy),sx,sy,card.getAlpha());
  }
  super.dispatchDraw(canvas);
 }
 @Override protected boolean drawChild(Canvas canvas,View child,long drawingTime){
  PreviewCard main=mainCard;
  // Live task surfaces are below the host window: drawing the main View later
  // cannot erase side chrome already painted over its transparent aperture.
  boolean belowMain=main!=null&&indexOfChild(child)<indexOfChild(main);
  PreviewCard retiring=retiringCard;
  boolean belowRetiring=retiring!=null&&child!=main&&child!=retiring;
  boolean project=child==projectedAdd;
  if(!belowMain&&!belowRetiring&&!project)return super.drawChild(canvas,child,drawingTime);
  int save=canvas.save();
  try{
   if(belowMain)clipOutside(canvas,main);
   if(belowRetiring)clipOutside(canvas,retiring);
   if(project){
    // Transform outside the child's RenderNode so the original rectangular
    // bounds do not clip corners that project past the unwarped card.
    float x=child.getX(),y=child.getY();
    canvas.translate(x,y);canvas.concat(addProjection);canvas.translate(-x,-y);
   }
   return super.drawChild(canvas,child,drawingTime);
  }finally{canvas.restoreToCount(save);}
 }
 private void clipOutside(Canvas canvas,PreviewCard card){
  if(card.getVisibility()!=View.VISIBLE||card.getAlpha()<=0)return;
  float sx=card.getScaleX(),sy=card.getScaleY();
  aperture.clipOutside(canvas,card.surfaceAperture,card.apertureWidth,card.apertureHeight,card.apertureRadius,
    card.getX()+card.getPivotX()*(1-sx),card.getY()+card.getPivotY()*(1-sy),sx,sy);
 }
 private String transition;
 private int requests,measures,layouts,frames;
 private long apertureTransforms;
 CanvasStage(Context context){super(context);getViewTreeObserver().addOnPreDrawListener(()->{
  if(transition!=null)frames++;
  // A drag changes child RenderNode properties without rebuilding this display
  // list. Invalidate only when a projected child's placement actually changes.
  long state=1;
  for(int i=0;i<getChildCount();i++){
   View child=getChildAt(i);if(!(child instanceof PreviewCard)||((PreviewCard)child).surfaceAperture==null)continue;
   state=31*state+System.identityHashCode(child);state=31*state+child.getVisibility();
   for(float v:new float[]{child.getX(),child.getY(),child.getScaleX(),child.getScaleY(),child.getPivotX(),child.getPivotY(),child.getAlpha()})state=31*state+Float.floatToIntBits(v);
  }
  if(state!=apertureTransforms){apertureTransforms=state;invalidate();}
  return true;
 });}
 void watch(String name){transition=name;requests=measures=layouts=frames=0;}
 void stopWatching(){if(transition!=null)Log.i("WindowDeck","canvas_layout_trace transition="+transition+" "+trace());transition=null;}
 String trace(){return "requests="+requests+" measures="+measures+" layouts="+layouts+" predraws="+frames+" requested="+isLayoutRequested()+" shown="+isShown()+" attached="+isAttachedToWindow()+" root_requested="+getRootView().isLayoutRequested();}
 @Override public void requestLayout(){
  super.requestLayout();
  if(transition!=null&&++requests<=4)Log.i("WindowDeck","canvas_layout_request transition="+transition+" request="+requests,new Throwable("layout origin"));
 }
 @Override protected void onMeasure(int width,int height){if(transition!=null)measures++;super.onMeasure(width,height);}
 @Override protected void onLayout(boolean changed,int l,int t,int r,int b){if(transition!=null)layouts++;super.onLayout(changed,l,t,r,b);}
}
