package dev.windowdeck.app;
public final class PaneLayoutTest {
 public static void main(String[] args){
  int[][] sizes={{1440,2650},{3168,1100},{1440,1400},{1080,1750},{360,400},{792,240},{360,650},{360,300},{792,220}};
  int cases=0;
  for(int[] size:sizes)for(int mode=0;mode<=1;mode++)for(int n=1;n<=3;n++)for(int primary=0;primary<n;primary++){
   int d=size[0]>1000?4:1;
   int gap=6*d,sideW=84*d,sideH=PaneLayout.previewHeight(size[0],size[1],gap,sideW,22*d,mode);
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
  System.out.println("PASS "+cases+" portrait/landscape/IME geometry cases");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
