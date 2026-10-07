package io.github.xitc.windowdeck;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.SurfaceControl;
import de.robv.android.xposed.XposedHelpers;

/** C17's own captureLayers path, sampled off the UI thread. No task snapshot cache,
 * host background, display screenshot, secure capture, or persisted image is involved. */
final class LiveTaskContent {
 static int layerId(SurfaceControl surface){
  if(surface==null||!surface.isValid())return -1;
  return (Integer)XposedHelpers.callMethod(surface,"getLayerId");
 }
 static boolean sample(SurfaceControl leash,int width,int height) throws Exception {
  Bitmap pixels=captureFrame(leash,width,height,64);
  if(pixels==null)return false;
  try{
   int covered=0;
   for(int y=1;y<=3;y++)for(int x=1;x<=3;x++)
    if((pixels.getPixel(pixels.getWidth()*x/4,pixels.getHeight()*y/4)>>>24)>=240)covered++;
   return covered>=5;
  }finally{pixels.recycle();}
 }
 /** Opaque and not a solid black buffer. Landscape reveal uses this; portrait keeps {@link #sample}. */
 static boolean presented(SurfaceControl leash,int width,int height) throws Exception {
  Bitmap pixels=captureFrame(leash,width,height,64);
  if(pixels==null)return false;
  try{
   int[] argb=new int[9];int i=0;
   for(int y=1;y<=3;y++)for(int x=1;x<=3;x++)argb[i++]=pixels.getPixel(pixels.getWidth()*x/4,pixels.getHeight()*y/4);
   return TaskSurfaceEvidence.presented(argb);
  }finally{pixels.recycle();}
 }
 /** Caller owns this software copy. Use only off the main thread with a copied task leash. */
 static Bitmap captureFrame(SurfaceControl leash,int width,int height,int maxEdge) throws Exception {
  Bitmap hardware=null;android.hardware.HardwareBuffer buffer=null;
  try{
   Class<?> capture=Class.forName("android.window.ScreenCaptureInternal");
   Object builder=XposedHelpers.newInstance(Class.forName("android.window.ScreenCaptureInternal$LayerCaptureArgs$Builder"),leash);
   XposedHelpers.callMethod(builder,"setSourceCrop",new Rect(0,0,width,height));
   XposedHelpers.callMethod(builder,"setChildrenOnly",true);
   XposedHelpers.callMethod(builder,"setPixelFormat",android.graphics.PixelFormat.RGBA_8888);
   XposedHelpers.callMethod(builder,"setFrameScale",Math.min(1f,maxEdge/(float)Math.max(width,height)));
   XposedHelpers.callMethod(builder,"setSecureContentPolicy",0);
   XposedHelpers.callMethod(builder,"setProtectedContentPolicy",0);
   Object shot=XposedHelpers.callStaticMethod(capture,"captureLayers",XposedHelpers.callMethod(builder,"build"));
   if(shot==null)return null;
   buffer=(android.hardware.HardwareBuffer)XposedHelpers.callMethod(shot,"getHardwareBuffer");
   if(buffer==null||(buffer.getUsage()&android.hardware.HardwareBuffer.USAGE_PROTECTED_CONTENT)!=0)return null;
   if(Boolean.TRUE.equals(XposedHelpers.callMethod(shot,"containsSecureLayers")))return null;
   hardware=(Bitmap)XposedHelpers.callMethod(shot,"asBitmap");
   if(hardware==null||hardware.getWidth()<3||hardware.getHeight()<3)return null;
   return hardware.copy(Bitmap.Config.ARGB_8888,false);
  }finally{
   if(hardware!=null)hardware.recycle();if(buffer!=null)buffer.close();
  }
 }
 private LiveTaskContent(){}
}
