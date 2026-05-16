package asia.lira.mercury.jit.runtime;

import asia.lira.mercury.Mercury;
import asia.lira.mercury.impl.FastMacro;
import asia.lira.mercury.impl.cache.MacroPrefetchLine;
import asia.lira.mercury.impl.cache.MacroPrefetchPlan;
import asia.lira.mercury.impl.cache.MacroPrefetchRegistry;
import asia.lira.mercury.impl.cache.MacroPrefetchRuntime;
import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import asia.lira.mercury.jit.registry.JitPreparationRegistry;
import asia.lira.mercury.jit.registry.OptimizedSlotRegistry;
import asia.lira.mercury.jit.registry.UnknownCommandBindingRegistry;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.Frame;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.Tracer;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.MacroException;
import net.minecraft.server.function.Procedure;
import net.minecraft.util.Identifier;

import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings({"unused", "UnusedReturnValue"})
public final class BaselineExecutionEngine {
    public static final AtomicLong DIRECT_CALLD_HITS = new AtomicLong();
    public static final AtomicLong DIRECT_CALLD_FALLBACKS = new AtomicLong();

    private BaselineExecutionEngine() {
    }

    public static void opCall(String functionId, ExecutionFrame frame, Object source, CommandExecutionContext<?> context) throws Throwable {
        ExecutionOutcome outcome = invokeCompiled(Identifier.of(functionId), frame, source, context, null, 0);
        if (outcome.mode() == ExecutionOutcome.Mode.FALLBACK) {
            throw new IllegalStateException("Missing compiled callee for " + functionId);
        }
    }

    public static ExecutionOutcome opJump(String functionId, ExecutionFrame frame, Object source, CommandExecutionContext<?> context) throws Throwable {
        return invokeCompiled(Identifier.of(functionId), frame, source, context, null, 0);
    }

    public static ExecutionOutcome completed() {
        return ExecutionOutcome.completed();
    }

    public static ExecutionOutcome returnValue(int returnValue) {
        return ExecutionOutcome.returnValue(returnValue);
    }

    public static ExecutionOutcome invokeCompiled(String functionId, ExecutionFrame frame, Object source, CommandExecutionContext<?> context, Frame commandFrame, int initialState) throws Throwable {
        return invokeCompiled(Identifier.of(functionId), frame, source, context, commandFrame, initialState);
    }

    public static void invokeBoundCommand(int bindingId, Object source, CommandExecutionContext<?> context, Frame commandFrame) throws Throwable {
        invokeBoundCommandInternal(bindingId, source, context, commandFrame);
    }

    public static void invokeActionFallback(int bindingId, Object source, CommandExecutionContext<?> context, Frame commandFrame) {
        invokeActionFallbackInternal(bindingId, source, context, commandFrame);
    }

    public static void ensureLoaded(ExecutionFrame frame, int[] slotIds) {
        for (int slotId : slotIds) {
            readSlot(frame, slotId);
        }
    }

    public static boolean tryDirectCalld(int planId, ExecutionFrame frame, Object source,
            CommandExecutionContext<?> context, Frame commandFrame) throws Throwable {
        MacroPrefetchPlan plan = MacroPrefetchRegistry.getInstance().plan(planId);
        if (plan == null || plan.functionDispatchArgIndex() < 0) {
            DIRECT_CALLD_FALLBACKS.incrementAndGet();
            return false;
        }
        MacroPrefetchLine line = MacroPrefetchRegistry.getInstance().line(planId);
        if (line == null || !line.isValid()) {
            DIRECT_CALLD_FALLBACKS.incrementAndGet();
            return false;
        }
        NbtElement argValue = line.valueAt(plan.functionDispatchArgIndex());
        if (argValue == null) {
            DIRECT_CALLD_FALLBACKS.incrementAndGet();
            return false;
        }
        String idStr = FastMacro.toString(argValue);
        Identifier targetId;
        try {
            targetId = Identifier.of(idStr);
        } catch (Exception ignored) {
            DIRECT_CALLD_FALLBACKS.incrementAndGet();
            return false;
        }
        BaselineCompiledFunctionRegistry.CompiledArtifact artifact =
                BaselineCompiledFunctionRegistry.getInstance().getArtifact(targetId);
        if (artifact == null || artifact.invokeEffectHandle() == null) {
            DIRECT_CALLD_FALLBACKS.incrementAndGet();
            return false;
        }
        ensureLoaded(frame, artifact.requiredSlots());
        artifact.invokeEffectHandle().invoke(frame, source, context, commandFrame);
        DIRECT_CALLD_HITS.incrementAndGet();
        return true;
    }

