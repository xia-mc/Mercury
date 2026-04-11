package asia.lira.mercury.jit.pass;

import asia.lira.mercury.jit.pipeline.LoweredUnit;

import java.util.ArrayList;
import java.util.List;

public final class UnresolvedCallIsolationPass implements BaselinePass {
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
                    if (callInstruction.bindingId() < 0 || context.internalNames().containsKey(callInstruction.targetFunction())) {
                        continue;
                    }
                    if (instructionIndex == block.instructions().size() - 1) {
                        continue;
                    }
                    current = isolate(current, blockIndex, instructionIndex, callInstruction);
                    changed = true;
                    break outer;
                }
            }
        } while (changed);
        return current;
    }

    private static LoweredUnit isolate(
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

}
