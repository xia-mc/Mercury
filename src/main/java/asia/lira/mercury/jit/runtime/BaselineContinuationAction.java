package asia.lira.mercury.jit.runtime;

import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.CommandAction;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.Frame;
import net.minecraft.server.command.AbstractServerCommandSource;

public final class BaselineContinuationAction<T extends AbstractServerCommandSource<T>> implements CommandAction<T> {
    private final BaselineCompiledFunctionRegistry.CompiledArtifact artifact;
    private final ExecutionFrame executionFrame;
    private final T source;
    private final int nextState;
    private final boolean ownsFrame;

    public BaselineContinuationAction(
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact,
            ExecutionFrame executionFrame,
            T source,
            int nextState,
            boolean ownsFrame
    ) {
        this.artifact = artifact;
        this.executionFrame = executionFrame;
        this.source = source;
        this.nextState = nextState;
        this.ownsFrame = ownsFrame;
    }

    @Override
    public void execute(CommandExecutionContext<T> context, Frame frame) {
        SynchronizationRuntime runtime = SynchronizationRuntime.getInstance();
        ExecutionFrame current = runtime.currentFrame();
        boolean pushed = false;
        if (current != executionFrame) {
            runtime.pushFrame(executionFrame);
            pushed = true;
        }

        try {
            BaselineCompiledAction.runArtifact(artifact, executionFrame, source, context, frame, nextState, ownsFrame);
        } catch (CommandSyntaxException commandSyntaxException) {
            FrameLifecycle.safeFlush(executionFrame);
            source.handleException(commandSyntaxException, false, context.getTracer());
        } catch (Throwable throwable) {
            FrameLifecycle.safeFlush(executionFrame);
            CommandSyntaxException wrapped = BaselineCompiledAction.findCommandSyntaxCause(throwable);
            if (wrapped != null) {
                source.handleException(wrapped, false, context.getTracer());
                return;
            }
            throw new RuntimeException("Failed to resume compiled function " + artifact.program().id(), throwable);
        } finally {
            FrameLifecycle.releaseIfOwnedAndOnTop(executionFrame, pushed);
        }
    }
}
