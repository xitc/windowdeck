package io.github.xitc.windowdeck;

/**
 * Single owner of the pending entrance and its flags.
 * Release keeps createdFromGesture. Reset, abandon, and consume clear it.
 * markFrameReady still applies after a reset, because a late portrait commit does.
 */
final class EntranceIntent<T> {
 interface ReleaseEffects<T> {
  T shown();
  void supersedeHang();
  void dropHangDue();
  void closeCover();
  void clearFlying();
 }

 private T pending;
 private boolean frontReady, frameReady, framePending, createdFromGesture, inputBlocked;

 T pending(){return pending;}
 boolean frontReady(){return frontReady;}
 boolean frameReady(){return frameReady;}
 boolean framePending(){return framePending;}
 boolean createdFromGesture(){return createdFromGesture;}
 boolean inputBlocked(){return inputBlocked;}

 void acceptGestureSource(T fresh) {
  pending = fresh;
  createdFromGesture = true;
  inputBlocked = true;
 }

 /** onResume: front becomes ready only while this gesture source is still pending. */
 void noteGestureFront() {
  if (createdFromGesture && pending != null) frontReady = true;
 }

 /** Hot-add adoption. Leaves framePending and createdFromGesture alone. */
 void adoptPending(T fresh) {
  pending = fresh;
  frontReady = false;
  frameReady = false;
  inputBlocked = true;
 }

 void markFrontReady() { frontReady = true; }

 void resetPending() {
  pending = null;
  frontReady = false;
  frameReady = false;
  framePending = false;
  createdFromGesture = false;
 }

 void blockInput() { inputBlocked = true; }

 void clearInputBlock() { inputBlocked = false; }

 /** False when input was already clear, so unlock does not repeat its log. */
 boolean consumeInputBlock() {
  if (!inputBlocked) return false;
  inputBlocked = false;
  return true;
 }

 /** Release path before the cover closes. Keeps pending and createdFromGesture. */
 void clearReadiness() {
  frontReady = false;
  frameReady = false;
  framePending = false;
 }

 /** Task release of one slot. Keeps createdFromGesture and frontReady. */
 boolean releasePending(T candidate) {
  if (pending != candidate) return false;
  pending = null;
  frameReady = false;
  framePending = false;
  return true;
 }

 /** Portrait live frame. Does not require a pending entrance. */
 void markFrameReady() {
  framePending = false;
  frameReady = true;
 }

 void markFramePending() { framePending = true; }

 void clearFramePending() { framePending = false; }

 void clearFrameProgress() {
  framePending = false;
  frameReady = false;
 }

 /** Arrival already owns the spring. Clears the gesture source, not the frame flags. */
 void handOffToHang() {
  pending = null;
  createdFromGesture = false;
  frontReady = false;
 }

 void consumePending() {
  pending = null;
  createdFromGesture = false;
 }

 /** Gesture motion finished. Does not clear framePending. */
 void clearGestureReveal() {
  frontReady = false;
  frameReady = false;
 }

 boolean abandon(T candidate) {
  if (pending != candidate) return false;
  pending = null;
  frameReady = false;
  framePending = false;
  createdFromGesture = false;
  inputBlocked = false;
  return true;
 }

 /**
  * Read the flying slot after cover-close callbacks, then fall back to pending.
  * createdFromGesture is left as it was. The cover closes before the slot is taken.
  */
 T release(ReleaseEffects<T> effects) {
  effects.supersedeHang();
  effects.dropHangDue();
  clearReadiness();
  clearInputBlock();
  effects.closeCover();
  T shown = effects.shown();
  T taken = shown != null ? shown : pending;
  pending = null;
  effects.clearFlying();
  return taken;
 }
}
