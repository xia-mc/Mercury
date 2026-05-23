package asia.lira.mercury.jit;

import asia.lira.mercury.impl.cache.MacroPrefetchRegistry;
import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import asia.lira.mercury.jit.registry.JitPreparationRegistry;
import asia.lira.mercury.jit.spi.CompiledArtifactLookup;
import asia.lira.mercury.jit.spi.PrefetchPlanLookup;
import asia.lira.mercury.jit.spi.SlotMetadataLookup;

/**
 * Aggregates the SPI lookups required by the JIT runtime / codegen / pass layers into a single
 * injectable dependency surface. Holds {@code @Stable}-style {@code final} references so JIT
 * compilation of consumer methods can devirtualize the SPI calls.
 *
 * <p>This is the lightweight DI step on top of the SPI interfaces introduced in
 * {@code jit/spi}. Production code resolves a JitContext via {@link #defaultContext()}, which
 * delegates to the existing singletons; tests can construct a {@code JitContext} with mock
 * implementations to exercise runtime/codegen logic without the full server stack.
 *
 * <p>Existing static {@code getInstance()} call sites continue to work — this class doesn't
 * force migration. Adopting it is a per-call-site choice driven by testability needs.
 */
public final class JitContext {
    private static final JitContext DEFAULT = new JitContext(
            MacroPrefetchRegistry.getInstance(),
            BaselineCompiledFunctionRegistry.getInstance(),
            JitPreparationRegistry.getInstance().slotRegistry()
    );

    private final PrefetchPlanLookup prefetchPlans;
    private final CompiledArtifactLookup compiledArtifacts;
    private final SlotMetadataLookup slotMetadata;

    public JitContext(
            PrefetchPlanLookup prefetchPlans,
            CompiledArtifactLookup compiledArtifacts,
            SlotMetadataLookup slotMetadata
    ) {
        this.prefetchPlans = prefetchPlans;
        this.compiledArtifacts = compiledArtifacts;
        this.slotMetadata = slotMetadata;
    }

    /**
     * Returns the process-wide default context backed by the production singletons. Use in
     * runtime / static-dispatch sites that don't yet take an explicit JitContext.
     */
    public static JitContext defaultContext() {
        return DEFAULT;
    }

    public PrefetchPlanLookup prefetchPlans() {
        return prefetchPlans;
    }

    public CompiledArtifactLookup compiledArtifacts() {
        return compiledArtifacts;
    }

    public SlotMetadataLookup slotMetadata() {
        return slotMetadata;
    }
}
