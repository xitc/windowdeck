package io.github.xitc.windowdeck;

import android.graphics.Bitmap;

/** Worker fills once, then posts to UI. Only the receiving owner may take or close. */
final class LayoutSnapshots implements AutoCloseable {
 private final Bitmap[] frames;
 LayoutSnapshots(int count){frames=new Bitmap[count];}
 void put(int index,Bitmap frame){
  if(frame==null||frame.isRecycled()||frames[index]!=null)throw new IllegalArgumentException("Invalid snapshot");
  frames[index]=frame;
 }
 Bitmap take(int index){Bitmap frame=frames[index];frames[index]=null;return frame;}
 @Override public void close(){
  for(int i=0;i<frames.length;i++)if(frames[i]!=null){frames[i].recycle();frames[i]=null;}
 }
}
