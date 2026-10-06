package io.github.xitc.windowdeck;

/** The stylised atomic-window presentation: side plates drawn as trapezoids.
 *
 *  <p>Off by default. While it is off a side window is a plain rectangle. While
 *  it is on, the edge of the plate facing the main window is shortened, in both
 *  the left-right and the top-bottom rail. The main window is never tapered.
 *
 *  <p>This switch covers the <em>shape</em> only. A landscape task is rotated
 *  into an upright plate by {@link OrientationPolicy#rotatePresentation} whether
 *  or not this is on.
 *
 *  <p>The entry screen owns the switch. It reaches the workbench as the
 *  {@link #EXTRA} intent extra, because the workbench runs inside the pscanvas
 *  process and cannot read this app's own preferences. */
final class AtomicPresentation {
 /** Off unless the user turns it on. */
 static final boolean DEFAULT=false;
 /** {@code boolean} extra on the workbench intent. */
 static final String EXTRA="windowdeck_atomic_rotate";

 /** Only side windows get the trapezoid; the main window stays a rectangle. */
 static boolean perspective(boolean enabled,boolean preview){
  return enabled&&preview;
 }

 private AtomicPresentation(){}
}
