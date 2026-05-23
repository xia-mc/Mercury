package asia.lira.mercury.jit.runtime;

import asia.lira.mercury.impl.cache.MacroPrefetchRuntime;
import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import asia.lira.mercury.jit.registry.UnknownCommandBindingRegistry;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.CommandQueueEntry;
import net.minecraft.command.Frame;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.MacroException;

/**
 * Schedules continuation entries for JIT-compiled functions that suspend on either an action
 * fallback ({@link #scheduleAction}) or a prefetched macro call ({@link #schedulePrefetched}).
 * After dispatch, either enqueues a {@link BaselineContinuationAction} for the next state or
 * releases the owned frame if the suspension is terminal.
 */
public final class SuspensionScheduler {
    private SuspensionScheduler() {
    }

    public static <T extends AbstractServerCommandSource<T>> void scheduleAction(
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact,
            ExecutionFrame executionFrame,
            T source,
            CommandExecutionContext<T> context,
            Frame frame,
            int bindingId,
            int nextState,
            boolean ownsFrame
    ) {
        UnknownCommandBindingRegistry.BindingPlan plan = UnknownCommandBindingRegistry.getInstance().plan(bindingId);
        if (plan == null || plan.action() == null) {
            throw new IllegalStateException("Missing action fallback binding " + bindingId + " for suspension");
        }

        @SuppressWarnings("unchecked")
        SourcedCommandAction<T> action = (SourcedCommandAction<T>) plan.action();
        context.enqueueCommand(new CommandQueueEntry<>(frame, action.bind(source)));
        enqueueContinuationOrRelease(artifact, executionFrame, source, context, frame, nextState, ownsFrame);
    }

    public static <T extends AbstractServerCommandSource<T>> void schedulePrefetched(
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact,
            ExecutionFrame executionFrame,
            T source,
            CommandExecutionContext<T> context,
            Frame frame,
            int planId,
            int nextState,
            boolean ownsFrame
    ) {
        try {
            MacroPrefetchRuntime.invokePrefetchedMacroDirect(planId, source, context, frame);
        } catch (MacroException e) {
            BaselineExecutionEngine.handleMacroException(source, e, context, planId);
        }
        enqueueContinuationOrRelease(artifact, executionFrame, source, context, frame, nextState, ownsFrame);
    }

    private static <T extends AbstractServerCommandSource<T>> void enqueueContinuationOrRelease(
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact,
            ExecutionFrame executionFrame,
            T source,
            CommandExecutionContext<T> context,
            Frame frame,
            int nextState,
            boolean ownsFrame
    ) {
        if (nextState >= 0) {
            context.enqueueCommand(new CommandQueueEntry<>(frame, new BaselineContinuationAction<>(artifact, executionFrame, source, nextState, ownsFrame)));
        } else {
            FrameLifecycle.releaseIfOwned(executionFrame, ownsFrame);
        }
    }
}
