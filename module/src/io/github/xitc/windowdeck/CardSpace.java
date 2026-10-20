package io.github.xitc.windowdeck;

/** The coordinate domain shared by rail targets, exchange rounding and frame barriers. */
final class CardSpace {
 final int x,y,width,height,stageWidth,stageHeight,rotation;
 final float density;
 final boolean portrait;
 CardSpace(int x,int y,int width,int height,int stageWidth,int stageHeight,int rotation,float density,boolean portrait){
  this.x=x;this.y=y;this.width=width;this.height=height;
  this.stageWidth=stageWidth;this.stageHeight=stageHeight;this.rotation=rotation;
  this.density=density;this.portrait=portrait;
 }
 boolean same(CardSpace other){
  return other!=null&&x==other.x&&y==other.y&&width==other.width&&height==other.height
   &&stageWidth==other.stageWidth&&stageHeight==other.stageHeight&&rotation==other.rotation
   &&Float.floatToIntBits(density)==Float.floatToIntBits(other.density)&&portrait==other.portrait;
 }
 @Override public String toString(){
  return x+","+y+":"+width+"x"+height+":"+stageWidth+"x"+stageHeight
   +":"+rotation+":"+Float.floatToIntBits(density)+":"+portrait;
 }
}
