package io.github.xitc.windowdeck;

import android.graphics.Canvas;
import android.util.Log;
import android.view.SurfaceView;
import android.view.ViewParent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Replace only our fitted card's native rectangular hole with the stage contour. */
final class SurfaceApertureHook {
 private static boolean attempted,installed;
 static synchronized boolean install(){
  if(attempted)return installed;
  attempted=true;
  try{
   Method method=SurfaceView.class.getDeclaredMethod("clearSurfaceViewPort",Canvas.class);
   if(method.getReturnType()!=void.class||Modifier.isStatic(method.getModifiers()))throw new NoSuchMethodException("SurfaceView viewport contract");
   method.setAccessible(true);
   XposedBridge.hookMethod(method,new XC_MethodHook(){
    @Override protected void beforeHookedMethod(MethodHookParam param){
     ViewParent parent=((SurfaceView)param.thisObject).getParent();
     if(parent instanceof PreviewCard&&((PreviewCard)parent).surfaceAperture!=null)param.setResult(null);
    }
   });
   installed=true;Log.i("WindowDeck","surface_aperture_hook ready=true");
  }catch(Throwable e){Log.w("WindowDeck","surface_aperture_hook unavailable",e);}
  return installed;
 }
 private SurfaceApertureHook(){}
}
