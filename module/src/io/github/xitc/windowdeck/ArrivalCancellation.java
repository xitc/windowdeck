package io.github.xitc.windowdeck;

/** One screen-arrival cancel, in the original order. EntranceRun stays the only token gate. */
final class ArrivalCancellation {
 interface Effects {
  boolean active();
  void cancelRun();
  void stopWatching();
  void logCancelled();
  void removeUnlock();
  void clearInput();
  void closeCover();
  void clearHangFrame();
  void clearHangGeometry();
  void clearFields();
  void resetPoses();
 }

 private boolean cancelling;

 void cancel(Effects effects, boolean preservePose) {
  if (cancelling || !effects.active()) return;
  cancelling = true;
  try {
   effects.cancelRun();
   effects.stopWatching();
   effects.logCancelled();
   effects.removeUnlock();
   effects.clearInput();
   effects.closeCover();
   effects.clearHangFrame();
   effects.clearHangGeometry();
   effects.clearFields();
   if (!preservePose) effects.resetPoses();
  } finally {
   cancelling = false;
  }
 }
}
