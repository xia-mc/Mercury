package asia.lira.mercury.jit.runtime;

import asia.lira.mercury.impl.cache.MacroPrefetchRuntime;
import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
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
        FrameLifecycle.AcquiredFrame acquired = FrameLifecycle.acquireOrReuse(artifact.program().id());
        ExecutionFrame current = acquired.frame();
        boolean ownsFrame = acquired.ownsFrame();

        try {
            runArtifact(artifact, current, source, context, frame, 0, ownsFrame);
        } catch (CommandSyntaxException commandSyntaxException) {
            FrameLifecycle.safeFlush(current);
            FrameLifecycle.releaseIfOwned(current, ownsFrame);
            source.handleException(commandSyntaxException, false, context.getTracer());
        } catch (Throwable throwable) {
            FrameLifecycle.safeFlush(current);
            FrameLifecycle.releaseIfOwned(current, ownsFrame);
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
                BaselineExecutionEngine.flushFrame(executionFrame);
                FrameLifecycle.releaseIfOwned(executionFrame, ownsFrame);
            }
            case RETURN -> {
                BaselineExecutionEngine.flushFrame(executionFrame);
                frame.succeed(outcome.returnValue());
                frame.doReturn();
                FrameLifecycle.releaseIfOwned(executionFrame, ownsFrame);
            }
            case SUSPEND -> {
                BaselineExecutionEngine.flushFrame(executionFrame);
                SuspensionScheduler.scheduleAction(artifact, executionFrame, source, context, frame, outcome.bindingId(), outcome.nextState(), ownsFrame);
            }
            case SUSPEND_PREFETCH -> {
                BaselineExecutionEngine.flushFrame(executionFrame);
                SuspensionScheduler.schedulePrefetched(artifact, executionFrame, source, context, frame, outcome.bindingId(), outcome.nextState(), ownsFrame);
            }
            case FALLBACK -> throw new IllegalStateException("Unexpected fallback outcome from compiled artifact " + artifact.program().id());
        }
    }

    // Back-compat aliases — older callers (and bytecode metadata) reference these symbols.
    static void safeFlush(ExecutionFrame frame) {
        FrameLifecycle.safeFlush(frame);
    }

    static void flushDirtySlots(ExecutionFrame frame) {
        BaselineExecutionEngine.flushFrame(frame);
    }
}
