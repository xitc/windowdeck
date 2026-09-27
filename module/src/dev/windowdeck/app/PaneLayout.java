package dev.windowdeck.app;
/** Pixel geometry. Each row is left, top, width, height.
 *  {@code gap} is 1 dp in pixels. TOP_BOTTOM plates 74 dp; LEFT_RIGHT 56×(h/5.4).
 *
 *  <p>{@link #visualCount} is the number of side-plate <em>slots</em> reserved in the
 *  rail, including the ＋ add slot while the rail is not full. It is
 *  {@code min(MAX_SIDE_SLOTS, count)}: 1,2,3,4,4 for 1..5 windows. The plates and the
 *  ＋ slot are laid out as one centred run of that many slots, so they never overlap;
 *  the add slot simply takes the slot right after the last card.
 *
 *  <p>The original workbench uses the same plate sizes for four side cards
 *  (74 dp rail: 4×74 + 3×13 + 2×12 = 359 dp on a 360 dp screen), so nothing here
 *  rescales the plates when the count grows — only the slot count changes. */
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
    return Math.min(Caps.MAX_SIDE_SLOTS,count);
  }

  /** Start of a run of {@code total} px centred on {@code extent}, clamped so the whole
   *  run stays inside {@code [lo, hi]}. Centring still uses the full extent — the
   *  documented 3-window geometry depends on that — the clamp only bites once a longer
   *  run would otherwise spill past the inset. */
  static int groupStart(int extent,int total,int lo,int hi){
    int start=(extent-total)/2;
    int maxStart=hi-total;
    if(start>maxStart)start=maxStart;
    if(start<lo)start=lo;
    return start;
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
    if(count>=Caps.MAX_TASKS||count<0||w<=0||h<=0)return null;
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
      int x=groupStart(w,total,sideInset(g),w-sideInset(g))+index*(sw+item);
      return new int[]{x,topInset(g),sw,sh};
    }
    int total=vis*sh+Math.max(0,vis-1)*item;
    int y=groupStart(h,total,topInset(g),h-bottomInset(g))+index*(sh+item);
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
