package asia.lira.mercury.jit.specialized.impl.execute;

import asia.lira.mercury.jit.runtime.BaselineExecutionEngine;
import asia.lira.mercury.jit.runtime.ExecutionFrame;
import asia.lira.mercury.jit.registry.JitPreparationRegistry;
import asia.lira.mercury.jit.specialized.api.SpecializedExecutor;
import asia.lira.mercury.jit.specialized.impl.data.StorageAccessRuntime;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.predicate.NumberRange;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class ExecuteExecutor implements SpecializedExecutor<ExecutePlan> {
    public static final SimpleCommandExceptionType DIVISION_BY_ZERO_EXCEPTION =
            new SimpleCommandExceptionType(Text.translatable("arguments.operation.div0"));

    @Override
    public void execute(ExecutePlan plan, ExecutionFrame frame, ServerCommandSource source) throws Exception {
        List<PendingStore> pendingStores = new ArrayList<>();
        for (ExecuteModifier modifier : plan.modifiers()) {
            switch (modifier) {
                case IfScoreCompareModifier compareModifier -> {
                    if (!testScoreCompare(frame, source, compareModifier)) {
                        return;
                    }
                }
                case IfScoreMatchesModifier matchesModifier -> {
                    if (!testScoreMatches(frame, source, matchesModifier)) {
                        return;
                    }
                }
                case StoreScoreModifier scoreModifier ->
                        pendingStores.add(new ScoreStore(scoreModifier.requestResult(), scoreModifier.holder(), scoreModifier.objective()));
                case StoreStorageModifier storageModifier ->
                        pendingStores.add(new StorageStore(storageModifier.requestResult(), storageModifier.storageId(), storageModifier.path(), storageModifier.numericType(), storageModifier.scale()));
            }
        }

        CommandResult result = executeTerminal(plan.terminal(), frame, source);
        for (PendingStore pendingStore : pendingStores) {
            pendingStore.apply(frame, source, result);
        }
    }

    private static boolean testScoreCompare(
            ExecutionFrame frame,
            ServerCommandSource source,
            IfScoreCompareModifier modifier
    ) {
        int left = readScore(frame, source, modifier.targetHolder(), modifier.targetObjective());
        int right = readScore(frame, source, modifier.sourceHolder(), modifier.sourceObjective());
        return switch (modifier.operator()) {
            case "=" -> left == right;
            case "<" -> left < right;
            case "<=" -> left <= right;
            case ">" -> left > right;
            case ">=" -> left >= right;
            default -> false;
        };
    }

    private static boolean testScoreMatches(
            ExecutionFrame frame,
            ServerCommandSource source,
            IfScoreMatchesModifier modifier
    ) {
        int value = readScore(frame, source, modifier.targetHolder(), modifier.targetObjective());
        NumberRange.IntRange range = modifier.range();
        return range.test(value);
    }

    private static CommandResult executeTerminal(
            ExecuteTerminal terminal,
            ExecutionFrame frame,
            ServerCommandSource source
    ) throws Exception {
        return switch (terminal) {
            case ScoreTerminalPlan scoreTerminalPlan -> executeScoreTerminal(scoreTerminalPlan, frame, source);
            case DataStorageTerminalPlan dataStorageTerminalPlan -> executeDataTerminal(dataStorageTerminalPlan);
        };
    }

    private static CommandResult executeScoreTerminal(
            ScoreTerminalPlan plan,
            ExecutionFrame frame,
            ServerCommandSource source
    ) throws CommandSyntaxException {
        return switch (plan.operation()) {
            case SET -> {
                writeScore(frame, source, plan.targetHolder(), plan.targetObjective(), plan.value());
                yield new CommandResult(true, plan.value());
            }
            case ADD -> {
                int next = readScore(frame, source, plan.targetHolder(), plan.targetObjective()) + plan.value();
                writeScore(frame, source, plan.targetHolder(), plan.targetObjective(), next);
                yield new CommandResult(true, next);
            }
            case GET -> new CommandResult(true, readScore(frame, source, plan.targetHolder(), plan.targetObjective()));
            case RESET -> {
                resetScore(frame, source, plan.targetHolder(), plan.targetObjective());
                yield new CommandResult(true, 1);
            }
            case SCORE_OPERATION -> {
                int left = readScore(frame, source, plan.targetHolder(), plan.targetObjective());
                int right = readScore(frame, source, plan.sourceHolder(), plan.sourceObjective());
                int result = applyOperation(left, right, plan.scoreboardOperation());
                writeScore(frame, source, plan.targetHolder(), plan.targetObjective(), result);
                if ("><".equals(plan.scoreboardOperation())) {
                    writeScore(frame, source, plan.sourceHolder(), plan.sourceObjective(), left);
                }
                yield new CommandResult(true, result);
            }
        };
    }

    private static CommandResult executeDataTerminal(DataStorageTerminalPlan plan) throws Exception {
        return switch (plan.operation()) {
            case SET_VALUE -> new CommandResult(true, StorageAccessRuntime.setValue(plan.targetStorageId(), plan.targetPath(), plan.value()));
            case SET_FROM_STORAGE -> new CommandResult(true, StorageAccessRuntime.setFromStorage(plan.targetStorageId(), plan.targetPath(), plan.sourceStorageId(), plan.sourcePath()));
            case MERGE_VALUE -> new CommandResult(true, StorageAccessRuntime.mergeValue(plan.targetStorageId(), plan.targetPath(), plan.value()));
            case MERGE_FROM_STORAGE -> new CommandResult(true, StorageAccessRuntime.mergeFromStorage(plan.targetStorageId(), plan.targetPath(), plan.sourceStorageId(), plan.sourcePath()));
        };
    }

    public static int readScore(ExecutionFrame frame, ServerCommandSource source, String holder, String objective) {
        Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(holder, objective);
        if (slotId != null) {
            return BaselineExecutionEngine.readSlot(frame, slotId);
        }

        Scoreboard scoreboard = source.getServer().getScoreboard();
        ScoreboardObjective targetObjective = scoreboard.getNullableObjective(objective);
        if (targetObjective == null) {
            return 0;
        }
        ReadableScoreboardScore score = scoreboard.getScore(ScoreHolder.fromName(holder), targetObjective);
        return score == null ? 0 : score.getScore();
    }

    public static void writeScore(ExecutionFrame frame, ServerCommandSource source, String holder, String objective, int value) {
        Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(holder, objective);
        if (slotId != null) {
            frame.setSlotValue(slotId, value);
            return;
        }

        Scoreboard scoreboard = source.getServer().getScoreboard();
        ScoreboardObjective targetObjective = scoreboard.getNullableObjective(objective);
        if (targetObjective == null) {
            return;
        }
        ScoreAccess access = scoreboard.getOrCreateScore(ScoreHolder.fromName(holder), targetObjective);
        access.setScore(value);
    }

    public static void resetScore(ExecutionFrame frame, ServerCommandSource source, String holder, String objective) {
        Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(holder, objective);
        if (slotId != null) {
            BaselineExecutionEngine.resetSlot(frame, slotId);
            return;
        }

        Scoreboard scoreboard = source.getServer().getScoreboard();
        ScoreboardObjective targetObjective = scoreboard.getNullableObjective(objective);
        if (targetObjective != null) {
            scoreboard.removeScore(ScoreHolder.fromName(holder), targetObjective);
        }
    }

    public static int applyOperation(int left, int right, String operation) throws CommandSyntaxException {
        return switch (operation) {
            case "=" -> right;
            case "+=" -> left + right;
            case "-=" -> left - right;
            case "*=" -> left * right;
            case "/=" -> {
                if (right == 0) {
                    throw DIVISION_BY_ZERO_EXCEPTION.create();
                }
                yield Math.floorDiv(left, right);
            }
            case "%=" -> {
                if (right == 0) {
                    throw DIVISION_BY_ZERO_EXCEPTION.create();
                }
                yield Math.floorMod(left, right);
            }
            case "<" -> Math.min(left, right);
            case ">" -> Math.max(left, right);
            case "><" -> right;
            default -> throw new IllegalArgumentException("Unsupported scoreboard operation " + operation);
        };
    }

    private sealed interface PendingStore permits ScoreStore, StorageStore {
        void apply(ExecutionFrame frame, ServerCommandSource source, CommandResult result) throws Exception;
    }

    private record ScoreStore(boolean requestResult, String holder, String objective) implements PendingStore {
        @Override
        public void apply(ExecutionFrame frame, ServerCommandSource source, CommandResult result) {
            int value = requestResult ? result.returnValue() : (result.successful() ? 1 : 0);
            writeScore(frame, source, holder, objective, value);
        }
    }

    private record StorageStore(
            boolean requestResult,
            net.minecraft.util.Identifier storageId,
            NbtPathArgumentType.NbtPath path,
            String numericType,
            double scale
    ) implements PendingStore {
        @Override
        public void apply(ExecutionFrame frame, ServerCommandSource source, CommandResult result) throws Exception {
            int value = requestResult ? result.returnValue() : (result.successful() ? 1 : 0);
            StorageAccessRuntime.writeNumericValue(storageId, path, numericType, scale, value);
        }
    }
}
