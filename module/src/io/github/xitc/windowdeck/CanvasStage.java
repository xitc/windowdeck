package io.github.xitc.windowdeck;

import android.content.Context;
import android.util.Log;
import android.widget.FrameLayout;

/** Bounded diagnostics for a canvas that requests layout but never traverses it. */
final class CanvasStage extends FrameLayout {
 private String transition;
 private int requests,measures,layouts,frames;
 CanvasStage(Context context){super(context);getViewTreeObserver().addOnPreDrawListener(()->{if(transition!=null)frames++;return true;});}
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
