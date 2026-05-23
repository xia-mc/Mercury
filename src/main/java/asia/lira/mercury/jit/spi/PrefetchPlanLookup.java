package asia.lira.mercury.jit.spi;

import asia.lira.mercury.impl.cache.MacroPrefetchLine;
import asia.lira.mercury.impl.cache.MacroPrefetchPlan;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of the macro-prefetch catalog, exposed to JIT runtime, codegen and pass layers
 * that only need to look up plans/lines without mutating the catalog or its activation state.
 *
 * <p>Lives in {@code jit/spi} so cross-cutting consumers can depend on the lookup contract
 * without pulling in the orchestration internals of {@code MacroPrefetchRegistry}.
 */
public interface PrefetchPlanLookup {
    @Nullable
    MacroPrefetchPlan plan(int planId);

    @Nullable
    MacroPrefetchLine line(int planId);

    @Nullable
    Integer planIdForBinding(int bindingId);
}
