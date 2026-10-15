package io.github.xitc.windowdeck;

/** Switch resize counters. The call increment happens before the elapsed time is added. */
final class ResizeStats {
 private int calls;
 private long nanos;

 void record(long elapsed) {
  calls++;
  nanos += elapsed;
 }

 void reset() { calls = 0; nanos = 0; }

 int calls() { return calls; }
 long nanos() { return nanos; }
}
