package io.github.xitc.windowdeck;

/** Runtime switches for the two entrance changes that still need a device pass.
 *
 *  <p>Both alter how the ROM's own window machinery is driven, so they default to the behaviour
 *  that was already shipping. Flip them per launch with an intent extra rather than by editing
 *  code or branching the tree — same shape as {@link AtomicPresentation#EXTRA}.
 *
 *  <p>Extra on the workbench intent, {@code boolean}:
 *  <pre>
 *  windowdeck_keep_surface  → experimental container move-to-back (TODO A1-3).
 *                             Moving a task can still stop its Activity; this switch alone does
 *                             not prove that the ROM retains the window surface.
 *  windowdeck_detach_leash  → unhook the module's task leash before the ROM's zoom transition
 *                             takes over during fullscreen EXIT. This is not the swipe entrance
 *                             fix: LauncherSwipeHook now waits for source-controller completion
 *                             before embedding. Exit detachment still needs device validation.
 *  </pre>
 */
final class TransitionPolicy {
 /** {@link #EXTRA_KEEP_SURFACE}: keep the container surface alive while backgrounded. */
 static final boolean KEEP_SURFACE_DEFAULT=false;
 static final String EXTRA_KEEP_SURFACE="windowdeck_keep_surface";
 /** {@link #EXTRA_DETACH_LEASH}: release our leash before the ROM's zoom transition. */
 static final boolean DETACH_LEASH_DEFAULT=false;
 static final String EXTRA_DETACH_LEASH="windowdeck_detach_leash";

 /** Embedded input stays blocked while a primary plate moves. Side previews can interrupt
  *  an entrance only after the host has submitted its first frame. */
 static boolean gesturesEnabled(boolean primary,boolean recovering,boolean entranceRunning,boolean framePending){
  return !recovering&&(!entranceRunning||(!primary&&!framePending));
 }

 static boolean startAccepted(int result){return result>=0&&result<=99;}

 private TransitionPolicy(){}
}
