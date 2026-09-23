package dev.windowdeck.app;
public final class CardPerspectiveTest {
 public static void main(String[] args){
  float[] tb=CardPerspective.quad(74,106,PaneLayout.TOP_BOTTOM);
  check(tb[0]==0&&tb[1]==0&&tb[2]==74&&tb[3]==0,"top-bottom keeps a full top edge");
  check(tb[5]==106&&tb[7]==106,"top-bottom shortens the bottom edge");
  check(Math.abs(tb[6]-(74-tb[4]))<0.01f,"top-bottom bottom edge stays centered");
  check(tb[6]>0&&tb[4]<74&&tb[6]<3,"top-bottom taper is a few dp, not a wedge");
  float[] lr=CardPerspective.quad(56,147,PaneLayout.LEFT_RIGHT);
  check(lr[0]==0&&lr[6]==0&&lr[7]==147,"left-right keeps a full outer edge");
  check(lr[2]==56&&lr[4]==56,"left-right shortens the inner edge");
  check(Math.abs(lr[3]-(147-lr[5]))<0.01f,"left-right inner edge stays centered");
  check(lr[3]>6&&lr[3]<10,"left-right inner taper is about 8 dp on a 56x147 plate");
  float[] copy=CardPerspective.quad(74,106,PaneLayout.TOP_BOTTOM);
  check(copy[6]==tb[6]&&copy[4]==tb[4],"quad does not depend on screen position");
  System.out.println("PASS mild trapezoid quads");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
