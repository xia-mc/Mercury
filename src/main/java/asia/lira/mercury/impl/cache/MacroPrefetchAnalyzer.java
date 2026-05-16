package asia.lira.mercury.impl.cache;

import asia.lira.mercury.impl.FastMacro;
import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.jit.registry.UnknownCommandBindingRegistry;
import asia.lira.mercury.mixin.accessor.MixinMacroVariableLineAccessor;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.util.Identifier;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MacroPrefetchAnalyzer {
    private static final Pattern FUNCTION_WITH_STORAGE_PATTERN = Pattern.compile("^function\\s+(\\S+)\\s+with\\s+storage\\s+(\\S+)\\s+(.+)$");
    private static final Pattern EXECUTE_STORE_STORAGE_PATTERN = Pattern.compile("^execute\\s+store\\s+(?:result|success)\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+\\S+\\s+\\S+\\s+run\\s+scoreboard\\s+players\\s+get\\s+(\\S+)\\s+(\\S+)$");
    private static final Pattern DATA_MODIFY_SET_VALUE_PATTERN = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+set\\s+value\\s+(.+)$");

    public List<MacroPrefetchPlan> analyze(
            Collection<FunctionIrRegistry.ParsedFunctionIr> functions,
            Map<Identifier, ? extends CommandFunction<?>> loadedFunctions
    ) {
        Map<MacroPrefetchKey, MacroPrefetchPlan> plansByKey = new LinkedHashMap<>();
        int nextPlanId = 0;

        for (FunctionIrRegistry.ParsedFunctionIr function : functions) {
            List<FunctionIrRegistry.ParseNode> nodes = function.nodes();
            for (int nodeIndex = 0; nodeIndex < nodes.size(); nodeIndex++) {
                if (!(nodes.get(nodeIndex) instanceof FunctionIrRegistry.CommandParseNode commandNode)) {
                    continue;
                }

                Matcher matcher = FUNCTION_WITH_STORAGE_PATTERN.matcher(commandNode.sourceText());
                if (!matcher.matches()) {
                    continue;
                }

                Identifier macroFunctionId = Identifier.of(matcher.group(1));
                CommandFunction<?> functionTarget = loadedFunctions.get(macroFunctionId);
                if (!(functionTarget instanceof FastMacro<?> fastMacro)) {
                    continue;
                }

                Identifier storageId = Identifier.of(matcher.group(2));
                String storagePathExpression = matcher.group(3).trim();
                NbtPathArgumentType.NbtPath storagePath = parsePath(storagePathExpression);
                if (storagePath == null) {
                    continue;
                }

                MacroPrefetchKey key = new MacroPrefetchKey(storageId, storagePathExpression, macroFunctionId, fastMacro.varNames);
                MacroPrefetchPlan plan = plansByKey.get(key);
                if (plan == null) {
                    Integer bindingId = UnknownCommandBindingRegistry.getInstance().bindingId(function.id(), nodeIndex);
                    if (bindingId == null) {
                        continue;
                    }

                    Map<String, String> observedFieldSources = collectObservedFields(function, storageId, storagePathExpression, fastMacro.varNames);
                    if (observedFieldSources.isEmpty()) {
                        continue;
                    }

                    plan = new MacroPrefetchPlan(
                            nextPlanId++,
                            key,
                            new MacroCallsiteKey(function.id(), nodeIndex, macroFunctionId, storageId, storagePathExpression),
                            function.id(),
                            nodeIndex,
                            bindingId,
                            fastMacro,
                            macroFunctionId,
                            storageId,
                            storagePathExpression,
                            storagePath,
                            fastMacro.varNames,
                            observedFieldSources,
                            buildSummary(macroFunctionId, storageId, storagePathExpression, fastMacro.varNames, observedFieldSources),
                            detectFunctionDispatchArgIndex(fastMacro)
                    );
                    plansByKey.put(key, plan);
                }
            }
        }

        return List.copyOf(plansByKey.values());
    }

    private static Map<String, String> collectObservedFields(
            FunctionIrRegistry.ParsedFunctionIr function,
            Identifier storageId,
            String storagePathExpression,
            List<String> argumentNames
    ) {
        Set<String> expected = new LinkedHashSet<>(argumentNames);
        Map<String, String> observed = new LinkedHashMap<>();
        String fieldPrefix = storagePathExpression + ".";

        for (FunctionIrRegistry.ParseNode node : function.nodes()) {
            if (!(node instanceof FunctionIrRegistry.CommandParseNode commandNode)) {
                continue;
            }

            Matcher executeStoreMatcher = EXECUTE_STORE_STORAGE_PATTERN.matcher(commandNode.sourceText());
            if (executeStoreMatcher.matches() && Identifier.of(executeStoreMatcher.group(1)).equals(storageId)) {
                String fullPath = executeStoreMatcher.group(2);
                if (fullPath.startsWith(fieldPrefix)) {
                    String field = fullPath.substring(fieldPrefix.length());
                    if (expected.contains(field)) {
                        observed.put(field, "execute-store:" + executeStoreMatcher.group(3) + "/" + executeStoreMatcher.group(4));
                    }
                }
            }

            Matcher setValueMatcher = DATA_MODIFY_SET_VALUE_PATTERN.matcher(commandNode.sourceText());
            if (setValueMatcher.matches()
                    && Identifier.of(setValueMatcher.group(1)).equals(storageId)
                    && setValueMatcher.group(2).equals(storagePathExpression)) {
                try {
                    if (StringNbtReader.parse(setValueMatcher.group(3)) instanceof NbtCompound compound) {
                        for (String key : compound.getKeys()) {
                            if (expected.contains(key)) {
                                observed.putIfAbsent(key, "default-value");
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return observed;
    }

    private static String buildSummary(
            Identifier macroFunctionId,
            Identifier storageId,
            String storagePathExpression,
            List<String> argumentNames,
            Map<String, String> observedFieldSources
    ) {
        return "macro=" + macroFunctionId
                + " storage=" + storageId + "/" + storagePathExpression
                + " args=" + argumentNames
                + " observed=" + observedFieldSources;
    }

    private static int detectFunctionDispatchArgIndex(FastMacro<?> fastMacro) {
        if (fastMacro.lines.size() != 1) return -1;
        Object line = fastMacro.lines.get(0);
        if (!(line instanceof MixinMacroVariableLineAccessor<?> accessor)) return -1;
        java.util.List<String> segments = accessor.mercury$getInvocation().segments();
        if (segments.size() != 2 || !segments.get(0).equals("function ") || !segments.get(1).isEmpty()) return -1;
        IntList variableIndices = accessor.mercury$getVariableIndices();
        if (variableIndices.size() != 1 || variableIndices.getInt(0) != 0) return -1;
        return 0;
    }

    private static NbtPathArgumentType.NbtPath parsePath(String expression) {
        try {
            return NbtPathArgumentType.nbtPath().parse(new com.mojang.brigadier.StringReader(expression));
        } catch (Exception ignored) {
            return null;
        }
    }
}
