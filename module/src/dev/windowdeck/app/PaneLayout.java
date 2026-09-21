package dev.windowdeck.app;
/** Pixel geometry. Each row is left, top, width, height. */
final class PaneLayout {
  static final int LEFT_RIGHT=0;
  static final int TOP_BOTTOM=1;

  static int previewHeight(int w,int h,int gap,int sideW,int badge,int mode){
    if(mode==TOP_BOTTOM)return Math.max(1,(int)(((long)sideW*(h-3*gap)+(long)badge*(w-2*gap))/Math.max(1,w-2*gap+sideW)));
    return badge+Math.round((h-2*gap)*sideW/(float)Math.max(1,w-sideW-3*gap));
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
    if(mode==TOP_BOTTOM){
      int sh=fitSideH(w,h,count,g,sideW,sideH,mode);
      int vis=visualCount(count);
      int top=vis>0?g+sh+g:g;
      return new int[]{g,top,Math.max(1,w-2*g),Math.max(1,h-top-g)};
    }
    int sw=fitSideW(w,h,count,g,sideW,sideH,mode);
    int vis=visualCount(count);
    int mw=vis>0?Math.max(1,w-sw-3*g):Math.max(1,w-2*g);
    return new int[]{vis>0?sw+2*g:g,g,mw,Math.max(1,h-2*g)};
  }

  static int[] sideBox(int w,int h,int count,int gap,int sideW,int sideH,int mode,int index){
    int g=Math.max(0,gap);
    int sw=fitSideW(w,h,count,g,sideW,sideH,mode);
    int sh=fitSideH(w,h,count,g,sideW,sideH,mode);
    if(mode==TOP_BOTTOM){
      int x=g+index*(sw+g);
      if(x+sw>w-g)x=Math.max(g,w-g-sw);
      return new int[]{x,g,sw,sh};
    }
    int x=g;
    int y=g+index*(sh+g);
    if(y+sh>h-g)y=Math.max(g,h-g-sh);
    return new int[]{x,y,sw,sh};
  }

  static int fitSideW(int w,int h,int count,int gap,int sideW,int sideH,int mode){
    int vis=Math.max(1,visualCount(count));
    int want=Math.max(1,sideW);
    if(mode==TOP_BOTTOM){
      int each=(w-2*gap-Math.max(0,vis-1)*gap)/vis;
      return Math.max(1,Math.min(want,Math.max(1,each)));
    }
    int max=w-3*gap-Math.max(80,w/3);
    return Math.max(1,Math.min(want,Math.max(1,max)));
  }

  static int fitSideH(int w,int h,int count,int gap,int sideW,int sideH,int mode){
    int vis=Math.max(1,visualCount(count));
    int want=Math.max(1,sideH);
    if(mode==TOP_BOTTOM){
      int max=(h-3*gap)/3;
      return Math.max(1,Math.min(want,Math.max(1,max)));
    }
    int each=(h-2*gap-Math.max(0,vis-1)*gap)/vis;
    return Math.max(1,Math.min(want,Math.max(1,each)));
  }
}
