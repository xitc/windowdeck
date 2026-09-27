package dev.windowdeck.app;

/** Workbench capacity, kept in one place so the layout, the entry policy and the
 *  cross-process state bridge cannot drift apart.
 *
 *  <p>The original workbench allows five tasks — vivo OriginOS
 *  {@code VivoMultiTaskPolicyManager.MAX_VIVO_MULTI_TASKS_SIZE = 5}
 *  (analysis/wmshell/sources/com/android/wm/shell/vivomultitask/policy). */
final class Caps {
  /** Total live tasks: one main window plus {@link #MAX_SIDE_SLOTS} side cards. */
  static final int MAX_TASKS = 5;
  /** Side-plate slots in the rail, including the ＋ add slot while the rail is not full. */
  static final int MAX_SIDE_SLOTS = MAX_TASKS - 1;
  private Caps(){}
}
