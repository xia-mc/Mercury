package asia.lira.mercury.jit.spi;

import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only view of the compiled-artifact catalog, exposed to JIT runtime and dispatch sites
 * that only need to look up compiled artifacts. The compilation orchestration (rebuild, tier2,
 * synthetic) stays on the concrete registry.
 */
public interface CompiledArtifactLookup {
    @Nullable
    BaselineCompiledFunctionRegistry.CompiledArtifact getArtifact(Identifier id);
}
