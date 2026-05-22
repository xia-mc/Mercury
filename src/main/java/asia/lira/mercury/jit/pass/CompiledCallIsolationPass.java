package asia.lira.mercury.jit.pass;

import asia.lira.mercury.jit.pipeline.LoweredUnit;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CompiledCallIsolationPass implements BaselinePass {
    private final Set<Identifier> synchronousTargets;

    public CompiledCallIsolationPass(Set<Identifier> synchronousTargets) {
        this.synchronousTargets = Set.copyOf(synchronousTargets);
    }

    @Override
    public LoweredUnit apply(LoweredUnit unit, BaselinePassContext context) {
        LoweredUnit current = unit;
        boolean changed;
        do {
            changed = false;
            outer:
            for (int blockIndex = 0; blockIndex < current.blocks().size(); blockIndex++) {
                LoweredUnit.LoweredBlock block = current.blocks().get(blockIndex);
                for (int instructionIndex = 0; instructionIndex < block.instructions().size(); instructionIndex++) {
                    LoweredUnit.LoweredInstruction instruction = block.instructions().get(instructionIndex);
                    if (!(instruction instanceof LoweredUnit.CallInstruction callInstruction)) {
                        continue;
                    }
                    if (!needsIsolation(callInstruction, context)) {
                        continue;
                    }
                    current = isolateInstruction(current, blockIndex, instructionIndex, callInstruction);
                    changed = true;
                    break outer;
                }

                if (block.terminator() instanceof LoweredUnit.JumpExternalTerminator jumpExternal
                        && needsIsolation(jumpExternal, context)) {
                    current = isolateTerminator(current, blockIndex, jumpExternal);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return current;
    }

    private boolean needsIsolation(LoweredUnit.CallInstruction callInstruction, BaselinePassContext context) {
        return callInstruction.bindingId() >= 0
                && context.internalNames().containsKey(callInstruction.targetFunction())
                && !synchronousTargets.contains(callInstruction.targetFunction());
    }

    private boolean needsIsolation(LoweredUnit.JumpExternalTerminator terminator, BaselinePassContext context) {
        return terminator.bindingId() >= 0
                && context.internalNames().containsKey(terminator.targetFunction())
                && !synchronousTargets.contains(terminator.targetFunction());
    }

    private static LoweredUnit isolateInstruction(
            LoweredUnit unit,
            int blockIndex,
            int instructionIndex,
            LoweredUnit.CallInstruction callInstruction
    ) {
        List<LoweredUnit.LoweredBlock> oldBlocks = unit.blocks();
        LoweredUnit.LoweredBlock block = oldBlocks.get(blockIndex);

        List<LoweredUnit.LoweredInstruction> prefix = List.copyOf(block.instructions().subList(0, instructionIndex));
        List<LoweredUnit.LoweredInstruction> suffix = List.copyOf(block.instructions().subList(instructionIndex + 1, block.instructions().size()));

        List<LoweredUnit.LoweredBlock> rewritten = new ArrayList<>(oldBlocks.size() + 1);
        int continuationIndex = blockIndex + 1;
        for (int i = 0; i < blockIndex; i++) {
            rewritten.add(LoweredUnit.remapShifted(oldBlocks.get(i), continuationIndex, 1));
        }

        rewritten.add(new LoweredUnit.LoweredBlock(
                block.programId(),
                prefix,
                new LoweredUnit.SuspendActionTerminator(callInstruction.bindingId(), continuationIndex, callInstruction.spillBeforeSlots())
        ));
        rewritten.add(new LoweredUnit.LoweredBlock(block.programId(), suffix, LoweredUnit.remapShifted(block.terminator(), continuationIndex, 1)));

        for (int i = blockIndex + 1; i < oldBlocks.size(); i++) {
            rewritten.add(LoweredUnit.remapShifted(oldBlocks.get(i), continuationIndex, 1));
        }

        int entryIndex = unit.entryIndex() > blockIndex ? unit.entryIndex() + 1 : unit.entryIndex();
        return new LoweredUnit(unit.entryId(), rewritten, entryIndex, unit.requiredSlots(), unit.promotedSlotLocals());
    }

    private static LoweredUnit isolateTerminator(
            LoweredUnit unit,
            int blockIndex,
            LoweredUnit.JumpExternalTerminator jumpExternal
    ) {
        List<LoweredUnit.LoweredBlock> rewritten = new ArrayList<>(unit.blocks());
        LoweredUnit.LoweredBlock block = rewritten.get(blockIndex);
        rewritten.set(blockIndex, new LoweredUnit.LoweredBlock(
                block.programId(),
                block.instructions(),
                new LoweredUnit.SuspendActionTerminator(jumpExternal.bindingId(), -1, jumpExternal.spillBeforeSlots())
        ));
        return new LoweredUnit(unit.entryId(), rewritten, unit.entryIndex(), unit.requiredSlots(), unit.promotedSlotLocals());
    }
}
