package io.github.xitc.windowdeck;

import android.os.Parcel;
import android.os.ResultReceiver;

/** Framework-only parcel types across launcher and pscanvas class loaders. */
final class HandoffProtocol {
 static final int READY=1,FAILED=2,CANCELLED=3,PREPARED=4,BACKGROUND=5,POSE_QUERY=6,POSE_COMMITTED=7,OWNERSHIP=8,BOOTSTRAP_COMMITTED=9,SOURCE_RELEASED=10,HOST_STARTED=11;
 static final int EXISTING_ACCEPTED=12;
 static final String TASK="task",CONTAINER="container",TARGET="target",RADIUS="radius",COMPLETE="complete",BACKDROP="backdrop",ROTATION="rotation",CORNERS="corners",CROP="crop",POSE_REPLY="pose_reply",POSE_STAGE="pose_stage";
 static final String SNAPSHOT="pose_snapshot",SNAPSHOT_CROP="pose_snapshot_crop",SNAPSHOT_COLOR_SPACE="pose_snapshot_color_space";
 /** Actual live fit: task bitmap size, task-space L/T/R/B crop and screen-space LT/RT/RB/LB quad. */
 static final String CONTENT_SIZE="content_size",CONTENT_CROP="content_crop",CONTENT_QUAD="content_quad";
 static final String PORTRAIT_LIMIT="windowdeck_portrait_limit",READY_DEADLINE="ready_deadline";
 static ResultReceiver transport(ResultReceiver receiver){
  Parcel parcel=Parcel.obtain();
  try{receiver.writeToParcel(parcel,0);parcel.setDataPosition(0);return ResultReceiver.CREATOR.createFromParcel(parcel);}
  finally{parcel.recycle();}
 }
 private HandoffProtocol(){}
}
