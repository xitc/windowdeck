package io.github.xitc.windowdeck;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Function;

/** A complete source frame captured before role swaps, cancellation or target layout. */
final class SwitchMotion<T> {
 private final IdentityHashMap<T,CardMotion.Pose> sources=new IdentityHashMap<>();

 static <T> SwitchMotion<T> capture(List<T> cards,Function<T,CardMotion.Pose> read){
  SwitchMotion<T> frame=new SwitchMotion<>();
  for(T card:cards){
   if(card==null||frame.sources.containsKey(card))return null;
   CardMotion.Pose pose=read.apply(card);
   if(pose==null)return null;
   frame.sources.put(card,pose);
  }
  return frame;
 }

 CardMotion.Pose source(T card){return sources.get(card);}
 private SwitchMotion(){}
}
