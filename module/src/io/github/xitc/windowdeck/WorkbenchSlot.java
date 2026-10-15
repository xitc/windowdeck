package io.github.xitc.windowdeck;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** One embedded task card. The id is assigned by the session, not by a hidden counter. */
final class WorkbenchSlot {
 final int id;
 final ComponentName component;
 final String label;
 int taskId=-1,sourceTaskId=-1,sourceUserId=-1,orientationAxis;
 ComponentName activeComponent;
 Intent launchIntent;
 final Rect renderBounds=new Rect();
 boolean failed,released,pinned,windowDrawn,handoffPolling,entranceWaitScheduled,entranceDrawTimedOut,fitPosted;
 SurfaceControl.Transaction transaction;
 volatile boolean embedded=true;
 SurfaceControl projectedLeash;
 boolean perspectiveApplied;
 int leashWrites;
 volatile boolean fullscreenHidden;
 final TaskSurfaceEvidence drawEvidence=new TaskSurfaceEvidence();
 final TaskOrientation taskOrientation=new TaskOrientation();
 final ImmersiveOrientation immersiveOrientation=new ImmersiveOrientation();
 int lastRequestedVisibleTypes=-1;
 long nextContentProbe;
 boolean contentProbeUnavailable;
 String drawDetail="unobserved";
 /** Last corner radius written by the surface renderer. Invalidated only when it changes. */
 int renderRadius=-1;
 float motionFromRadius=-1f;
 volatile CardMotion.Pose motionPose,fittedPose;
 int fittedTask=-1,fittedLayer=-1,fittedAxis;
 final Rect fittedBounds=new Rect();
 final Rect resizedBounds=new Rect();
 int resizedTask=-1;
 PreviewCard card;
 ImageView icon;
 RecoveryCover recoveryCover;
 TextView pin;
 View surface;
 int pendingTask=-1;
 boolean quietDetach,pickerLaunch;
 WorkbenchSlot(int id,ComponentName component,String label){
  this.id=id;this.component=component;this.label=label;
 }
}
