package asia.lira.mercury.impl.cache;

import asia.lira.mercury.impl.FastMacro;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.Frame;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.MacroException;
import net.minecraft.server.function.Procedure;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

public final class MacroPrefetchRuntime {
    private MacroPrefetchRuntime() {
    }

    public static void prefetch(int planId) {
        MacroPrefetchRegistry.getInstance().prefetch(planId);
    }

    public static <T extends AbstractServerCommandSource<T>> void invokePrefetchedMacro(
            int planId,
            Object source,
            CommandExecutionContext<?> rawContext,
            Frame frame
    ) throws MacroException {
        @SuppressWarnings("unchecked")
        T typedSource = (T) source;
        @SuppressWarnings("unchecked")
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;
        Procedure<T> procedure = resolveMacro(planId, typedSource);
        CommandExecutionContext.enqueueProcedureCall(context, procedure, typedSource, typedSource.getReturnValueConsumer());
    }

    public static NbtCompound loadArgumentsForTier2(int planId) throws MacroException {
        MacroPrefetchRegistry registry = MacroPrefetchRegistry.getInstance();
        MacroPrefetchPlan plan = registry.plan(planId);
        if (plan == null) {
            throw new IllegalStateException("Missing macro prefetch plan " + planId);
        }
        MacroArgumentProvider provider = registry.activeProvider(planId);
        return provider != null ? provider.resolveArguments(plan.argumentNames()) : registry.loadArgumentsCompound(planId);
    }

    public static <T extends AbstractServerCommandSource<T>> void invokeExpandedMacroNoProfile(
            int planId,
            Object source,
            CommandExecutionContext<?> rawContext,
            Frame frame
    ) throws MacroException {
        // Tier-2 dispatch fallback path. Currently identical to invokePrefetchedMacro after the
        // 3-path consolidation; kept as a separate symbol so the JIT bytecode can keep dispatching
        // through a stable INVOKESTATIC target without a guard branch.
        invokePrefetchedMacro(planId, source, rawContext, frame);
    }

    public static <T extends AbstractServerCommandSource<T>> void invokePrefetchedMacroDirect(
            int planId,
            Object rawSource,
            CommandExecutionContext<?> rawContext,
            Frame frame
    ) throws MacroException {
        @SuppressWarnings("unchecked")
        T typedSource = (T) rawSource;
        @SuppressWarnings("unchecked")
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;
        Procedure<T> procedure = resolveMacro(planId, typedSource);

        // Inline CommandFunctionAction + SingleCommandAction as direct calls instead of queue entries.
        context.decrementCommandQuota();
        if (context.getTracer() != null) {
            context.getTracer().traceFunctionCall(frame.depth(), procedure.id(), procedure.entries().size());
        }
        int childDepth = frame.depth() + 1;
        DirectFrameControl childControl = new DirectFrameControl(context, childDepth);
        Frame childFrame = new Frame(childDepth, typedSource.getReturnValueConsumer(), childControl);
        for (var entry : procedure.entries()) {
            entry.execute(typedSource, context, childFrame);
            if (childControl.discarded()) {
                break;
            }
        }
    }

    /**
     * Shared prelude: resolves the procedure for {@code planId} by consulting active prefetch
     * cache, installed specializations and fallback NBT load. Updates stats, records the
     * invocation for profile collection, and installs any pending specialization compiled by
     * a previous invocation. Returns the resolved {@link Procedure} for dispatch.
     *
     * <p>Both {@link #invokePrefetchedMacro} and {@link #invokePrefetchedMacroDirect} share this
     * prelude; they differ only in whether the procedure is enqueued or run synchronously.
     */
    @SuppressWarnings("unchecked")
    private static <T extends AbstractServerCommandSource<T>> Procedure<T> resolveMacro(
            int planId,
            T typedSource
    ) throws MacroException {
        MacroPrefetchRegistry registry = MacroPrefetchRegistry.getInstance();
        MacroOptimizationCoordinator coord = MacroOptimizationCoordinator.getInstance();
        MacroPrefetchPlan plan = registry.plan(planId);
        if (plan == null) {
            throw new IllegalStateException("Missing macro prefetch plan " + planId);
        }

        coord.installPending(typedSource.getDispatcher());
        registry.onMacroWithStorageCall(planId);
        @Nullable MacroArgumentProvider provider = registry.activeProvider(planId);
        boolean prefetchHit = provider != null;
        NbtCompound arguments = provider != null
                ? provider.resolveArguments(plan.argumentNames())
                : registry.loadArgumentsCompound(planId);
        InstalledMacroSpecialization installed = coord.matchingInstalled(planId, arguments);
        boolean specializedUsed = false;
        boolean guardHit = false;
        Procedure<T> procedure;
        if (installed != null) {
            guardHit = true;
            specializedUsed = true;
            procedure = (Procedure<T>) installed.procedure();
        } else if (prefetchHit) {
            registry.recordHit();
            procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(provider, typedSource.getDispatcher());
        } else {
            registry.recordMiss();
            procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(arguments, typedSource.getDispatcher());
        }
        coord.recordInvocation(
                planId,
                plan.callsiteKey(),
                plan.argumentNames(),
                arguments,
                prefetchHit,
                specializedUsed,
                guardHit
        );
        coord.installPending(typedSource.getDispatcher());
        return procedure;
    }

    private static final class DirectFrameControl implements Frame.Control {
        private final CommandExecutionContext<?> context;
        private final int depth;
        private boolean discarded;

        private DirectFrameControl(CommandExecutionContext<?> context, int depth) {
            this.context = context;
            this.depth = depth;
        }

        @Override
        public void discard() {
            discarded = true;
            context.escape(depth);
        }

        private boolean discarded() {
            return discarded;
        }
    }

    public static void onStoreToMacroStorage(Identifier storageId) {
        MacroPrefetchRegistry.getInstance().onStoreToMacroStorage(storageId);
    }

    public static void onOpaqueStorageWrite(@Nullable Identifier storageId, String reason) {
        MacroPrefetchRegistry.getInstance().onOpaqueStorageWrite(storageId, reason);
    }
}
