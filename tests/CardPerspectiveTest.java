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
  for(int density=1;density<=4;density++){
   float[] scaled=CardPerspective.quad(56*density,147*density,PaneLayout.LEFT_RIGHT,density);
   for(int i=0;i<8;i++)check(Math.abs(scaled[i]/density-lr[i])<0.001f,"density must not amplify taper");
   check(Math.abs((scaled[5]-scaled[3])/(147*density)-456f/512f)<0.001f,"inner edge ratio stays 0.890625");
  }
  for(int density=1;density<=4;density++){
   int w=56*density,h=147*density;
   float[] upper=CardPerspective.columnQuad(w,h,density,h*2),lower=CardPerspective.columnQuad(w,h,density,-h);
   check(Math.abs(upper[3]/density-51.45f)<0.01f,"upper card leans down toward horizon");
   check(upper[5]>h&&lower[3]<0,"off-card horizon must not flatten an edge");
   check(Math.abs((h-lower[5])-upper[3])<0.01f,"lower card mirrors upper direction");
   for(float[] q:new float[][]{upper,lower})check(q[5]>q[3],"shared perspective must not invert the inner edge");
  }
  // Four cards matching the reference arrangement must share one vanishing Y.
  float horizon=375;int[] tops={83,233,383,533};float previous=Float.POSITIVE_INFINITY;
  for(int top:tops){
   float[] q=CardPerspective.columnQuad(40,137,1,horizon-top);
   check(q[3]<previous,"top-edge slope varies continuously down the column");previous=q[3];
   float f=(q[5]-q[3])/137f;
   check(Math.abs(top+q[3]/(1-f)-horizon)<0.01f,"all cards share the same screen-space horizon");
  }
  check(CardPerspective.columnQuad(40,137,1,horizon-tops[1])[3]>0,"reference second card slopes down to the right");
  check(CardPerspective.columnQuad(56,147,1,-7)[3]<0,"second card below horizon slopes up rather than flat");
  System.out.println("PASS mild trapezoid quads, shared-horizon direction and density-invariant perspective");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