    public static int readSlot(ExecutionFrame frame, int slotId) {
        if (frame.isLoaded(slotId)) {
            return frame.getSlotValue(slotId);
        }

        OptimizedSlotRegistry.SlotMetadata metadata = JitPreparationRegistry.getInstance().slotRegistry().getSlot(slotId);
        if (metadata == null) {
            return 0;
        }

        Scoreboard scoreboard = Mercury.SERVER.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(metadata.key().objectiveName());
        if (objective == null) {
            frame.loadSlotValue(slotId, 0);
            return 0;
        }

        ReadableScoreboardScore score = scoreboard.getScore(ScoreHolder.fromName(metadata.key().holderName()), objective);
        int value = score == null ? 0 : score.getScore();
        frame.loadSlotValue(slotId, value);
        return value;
    }

    public static void resetSlot(ExecutionFrame frame, int slotId) {
        frame.invalidateSlot(slotId);

        OptimizedSlotRegistry.SlotMetadata metadata = JitPreparationRegistry.getInstance().slotRegistry().getSlot(slotId);
        if (metadata == null) {
            return;
        }

        Scoreboard scoreboard = Mercury.SERVER.getScoreboard();
        ScoreboardObjective objective = scoreboard.getNullableObjective(metadata.key().objectiveName());
        if (objective == null) {
            return;
        }
        scoreboard.removeScore(ScoreHolder.fromName(metadata.key().holderName()), objective);
    }

    private static ExecutionOutcome invokeCompiled(Identifier id, ExecutionFrame frame, Object source, CommandExecutionContext<?> context, Frame commandFrame, int initialState) throws Throwable {
        BaselineCompiledFunctionRegistry.CompiledArtifact artifact = BaselineCompiledFunctionRegistry.getInstance().getArtifact(id);
        if (artifact == null) {
            return ExecutionOutcome.fallback();
        }
        ensureLoaded(frame, artifact.requiredSlots());
        return artifact.invoke(frame, source, context, commandFrame, initialState);
    }

