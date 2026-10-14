package io.github.xitc.windowdeck;

/** Bounded raw UI frame timestamps. No refresh-rate assumption or smoothness verdict. */
final class MotionFrameSamples {
 private final long[] frames=new long[96],dispatch=new long[96];
 private int count;
 private boolean truncated;
 void add(long frame,long now){
  if(count>0&&frame<=frames[count-1])return;
  if(count==frames.length){truncated=true;return;}
  frames[count]=frame;dispatch[count]=now;count++;
 }
 int count(){return count;}
 long maxGap(){long gap=0;for(int i=1;i<count;i++)gap=Math.max(gap,frames[i]-frames[i-1]);return gap;}
 String trace(){
  StringBuilder out=new StringBuilder("samples=").append(count).append(" truncated=").append(truncated)
    .append(" max_gap_ns=").append(maxGap());
  out.append(" frame_ns=[");append(out,frames);out.append("] dispatch_ns=[");append(out,dispatch);return out.append(']').toString();
 }
 private void append(StringBuilder out,long[] values){for(int i=0;i<count;i++){if(i>0)out.append(',');out.append(values[i]);}}
}
