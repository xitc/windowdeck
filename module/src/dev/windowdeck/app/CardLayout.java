package dev.windowdeck.app;

/** Fits visible cards without feeding their aspect ratios back into task rendering. */
final class CardLayout {
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
 static void contain(int[] box,int contentW,int contentH){
  if(contentW<=0||contentH<=0||box[2]<=0||box[3]<=0)return;
  double scale=Math.min(box[2]/(double)contentW,box[3]/(double)contentH);
  int cw=Math.max(1,Math.min(box[2],(int)Math.round(contentW*scale)));
  int ch=Math.max(1,Math.min(box[3],(int)Math.round(contentH*scale)));
  box[0]+=(box[2]-cw)/2;box[1]+=(box[3]-ch)/2;box[2]=cw;box[3]=ch;
 }
}
