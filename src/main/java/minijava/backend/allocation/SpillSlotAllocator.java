package minijava.backend.allocation;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import minijava.backend.registers.VirtualRegister;

class SpillSlotAllocator {

  public final Map<VirtualRegister, Integer> spillSlots = new HashMap<>();
  private final SortedSet<Integer> freeSpillSlots = new TreeSet<>();

  public void allocateSpillSlot(VirtualRegister register) {
    Integer slot = spillSlots.get(register);
    if (slot != null) {
      // We already assigned a spill slot
      return;
    }
    // When we can't reuse a spill slot, we just allocate a new one, which (assuming contiguous use
    // of spill slots) will have index spillSlots.size().
    slot = spillSlots.size();
    if (!freeSpillSlots.isEmpty()) {
      slot = freeSpillSlots.first();
      freeSpillSlots.remove(slot);
    }
    spillSlots.put(register, slot);
  }

  public void freeSpillSlot(VirtualRegister register) {
    Integer slot = spillSlots.get(register);
    if (slot == null) {
      // This value wasn't spilled
      return;
    }
    freeSpillSlots.add(slot);
  }
}
