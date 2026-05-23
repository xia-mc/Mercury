package asia.lira.mercury.impl.cache;

import asia.lira.mercury.Mercury;
import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.jit.spi.PrefetchPlanLookup;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.MacroException;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MacroPrefetchRegistry implements PrefetchPlanLookup {
    private static final MacroPrefetchRegistry INSTANCE = new MacroPrefetchRegistry();
    private static final int ACTIVATION_CALL_THRESHOLD = 2;
    private static final int ACTIVATION_STORE_FOLLOW_THRESHOLD = 1;

    private final MacroPrefetchAnalyzer analyzer = new MacroPrefetchAnalyzer();
    private final MacroPrefetchStats stats = new MacroPrefetchStats();
    private final Map<Integer, MacroPrefetchPlan> plansById = new LinkedHashMap<>();
    private final Map<Integer, MacroPrefetchLine> linesByPlanId = new LinkedHashMap<>();
    private final Map<Integer, Integer> planIdByBindingId = new LinkedHashMap<>();
    private int generation;

    private MacroPrefetchRegistry() {
    }

    public static MacroPrefetchRegistry getInstance() {
        return INSTANCE;
    }

    public void clear() {
        plansById.clear();
        linesByPlanId.clear();
        planIdByBindingId.clear();
        stats.reset();
        generation = 0;
    }

    public void rebuild(
            Collection<FunctionIrRegistry.ParsedFunctionIr> functions,
            Map<Identifier, ? extends CommandFunction<?>> loadedFunctions
    ) {
        clear();
        for (MacroPrefetchPlan plan : analyzer.analyze(functions, loadedFunctions)) {
            plansById.put(plan.id(), plan);
            linesByPlanId.put(plan.id(), new MacroPrefetchLine(plan.key(), plan.argumentNames()));
            planIdByBindingId.put(plan.bindingId(), plan.id());
        }
    }

    public @Nullable Integer planIdForBinding(int bindingId) {
        return planIdByBindingId.get(bindingId);
    }

    public @Nullable MacroPrefetchPlan plan(int planId) {
        return plansById.get(planId);
    }

    public @Nullable MacroPrefetchLine line(int planId) {
        return linesByPlanId.get(planId);
    }

    public List<MacroPrefetchPlan> candidatePlans() {
        return List.copyOf(plansById.values());
    }

    public List<MacroPrefetchPlan> activePlans() {
        List<MacroPrefetchPlan> active = new ArrayList<>();
        for (Map.Entry<Integer, MacroPrefetchPlan> entry : plansById.entrySet()) {
            MacroPrefetchLine line = linesByPlanId.get(entry.getKey());
            if (line != null && line.isActive()) {
                active.add(entry.getValue());
            }
        }
        return List.copyOf(active);
    }

    public MacroPrefetchStats stats() {
        return stats;
    }

    public void prefetch(int planId) {
        MacroPrefetchPlan plan = requirePlan(planId);
        MacroPrefetchLine line = requireLine(planId);
        if (!line.isValid()) {
            refreshLine(plan, line, "prefetch");
        }
    }

    public void onMacroWithStorageCall(int planId) {
        generation++;
        MacroPrefetchLine line = requireLine(planId);
        line.recordMacroCall(generation);
        if (!line.isActive()
                && line.recentMacroCalls() >= ACTIVATION_CALL_THRESHOLD
                && line.storeThenCallMatches() >= ACTIVATION_STORE_FOLLOW_THRESHOLD) {
            line.activate();
            stats.recordPromotion();
            refreshLine(requirePlan(planId), line, "activate");
        }
    }

    public void onStoreToMacroStorage(Identifier storageId) {
        generation++;
        for (Map.Entry<Integer, MacroPrefetchPlan> entry : plansById.entrySet()) {
            if (!entry.getValue().storageId().equals(storageId)) {
                continue;
            }
            MacroPrefetchLine line = requireLine(entry.getKey());
            line.recordStoreEvent(generation);
            if (line.isActive()) {
                refreshLine(entry.getValue(), line, "store-refresh");
            }
        }
    }

    public void onOpaqueStorageWrite(@Nullable Identifier storageId, String reason) {
        if (storageId == null) {
            invalidateAll(reason);
            return;
        }
        for (Map.Entry<Integer, MacroPrefetchPlan> entry : plansById.entrySet()) {
            if (entry.getValue().storageId().equals(storageId)) {
                MacroPrefetchLine line = requireLine(entry.getKey());
                if (line.isActive() || line.isValid()) {
                    line.invalidate(reason);
                    stats.recordEviction();
                }
            }
        }
    }

    public @Nullable MacroArgumentProvider activeProvider(int planId) {
        MacroPrefetchLine line = line(planId);
        if (line == null || !line.isActive() || !line.isValid()) {
            return null;
        }
        return line;
    }

    public NbtCompound loadArgumentsCompound(int planId) throws MacroException {
        MacroPrefetchPlan plan = requirePlan(planId);
        MacroPrefetchLine line = requireLine(planId);
        refreshLine(plan, line, "lazy-load");
        return line.resolveArguments(plan.argumentNames());
    }

    private void refreshLine(MacroPrefetchPlan plan, MacroPrefetchLine line, String reason) {
        try {
            DataCommandStorage storage = Mercury.SERVER.getDataCommandStorage();
            NbtCompound root = storage.get(plan.storageId());
            List<NbtElement> pathValues = List.copyOf(plan.storagePath().get(root));
            if (pathValues.isEmpty() || !(pathValues.get(pathValues.size() - 1) instanceof NbtCompound compound)) {
                line.invalidate(reason + ":not-compound");
                stats.recordFallback();
                return;
            }

            List<NbtElement> values = new ArrayList<>(plan.argumentNames().size());
            for (String argumentName : plan.argumentNames()) {
                NbtElement value = compound.get(argumentName);
                if (value == null) {
                    line.invalidate(reason + ":missing-" + argumentName);
                    stats.recordFallback();
                    return;
                }
                values.add(value);
            }
            line.refresh(values);
        } catch (Exception exception) {
            line.invalidate(reason + ":exception");
            stats.recordFallback();
        }
    }

    public void recordHit() {
        stats.recordHit();
    }

    public void recordMiss() {
        stats.recordMiss();
    }

    public void invalidateAll(String reason) {
        for (MacroPrefetchLine line : linesByPlanId.values()) {
            if (line.isActive() || line.isValid()) {
                line.invalidate(reason);
                stats.recordEviction();
            }
        }
    }

    private MacroPrefetchPlan requirePlan(int planId) {
        MacroPrefetchPlan plan = plansById.get(planId);
        if (plan == null) {
            throw new IllegalStateException("Missing macro prefetch plan " + planId);
        }
        return plan;
    }

    private MacroPrefetchLine requireLine(int planId) {
        MacroPrefetchLine line = linesByPlanId.get(planId);
        if (line == null) {
            throw new IllegalStateException("Missing macro prefetch line " + planId);
        }
        return line;
    }
}