    @SuppressWarnings("unchecked")
    private static <T extends AbstractServerCommandSource<T>> void invokeBoundCommandInternal(
            int bindingId,
            Object source,
            CommandExecutionContext<?> rawContext,
            Frame commandFrame
    ) throws Throwable {
        T typedSource = (T) source;
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;
        if (commandFrame == null) {
            throw new IllegalStateException("Missing frame for bound command " + bindingId);
        }

        context.getProfiler().push(() -> "execute(bound) " + bindingId);
        try {
            context.decrementCommandQuota();
            int result = UnknownCommandBindingRegistry.getInstance().invokeBoundCommand(bindingId, typedSource);
            Tracer tracer = context.getTracer();
            if (tracer != null) {
                UnknownCommandBindingRegistry.BindingPlan plan = UnknownCommandBindingRegistry.getInstance().plan(bindingId);
                tracer.traceCommandEnd(commandFrame.depth(), plan == null ? "<bound>" : plan.sourceText(), result);
            }
            invalidateOpaqueStorage(bindingId);
        } catch (CommandSyntaxException exception) {
            typedSource.handleException(exception, false, context.getTracer());
        } finally {
            context.getProfiler().pop();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends AbstractServerCommandSource<T>> void invokeActionFallbackInternal(
            int bindingId,
            Object source,
            CommandExecutionContext<?> rawContext,
            Frame commandFrame
    ) {
        T typedSource = (T) source;
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;
        if (commandFrame == null) {
            throw new IllegalStateException("Missing frame for action fallback " + bindingId);
        }
        UnknownCommandBindingRegistry.getInstance().invokeActionFallback(bindingId, typedSource, context, commandFrame);
        invalidateOpaqueStorage(bindingId);
    }

    @SuppressWarnings("unchecked")
    private static <T extends AbstractServerCommandSource<T>> void invokeFunctionBridgeInternal(
            String sourceText,
            Object source,
            CommandExecutionContext<?> rawContext
    ) throws CommandSyntaxException, MacroException {
        T typedSource = (T) source;
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;

        ParsedFunctionInvocation invocation = ParsedFunctionInvocation.parse(sourceText);
        CommandFunction<T> function = (CommandFunction<T>) Mercury.SERVER.getCommandFunctionManager().getFunction(invocation.id())
                .orElseThrow(() -> new IllegalStateException("Missing function " + invocation.id()));

        NbtCompound arguments = switch (invocation.argumentKind()) {
            case NONE -> null;
            case INLINE -> StringNbtReader.parse(invocation.argumentPayload());
            case STORAGE -> readStorageArguments(invocation.storageId(), invocation.argumentPayload());
        };

        Procedure<T> procedure = function.withMacroReplaced(arguments, typedSource.getDispatcher());
        CommandExecutionContext.enqueueProcedureCall(context, procedure, typedSource, typedSource.getReturnValueConsumer());
    }

    private static NbtCompound readStorageArguments(Identifier storageId, String pathExpression) throws CommandSyntaxException {
        DataCommandStorage storage = Mercury.SERVER.getDataCommandStorage();
        NbtCompound root = storage.get(storageId);
        NbtPathArgumentType.NbtPath path = NbtPathArgumentType.nbtPath().parse(new StringReader(pathExpression));
        java.util.Collection<NbtElement> results = path.get(root);
        if (results.isEmpty()) {
            throw new IllegalStateException("Missing storage path " + pathExpression + " in " + storageId);
        }
        NbtElement first = results.iterator().next();
        if (first instanceof NbtCompound compound) {
            return compound.copy();
        }
        throw new IllegalStateException("Storage path " + pathExpression + " in " + storageId + " is not a compound");
    }

    private record ParsedFunctionInvocation(
            Identifier id,
            ArgumentKind argumentKind,
            String argumentPayload,
            Identifier storageId
    ) {
        static ParsedFunctionInvocation parse(String sourceText) {
            if (!sourceText.startsWith("function ")) {
                throw new IllegalArgumentException("Unsupported function bridge source: " + sourceText);
            }

            String remainder = sourceText.substring("function ".length()).trim();
            int firstSpace = remainder.indexOf(' ');
            if (firstSpace < 0) {
                return new ParsedFunctionInvocation(Identifier.of(remainder), ArgumentKind.NONE, "", null);
            }

            Identifier id = Identifier.of(remainder.substring(0, firstSpace));
            String suffix = remainder.substring(firstSpace + 1).trim();
            if (suffix.startsWith("{")) {
                return new ParsedFunctionInvocation(id, ArgumentKind.INLINE, suffix, null);
            }
            if (!suffix.startsWith("with storage ")) {
                throw new IllegalArgumentException("Unsupported function bridge suffix: " + sourceText);
            }

            String storageTail = suffix.substring("with storage ".length()).trim();
            int storageSpace = storageTail.indexOf(' ');
            if (storageSpace < 0) {
                throw new IllegalArgumentException("Missing storage path in function bridge: " + sourceText);
            }

            Identifier storageId = Identifier.of(storageTail.substring(0, storageSpace));
            String pathExpression = storageTail.substring(storageSpace + 1).trim();
            return new ParsedFunctionInvocation(id, ArgumentKind.STORAGE, pathExpression, storageId);
        }
    }

    private enum ArgumentKind {
        NONE,
        INLINE,
        STORAGE
    }

    private static void invalidateOpaqueStorage(int bindingId) {
        UnknownCommandBindingRegistry.BindingPlan plan = UnknownCommandBindingRegistry.getInstance().plan(bindingId);
        if (plan == null) {
            return;
        }
        MacroPrefetchRuntime.onOpaqueStorageWrite(extractStorageId(plan.sourceText()), "opaque-storage-command");
    }

    private static Identifier extractStorageId(String sourceText) {
        if (!sourceText.contains(" storage ")) {
            return null;
        }
        String[] parts = sourceText.trim().split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("storage".equals(parts[i])) {
                try {
                    return Identifier.of(parts[i + 1]);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public record ExecutionOutcome(
            Mode mode,
            int returnValue,
            int bindingId,
            int nextState
    ) {
        public static final ExecutionOutcome COMPLETED = new ExecutionOutcome(Mode.COMPLETE, 0, -1, -1);

        public static ExecutionOutcome completed() {
            return COMPLETED;
        }

        public static ExecutionOutcome returnValue(int returnValue) {
            return new ExecutionOutcome(Mode.RETURN, returnValue, -1, -1);
        }

        public static ExecutionOutcome suspend(int bindingId, int nextState) {
            return new ExecutionOutcome(Mode.SUSPEND, 0, bindingId, nextState);
        }

        public static ExecutionOutcome suspendPrefetch(int planId, int nextState) {
            return new ExecutionOutcome(Mode.SUSPEND_PREFETCH, 0, planId, nextState);
        }

        public static ExecutionOutcome fallback() {
            return new ExecutionOutcome(Mode.FALLBACK, 0, -1, -1);
        }

        public enum Mode {
            COMPLETE,
            RETURN,
            SUSPEND,
            SUSPEND_PREFETCH,
            FALLBACK
        }
    }
}
