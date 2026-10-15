package io.github.xitc.windowdeck;

import java.util.ArrayList;

/** Live card list and which card is primary. Flow flags stay on the controllers that own them. */
final class WorkbenchSession {
 final ArrayList<WorkbenchSlot> slots=new ArrayList<>();
 int primary;
 int nextId;
}
