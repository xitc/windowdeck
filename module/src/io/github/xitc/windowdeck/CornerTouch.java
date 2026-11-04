package io.github.xitc.windowdeck;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Immutable display coordinates captured before Launcher rotates its event copy. */
final class CornerTouch {
 final int rotation,width,height,device,display,action,pointers;
 final long downTime,eventTime;
 final float x,y;
 CornerTouch(int rotation,int width,int height,int device,int display,int action,int pointers,
   long downTime,long eventTime,float x,float y){
  this.rotation=rotation;this.width=width;this.height=height;this.device=device;this.display=display;
  this.action=action;this.pointers=pointers;this.downTime=downTime;this.eventTime=eventTime;this.x=x;this.y=y;
 }
 static boolean supported(int rotation){return rotation==0||rotation==1||rotation==3;}
 boolean valid(){
  return supported(rotation)&&display==0&&width>0&&height>0&&pointers==1&&Float.isFinite(x)&&Float.isFinite(y)
    &&(rotation==0?height>width:width>height);
 }
 boolean matches(int device,int display,int action,int pointers,long downTime,long eventTime){
  return this.device==device&&this.display==display&&this.action==action&&this.pointers==pointers
    &&this.downTime==downTime&&this.eventTime==eventTime;
 }
 boolean sameDisplay(int rotation,int width,int height){return this.rotation==rotation&&this.width==width&&this.height==height;}
 /** C17 may replay its pending copies after the framework input callback returns. */
 static final class History {
  private final ArrayDeque<CornerTouch> frames=new ArrayDeque<>();
  void remember(CornerTouch touch){
   CornerTouch last=frames.peekLast();
   if(last!=null&&(last.downTime!=touch.downTime||last.device!=touch.device||last.display!=touch.display))frames.clear();
   frames.addLast(touch);
   while(frames.size()>256||(!frames.isEmpty()&&touch.eventTime-frames.peekFirst().eventTime>2000))frames.removeFirst();
  }
  CornerTouch find(int device,int display,int action,int pointers,long downTime,long eventTime,long now){
   if(now<eventTime||now-eventTime>2000)return null;
   for(Iterator<CornerTouch> i=frames.descendingIterator();i.hasNext();){
    CornerTouch touch=i.next();if(touch.matches(device,display,action,pointers,downTime,eventTime))return touch;
   }
   return null;
  }
 }
}
