package asia.lira.mercury.jit.pipeline;

import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LoweredUnitInliner {
    private final Map<Identifier, LoweredUnit> originals;
    private final Map<Identifier, Integer> originalIncomingCalls;
    private final Map<Identifier, LoweredUnit> memoized = new LinkedHashMap<>();

    private LoweredUnitInliner(Map<Identifier, LoweredUnit> originals) {
        this.originals = originals;
        this.originalIncomingCalls = collectIncomingCalls(originals.values());
    }

    public static InlineResult inlineAll(Map<Identifier, LoweredUnit> originals) {
        LoweredUnitInliner inliner = new LoweredUnitInliner(originals);
        Map<Identifier, LoweredUnit> transformed = new LinkedHashMap<>();
        for (Identifier id : originals.keySet()) {
            transformed.put(id, inliner.transform(id, new ArrayDeque<>()));
        }

        Map<Identifier, Integer> residualIncomingCalls = collectIncomingCalls(transformed.values());
        Map<Identifier, LoweredUnit> kept = new LinkedHashMap<>();
        Set<Identifier> omitted = new LinkedHashSet<>();
        for (Map.Entry<Identifier, LoweredUnit> entry : transformed.entrySet()) {
            Identifier id = entry.getKey();
            LoweredUnit unit = entry.getValue();
            if (inliner.originalIncomingCalls.getOrDefault(id, 0) > 0
                    && residualIncomingCalls.getOrDefault(id, 0) == 0
                    && isInlineCandidate(unit)) {
                omitted.add(id);
                continue;
            }
            kept.put(id, unit);
        }

        return new InlineResult(kept, omitted);
    }

    private LoweredUnit transform(Identifier id, ArrayDeque<Identifier> stack) {
        LoweredUnit existing = memoized.get(id);
        if (existing != null) {
            return existing;
        }

        LoweredUnit original = originals.get(id);
        if (original == null) {
            throw new IllegalStateException("Missing lowered unit " + id);
        }

        if (stack.contains(id)) {
            return original;
        }

        stack.push(id);
        LoweredUnit current = original;
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

                    Identifier targetId = callInstruction.targetFunction();
                    if (targetId == null || targetId.equals(id) || stack.contains(targetId)) {
                        continue;
                    }

                    LoweredUnit targetUnit = originals.containsKey(targetId) ? transform(targetId, stack) : null;
                    if (targetUnit == null || !shouldInline(current, targetUnit, targetId)) {
                        continue;
                    }

                    current = inlineCall(current, blockIndex, instructionIndex, targetUnit);
                    changed = true;
                    break outer;
                }
            }
        } while (changed);

        stack.pop();
        memoized.put(id, current);
        return current;
    }

    private boolean shouldInline(LoweredUnit caller, LoweredUnit callee, Identifier calleeId) {
        if (!isInlineCandidate(callee)) {
            return false;
        }
        if (isMandatoryInlineCandidate(callee)) {
            return true;
        }

        int blockCount = callee.blocks().size();
        int instructionCount = countInstructions(callee);
        if (blockCount > 4 || instructionCount > 12) {
            return false;
        }

        int overlap = sharedSlotCount(caller.requiredSlots(), callee.requiredSlots());
        int callSites = originalIncomingCalls.getOrDefault(calleeId, 0);
        int cost = instructionCount + blockCount + Math.max(0, callSites - 1) * 3 - overlap * 2;
        return cost <= 6;
    }

    private static boolean isMandatoryInlineCandidate(LoweredUnit unit) {
        return isInlineCandidate(unit)
                && unit.blocks().size() == 1
                && unit.blocks().getFirst().terminator() instanceof LoweredUnit.CompleteTerminator;
    }

    private static boolean isInlineCandidate(LoweredUnit unit) {
        int exitCount = 0;
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            if (block.terminator() instanceof LoweredUnit.CompleteTerminator
                    || block.terminator() instanceof LoweredUnit.ReturnValueTerminator) {
                exitCount++;
                continue;
            }
            if (block.terminator() instanceof LoweredUnit.JumpLocalTerminator) {
                continue;
            }
            return false;
        }
        return exitCount == 1;
    }

    private static LoweredUnit inlineCall(
            LoweredUnit caller,
            int blockIndex,
            int instructionIndex,
            LoweredUnit callee
    ) {
        List<LoweredUnit.LoweredBlock> oldBlocks = caller.blocks();
        LoweredUnit.LoweredBlock callBlock = oldBlocks.get(blockIndex);

        List<LoweredUnit.LoweredInstruction> prefixInstructions = List.copyOf(callBlock.instructions().subList(0, instructionIndex));
        List<LoweredUnit.LoweredInstruction> suffixInstructions = List.copyOf(callBlock.instructions().subList(instructionIndex + 1, callBlock.instructions().size()));

        Map<Integer, Integer> callerIndexMap = new LinkedHashMap<>();
        List<LoweredUnit.LoweredBlock> newBlocks = new ArrayList<>();

        for (int i = 0; i < blockIndex; i++) {
            callerIndexMap.put(i, newBlocks.size());
            newBlocks.add(oldBlocks.get(i));
        }

        int prefixIndex = newBlocks.size();
        callerIndexMap.put(blockIndex, prefixIndex);
        newBlocks.add(new LoweredUnit.LoweredBlock(callBlock.programId(), prefixInstructions, new LoweredUnit.JumpLocalTerminator(-1)));

        Map<Integer, Integer> calleeIndexMap = new LinkedHashMap<>();
        for (int i = 0; i < callee.blocks().size(); i++) {
            calleeIndexMap.put(i, newBlocks.size());
            newBlocks.add(callee.blocks().get(i));
        }

        int continuationIndex = newBlocks.size();
        newBlocks.add(new LoweredUnit.LoweredBlock(callBlock.programId(), suffixInstructions, callBlock.terminator()));

        for (int i = blockIndex + 1; i < oldBlocks.size(); i++) {
            callerIndexMap.put(i, newBlocks.size());
            newBlocks.add(oldBlocks.get(i));
        }

        List<LoweredUnit.LoweredBlock> patched = new ArrayList<>(newBlocks.size());
        for (int i = 0; i < newBlocks.size(); i++) {
            LoweredUnit.LoweredBlock block = newBlocks.get(i);
            LoweredUnit.LoweredTerminator terminator = block.terminator();

            if (i == prefixIndex) {
                terminator = new LoweredUnit.JumpLocalTerminator(calleeIndexMap.get(callee.entryIndex()));
            } else if (calleeIndexMap.containsValue(i)) {
                terminator = remapCalleeTerminator(terminator, calleeIndexMap, continuationIndex);
            } else {
                terminator = remapCallerTerminator(terminator, callerIndexMap);
            }

            patched.add(new LoweredUnit.LoweredBlock(block.programId(), block.instructions(), terminator));
        }

        int[] mergedRequiredSlots = union(caller.requiredSlots(), callee.requiredSlots());
        int entryIndex = callerIndexMap.getOrDefault(caller.entryIndex(), caller.entryIndex());
        return new LoweredUnit(caller.entryId(), patched, entryIndex, mergedRequiredSlots, caller.promotedSlotLocals());
    }

    private static LoweredUnit.LoweredTerminator remapCalleeTerminator(
            LoweredUnit.LoweredTerminator terminator,
            Map<Integer, Integer> calleeIndexMap,
            int continuationIndex
    ) {
        if (terminator instanceof LoweredUnit.CompleteTerminator
                || terminator instanceof LoweredUnit.ReturnValueTerminator) {
            return new LoweredUnit.JumpLocalTerminator(continuationIndex);
        }
        if (terminator instanceof LoweredUnit.JumpLocalTerminator jumpLocalTerminator) {
            return new LoweredUnit.JumpLocalTerminator(calleeIndexMap.get(jumpLocalTerminator.targetBlockIndex()));
        }
        throw new IllegalStateException("Unexpected callee terminator " + terminator);
    }

    private static LoweredUnit.LoweredTerminator remapCallerTerminator(
            LoweredUnit.LoweredTerminator terminator,
            Map<Integer, Integer> callerIndexMap
    ) {
        if (terminator instanceof LoweredUnit.JumpLocalTerminator jumpLocalTerminator) {
            return new LoweredUnit.JumpLocalTerminator(callerIndexMap.get(jumpLocalTerminator.targetBlockIndex()));
        }
        return terminator;
    }

    private static int sharedSlotCount(int[] callerSlots, int[] calleeSlots) {
        Set<Integer> caller = new LinkedHashSet<>();
        for (int callerSlot : callerSlots) {
            caller.add(callerSlot);
        }
        int shared = 0;
        for (int calleeSlot : calleeSlots) {
            if (caller.contains(calleeSlot)) {
                shared++;
            }
        }
        return shared;
    }

    private static int countInstructions(LoweredUnit unit) {
        int count = 0;
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            count += block.instructions().size();
        }
        return count;
    }

    private static int[] union(int[] a, int[] b) {
        Set<Integer> values = new LinkedHashSet<>();
        for (int value : a) {
            values.add(value);
        }
        for (int value : b) {
            values.add(value);
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<Identifier, Integer> collectIncomingCalls(Collection<LoweredUnit> units) {
        Map<Identifier, Integer> counts = new LinkedHashMap<>();
        for (LoweredUnit unit : units) {
            for (LoweredUnit.LoweredBlock block : unit.blocks()) {
                for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                    if (instruction instanceof LoweredUnit.CallInstruction callInstruction) {
                        counts.merge(callInstruction.targetFunction(), 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    public record InlineResult(
            Map<Identifier, LoweredUnit> units,
            Set<Identifier> omittedIds
    ) {
        public InlineResult {
            units = Map.copyOf(new LinkedHashMap<>(units));
            omittedIds = Set.copyOf(new LinkedHashSet<>(omittedIds));
        }
    }
}
