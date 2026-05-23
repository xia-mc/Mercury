package asia.lira.mercury.jit.registry;

import asia.lira.mercury.jit.pipeline.SlotKey;
import asia.lira.mercury.jit.spi.SlotMetadataLookup;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OptimizedSlotRegistry implements SlotMetadataLookup {
    private final Map<SlotKey, Integer> slotIds = new LinkedHashMap<>();
    private final List<SlotMetadata> slots = new ArrayList<>();
    private final Map<String, List<Integer>> objectiveToSlots = new LinkedHashMap<>();
    private final Map<String, List<Integer>> holderToSlots = new LinkedHashMap<>();

    public void clear() {
        slotIds.clear();
        slots.clear();
        objectiveToSlots.clear();
        holderToSlots.clear();
    }

    public int register(SlotKey key, int objectiveId) {
        Integer existing = slotIds.get(key);
        if (existing != null) {
            return existing;
        }

        int slotId = slots.size();
        slotIds.put(key, slotId);
        slots.add(new SlotMetadata(slotId, key, objectiveId));
        objectiveToSlots.computeIfAbsent(key.objectiveName(), ignored -> new ArrayList<>()).add(slotId);
        holderToSlots.computeIfAbsent(key.holderName(), ignored -> new ArrayList<>()).add(slotId);
        return slotId;
    }

    public @Nullable Integer getSlotId(String holderName, String objectiveName) {
        return slotIds.get(new SlotKey(holderName, objectiveName));
    }

    public @Nullable SlotMetadata getSlot(int slotId) {
        if (slotId < 0 || slotId >= slots.size()) {
            return null;
        }
        return slots.get(slotId);
    }

    public List<Integer> getSlotsForObjective(String objectiveName) {
        List<Integer> slots = objectiveToSlots.get(objectiveName);
        return slots == null ? List.of() : List.copyOf(slots);
    }

    public List<Integer> getSlotsForHolder(String holderName) {
        List<Integer> slots = holderToSlots.get(holderName);
        return slots == null ? List.of() : List.copyOf(slots);
    }

    public int count() {
        return slots.size();
    }

    public record SlotMetadata(
            int slotId,
            SlotKey key,
            int objectiveId
    ) {
    }
}
