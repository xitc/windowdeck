package io.github.xitc.windowdeck;

/** Evidence belongs to a task and native layer generation, never a Java leash wrapper.
 * Missing ROM draw metadata is unknown. A not-drawn flag does not erase an in-flight
 * or accepted child-layer sample; {@link #invalidate()} does. Cold embed can leave
 * mDrawState at 0 across a real frame. */
final class TaskSurfaceEvidence {
 static final int LIVE_CONTENT = 3; // Module fallback, not a C17 hasDrawnWindow value.
 private int task=-1,leash=-1,plate=-1,width,height,renderWidth,renderHeight,generation;
 private boolean content,pending;
 boolean bind(int task,int leash,int plate,int width,int height){
  return bind(task,leash,plate,width,height,width,height);
 }
 boolean bind(int task,int leash,int plate,int width,int height,int renderWidth,int renderHeight){
  if(task<0||leash<0||plate<0||width<=0||height<=0||renderWidth<=0||renderHeight<=0){invalidate();return false;}
  if(this.task!=task||this.leash!=leash||this.plate!=plate||this.width!=width||this.height!=height||this.renderWidth!=renderWidth||this.renderHeight!=renderHeight){
   invalidate();this.task=task;this.leash=leash;this.plate=plate;this.width=width;this.height=height;this.renderWidth=renderWidth;this.renderHeight=renderHeight;
  }
  return true;
 }
 int resolve(int cached,int bundle,boolean hasBundleState){
  // C17 updates mDrawState in onTaskInfoChanged before replacing mTaskInfo (some
  // branches return early). The stored info bundle can therefore be older than this field.
  int state=cached>=0&&cached<=2?cached:(hasBundleState?bundle:-1);
  if(state==0)return content?LIVE_CONTENT:0;
  if(state==1||state==2)return state;
  return content?LIVE_CONTENT:-1;
 }
 int beginProbe(){if(pending||content||task<0)return -1;pending=true;return generation;}
 boolean completeProbe(int token,boolean hasContent){
  if(token!=generation||!pending)return false;
  pending=false;content=hasContent;return true;
 }
 void invalidate(){generation++;content=false;pending=false;task=-1;leash=-1;plate=-1;width=height=renderWidth=renderHeight=0;}
 static boolean ready(int state){return state==1||state==2||state==LIVE_CONTENT;}
 boolean hasContent(){return content;}
 /** Transport validation for a fresh, task-bound WM snapshot. Black loading
  * pages are valid animation sources; this does not grant live readiness. */
 static boolean opaqueSnapshot(int[] argb){
  if(argb==null||argb.length<5)return false;
  int opaque=0;
  for(int color:argb)if(((color>>>24)&255)>=240)opaque++;
  return opaque>=5;
 }
 /** A presented frame has opaque pixels and is not a solid black buffer. */
 static boolean presented(int[] argb){
  if(argb==null||argb.length<5)return false;
  int opaque=0,dark=0;
  for(int color:argb){
   if(((color>>>24)&255)<240)continue;
   opaque++;
   int red=(color>>16)&255,green=(color>>8)&255,blue=color&255;
   if(red+green+blue<36)dark++;
  }
  return opaque>=5&&dark*2<opaque;
 }
 /** A rotation buffer that is almost entirely white. Gray warning text still passes. */
 static boolean washed(int[] argb){
  if(argb==null||argb.length<5)return true;
  int opaque=0,bright=0;
  for(int color:argb){
   if(((color>>>24)&255)<240)continue;
   opaque++;
   if(((color>>16)&255)+((color>>8)&255)+(color&255)>740)bright++;
  }
  return opaque<5||bright*2>=opaque;
 }
}
