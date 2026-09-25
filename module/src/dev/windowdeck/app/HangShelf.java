package dev.windowdeck.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.List;

/** Slides each live window to the original edge fold, then leaves a 20 dp strip. */
final class HangShelf {
 static final class Card {
  final Bitmap face; final String pkg; final Rect start; final boolean main;
  Card(Bitmap face,String pkg,Rect start,boolean main){this.face=face;this.pkg=pkg;this.start=new Rect(start);this.main=main;}
 }
 private static final int SMALL_DP=20,LARGE_DP=48;
 private final ArrayList<ImageView> views=new ArrayList<>();
 private final ArrayList<WindowManager.LayoutParams> params=new ArrayList<>();
 private final ArrayList<Rect> starts=new ArrayList<>();
 private final ArrayList<Rect> ends=new ArrayList<>();
 private WindowManager windowManager;
 private ValueAnimator anim;
 void show(Context context,List<Card> cards,boolean topBottom,Runnable restore){
  hide();
  if(cards==null||cards.isEmpty())throw new IllegalStateException("没有可挂起的窗口");
  Context app=context.getApplicationContext();
  windowManager=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
  Rect screen=windowManager.getCurrentWindowMetrics().getBounds();
  boolean portrait=screen.height()>screen.width();
  int small=Ui.dp(app,SMALL_DP),large=Ui.dp(app,LARGE_DP);
  Rect[] targets=ends(cards,screen,topBottom,portrait,small,large);
  PackageManager pm=app.getPackageManager();
  int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
  for(int i=0;i<cards.size();i++){
   Card card=cards.get(i);
   ImageView view=new ImageView(app);
   view.setScaleType(card.face==null?ImageView.ScaleType.CENTER_INSIDE:ImageView.ScaleType.CENTER_CROP);
   view.setBackgroundColor(0xff202838);
   if(card.face!=null)view.setImageBitmap(card.face);
   else try{view.setImageDrawable(pm.getApplicationIcon(card.pkg));}catch(Throwable ignored){}
   Ui.round(view,Ui.dp(app,card.main?18:10));
   view.setContentDescription(card.main?"主窗口已挂起，点按恢复":"侧窗口已挂起，点按恢复");
   view.setOnClickListener(v->restore.run());
   WindowManager.LayoutParams lp=new WindowManager.LayoutParams(card.start.width(),card.start.height(),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,flags,PixelFormat.TRANSLUCENT);
   lp.gravity=Gravity.TOP|Gravity.LEFT;
   lp.x=card.start.left;lp.y=card.start.top;
   lp.setTitle(card.main?"WindowDeckHangMain":"WindowDeckHangSide");
   lp.setFitInsetsTypes(0);
   windowManager.addView(view,lp);
   views.add(view);params.add(lp);starts.add(new Rect(card.start));ends.add(targets[i]);
  }
  anim=ValueAnimator.ofFloat(0f,1f);
  anim.setDuration(600);
  anim.setInterpolator(HangShelf::settle);
  anim.addUpdateListener(a->{
   if(anim!=a)return;
   float f=(Float)a.getAnimatedValue();
   for(int i=0;i<views.size();i++){
    Rect from=starts.get(i),to=ends.get(i);WindowManager.LayoutParams lp=params.get(i);
    lp.x=from.left+Math.round((to.left-from.left)*f);
    lp.y=from.top+Math.round((to.top-from.top)*f);
    try{windowManager.updateViewLayout(views.get(i),lp);}catch(Throwable ignored){}
   }
  });
  anim.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){if(anim==animation)anim=null;}});
  anim.start();
 }
 void hide(){
  ValueAnimator current=anim;anim=null;
  if(current!=null)current.cancel();
  if(windowManager!=null)for(ImageView view:views)try{windowManager.removeViewImmediate(view);}catch(Throwable ignored){}
  views.clear();params.clear();starts.clear();ends.clear();
 }
 static float settle(float t){
  if(t<=0f)return 0f;
  if(t>=1f)return 1f;
  return 1f-(float)(Math.exp(-5.5*t)*Math.cos(t*Math.PI*1.5));
 }
 static Rect[] ends(List<Card> cards,Rect screen,boolean topBottom,boolean portrait,int small,int large){
  Rect[] out=new Rect[cards.size()];
  Rect group=null;
  for(Card card:cards){
   if(card.main)continue;
   if(group==null)group=new Rect(card.start);else group.union(card.start);
  }
  int dx=0,dy=0;
  if(group!=null){
   if(!topBottom){dx=small-group.right;dy=(screen.height()-group.height())/2-group.top;}
   else{dx=(screen.width()-group.width())/2-group.left;dy=large-group.bottom;}
  }
  for(int i=0;i<cards.size();i++){
   Card card=cards.get(i);
   Rect end=new Rect(card.start);
   if(card.main){
    if(portrait){end.offsetTo(screen.right-small,(screen.height()-card.start.height())/2);}
    else{end.offsetTo((screen.width()-card.start.width())/2,screen.bottom-small);}
   }else end.offset(dx,dy);
   out[i]=end;
  }
  return out;
 }
}
