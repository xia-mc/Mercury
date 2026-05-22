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

    @SuppressWarnings("unchecked")
    public static <T extends AbstractServerCommandSource<T>> void invokePrefetchedMacro(
            int planId,
            Object source,
            CommandExecutionContext<?> rawContext,
            Frame frame
    ) throws MacroException {
        T typedSource = (T) source;
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;
        MacroPrefetchRegistry registry = MacroPrefetchRegistry.getInstance();
        MacroPrefetchPlan plan = registry.plan(planId);
        if (plan == null) {
            throw new IllegalStateException("Missing macro prefetch plan " + planId);
        }

        MacroOptimizationCoordinator.getInstance().installPending(typedSource.getDispatcher());
        registry.onMacroWithStorageCall(planId);
        @Nullable MacroArgumentProvider provider = registry.activeProvider(planId);
        boolean prefetchHit = provider != null;
        NbtCompound arguments = provider != null
                ? provider.resolveArguments(plan.argumentNames())
                : registry.loadArgumentsCompound(planId);
        Procedure<T> procedure;
        InstalledMacroSpecialization installed = MacroOptimizationCoordinator.getInstance().matchingInstalled(planId, arguments);
        boolean specializedUsed = false;
        boolean guardHit = false;
        if (installed != null) {
            guardHit = true;
            specializedUsed = true;
            procedure = (Procedure<T>) installed.procedure();
        } else {
            if (prefetchHit) {
                registry.recordHit();
                procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(provider, typedSource.getDispatcher());
            } else {
                registry.recordMiss();
                procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(arguments, typedSource.getDispatcher());
            }
        }
        MacroOptimizationCoordinator.getInstance().recordInvocation(
                planId,
                plan.callsiteKey(),
                plan.argumentNames(),
                arguments,
                prefetchHit,
                specializedUsed,
                guardHit
        );
        MacroOptimizationCoordinator.getInstance().installPending(typedSource.getDispatcher());
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

    @SuppressWarnings("unchecked")
    public static <T extends AbstractServerCommandSource<T>> void invokeExpandedMacroNoProfile(
            int planId,
            Object source,
            CommandExecutionContext<?> rawContext,
            Frame frame
    ) throws MacroException {
        T typedSource = (T) source;
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;
        MacroPrefetchRegistry registry = MacroPrefetchRegistry.getInstance();
        MacroPrefetchPlan plan = registry.plan(planId);
        if (plan == null) {
            throw new IllegalStateException("Missing macro prefetch plan " + planId);
        }
        MacroOptimizationCoordinator.getInstance().installPending(typedSource.getDispatcher());
        registry.onMacroWithStorageCall(planId);
        MacroArgumentProvider provider = registry.activeProvider(planId);
        boolean prefetchHit = provider != null;
        NbtCompound arguments = provider != null
                ? provider.resolveArguments(plan.argumentNames())
                : registry.loadArgumentsCompound(planId);
        InstalledMacroSpecialization installed = MacroOptimizationCoordinator.getInstance().matchingInstalled(planId, arguments);
        boolean specializedUsed = false;
        boolean guardHit = false;
        Procedure<T> procedure;
        if (installed != null) {
            guardHit = true;
            specializedUsed = true;
            procedure = (Procedure<T>) installed.procedure();
        } else if (provider != null) {
            registry.recordHit();
            procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(provider, typedSource.getDispatcher());
        } else {
            registry.recordMiss();
            procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(arguments, typedSource.getDispatcher());
        }
        MacroOptimizationCoordinator.getInstance().recordInvocation(
                planId,
                plan.callsiteKey(),
                plan.argumentNames(),
                arguments,
                prefetchHit,
                specializedUsed,
                guardHit
        );
        MacroOptimizationCoordinator.getInstance().installPending(typedSource.getDispatcher());
        CommandExecutionContext.enqueueProcedureCall(context, procedure, typedSource, typedSource.getReturnValueConsumer());
    }

    @SuppressWarnings("unchecked")
    public static <T extends AbstractServerCommandSource<T>> void invokePrefetchedMacroDirect(
            int planId,
            Object rawSource,
            CommandExecutionContext<?> rawContext,
            Frame frame
    ) throws MacroException {
        T typedSource = (T) rawSource;
        CommandExecutionContext<T> context = (CommandExecutionContext<T>) rawContext;
        MacroPrefetchRegistry registry = MacroPrefetchRegistry.getInstance();
        MacroPrefetchPlan plan = registry.plan(planId);
        if (plan == null) {
            throw new IllegalStateException("Missing macro prefetch plan " + planId);
        }

        MacroOptimizationCoordinator.getInstance().installPending(typedSource.getDispatcher());
        registry.onMacroWithStorageCall(planId);
        @Nullable MacroArgumentProvider provider = registry.activeProvider(planId);
        boolean prefetchHit = provider != null;
        NbtCompound arguments = provider != null
                ? provider.resolveArguments(plan.argumentNames())
                : registry.loadArgumentsCompound(planId);
        InstalledMacroSpecialization installed = MacroOptimizationCoordinator.getInstance().matchingInstalled(planId, arguments);
        boolean specializedUsed = false;
        boolean guardHit = false;
        Procedure<T> procedure;
        if (installed != null) {
            guardHit = true;
            specializedUsed = true;
            procedure = (Procedure<T>) installed.procedure();
        } else {
            if (prefetchHit) {
                registry.recordHit();
                procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(provider, typedSource.getDispatcher());
            } else {
                registry.recordMiss();
                procedure = ((FastMacro<T>) plan.macro()).withMacroReplaced(arguments, typedSource.getDispatcher());
            }
        }
        MacroOptimizationCoordinator.getInstance().recordInvocation(
                planId,
                plan.callsiteKey(),
                plan.argumentNames(),
                arguments,
                prefetchHit,
                specializedUsed,
                guardHit
        );
        MacroOptimizationCoordinator.getInstance().installPending(typedSource.getDispatcher());

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
