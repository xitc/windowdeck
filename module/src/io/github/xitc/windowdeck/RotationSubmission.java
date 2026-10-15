package io.github.xitc.windowdeck;

/** Last rotation submitted with a layout change. A non-changing run leaves the record in place. */
final class RotationSubmission<T> {
 private int task = -1;
 private int generation;
 private T slot;

 void record(boolean changingLayout, int task, int generation, T slot) {
  if (!changingLayout) return;
  this.task = task;
  this.generation = generation;
  this.slot = slot;
 }

 int task() { return task; }
 int generation() { return generation; }
 T slot() { return slot; }
}
