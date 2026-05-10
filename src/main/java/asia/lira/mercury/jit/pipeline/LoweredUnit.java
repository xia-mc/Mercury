package asia.lira.mercury.jit.pipeline;

import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record LoweredUnit(
        Identifier entryId,
        List<LoweredBlock> blocks,
        int entryIndex,
        int[] requiredSlots,
        Map<Integer, Integer> promotedSlotLocals
) {
    public LoweredUnit {
        promotedSlotLocals = Map.copyOf(new LinkedHashMap<>(promotedSlotLocals));
        blocks = List.copyOf(blocks);
    }

    public boolean isPromoted(int slotId) {
        return promotedSlotLocals.containsKey(slotId);
    }

    public int localIndexFor(int slotId) {
        Integer localIndex = promotedSlotLocals.get(slotId);
        if (localIndex == null) {
            throw new IllegalArgumentException("Slot " + slotId + " is not promoted in " + entryId);
        }
        return localIndex;
    }

    public record LoweredBlock(
            Identifier programId,
            List<LoweredInstruction> instructions,
            LoweredTerminator terminator
    ) {
        public LoweredBlock {
            instructions = List.copyOf(instructions);
        }
    }

    public sealed interface LoweredInstruction permits SetConstInstruction, AddConstInstruction, GetInstruction, ResetInstruction, OperationInstruction, CallInstruction, ReflectiveBridgeInstruction, ActionBridgeInstruction, SpecializedInstruction, PrefetchMacroLineInstruction, PrefetchedMacroCallInstruction, Tier2MacroDispatchInstruction {
        String sourceText();
    }

    public record SetConstInstruction(int slotId, int value, String sourceText) implements LoweredInstruction {
    }

    public record AddConstInstruction(int slotId, int delta, String sourceText) implements LoweredInstruction {
    }

    public record GetInstruction(int slotId, String sourceText) implements LoweredInstruction {
    }

    public record ResetInstruction(int slotId, String sourceText) implements LoweredInstruction {
    }

    public enum ArithmeticSemantics {
        VANILLA,
        NON_ZERO_DIVISOR,
        TRUNCATING_SAFE
    }

    public record OperationInstruction(
            int primarySlot,
            int secondarySlot,
            String operation,
            String sourceText,
            ArithmeticSemantics arithmeticSemantics
    ) implements LoweredInstruction {
        public OperationInstruction(int primarySlot, int secondarySlot, String operation, String sourceText) {
            this(primarySlot, secondarySlot, operation, sourceText, ArithmeticSemantics.VANILLA);
        }
    }

    public record CallInstruction(
            Identifier targetFunction,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            String sourceText
    ) implements LoweredInstruction {
        public CallInstruction {
            spillBeforeSlots = spillBeforeSlots.clone();
            reloadAfterSlots = reloadAfterSlots.clone();
        }
    }

    public record ReflectiveBridgeInstruction(
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            String sourceText
    ) implements LoweredInstruction {
        public ReflectiveBridgeInstruction {
            spillBeforeSlots = spillBeforeSlots.clone();
            reloadAfterSlots = reloadAfterSlots.clone();
        }
    }

    public record ActionBridgeInstruction(
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            String sourceText
    ) implements LoweredInstruction {
        public ActionBridgeInstruction {
            spillBeforeSlots = spillBeforeSlots.clone();
            reloadAfterSlots = reloadAfterSlots.clone();
        }
    }

    public record SpecializedInstruction(
            int specializedId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            String sourceText
    ) implements LoweredInstruction {
        public SpecializedInstruction {
            spillBeforeSlots = spillBeforeSlots.clone();
            reloadAfterSlots = reloadAfterSlots.clone();
        }
    }

    public record PrefetchMacroLineInstruction(
            int planId,
            String sourceText
    ) implements LoweredInstruction {
    }

    public record PrefetchedMacroCallInstruction(
            int planId,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            String sourceText
    ) implements LoweredInstruction {
        public PrefetchedMacroCallInstruction {
            spillBeforeSlots = spillBeforeSlots.clone();
            reloadAfterSlots = reloadAfterSlots.clone();
        }
    }

    public record Tier2DispatchTarget(
            String guardSignature,
            Identifier targetFunction,
            String targetInternalName,
            int[] requiredSlots
    ) {
        public Tier2DispatchTarget {
            requiredSlots = requiredSlots.clone();
        }
    }

    public record Tier2MacroDispatchInstruction(
            int planId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            List<Tier2DispatchTarget> targets,
            String sourceText
    ) implements LoweredInstruction {
        public Tier2MacroDispatchInstruction {
            spillBeforeSlots = spillBeforeSlots.clone();
            reloadAfterSlots = reloadAfterSlots.clone();
            targets = List.copyOf(targets);
        }
    }

    public sealed interface LoweredTerminator permits CompleteTerminator, ReturnValueTerminator, JumpLocalTerminator, JumpExternalTerminator, SuspendActionTerminator, SuspendPrefetchedMacroTerminator, Tier2MacroDispatchTerminator {
    }

    public record CompleteTerminator() implements LoweredTerminator {
    }

    public record ReturnValueTerminator(int returnValue) implements LoweredTerminator {
    }

    public record JumpLocalTerminator(int targetBlockIndex) implements LoweredTerminator {
    }

    public record JumpExternalTerminator(
            Identifier targetFunction,
            int bindingId,
            int[] spillBeforeSlots
    ) implements LoweredTerminator {
        public JumpExternalTerminator {
            spillBeforeSlots = spillBeforeSlots.clone();
        }
    }

    public record SuspendActionTerminator(
            int bindingId,
            int continuationBlockIndex,
            int[] spillBeforeSlots
    ) implements LoweredTerminator {
        public SuspendActionTerminator {
            spillBeforeSlots = spillBeforeSlots.clone();
        }
    }

    public record SuspendPrefetchedMacroTerminator(
            int planId,
            int bindingId,
            int continuationBlockIndex,
            int[] spillBeforeSlots
    ) implements LoweredTerminator {
        public SuspendPrefetchedMacroTerminator {
            spillBeforeSlots = spillBeforeSlots.clone();
        }
    }

    public record Tier2MacroDispatchTerminator(
            int planId,
            int continuationBlockIndex,
            int[] spillBeforeSlots,
            List<Tier2DispatchTarget> targets
    ) implements LoweredTerminator {
        public Tier2MacroDispatchTerminator {
            spillBeforeSlots = spillBeforeSlots.clone();
            targets = List.copyOf(targets);
        }
    }

    public static LoweredBlock remapShifted(LoweredBlock block, int fromIndex, int delta) {
        return new LoweredBlock(block.programId(), block.instructions(), remapShifted(block.terminator(), fromIndex, delta));
    }

    public static LoweredTerminator remapShifted(LoweredTerminator terminator, int fromIndex, int delta) {
        if (terminator instanceof JumpLocalTerminator jumpLocalTerminator) {
            int target = jumpLocalTerminator.targetBlockIndex();
            return new JumpLocalTerminator(target >= fromIndex ? target + delta : target);
        }
        if (terminator instanceof SuspendActionTerminator suspendActionTerminator) {
            int next = suspendActionTerminator.continuationBlockIndex();
            if (next >= fromIndex) {
                next += delta;
            }
            return new SuspendActionTerminator(suspendActionTerminator.bindingId(), next, suspendActionTerminator.spillBeforeSlots());
        }
        if (terminator instanceof SuspendPrefetchedMacroTerminator suspendPrefetchedMacroTerminator) {
            int next = suspendPrefetchedMacroTerminator.continuationBlockIndex();
            if (next >= fromIndex) {
                next += delta;
            }
            return new SuspendPrefetchedMacroTerminator(
                    suspendPrefetchedMacroTerminator.planId(),
                    suspendPrefetchedMacroTerminator.bindingId(),
                    next,
                    suspendPrefetchedMacroTerminator.spillBeforeSlots()
            );
        }
        if (terminator instanceof Tier2MacroDispatchTerminator tier2MacroDispatchTerminator) {
            int next = tier2MacroDispatchTerminator.continuationBlockIndex();
            if (next >= fromIndex) {
                next += delta;
            }
            return new Tier2MacroDispatchTerminator(
                    tier2MacroDispatchTerminator.planId(),
                    next,
                    tier2MacroDispatchTerminator.spillBeforeSlots(),
                    tier2MacroDispatchTerminator.targets()
            );
        }
        return terminator;
    }
}
