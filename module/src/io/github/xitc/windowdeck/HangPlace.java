package io.github.xitc.windowdeck;

import java.util.List;

/** Where a desktop hang-add lands. Swipe and replace do not take the original-plus branch. */
final class HangPlace {
 /** {@code MainActivity.getPreferences} stores this file name, the activity's local class name. */
 static final String PREF_FILE="MainActivity";
 static final String PREF="original_plus";
 static final boolean DEFAULT=false;

 static <T> int place(List<T> cards,int primary,T fresh,boolean replace,boolean hang,boolean originalPlus){
  if(replace){cards.set(primary,fresh);return primary;}
  if(hang&&!cards.isEmpty()){
   if(originalPlus){cards.add(fresh);return primary;}
   T oldMain=cards.remove(primary);cards.add(0,fresh);cards.add(oldMain);return 0;
  }
  cards.add(fresh);return cards.size()-1;
 }

 /** Card that returns from the main shelf edge, or -1 when this arrival has none. */
 static int shelfMain(int count,int primary,boolean hungMain,boolean originalPlus){
  if(!hungMain||count<2)return -1;
  if(originalPlus)return primary>=0&&primary<count?primary:-1;
  return count-1;
 }

 private HangPlace(){}
}
