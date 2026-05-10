package asia.lira.mercury.jit.pass;

import asia.lira.mercury.jit.pipeline.LoweredUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ArithmeticSemanticsOptimizationPass implements BaselinePass {
    @Override
    public LoweredUnit apply(LoweredUnit unit, BaselinePassContext context) {
        List<LoweredUnit.LoweredBlock> rewrittenBlocks = new ArrayList<>(unit.blocks().size());
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            rewrittenBlocks.add(rewriteBlock(block));
        }
        return new LoweredUnit(
                unit.entryId(),
                rewrittenBlocks,
                unit.entryIndex(),
                unit.requiredSlots(),
                unit.promotedSlotLocals()
        );
    }

    private static LoweredUnit.LoweredBlock rewriteBlock(LoweredUnit.LoweredBlock block) {
        Map<Integer, Integer> knownConstants = new LinkedHashMap<>();
        List<LoweredUnit.LoweredInstruction> rewrittenInstructions = new ArrayList<>(block.instructions().size());
        for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
            switch (instruction) {
                case LoweredUnit.SetConstInstruction setConst -> {
                    knownConstants.put(setConst.slotId(), setConst.value());
                    rewrittenInstructions.add(instruction);
                }
                case LoweredUnit.AddConstInstruction addConst -> {
                    knownConstants.computeIfPresent(addConst.slotId(), (ignored, value) -> value + addConst.delta());
                    rewrittenInstructions.add(instruction);
                }
                case LoweredUnit.ResetInstruction reset -> {
                    knownConstants.remove(reset.slotId());
                    rewrittenInstructions.add(instruction);
                }
                case LoweredUnit.OperationInstruction operation -> {
                    LoweredUnit.ArithmeticSemantics semantics = arithmeticSemantics(operation, knownConstants);
                    rewrittenInstructions.add(new LoweredUnit.OperationInstruction(
                            operation.primarySlot(),
                            operation.secondarySlot(),
                            operation.operation(),
                            operation.sourceText(),
                            semantics
                    ));
                    updateConstantsAfterOperation(operation, knownConstants);
                }
                case LoweredUnit.GetInstruction ignored -> rewrittenInstructions.add(instruction);
                default -> {
                    knownConstants.clear();
                    rewrittenInstructions.add(instruction);
                }
            }
        }
        return new LoweredUnit.LoweredBlock(block.programId(), rewrittenInstructions, block.terminator());
    }

    private static LoweredUnit.ArithmeticSemantics arithmeticSemantics(
            LoweredUnit.OperationInstruction operation,
            Map<Integer, Integer> knownConstants
    ) {
        String op = operation.operation();
        if (!"/=".equals(op) && !"%=".equals(op)) {
            return operation.arithmeticSemantics();
        }

        Integer right = knownConstants.get(operation.secondarySlot());
        if (right == null || right == 0) {
            return LoweredUnit.ArithmeticSemantics.VANILLA;
        }

        Integer left = knownConstants.get(operation.primarySlot());
        if (left != null && sameSignOrZero(left, right)) {
            return LoweredUnit.ArithmeticSemantics.TRUNCATING_SAFE;
        }
        return LoweredUnit.ArithmeticSemantics.NON_ZERO_DIVISOR;
    }

    private static void updateConstantsAfterOperation(
            LoweredUnit.OperationInstruction operation,
            Map<Integer, Integer> knownConstants
    ) {
        Integer left = knownConstants.get(operation.primarySlot());
        Integer right = knownConstants.get(operation.secondarySlot());
        switch (operation.operation()) {
            case "=" -> {
                if (right == null) {
                    knownConstants.remove(operation.primarySlot());
                } else {
                    knownConstants.put(operation.primarySlot(), right);
                }
            }
            case "+=" -> updateBinaryConstant(operation.primarySlot(), left, right, knownConstants, Integer::sum);
            case "-=" -> updateBinaryConstant(operation.primarySlot(), left, right, knownConstants, (a, b) -> a - b);
            case "*=" -> updateBinaryConstant(operation.primarySlot(), left, right, knownConstants, (a, b) -> a * b);
            case "/=" -> {
                if (left != null && right != null && right != 0) {
                    knownConstants.put(operation.primarySlot(), Math.floorDiv(left, right));
                } else {
                    knownConstants.remove(operation.primarySlot());
                }
            }
            case "%=" -> {
                if (left != null && right != null && right != 0) {
                    knownConstants.put(operation.primarySlot(), Math.floorMod(left, right));
                } else {
                    knownConstants.remove(operation.primarySlot());
                }
            }
            case "<" -> updateBinaryConstant(operation.primarySlot(), left, right, knownConstants, Math::min);
            case ">" -> updateBinaryConstant(operation.primarySlot(), left, right, knownConstants, Math::max);
            case "><" -> {
                if (left == null) {
                    knownConstants.remove(operation.secondarySlot());
                } else {
                    knownConstants.put(operation.secondarySlot(), left);
                }
                if (right == null) {
                    knownConstants.remove(operation.primarySlot());
                } else {
                    knownConstants.put(operation.primarySlot(), right);
                }
            }
            default -> knownConstants.remove(operation.primarySlot());
        }
    }

    private static void updateBinaryConstant(
            int slotId,
            Integer left,
            Integer right,
            Map<Integer, Integer> knownConstants,
            IntBinaryOperation operation
    ) {
        if (left == null || right == null) {
            knownConstants.remove(slotId);
            return;
        }
        knownConstants.put(slotId, operation.apply(left, right));
    }

    private static boolean sameSignOrZero(int left, int right) {
        return left == 0 || (left < 0 && right < 0) || (left > 0 && right > 0);
    }

    private interface IntBinaryOperation {
        int apply(int left, int right);
    }
}
