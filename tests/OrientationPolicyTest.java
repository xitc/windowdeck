package dev.windowdeck.app;
public final class OrientationPolicyTest {
 public static void main(String[] args){
  for(int x:new int[]{0,6,8,11})check(OrientationPolicy.axis(x)==2,"landscape variants");
  for(int x:new int[]{1,7,9,12})check(OrientationPolicy.axis(x)==1,"portrait variants");
  for(int x:new int[]{-1,2,3,4,5,10,13,14})check(OrientationPolicy.axis(x)==0,"unspecified/flexible is not forced");
  check(OrientationPolicy.rotatePresentation(3168,1440,1440,3168),"landscape rotates clockwise on portrait display");
  check(!OrientationPolicy.rotatePresentation(3168,1440,3168,1440),"landscape display removes extra rotation");
  check(!OrientationPolicy.rotatePresentation(1440,3168,1440,3168),"portrait app stays upright");
  check(!OrientationPolicy.rotatePresentation(0,0,1440,3168),"pending surface stays upright");
  int[] presented=OrientationPolicy.presentationSize(3168,1440,true);
  check(presented[0]==1440&&presented[1]==3168,"rotated presentation swaps axes only");
  for(int[] base:new int[][]{{1440,4130},{1440,2100},{3008,1280},{360,400}}){
   for(int[] display:new int[][]{{1440,3168},{3168,1440}}){
    int[] land=OrientationPolicy.bounds(base[0],base[1],display[0],display[1],2);
    int[] port=OrientationPolicy.bounds(base[0],base[1],display[0],display[1],1);
    int[] auto=OrientationPolicy.bounds(base[0],base[1],display[0],display[1],0);
    check(land[0]==3168&&land[1]==1440,"landscape independent of pane or device rotation");
    check(port[0]==1440&&port[1]==3168,"portrait stays independent");
    check(auto[0]==base[0]&&auto[1]==base[1],"flexible follows pane");
   }
  }
  System.out.println("PASS orientation variants and independent render bounds");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
