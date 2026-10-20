package io.github.xitc.windowdeck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Transactional side-only order. The live list stays untouched until recovery commits. */
final class SideSortOrder<T> {
 private final ArrayList<T> original,preview;
 private final int primary;
 private boolean closed;
 SideSortOrder(List<T> slots,int primary){
  if(primary<0||primary>=slots.size()||slots.contains(null)||new HashSet<>(slots).size()!=slots.size())
   throw new IllegalArgumentException("Invalid task set");
  original=new ArrayList<>(slots);preview=new ArrayList<>(slots);this.primary=primary;
 }
 List<T> preview(){return Collections.unmodifiableList(preview);}
 int index(T slot){return preview.indexOf(slot);}
 int neighbor(T slot,int direction){
  int i=index(slot);if(i<0||i==primary||closed||Math.abs(direction)!=1)return -1;
  int next=i+direction;if(next==primary)next+=direction;
  return next>=0&&next<preview.size()?next:-1;
 }
 boolean step(T slot,int direction){
  int next=neighbor(slot,direction);if(next<0)return false;
  Collections.swap(preview,index(slot),next);return true;
 }
 boolean valid(List<T> live,int currentPrimary){return !closed&&primary==currentPrimary&&original.equals(live);}
 boolean commit(List<T> live,int currentPrimary){
  if(!valid(live,currentPrimary)){closed=true;return false;}
  for(int i=0;i<preview.size();i++)if(i!=primary)live.set(i,preview.get(i));
  closed=true;return true;
 }
 /** Swept unscaled rectangle permits fast motion across multiple adjacent targets. */
 static boolean crosses(float position,float size,float home,float candidate,float candidateSize,int direction){
  if(Math.abs(position-home)<size*.5f)return false;
  float middle=candidate+(int)candidateSize/2;
  if(direction>0)return position>home&&position+size>middle&&home<candidate+candidateSize;
  return position<home&&position<middle&&home+size>candidate;
 }
 void abort(){closed=true;}
}
