package asia.lira.mercury.jit.runtime;

import asia.lira.mercury.jit.registry.JitPreparationRegistry;
import net.minecraft.util.Identifier;

/**
 * Centralizes the acquire / pop / safe-flush ritual that wraps every JIT-compiled
 * function invocation. Both {@link BaselineCompiledAction} and
 * {@link BaselineContinuationAction} go through these helpers so the dirty-slot
 * persistence and frame-stack discipline are identical across normal exits and
 * exceptional paths (vanilla parity: scoreboard writes from successful commands
 * must survive a later failing command).
 */
public final class FrameLifecycle {
    private FrameLifecycle() {
    }

    /**
     * If no execution frame is currently pushed, allocate one sized for
     * {@code artifactId}'s slot count and push it. Returns the resolved frame
     * along with whether this call owns it (caller must pop only if it owns).
     */
    public static AcquiredFrame acquireOrReuse(Identifier artifactId) {
        SynchronizationRuntime runtime = SynchronizationRuntime.getInstance();
        ExecutionFrame current = runtime.currentFrame();
        if (current != null) {
            return new AcquiredFrame(current, false);
        }
        ExecutionFrame fresh = new ExecutionFrame(artifactId, JitPreparationRegistry.getInstance().slotRegistry().count());
        runtime.pushFrame(fresh);
        return new AcquiredFrame(fresh, true);
    }

    /**
     * Pops {@code frame} from the runtime stack if {@code ownsFrame} is true and
     * the runtime still has it on top. Safe to call from exceptional paths where
     * a nested call may have already popped a child frame.
     */
    public static void releaseIfOwnedAndOnTop(ExecutionFrame frame, boolean ownsFrame) {
        if (!ownsFrame) {
            return;
        }
        SynchronizationRuntime runtime = SynchronizationRuntime.getInstance();
        if (runtime.currentFrame() == frame) {
            runtime.popFrame(frame);
        }
    }

    /**
     * Pops {@code frame} from the runtime stack if {@code ownsFrame} is true,
     * matching the original {@code BaselineCompiledAction.scheduleSuspension} behavior
     * where the frame is removed from the stack even if a nested operation pushed
     * additional frames after it. Used by suspension scheduling, which knows the
     * outer frame is no longer needed.
     */
    public static void releaseIfOwned(ExecutionFrame frame, boolean ownsFrame) {
        if (!ownsFrame) {
            return;
        }
        SynchronizationRuntime.getInstance().popFrame(frame);
    }

    /**
     * Best-effort flush of dirty slots that swallows any exception so the
     * original error in the surrounding catch block can still propagate.
     */
    public static void safeFlush(ExecutionFrame frame) {
        try {
            BaselineExecutionEngine.flushFrame(frame);
        } catch (Throwable ignored) {
            // original exception must propagate; flush is best-effort
        }
    }

    public record AcquiredFrame(ExecutionFrame frame, boolean ownsFrame) {
    }
}
