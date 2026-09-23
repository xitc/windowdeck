package dev.windowdeck.app;
public final class PaneLayoutTest {
 public static void main(String[] args){
  int[][] sizes={{1440,2650},{3168,1100},{1440,1400},{1080,1750},{360,400},{792,240},{360,650},{360,300},{792,220}};
  int cases=0;
  for(int[] size:sizes)for(int mode=0;mode<=1;mode++)for(int n=1;n<=3;n++)for(int primary=0;primary<n;primary++){
   int d=size[0]>1000?4:1;
   int gap=d;
   int sideW=mode==PaneLayout.TOP_BOTTOM?PaneLayout.topBottomSideW(size[0],d):PaneLayout.leftRightSideW(size[0],d);
   int sideH=PaneLayout.previewHeight(size[0],size[1],gap,sideW,mode==PaneLayout.TOP_BOTTOM?0:22*d,mode);
   int[][] boxes=PaneLayout.compute(size[0],size[1],n,primary,gap,sideW,sideH,mode);
   check(boxes.length==n,"count");
   int[] main=boxes[primary];
   check(main[2]*main[3]>=1,"main area");
   int[] stableMain=PaneLayout.compute(size[0],size[1],n,0,gap,sideW,sideH,mode)[0];
   check(java.util.Arrays.equals(main,stableMain),"promoting preserves main bounds");
   for(int i=0;i<n;i++){
    int[] a=boxes[i];
    check(a[0]>=0&&a[1]>=0&&a[2]>0&&a[3]>0,"positive content");
    check(a[0]+a[2]<=size[0]&&a[1]+a[3]<=size[1],"inside viewport");
    for(int j=0;j<i;j++){
     int[] b=boxes[j];
     check(a[0]>=b[0]+b[2]||b[0]>=a[0]+a[2]||a[1]>=b[1]+b[3]||b[1]>=a[1]+a[3],"no overlap");
    }
    if(i!=primary)check(a[2]*a[3]<=main[2]*main[3],"main not smaller than side");
   }
   int[] add=PaneLayout.addBox(size[0],size[1],n,gap,sideW,sideH,mode);
   if(n>=3)check(add==null,"no add when full");
   else{
    check(add!=null&&add[2]>0&&add[3]>0,"add slot");
    check(add[0]>=0&&add[1]>=0&&add[0]+add[2]<=size[0]&&add[1]+add[3]<=size[1],"add inside");
    for(int i=0;i<n;i++){
     int[] b=boxes[i];
     check(add[0]>=b[0]+b[2]||b[0]>=add[0]+add[2]||add[1]>=b[1]+b[3]||b[1]>=add[1]+add[3],"add no overlap");
    }
   }
   cases++;
  }
  check(PaneLayout.compute(360,650,0,0,6,112,147,0).length==0,"empty layout");
  check(PaneLayout.addBox(0,650,2,6,112,147,0)==null,"unmeasured add hidden");
  check(PaneLayout.visualCount(1)==1&&PaneLayout.visualCount(2)==2&&PaneLayout.visualCount(3)==2,"visual counts");
  check(PaneLayout.addBox(400,800,3,8,112,147,0)==null,"full add null");

  int sw=PaneLayout.topBottomSideW(360,1),sh=PaneLayout.topBottomSideH(792,1);
  check(sw==74,"side plate stays original 74 dp wide");
  check(sh==112,"phone 6.5 height split on 360x792");
  int[][] three=PaneLayout.compute(360,792,3,0,1,sw,sh,PaneLayout.TOP_BOTTOM);
  check(three[1][2]==sw&&three[2][2]==sw&&three[1][3]==sh&&three[2][3]==sh,"full combo uses two equal top plates");
  check(three[1][1]==10&&three[2][1]==10,"top plates share the top rail");
  check(three[1][0]==(360-74-13-74)/2,"two 74 dp plates are centered as a group");
  check(three[1][0]+three[1][2]+13==three[2][0],"13 dp gap between the two top plates");
  check(three[0][1]==three[1][1]+three[1][3]+12,"12 dp between top rail and main");
  check(three[0][0]==12&&three[0][0]+three[0][2]==348,"main uses 12 dp side insets");
  check(three[0][1]+three[0][3]==750,"42 dp bottom inset under main");
  int[] add=PaneLayout.addBox(360,792,2,1,sw,sh,PaneLayout.TOP_BOTTOM);
  int[][] two=PaneLayout.compute(360,792,2,0,1,sw,sh,PaneLayout.TOP_BOTTOM);
  check(add!=null&&add[2]==sw&&add[3]==sh&&add[1]==10,"add matches a top plate");
  check(add[0]==two[1][0]+two[1][2]+13,"two-app add is the second top plate");
  check(PaneLayout.addBox(360,792,3,1,sw,sh,PaneLayout.TOP_BOTTOM)==null,"three apps fill both top plates");

  int lrW=PaneLayout.leftRightSideW(360,1),lrH=PaneLayout.leftRightSideH(792,1);
  check(lrW==56,"left-right plate stays 56 dp wide");
  check(lrH==146,"left-right height is screen/5.4 on 360x792");
  int[][] rail=PaneLayout.compute(360,792,3,0,1,lrW,lrH,PaneLayout.LEFT_RIGHT);
  check(rail[1][2]==56&&rail[2][2]==56&&rail[1][3]==146&&rail[2][3]==146,"two equal left plates");
  check(rail[1][0]==12&&rail[2][0]==12,"left plates use 12 dp inset");
  check(rail[1][1]+rail[1][3]+13==rail[2][1],"13 dp gap between left plates");
  int col=(792-146-13-146)/2;
  check(rail[1][1]==col,"left column is vertically centered");
  check(rail[0][0]==12+56&&rail[0][0]+rail[0][2]==348,"main covers the inner edge of the left plates");
  int[] lrAdd=PaneLayout.addBox(360,792,2,1,lrW,lrH,PaneLayout.LEFT_RIGHT);
  check(lrAdd!=null&&lrAdd[2]==56&&lrAdd[3]==146&&lrAdd[1]==rail[2][1],"add matches a left plate");
  System.out.println("PASS "+cases+" portrait/landscape/IME geometry cases");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
