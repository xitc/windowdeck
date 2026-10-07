package io.github.xitc.windowdeck;

/** A deliberately narrow compatibility candidate, not video playback detection. */
final class ImmersiveOrientation {
 private static final long ENTER_MS=350;
 private int task=-1;
 private String component;
 private boolean eligible,seenBars,landscape;
 private long hiddenSince=-1;
 void bind(int id,String identity,boolean allow){
  if(task==id&&same(component,identity)&&eligible==allow)return;
  task=id;component=identity;eligible=allow;seenBars=landscape=false;hiddenSince=-1;
 }
 boolean observe(int id,String identity,int visibleTypes,long now){
  if(!eligible||id<0||id!=task||!same(component,identity)||visibleTypes<0)return false;
  boolean before=landscape;
  // WindowInsets.Type.statusBars() == 1, navigationBars() == 2.
  if((visibleTypes&3)!=0){seenBars=true;hiddenSince=-1;landscape=false;}
  else if(seenBars){
   if(hiddenSince<0||now<hiddenSince)hiddenSince=now;
   if(now-hiddenSince>=ENTER_MS)landscape=true;
  }
  return before!=landscape;
 }
 boolean clear(){boolean before=landscape;seenBars=landscape=false;hiddenSince=-1;return before;}
 boolean landscape(){return landscape;}
 private static boolean same(String a,String b){return a==null?b==null:a.equals(b);}
}
