package dev.windowdeck.app;
/** Pixel geometry. Each row is left, top, width, height.
 *  {@code gap} is 1 dp in pixels. TOP_BOTTOM plates 74 dp; LEFT_RIGHT 56×(h/5.4). */
final class PaneLayout {
  static final int LEFT_RIGHT=0;
  static final int TOP_BOTTOM=1;
  static final int TOP_RAIL=2;
  static final float TOP_SPLIT=6.5f;
  static final float LEFT_SPLIT=5.4f;

  static int dp(int unit,int value){return Math.max(0,Math.max(1,unit)*value);}

  static int topInset(int unit){return dp(unit,10);}
  static int bottomInset(int unit){return dp(unit,42);}
  static int sideInset(int unit){return dp(unit,12);}
  static int toMain(int unit,int mode){return mode==LEFT_RIGHT?0:dp(unit,12);}
  static int itemGap(int unit){return dp(unit,13);}

  static int topBottomSideW(int w,int unit){
    int want=dp(unit,74);
    int max=Math.max(1,w-2*sideInset(unit)-itemGap(unit));
    return Math.max(1,Math.min(want,max));
  }

  static int topBottomSideH(int h,int unit){
    int rest=h-topInset(unit)-bottomInset(unit)-toMain(unit,TOP_BOTTOM);
    if(rest<=1)return 1;
    int want=Math.max(1,Math.round(rest/TOP_SPLIT));
    return Math.min(want,Math.max(1,rest/2));
  }

  static int leftRightSideW(int w,int unit){
    int want=dp(unit,56);
    int max=Math.max(1,w-2*sideInset(unit)-toMain(unit,LEFT_RIGHT)-Math.max(80,w/3));
    return Math.max(1,Math.min(want,max));
  }

  static int leftRightSideH(int h,int unit){
    if(h<=1)return 1;
    int want=Math.max(1,(int)(h/LEFT_SPLIT));
    return Math.min(want,Math.max(1,h/3));
  }

  static int previewHeight(int w,int h,int gap,int sideW,int badge,int mode){
    if(mode==TOP_BOTTOM)return topBottomSideH(h,gap);
    return leftRightSideH(h,gap);
  }

  static int visualCount(int count){
    if(count<=0)return 0;
    return Math.max(0,count-1)+(count<3?1:0);
  }

  static int[][] compute(int w,int h,int count,int primary,int gap,int sideW,int sideH,int mode){
    int[][] out=new int[Math.max(0,count)][4];
    if(count<=0||w<=0||h<=0)return out;
    int p=Math.max(0,Math.min(primary,count-1));
    int[] main=mainBox(w,h,count,gap,sideW,sideH,mode);
    int row=0;
    for(int i=0;i<count;i++)out[i]=i==p?main:sideBox(w,h,count,gap,sideW,sideH,mode,row++);
    return out;
  }

  static int[] addBox(int w,int h,int count,int gap,int sideW,int sideH,int mode){
    if(count>=3||count<0||w<=0||h<=0)return null;
    return sideBox(w,h,count,gap,sideW,sideH,mode,Math.max(0,count-1));
  }

  static int[] mainBox(int w,int h,int count,int gap,int sideW,int sideH,int mode){
    int g=Math.max(0,gap);
    int vis=visualCount(count);
    int left=sideInset(g),top=topInset(g),bottom=bottomInset(g),right=sideInset(g);
    if(mode==TOP_BOTTOM){
      int sh=fitSideH(w,h,count,g,sideW,sideH,mode);
      int y=vis>0?top+sh+toMain(g,mode):top;
      return new int[]{left,y,Math.max(1,w-left-right),Math.max(1,h-y-bottom)};
    }
    int sw=fitSideW(w,h,count,g,sideW,sideH,mode);
    int x=vis>0?left+sw+toMain(g,mode):left;
    return new int[]{x,top,Math.max(1,w-x-right),Math.max(1,h-top-bottom)};
  }

  static int[] sideBox(int w,int h,int count,int gap,int sideW,int sideH,int mode,int index){
    int g=Math.max(0,gap);
    int sw=fitSideW(w,h,count,g,sideW,sideH,mode);
    int sh=fitSideH(w,h,count,g,sideW,sideH,mode);
    int vis=Math.max(1,visualCount(count));
    int item=itemGap(g);
    if(mode==TOP_BOTTOM){
      int total=vis*sw+Math.max(0,vis-1)*item;
      int x=Math.max(sideInset(g),(w-total)/2)+index*(sw+item);
      if(x+sw>w-sideInset(g))x=Math.max(sideInset(g),w-sideInset(g)-sw);
      return new int[]{x,topInset(g),sw,sh};
    }
    int total=vis*sh+Math.max(0,vis-1)*item;
    int y=Math.max(topInset(g),(h-total)/2)+index*(sh+item);
    if(y+sh>h-bottomInset(g))y=Math.max(topInset(g),h-bottomInset(g)-sh);
    return new int[]{sideInset(g),y,sw,sh};
  }

  static int fitSideW(int w,int h,int count,int gap,int sideW,int sideH,int mode){
    if(mode==TOP_BOTTOM){
      int vis=Math.max(1,visualCount(count));
      int want=topBottomSideW(w,gap);
      int each=(w-2*sideInset(gap)-Math.max(0,vis-1)*itemGap(gap))/vis;
      return Math.max(1,Math.min(want,Math.max(1,each)));
    }
    return leftRightSideW(w,gap);
  }

  static int fitSideH(int w,int h,int count,int gap,int sideW,int sideH,int mode){
    if(mode==TOP_BOTTOM){
      int want=Math.max(1,sideH<=0?topBottomSideH(h,gap):sideH);
      return Math.max(1,Math.min(want,topBottomSideH(h,gap)));
    }
    int vis=Math.max(1,visualCount(count));
    int want=leftRightSideH(h,gap);
    int each=(h-topInset(gap)-bottomInset(gap)-Math.max(0,vis-1)*itemGap(gap))/vis;
    return Math.max(1,Math.min(want,Math.max(1,each)));
  }
}
