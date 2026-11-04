package io.github.xitc.windowdeck;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.SystemClock;
import android.view.SurfaceControl;
import android.view.View;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.function.Consumer;

/** One current, identity-checked side group for the shared entrance clock. */
final class HandoffRail {
 final String key;
 final int[] geometry;
 final int[] origin;
 final ArrayList<WorkbenchSlot> slots;
 final CardMotion.Pose[] poses;
 final int[] widths,heights,presented,maxEdges;
 final boolean[] rotated;
 final SurfaceControl[] copies;
 final View add;
 final CanvasStage stage;
 boolean cancelled,liveReady;
 Bitmap frame;
 HandoffRail(String key,HandoffController.Host host,WorkbenchSession session,CanvasBinding rom) throws Exception {
  this.key=key;stage=host.stage();add=host.addCard();slots=new ArrayList<>();
  for(int i=0;i<session.slots.size();i++)if(i!=session.primary)slots.add(session.slots.get(i));
  int n=slots.size();poses=new CardMotion.Pose[n];widths=new int[n];heights=new int[n];presented=new int[n];maxEdges=new int[n];rotated=new boolean[n];copies=new SurfaceControl[n];
  origin=new int[2];stage.getLocationOnScreen(origin);
  View root=stage.getRootView();int mode=host.effectiveMode();float extent=1;
  try{
   for(int i=0;i<n;i++){
    WorkbenchSlot s=slots.get(i);int w=s.surface.getWidth(),h=s.surface.getHeight();
    poses[i]=host.surfacePose(s,w,h);widths[i]=s.renderBounds.width();heights[i]=s.renderBounds.height();
    presented[i]=host.presentationSize(s)[0];rotated[i]=host.rotatePresentation(s);
    float[] box=DisplayGeometry.quadBounds(poses[i].quad);
    maxEdges[i]=Math.max(1,Math.min(1024,(int)Math.ceil(Math.max(box[2]-box[0],box[3]-box[1]))));
    copies[i]=(SurfaceControl)XposedHelpers.newInstance(SurfaceControl.class,rom.taskLeash.get(s.surface),"WindowDeckEntranceRail");
    int axis=mode==PaneLayout.TOP_BOTTOM?1:0;
    for(int j=axis;j<8;j+=2)extent=Math.max(extent,origin[axis]+poses[i].quad[j]);
   }
   if(add!=null&&add.getVisibility()==View.VISIBLE){
    int[] where=new int[2];add.getLocationOnScreen(where);
    extent=Math.max(extent,mode==PaneLayout.TOP_BOTTOM?where[1]+add.getHeight():where[0]+add.getWidth());
   }
   geometry=new int[]{root.getWidth(),root.getHeight(),mode,(int)Math.ceil(extent)+1};
   if(!RailEntrance.valid(geometry))throw new IllegalStateException("Unmeasured entrance rail");
  }catch(Exception e){releaseCopies();throw e;}
 }
 void capture(Handler worker,Handler main,long deadline,Consumer<HandoffRail> ready){
  if(!worker.post(()->{
   LayoutSnapshots pixels=new LayoutSnapshots(slots.size());boolean complete=true;
   try{
    for(int i=0;i<copies.length;i++){
     if(SystemClock.uptimeMillis()>=deadline){complete=false;break;}
     Bitmap bitmap=LiveTaskContent.captureFrame(copies[i],widths[i],heights[i],maxEdges[i]);
     if(bitmap==null){complete=false;break;}pixels.put(i,bitmap);
    }
   }catch(Throwable e){complete=false;android.util.Log.w("WindowDeck","handoff_rail_capture_failed",e);}
   finally{releaseCopies();}
   final boolean captured=complete;
   if(!main.post(()->{
    try{if(!cancelled&&captured&&SystemClock.uptimeMillis()<deadline)frame=compose(pixels);}
    catch(Throwable e){android.util.Log.w("WindowDeck","handoff_rail_compose_failed",e);}
    finally{pixels.close();ready.accept(this);}
   }))pixels.close();
  })){releaseCopies();ready.accept(this);}
 }
 private Bitmap compose(LayoutSnapshots pixels){
  int w=geometry[2]==PaneLayout.LEFT_RIGHT?geometry[3]:geometry[0],h=geometry[2]==PaneLayout.TOP_BOTTOM?geometry[3]:geometry[1];
  Bitmap software=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
  try{
   Canvas canvas=new Canvas(software);canvas.translate(origin[0],origin[1]);
   for(int i=0;i<slots.size();i++){
    Bitmap task=pixels.take(i);
    try{
     RecoveryCover cover=new RecoveryCover(stage.getContext());cover.setBitmap(task);
     cover.fit(CardMotion.sourceQuad(poses[i],presented[i],rotated[i]),poses[i].quad,widths[i],heights[i],CardMotion.sourceRadius(poses[i]));
     cover.layout(0,0,geometry[0],geometry[1]);cover.draw(canvas);
     chrome(canvas,slots.get(i).icon);chrome(canvas,slots.get(i).pin);
    }finally{task.recycle();}
   }
   chrome(canvas,add);return software.copy(Bitmap.Config.HARDWARE,false);
  }finally{software.recycle();}
 }
 private void chrome(Canvas canvas,View view){
  if(view==null||view.getVisibility()!=View.VISIBLE)return;
  int[] where=new int[2];view.getLocationOnScreen(where);int save=canvas.save();
  canvas.translate(where[0]-origin[0],where[1]-origin[1]);view.draw(canvas);canvas.restoreToCount(save);
 }
 private void releaseCopies(){for(int i=0;i<copies.length;i++)if(copies[i]!=null){copies[i].release();copies[i]=null;}}
 void cancel(){cancelled=true;frame=null;}
}
