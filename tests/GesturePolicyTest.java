package dev.windowdeck.app;
public final class GesturePolicyTest {
 public static void main(String[] args){
  for(float density:new float[]{1,2,4}){
   float width=84*density;
   check(!GesturePolicy.dismiss(10*density,0,1500*density,width,density),"short fast tap is not dismissal");
   check(!GesturePolicy.dismiss(-60*density,0,1500*density,width,density),"opposite direction");
   check(!GesturePolicy.dismiss(40*density,50*density,1500*density,width,density),"cross-axis movement");
   check(!GesturePolicy.dismiss(25*density,0,0,width,density),"short drag rebounds");
   check(GesturePolicy.dismiss(40*density,0,0,width,density),"deliberate slow swipe");
   check(GesturePolicy.dismiss(25*density,0,1000*density,width,density),"deliberate fling");
   check(!GesturePolicy.dismiss(25*density,0,-1000*density,width,density),"reversed fling rebounds");
  }
  float prev=0;
  for(int i=0;i<=1000;i++){float value=GesturePolicy.spring(i/1000f);check(value>=prev&&value>=0&&value<=1,"spring stays inside viewport without backwards jump");prev=value;}
  check(prev==1,"settles exactly");
  System.out.println("PASS gesture thresholds at 3 densities and 1001 spring samples");
 }
 static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
}
