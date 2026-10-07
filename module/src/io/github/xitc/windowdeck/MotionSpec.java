package io.github.xitc.windowdeck;

/** Scene durations are centralized here. Ordinary entrances follow C17 rapid-reaction
 * rectangle timing (420 ms); replacement and multi-window retain separate scene timing.
 * The native 420 ms reference is rapidreaction/utils/c.java:75, with the path in utils/a.java.
 * Shelf/switch/fade remain module choices; the 600 ms stagger table comes from vivo. */
final class MotionSpec {
 /** Container entrance: full screen → card slot. */
 static final long ENTRANCE_MS = 420;
 static final long REPLACE_MS = 300;
 static final long MULTI_WINDOW_MS = 600;
 enum Scene { ADD, REPLACE, MULTI_WINDOW }
 /** A visible task joining the workbench. */
 static final long ADD_CARD_MS = 420;
 /** Re-arranging the cards that are already there (promote / pin / layout flip). */
 static final long SWITCH_MS = 360;
 /** The hang shelf sliding the live windows to the edge fold. */
 static final long SHELF_MS = 500;
 /** Snapshot → real card hand-off. Short on purpose: the two are already pixel-identical. */
 static final long CROSS_FADE_MS = 110;
 /** Watchdog for the entrance cover; a failure path must never leave it on screen. */
 static final long COVER_TIMEOUT_MS = 2000;
 /** Landscape release frame stays up until a non-black child frame, then still ends. */
 static final long LANDSCAPE_REVEAL_MS = 4000;
 /** Watchdog for "the workbench window has committed a frame". */
 static final long FRAME_TIMEOUT_MS = 250;
 /** Poll only while an entrance is waiting for a rebuilt task surface. */
 static final long SURFACE_RETRY_MS = 16;
 /** Unconditional entrance unlock. Kept as the last-resort escape hatch (was 1500 ms). */
 static final long ARRIVAL_UNLOCK_MS = 1500;
 /** The card that settles back after a cancelled drag. */
 static final long DRAG_SETTLE_MS = 240;
 /** The card that leaves the rail after a committed drag. */
 static final long DRAG_DISMISS_MS = 140;
 /** vivo stagger table, indexed by position id ({@code BasicLeashAnimation.DELAY_TIME}). */
 static final long[] DELAY_TIME = {0, 20, 40, 50, 60};

 /** Geometry family — the default for every plate-sized movement. */
 static float geometry(float fraction) {
  return SpringCurve.geometry(fraction);
 }

 /** Preview family — small chips and badges only. */
 static float preview(float fraction) {
  return SpringCurve.preview(fraction);
 }

 /** Stagger delay for a card index, clamped to the last entry. */
 static long delayFor(int index) {
  if (index <= 0) return DELAY_TIME[0];
  return DELAY_TIME[Math.min(index, DELAY_TIME.length - 1)];
 }

 /** Total length of a staggered run: the last card still travels the full {@code baseMs}. */
 static long staggeredTotal(long baseMs, int cardCount) {
  return baseMs + delayFor(cardCount - 1);
 }

 /** Display positions: primary 0, side rail 1..4, independent of the primary's list index. */
 static int entrancePosition(int slotIndex, int primaryIndex) {
  return slotIndex == primaryIndex ? 0 : Math.max(1, slotIndex < primaryIndex ? slotIndex+1 : slotIndex);
 }

 static long entranceTotal(int cardCount) {
  return ENTRANCE_MS;
 }
 static long duration(Scene scene){
  return scene==Scene.REPLACE?REPLACE_MS:scene==Scene.MULTI_WINDOW?MULTI_WINDOW_MS:ENTRANCE_MS;
 }
 static float entranceFraction(float fraction,Scene scene,int position){
  return scene==Scene.MULTI_WINDOW?staggered(fraction,position,MULTI_WINDOW_MS):fraction;
 }
 static long delayFor(Scene scene,int position){
  return scene==Scene.MULTI_WINDOW?delayFor(position):0;
 }

 /**
  * Re-times a global animator fraction for one card so the rail grows in sequence instead of
  * appearing on a single frame. Every card still finishes together at {@code fraction = 1},
  * which is exactly what vivo's {@code 600 - DELAY_TIME[n]} duration does.
  */
 static float staggered(float fraction, int index, long totalMs) {
  if (totalMs <= 0) return fraction;
  float offset = delayFor(index) / (float) totalMs;
  if (offset <= 0f) return fraction;
  float local = (fraction - offset) / (1f - offset);
  return local <= 0f ? 0f : (local >= 1f ? 1f : local);
 }

 static float lerp(float from, float to, float fraction) {
  return from + (to - from) * fraction;
 }

 /** Interpolate a screen-space radius, then convert it back to the scaled view's space.
  *  Multiplying here would scale the radius twice. */
 static float viewRadius(float fromScreen, float toScreen, float fraction, float scaleX, float scaleY) {
  return lerp(fromScreen, toScreen, fraction) / Math.max(1e-4f, Math.min(scaleX, scaleY));
 }

 private MotionSpec() {}
}
