package io.github.xitc.windowdeck;

/** A fresh release must retain task pixels and commit their pose before
 * finishing Recents. Display-capture brightness cannot resolve this wait. */
final class ReleaseFrameGate {
 private final int width,height;
 private final long deadline;
 private boolean pixels,committed,cancelled;
 ReleaseFrameGate(int width,int height,long deadline){
  if(width<2||height<2||deadline<=0)throw new IllegalArgumentException("release frame");
  this.width=width;this.height=height;this.deadline=deadline;
 }
 /** Null accepts this one capture; failures cancel the gate permanently. */
 String accept(int width,int height,boolean opaque,long now){
  if(cancelled||pixels)return "release_capture_retired";
  String failure=now>=deadline?"release_pixels_timeout":!opaque?"release_pixels_unavailable":
    width!=this.width||height!=this.height?"portrait_pixels_size_changed":null;
  if(failure!=null)cancel();else pixels=true;
  return failure;
 }
 boolean pixelsReady(){return pixels&&!cancelled;}
 long deadline(){return deadline;}
 boolean commit(long now){
  if(cancelled||!pixels||now>=deadline){if(now>=deadline)cancel();return false;}
  committed=true;return true;
 }
 boolean mayDispatch(){return committed&&!cancelled;}
 void cancel(){cancelled=true;}
}
