package io.github.xitc.windowdeck;

/** Fits visible cards without feeding their aspect ratios back into task rendering. */
final class CardLayout {
 static final int MAIN_MIN_PERCENT=80,MAIN_DEFAULT_PERCENT=100,MAIN_MAX_PERCENT=130;
 static int mainPercent(int percent){return Math.max(MAIN_MIN_PERCENT,Math.min(MAIN_MAX_PERCENT,percent));}
 static final class Result {
  final int[][] cards;
  final int[] add;
  Result(int[][] cards,int[] add){this.cards=cards;this.add=add;}
 }
 static Result compute(int w,int h,int primary,int gap,int sideW,int sideH,int mode,
                       int badge,int minTouch,int[][] renderSizes){
  int count=renderSizes.length;
  int[][] cards=PaneLayout.compute(w,h,count,primary,gap,sideW,sideH,mode);
  int[] add=PaneLayout.addBox(w,h,count,gap,sideW,sideH,mode);
  int p=Math.max(0,Math.min(primary,count-1));
  for(int i=0;i<count;i++){
   int[] box=cards[i],size=renderSizes[i];
   if(size[0]>0&&size[1]>0&&i==p)contain(box,size[0],size[1]);
  }
  return new Result(cards,add);
 }
 /** Resize only the visible main plate. Keep task buffers and side hit targets intact. */
 static Result scaleMain(Result result,int primary,int w,int h,int unit,int mode,int minTouch,int percent){
  percent=mainPercent(percent);
  if(percent==MAIN_DEFAULT_PERCENT||result.cards.length==0||w<=0||h<=0)return result;
  int p=Math.max(0,Math.min(primary,result.cards.length-1));
  int[] main=result.cards[p];
  if(main[2]<=0||main[3]<=0)return result;
  // Include the add slot even in a one-task workbench. It must remain reachable.
  int left=PaneLayout.sideInset(unit),top=PaneLayout.topInset(unit);
  int right=w-PaneLayout.sideInset(unit),bottom=h-PaneLayout.bottomInset(unit);
  for(int i=0;i<=result.cards.length;i++){
   int[] side=i==result.cards.length?result.add:i==p?null:result.cards[i];
   if(side==null)continue;
   if(mode==PaneLayout.TOP_BOTTOM)top=Math.max(top,side[1]+Math.min(side[3],Math.max(1,minTouch)));
   else left=Math.max(left,side[0]+Math.min(side[2],Math.max(1,minTouch)));
  }
  if(right<=left||bottom<=top)return result;
  double scale=Math.min(percent/100.0,Math.min((right-left)/(double)main[2],(bottom-top)/(double)main[3]));
  // Degenerate viewports retain the existing fallback rather than shrinking on an increase.
  if(percent>MAIN_DEFAULT_PERCENT&&scale<=1)return result;
  int width=Math.max(1,Math.min(right-left,(int)Math.round(main[2]*scale)));
  int height=Math.max(1,Math.min(bottom-top,(int)Math.round(main[3]*scale)));
  int x=main[0]+(main[2]-width)/2,y=main[1]+(main[3]-height)/2;
  main[0]=Math.max(left,Math.min(right-width,x));main[1]=Math.max(top,Math.min(bottom-height,y));
  main[2]=width;main[3]=height;
  return result;
 }
 static void contain(int[] box,int contentW,int contentH){
  if(contentW<=0||contentH<=0||box[2]<=0||box[3]<=0)return;
  double scale=Math.min(box[2]/(double)contentW,box[3]/(double)contentH);
  int cw=Math.max(1,Math.min(box[2],(int)Math.round(contentW*scale)));
  int ch=Math.max(1,Math.min(box[3],(int)Math.round(contentH*scale)));
  box[0]+=(box[2]-cw)/2;box[1]+=(box[3]-ch)/2;box[2]=cw;box[3]=ch;
 }
}
