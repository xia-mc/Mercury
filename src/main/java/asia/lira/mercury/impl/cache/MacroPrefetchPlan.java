package asia.lira.mercury.impl.cache;

import asia.lira.mercury.impl.FastMacro;

import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;

public record MacroPrefetchPlan(
        int id,
        MacroPrefetchKey key,
        MacroCallsiteKey callsiteKey,
        Identifier ownerFunctionId,
        int nodeIndex,
        int bindingId,
        FastMacro<?> macro,
        Identifier macroFunctionId,
        Identifier storageId,
        String storagePathExpression,
        NbtPathArgumentType.NbtPath storagePath,
        List<String> argumentNames,
        Map<String, String> observedFieldSources,
        String generatedMacroSummary,
        int functionDispatchArgIndex,
        boolean noFunctionCalls
) {
    public MacroPrefetchPlan {
        argumentNames = List.copyOf(argumentNames);
        observedFieldSources = Map.copyOf(observedFieldSources);
    }
}
