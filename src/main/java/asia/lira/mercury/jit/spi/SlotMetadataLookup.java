package asia.lira.mercury.jit.spi;

import asia.lira.mercury.jit.registry.OptimizedSlotRegistry;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of the slot-metadata table. Runtime needs {@link #getSlot} for flush and
 * reload; preparation needs {@link #count} when sizing frames. Mutation (register/clear)
 * stays on the concrete {@code OptimizedSlotRegistry}.
 */
public interface SlotMetadataLookup {
    @Nullable
    OptimizedSlotRegistry.SlotMetadata getSlot(int slotId);

    int count();
}
