package dev.windowdeck.app;

import java.util.Collections;
import java.util.List;

/** Keeps every uninvolved side card in its current rail position. */
final class CardOrder {
 static <T> void promote(List<T> cards,int primary,int selected){
  Collections.swap(cards,primary,selected);
 }
}
