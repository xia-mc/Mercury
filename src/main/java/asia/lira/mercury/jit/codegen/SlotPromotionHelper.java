package asia.lira.mercury.jit.codegen;

import asia.lira.mercury.jit.pipeline.LoweredUnit;
import org.objectweb.asm.MethodVisitor;

import java.util.Map;

/**
 * Helpers for emitting the spill/reload bytecode for promoted slots.
 *
 * <p>A "promoted" slot is one that {@link asia.lira.mercury.jit.pass.SlotPromotionPass} has
 * elected to keep in a local variable instead of the frame's slot array. Such slots must be
 * synchronized with the frame's array at well-defined boundaries:
 * <ul>
 *   <li>Spill (local → frame) before invoking external code that may read the frame.</li>
 *   <li>Reload (frame → local) after invoking external code that may have mutated the frame.</li>
 *   <li>"Via read" variants additionally consult the scoreboard via the runtime to pick up
 *       changes made outside the JIT frame.</li>
 * </ul>
 *
 * <p>Extracted from BaselineBytecodeCompiler for reuse by future emitter splits and to
 * isolate the promotion contract from the larger compiler orchestration.
 */
public final class SlotPromotionHelper {
    private SlotPromotionHelper() {
    }

    public static void spillAllPromoted(MethodVisitor visitor, LoweredUnit unit) {
        for (Map.Entry<Integer, Integer> entry : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildSpillPromotedSlot(visitor, entry.getKey(), entry.getValue());
        }
    }

    public static void spillPromotedSlots(MethodVisitor visitor, LoweredUnit unit, int[] slotIds) {
        for (int slotId : slotIds) {
            if (unit.isPromoted(slotId)) {
                BaselineBytecodeOps.buildSpillPromotedSlot(visitor, slotId, unit.localIndexFor(slotId));
            }
        }
    }

    public static void reloadPromotedSlots(MethodVisitor visitor, LoweredUnit unit, int[] slotIds) {
        for (int slotId : slotIds) {
            if (unit.isPromoted(slotId)) {
                BaselineBytecodeOps.buildReloadPromotedSlot(visitor, slotId, unit.localIndexFor(slotId));
            }
        }
    }

    public static void reloadAllPromotedSlots(MethodVisitor visitor, LoweredUnit unit) {
        for (Map.Entry<Integer, Integer> entry : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildReloadPromotedSlot(visitor, entry.getKey(), entry.getValue());
        }
    }

    public static void reloadAllPromotedSlotsViaRead(MethodVisitor visitor, LoweredUnit unit) {
        for (Map.Entry<Integer, Integer> entry : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildReadIntoPromotedSlot(visitor, entry.getKey(), entry.getValue());
        }
    }

    public static void reloadPromotedSlotsViaRead(MethodVisitor visitor, LoweredUnit unit, int[] slotIds) {
        for (int slotId : slotIds) {
            if (unit.isPromoted(slotId)) {
                BaselineBytecodeOps.buildReadIntoPromotedSlot(visitor, slotId, unit.localIndexFor(slotId));
            }
        }
    }
}
