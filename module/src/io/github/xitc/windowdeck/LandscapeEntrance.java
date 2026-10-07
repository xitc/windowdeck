package io.github.xitc.windowdeck;

/** One rotated landscape task on the portrait card, in screen coordinates. */
final class LandscapeEntrance {
 static int[] card(int stageW,int stageH,int stageX,int stageY,int unit,int renderW,int renderH){
  if(stageW<2||stageH<2||renderW<1||renderH<1)return null;
  int gap=Math.max(1,unit);
  int sideW=PaneLayout.leftRightSideW(stageW,gap);
  int sideH=PaneLayout.leftRightSideH(stageH,gap);
  int[] size=OrientationPolicy.presentationSize(renderW,renderH,OrientationPolicy.rotatePresentation(renderW,renderH,stageW,stageH));
  CardLayout.Result geometry=CardLayout.compute(stageW,stageH,0,gap,sideW,sideH,PaneLayout.LEFT_RIGHT,0,gap*48,new int[][]{size});
  if(geometry.cards.length==0)return null;
  int[] box=geometry.cards[0];
  return new int[]{stageX+box[0],stageY+box[1],stageX+box[0]+box[2],stageY+box[1]+box[3]};
 }
 private LandscapeEntrance(){}
}
