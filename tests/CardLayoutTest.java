package dev.windowdeck.app;

public final class CardLayoutTest {
 public static void main(String[] args){
  int cases=0;
  int[][] viewports={{360,792},{360,650},{360,300},{792,360},{792,220},{1440,2650},{3168,1100}};
  for(int[] viewport:viewports)for(int mode=0;mode<2;mode++)for(int n=1;n<=3;n++)for(int p=0;p<n;p++)for(int mask=0;mask<(1<<n);mask++){
   int w=viewport[0],h=viewport[1],d=w>1000?4:1,badge=0;
   int gap=d;
   int sideW=mode==PaneLayout.TOP_BOTTOM?PaneLayout.topBottomSideW(w,d):PaneLayout.leftRightSideW(w,d);
   int sideH=PaneLayout.previewHeight(w,h,gap,sideW,badge,mode);
   int[][] baseline=PaneLayout.compute(w,h,n,p,gap,sideW,sideH,mode);
   int[][] sizes=new int[n][2];
   for(int i=0;i<n;i++)sizes[i]=(mask&(1<<i))==0?new int[]{1440,3168}:new int[]{3168,1440};
   CardLayout.Result result=CardLayout.compute(w,h,p,gap,sideW,sideH,mode,badge,48*d,sizes);
   for(int i=0;i<n;i++){
    int[] card=result.cards[i];inside(card,w,h);
    for(int j=0;j<i;j++)separate(card,result.cards[j]);
    if(i==p){
     int[] region=baseline[i];
     check(card[0]>=region[0]&&card[1]>=region[1]&&card[0]+card[2]<=region[0]+region[2]&&card[1]+card[3]<=region[1]+region[3],"main stays in allocation");
     double scale=Math.min(region[2]/(double)sizes[i][0],region[3]/(double)sizes[i][1]);
     check(Math.abs(card[2]-sizes[i][0]*scale)<=1&&Math.abs(card[3]-sizes[i][1]*scale)<=1,"main tightly fits content without cropping");
    }else{
     check(card[3]>=Math.min(baseline[i][3],48*d),"preview touch height retained");
     check(card[3]<=baseline[i][3],"preview stays within budget");
     check(java.util.Arrays.equals(card,baseline[i]),"side plates keep the original box");
    }
   }
   if(n<3){
    inside(result.add,w,h);for(int[] card:result.cards)separate(result.add,card);
    check(result.add[2]==sideW&&result.add[3]==sideH,"add matches a side plate");
   }
   else check(result.add==null,"full slots hide add");
   // Changing the primary must not mutate task render dimensions.
   for(int i=0;i<n;i++)check(sizes[i][0]==((mask&(1<<i))==0?1440:3168),"render size unchanged");
   cases++;
  }
  int sh=PaneLayout.previewHeight(360,792,1,56,0,0);
  CardLayout.Result landscape=CardLayout.compute(360,792,0,1,56,sh,0,0,48,new int[][]{{3168,1440},{3168,1440}});
  check(landscape.cards[0][3]<130,"landscape main no tall empty frame");
  check(landscape.cards[1][2]==56&&landscape.cards[1][3]==146,"landscape preview keeps the 56x147 plate");
  check(landscape.add[2]==56&&landscape.add[3]==146,"add matches the left plate");
  CardLayout.Result pending=CardLayout.compute(360,792,0,1,56,sh,0,0,48,new int[][]{{0,0},{0,0}});
  int[] lrHole=PaneLayout.mainBox(360,792,2,1,56,sh,0);
  check(pending.cards[0][3]==lrHole[3],"uninitialized render keeps allocation");
  for(int mode=0;mode<2;mode++){
   int unit=1;
   int sideW=mode==0?PaneLayout.leftRightSideW(360,1):PaneLayout.topBottomSideW(360,1);
   int height=PaneLayout.previewHeight(360,792,unit,sideW,0,mode);
   int[] rotated=OrientationPolicy.presentationSize(3168,1440,true);
   CardLayout.Result reference=CardLayout.compute(360,792,0,unit,sideW,height,mode,0,48,new int[][]{rotated,{1440,3168}});
   check(reference.cards[0][3]>550,"rotated game uses long main card instead of short center strip");
   check(Math.abs(reference.cards[0][3]/(double)reference.cards[0][2]-2.2)<0.01,"game retains its true ratio after rotation");
   if(mode==0){
    check(reference.cards[1][0]<reference.cards[0][0],"reference left rail with main on right");
    check(reference.cards[1][2]==56&&reference.cards[1][3]==146,"left plates stay 56x147");
    check(reference.add[2]==56&&reference.add[1]==reference.cards[1][1]+reference.cards[1][3]+13,"left add is the second plate");
   }
   else{
    int[] tbHole=PaneLayout.mainBox(360,792,2,unit,sideW,height,mode);
    check(reference.cards[1][1]+reference.cards[1][3]<reference.cards[0][1],"reference top rail with main below");
    check(reference.cards[0][0]>=tbHole[0]&&reference.cards[0][0]+reference.cards[0][2]<=tbHole[0]+tbHole[2],"main letterboxes inside the remaining hole");
    check(reference.cards[0][2]<tbHole[2],"portrait main does not stretch to the hole width");
    check(reference.cards[1][2]==sideW&&reference.add[2]==sideW,"two top plates including add");
    check(reference.add[0]==reference.cards[1][0]+reference.cards[1][2]+13,"top plates keep 13 dp gap");
   }
   CardLayout.Result ime=CardLayout.compute(360,220,0,unit,sideW,height,mode,0,48,new int[][]{rotated,{1440,3168}});
   inside(ime.cards[0],360,220);inside(ime.cards[1],360,220);separate(ime.cards[0],ime.cards[1]);
  }
  System.out.println("PASS "+cases+" mixed-orientation card geometry cases and compact/fallback regressions");
 }
 static void inside(int[] r,int w,int h){check(r!=null&&r[0]>=0&&r[1]>=0&&r[2]>0&&r[3]>0&&r[0]+r[2]<=w&&r[1]+r[3]<=h,"inside viewport");}
 static void separate(int[] a,int[] b){check(a[0]>=b[0]+b[2]||b[0]>=a[0]+a[2]||a[1]>=b[1]+b[3]||b[1]>=a[1]+a[3],"no overlap");}
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
