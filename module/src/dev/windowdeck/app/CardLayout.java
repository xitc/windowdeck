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
  int p=Math.max(0,Math.min(primary,count-1)),nextY=Math.max(0,gap);
  for(int i=0;i<count;i++){
   int[] box=cards[i],size=renderSizes[i];
   if(size[0]>0&&size[1]>0){
    if(i==p){
     // Center the main card in its reserved region. Never crop the app.
     double scale=Math.min(box[2]/(double)size[0],box[3]/(double)size[1]);
     int cw=Math.max(1,Math.min(box[2],(int)Math.round(size[0]*scale)));
     int ch=Math.max(1,Math.min(box[3],(int)Math.round(size[1]*scale)));
     box[0]+=(box[2]-cw)/2;box[1]+=(box[3]-ch)/2;box[2]=cw;box[3]=ch;
    }else{
     long wanted=Math.round(box[2]*(double)size[1]/size[0])+Math.max(0,badge);
     box[3]=(int)Math.min(box[3],Math.max(Math.max(1,minTouch),wanted));
    }
   }
   if(i!=p&&mode==PaneLayout.LEFT_RIGHT){box[1]=nextY;nextY+=box[3]+Math.max(0,gap);}
  }
  if(add!=null){
   add[3]=Math.min(add[2],add[3]);
   if(mode==PaneLayout.LEFT_RIGHT)add[1]=nextY;
  }
  return new Result(cards,add);
 }
}
