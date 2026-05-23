package asia.lira.mercury.jit;

import asia.lira.mercury.impl.cache.MacroPrefetchLine;
import asia.lira.mercury.impl.cache.MacroPrefetchPlan;
import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import asia.lira.mercury.jit.registry.OptimizedSlotRegistry;
import asia.lira.mercury.jit.spi.CompiledArtifactLookup;
import asia.lira.mercury.jit.spi.PrefetchPlanLookup;
import asia.lira.mercury.jit.spi.SlotMetadataLookup;
import net.minecraft.util.Identifier;

/**
 * Smoke test that a JitContext can be constructed with mock SPI implementations and queried
 * without any of the production singletons being initialized. This is the testability payoff
 * of B1's SPI extraction + B7's JitContext aggregation: previously, runtime/codegen code
 * implicitly depended on a live Minecraft server because every registry was a singleton.
 */
public final class JitContextTest {
    private JitContextTest() {
    }

    public static void main(String[] args) {
        assertContextWiresMockSpiImplementations();
    }

    private static void assertContextWiresMockSpiImplementations() {
        PrefetchPlanLookup prefetch = new PrefetchPlanLookup() {
            @Override
            public MacroPrefetchPlan plan(int planId) {
                return null;
            }

            @Override
            public MacroPrefetchLine line(int planId) {
                return null;
            }

            @Override
            public Integer planIdForBinding(int bindingId) {
                return null;
            }
        };
        CompiledArtifactLookup artifacts = id -> null;
        SlotMetadataLookup slots = new SlotMetadataLookup() {
            @Override
            public OptimizedSlotRegistry.SlotMetadata getSlot(int slotId) {
                return null;
            }

            @Override
            public int count() {
                return 42;
            }
        };

        JitContext ctx = new JitContext(prefetch, artifacts, slots);

        if (ctx.prefetchPlans() != prefetch) {
            throw new AssertionError("PrefetchPlanLookup not wired");
        }
        if (ctx.compiledArtifacts() != artifacts) {
            throw new AssertionError("CompiledArtifactLookup not wired");
        }
        if (ctx.slotMetadata() != slots) {
            throw new AssertionError("SlotMetadataLookup not wired");
        }
        if (ctx.slotMetadata().count() != 42) {
            throw new AssertionError("SPI dispatch broken: expected count=42");
        }
        if (ctx.compiledArtifacts().getArtifact(Identifier.of("test", "missing")) != null) {
            throw new AssertionError("Mock artifact lookup should return null");
        }
    }
}
