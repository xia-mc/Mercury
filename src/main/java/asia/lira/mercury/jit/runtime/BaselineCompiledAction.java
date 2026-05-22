package asia.lira.mercury.jit.runtime;

import asia.lira.mercury.impl.cache.MacroPrefetchRuntime;
import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import asia.lira.mercury.jit.registry.JitPreparationRegistry;
import asia.lira.mercury.jit.registry.Tier2CompilationCoordinator;
import asia.lira.mercury.jit.registry.UnknownCommandBindingRegistry;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.CommandQueueEntry;
import net.minecraft.command.Frame;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.MacroException;

public final class BaselineCompiledAction<T extends AbstractServerCommandSource<T>> implements SourcedCommandAction<T> {
    private final BaselineCompiledFunctionRegistry.CompiledArtifact artifact;

    public BaselineCompiledAction(BaselineCompiledFunctionRegistry.CompiledArtifact artifact) {
        this.artifact = artifact;
    }

    @Override
    public void execute(T source, CommandExecutionContext<T> context, Frame frame) {
        Tier2CompilationCoordinator.getInstance().onFunctionInvocation(artifact.program().id(), source.getDispatcher());
        SynchronizationRuntime runtime = SynchronizationRuntime.getInstance();
        ExecutionFrame current = runtime.currentFrame();
        boolean ownsFrame = false;
        if (current == null) {
            current = new ExecutionFrame(artifact.program().id(), JitPreparationRegistry.getInstance().slotRegistry().count());
            runtime.pushFrame(current);
            ownsFrame = true;
        }

        try {
            runArtifact(artifact, current, source, context, frame, 0, ownsFrame);
        } catch (CommandSyntaxException commandSyntaxException) {
            if (ownsFrame && runtime.currentFrame() == current) {
                runtime.popFrame(current);
            }
            source.handleException(commandSyntaxException, false, context.getTracer());
        } catch (Throwable throwable) {
            if (ownsFrame && runtime.currentFrame() == current) {
                runtime.popFrame(current);
            }
            throw new RuntimeException("Failed to execute compiled function " + artifact.program().id(), throwable);
        }
    }

    static <T extends AbstractServerCommandSource<T>> void runArtifact(
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact,
            ExecutionFrame executionFrame,
            T source,
            CommandExecutionContext<T> context,
            Frame frame,
            int initialState,
            boolean ownsFrame
    ) throws Throwable {
        BaselineExecutionEngine.ensureLoaded(executionFrame, artifact.requiredSlots());
        BaselineExecutionEngine.ExecutionOutcome outcome = artifact.invoke(executionFrame, source, context, frame, initialState);
        switch (outcome.mode()) {
            case COMPLETE -> {
                flushDirtySlots(executionFrame);
                if (ownsFrame && SynchronizationRuntime.getInstance().currentFrame() == executionFrame) {
                    SynchronizationRuntime.getInstance().popFrame(executionFrame);
                }
            }
            case RETURN -> {
                flushDirtySlots(executionFrame);
                frame.succeed(outcome.returnValue());
                frame.doReturn();
                if (ownsFrame && SynchronizationRuntime.getInstance().currentFrame() == executionFrame) {
                    SynchronizationRuntime.getInstance().popFrame(executionFrame);
                }
            }
            case SUSPEND -> {
                flushDirtySlots(executionFrame);
                scheduleSuspension(artifact, executionFrame, source, context, frame, outcome.bindingId(), outcome.nextState(), ownsFrame);
            }
            case SUSPEND_PREFETCH -> {
                flushDirtySlots(executionFrame);
                schedulePrefetchedSuspension(artifact, executionFrame, source, context, frame, outcome.bindingId(), outcome.nextState(), ownsFrame);
            }
            case FALLBACK -> throw new IllegalStateException("Unexpected fallback outcome from compiled artifact " + artifact.program().id());
        }
    }

    private static <T extends AbstractServerCommandSource<T>> void scheduleSuspension(
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
        if (nextState >= 0) {
            context.enqueueCommand(new CommandQueueEntry<>(frame, new BaselineContinuationAction<>(artifact, executionFrame, source, nextState, ownsFrame)));
        } else if (ownsFrame) {
            SynchronizationRuntime.getInstance().popFrame(executionFrame);
        }
    }

    private static <T extends AbstractServerCommandSource<T>> void schedulePrefetchedSuspension(
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
            throw new RuntimeException("Failed to instantiate prefetched macro " + planId, e);
        }
        if (nextState >= 0) {
            context.enqueueCommand(new CommandQueueEntry<>(frame, new BaselineContinuationAction<>(artifact, executionFrame, source, nextState, ownsFrame)));
        } else if (ownsFrame) {
            SynchronizationRuntime.getInstance().popFrame(executionFrame);
        }
    }

    static void flushDirtySlots(ExecutionFrame frame) {
        BaselineExecutionEngine.flushFrame(frame);
    }
}
