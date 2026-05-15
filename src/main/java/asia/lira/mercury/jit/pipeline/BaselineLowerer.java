package asia.lira.mercury.jit.pipeline;

import asia.lira.mercury.jit.registry.BaselineCompiledFunctionRegistry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BaselineLowerer {
    private BaselineLowerer() {
    }

    public static LoweredUnit lower(
            Identifier entryId,
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            int[] requiredSlots
    ) {
        List<LoweredUnit.LoweredBlock> blocks = new ArrayList<>();
        Map<Identifier, Integer> entryIndices = new LinkedHashMap<>();
        for (BaselineProgram program : unit.programs()) {
            entryIndices.put(program.id(), blocks.size());
            blocks.addAll(lowerProgram(program));
        }

        List<LoweredUnit.LoweredBlock> patchedBlocks = patchTerminators(blocks, entryIndices);
        return new LoweredUnit(entryId, patchedBlocks, entryIndices.getOrDefault(entryId, 0), requiredSlots, Map.of());
    }

    private static List<LoweredUnit.LoweredBlock> lowerProgram(
            BaselineProgram program
    ) {
        List<LoweredUnit.LoweredBlock> blocks = new ArrayList<>();
        List<LoweredUnit.LoweredInstruction> currentInstructions = new ArrayList<>();

        for (int i = 0; i < program.instructions().size(); i++) {
            if (currentInstructions == null) {
                break;
            }
            BaselineInstruction instruction = program.instructions().get(i);
            boolean hasRemaining = i < program.instructions().size() - 1;
            switch (instruction.opCode()) {
                case SET_CONST -> currentInstructions.add(new LoweredUnit.SetConstInstruction(
                        instruction.primarySlot(),
                        instruction.immediate(),
                        instruction.sourceText()
                ));
                case ADD_CONST -> currentInstructions.add(new LoweredUnit.AddConstInstruction(
                        instruction.primarySlot(),
                        instruction.immediate(),
                        instruction.sourceText()
                ));
                case GET -> currentInstructions.add(new LoweredUnit.GetInstruction(
                        instruction.primarySlot(),
                        instruction.sourceText()
                ));
                case RESET -> currentInstructions.add(new LoweredUnit.ResetInstruction(
                        instruction.primarySlot(),
                        instruction.sourceText()
                ));
                case OPERATION -> currentInstructions.add(new LoweredUnit.OperationInstruction(
                        instruction.primarySlot(),
                        instruction.secondarySlot(),
                        instruction.operation(),
                        instruction.sourceText()
                ));
                case CALL -> {
                    currentInstructions.add(new LoweredUnit.CallInstruction(
                            instruction.targetFunction(),
                            instruction.bindingId(),
                            new int[0],
                            new int[0],
                            instruction.sourceText()
                    ));
                }
                case REFLECTIVE_BRIDGE -> currentInstructions.add(new LoweredUnit.ReflectiveBridgeInstruction(
                        instruction.bindingId(),
                        new int[0],
                        new int[0],
                        instruction.sourceText()
                ));
                case ACTION_BRIDGE -> currentInstructions.add(new LoweredUnit.ActionBridgeInstruction(
                        instruction.bindingId(),
                        new int[0],
                        new int[0],
                        instruction.sourceText()
                ));
                case SPECIALIZED -> currentInstructions.add(new LoweredUnit.SpecializedInstruction(
                        instruction.immediate(),
                        new int[0],
                        new int[0],
                        instruction.sourceText()
                ));
                case SUSPEND_ACTION -> {
                    blocks.add(new LoweredUnit.LoweredBlock(
                            program.id(),
                            List.copyOf(currentInstructions),
                            new LoweredUnit.SuspendActionTerminator(instruction.bindingId(), hasRemaining ? blocks.size() + 1 : -1, new int[0])
                    ));
                    currentInstructions = new ArrayList<>();
                }
                case JUMP -> {
                    blocks.add(new LoweredUnit.LoweredBlock(
                            program.id(),
                            List.copyOf(currentInstructions),
                            new LoweredUnit.JumpExternalTerminator(instruction.targetFunction(), instruction.bindingId(), new int[0])
                    ));
                    currentInstructions = null;
                }
                case RETURN_VALUE -> {
                    blocks.add(new LoweredUnit.LoweredBlock(
                            program.id(),
                            List.copyOf(currentInstructions),
                            new LoweredUnit.ReturnValueTerminator(instruction.immediate())
                    ));
                    currentInstructions = null;
                }
            }
        }

        if (currentInstructions != null) {
            blocks.add(new LoweredUnit.LoweredBlock(program.id(), List.copyOf(currentInstructions), new LoweredUnit.CompleteTerminator()));
        }
        return blocks;
    }

    private static List<LoweredUnit.LoweredBlock> patchTerminators(
            List<LoweredUnit.LoweredBlock> blocks,
            Map<Identifier, Integer> entryIndices
    ) {
        List<LoweredUnit.LoweredBlock> patched = new ArrayList<>(blocks.size());
        for (LoweredUnit.LoweredBlock block : blocks) {
            LoweredUnit.LoweredTerminator terminator = block.terminator();
            if (terminator instanceof LoweredUnit.JumpExternalTerminator jumpExternalTerminator) {
                Integer localIndex = entryIndices.get(jumpExternalTerminator.targetFunction());
                if (localIndex != null) {
                    terminator = new LoweredUnit.JumpLocalTerminator(localIndex);
                }
            }
            patched.add(new LoweredUnit.LoweredBlock(block.programId(), block.instructions(), terminator));
        }
        return patched;
    }
}
