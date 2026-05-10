package asia.lira.mercury.jit.codegen;

import asia.lira.mercury.impl.cache.MacroPrefetchRuntime;
import asia.lira.mercury.jit.pipeline.LoweredUnit;
import asia.lira.mercury.jit.runtime.BaselineExecutionEngine;
import asia.lira.mercury.jit.runtime.ExecutionFrame;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.text.Text;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

public final class BaselineBytecodeOps {
    public static final SimpleCommandExceptionType DIVISION_BY_ZERO_EXCEPTION =
            new SimpleCommandExceptionType(Text.translatable("arguments.operation.div0"));

    private static final String FRAME_INTERNAL = Type.getInternalName(ExecutionFrame.class);
    private static final String RUNTIME_INTERNAL = Type.getInternalName(BaselineExecutionEngine.class);
    private static final String PREFETCH_RUNTIME_INTERNAL = Type.getInternalName(MacroPrefetchRuntime.class);
    private static final String OUTCOME_DESC = Type.getDescriptor(BaselineExecutionEngine.ExecutionOutcome.class);
    private static final String OUTCOME_INTERNAL = Type.getInternalName(BaselineExecutionEngine.ExecutionOutcome.class);
    private static final String OUTCOME_MODE_INTERNAL = Type.getInternalName(BaselineExecutionEngine.ExecutionOutcome.Mode.class);
    private static final String CONTEXT_DESC = Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class);
    private static final String COMMAND_FRAME_DESC = Type.getDescriptor(net.minecraft.command.Frame.class);

    private BaselineBytecodeOps() {
    }

    public static void buildSetConst(MethodVisitor visitor, int slotId, int value) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        pushInt(visitor, value);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    public static void buildGet(MethodVisitor visitor, int slotId) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitInsn(Opcodes.POP);
    }

    public static void buildAddConst(MethodVisitor visitor, int slotId, int delta) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        pushInt(visitor, delta);
        visitor.visitInsn(Opcodes.IADD);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    public static void buildReset(MethodVisitor visitor, int slotId) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, "resetSlot", "(L" + FRAME_INTERNAL + ";I)V", false);
    }

    public static void buildOperation(
            MethodVisitor visitor,
            int primarySlot,
            int secondarySlot,
            String operation,
            LoweredUnit.ArithmeticSemantics arithmeticSemantics
    ) {
        switch (operation) {
            case "=" -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, primarySlot);
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, secondarySlot);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
            }
            case "+=", "-=", "*=", "/=", "%=", "<", ">" ->
                    buildBinaryOperation(visitor, primarySlot, secondarySlot, operation, arithmeticSemantics);
            case "><" -> buildSwap(visitor, primarySlot, secondarySlot);
            default -> throw new IllegalArgumentException("Unsupported scoreboard operation: " + operation);
        }
    }

    public static void buildCompiledFallbackCall(MethodVisitor visitor, String functionId, boolean returnOutcome) {
        visitor.visitLdcInsn(functionId);
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitInsn(Opcodes.ICONST_0);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "invokeCompiled",
                "(Ljava/lang/String;L" + FRAME_INTERNAL + ";Ljava/lang/Object;" + CONTEXT_DESC + COMMAND_FRAME_DESC + "I)" + OUTCOME_DESC,
                false
        );
        if (returnOutcome) {
            visitor.visitInsn(Opcodes.ARETURN);
        } else {
            visitor.visitInsn(Opcodes.POP);
        }
    }

    public static void buildStaticInvoke(MethodVisitor visitor, String targetInternalName, boolean returnOutcome) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitInsn(Opcodes.ICONST_0);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                targetInternalName,
                "invoke",
                "(L" + FRAME_INTERNAL + ";Ljava/lang/Object;" + CONTEXT_DESC + COMMAND_FRAME_DESC + "I)" + OUTCOME_DESC,
                false
        );

        if (returnOutcome) {
            visitor.visitInsn(Opcodes.ARETURN);
        } else {
            visitor.visitInsn(Opcodes.POP);
        }
    }

    public static void buildEnsureLoadedFromStaticField(MethodVisitor visitor, String targetInternalName, String fieldName) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitFieldInsn(Opcodes.GETSTATIC, targetInternalName, fieldName, "[I");
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, "ensureLoaded", "(L" + FRAME_INTERNAL + ";[I)V", false);
    }

    public static void buildInlineRequiredSlots(MethodVisitor visitor, int[] requiredSlots) {
        for (int slotId : requiredSlots) {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
            pushInt(visitor, slotId);
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_INTERNAL, "readSlot", "(L" + FRAME_INTERNAL + ";I)I", false);
            visitor.visitInsn(Opcodes.POP);
        }
    }

    public static void buildIntArray(MethodVisitor visitor, int[] values) {
        pushInt(visitor, values.length);
        visitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
        for (int i = 0; i < values.length; i++) {
            visitor.visitInsn(Opcodes.DUP);
            pushInt(visitor, i);
            pushInt(visitor, values[i]);
            visitor.visitInsn(Opcodes.IASTORE);
        }
    }

    public static void buildInitializePromotedSlot(MethodVisitor visitor, int slotId, int localIndex) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitVarInsn(Opcodes.ISTORE, localIndex);
    }

    public static void buildSpillPromotedSlot(MethodVisitor visitor, int slotId, int localIndex) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        visitor.visitVarInsn(Opcodes.ILOAD, localIndex);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    public static void buildReloadPromotedSlot(MethodVisitor visitor, int slotId, int localIndex) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, slotId);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitVarInsn(Opcodes.ISTORE, localIndex);
    }

    public static void buildStorePromotedLocal(MethodVisitor visitor, int localIndex, int value) {
        pushInt(visitor, value);
        visitor.visitVarInsn(Opcodes.ISTORE, localIndex);
    }

    public static void buildAddPromotedLocal(MethodVisitor visitor, int localIndex, int delta) {
        visitor.visitVarInsn(Opcodes.ILOAD, localIndex);
        pushInt(visitor, delta);
        visitor.visitInsn(Opcodes.IADD);
        visitor.visitVarInsn(Opcodes.ISTORE, localIndex);
    }

    public static void buildGetPromotedLocal(MethodVisitor visitor, int localIndex) {
        visitor.visitVarInsn(Opcodes.ILOAD, localIndex);
        visitor.visitInsn(Opcodes.POP);
    }

    public static void buildPromotedOperation(
            MethodVisitor visitor,
            int primaryLocal,
            int secondaryLocal,
            String operation,
            LoweredUnit.ArithmeticSemantics arithmeticSemantics
    ) {
        switch (operation) {
            case "=" -> {
                visitor.visitVarInsn(Opcodes.ILOAD, secondaryLocal);
                visitor.visitVarInsn(Opcodes.ISTORE, primaryLocal);
            }
            case "><" -> {
                visitor.visitVarInsn(Opcodes.ILOAD, primaryLocal);
                visitor.visitVarInsn(Opcodes.ISTORE, 5);
                visitor.visitVarInsn(Opcodes.ILOAD, secondaryLocal);
                visitor.visitVarInsn(Opcodes.ISTORE, primaryLocal);
                visitor.visitVarInsn(Opcodes.ILOAD, 5);
                visitor.visitVarInsn(Opcodes.ISTORE, secondaryLocal);
            }
            case "+=", "-=", "*=", "/=", "%=", "<", ">" -> {
                visitor.visitVarInsn(Opcodes.ILOAD, primaryLocal);
                visitor.visitVarInsn(Opcodes.ILOAD, secondaryLocal);
                buildArithmeticOperation(visitor, operation, arithmeticSemantics);
                visitor.visitVarInsn(Opcodes.ISTORE, primaryLocal);
            }
            default -> throw new IllegalArgumentException("Unsupported promoted operation: " + operation);
        }
    }

    public static void buildMixedOperationPrimaryPromoted(
            MethodVisitor visitor,
            int primaryLocal,
            int secondarySlot,
            String operation,
            LoweredUnit.ArithmeticSemantics arithmeticSemantics
    ) {
        switch (operation) {
            case "=" -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, secondarySlot);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
                visitor.visitVarInsn(Opcodes.ISTORE, primaryLocal);
            }
            case "><" -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, secondarySlot);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
                visitor.visitVarInsn(Opcodes.ISTORE, 5);
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, secondarySlot);
                visitor.visitVarInsn(Opcodes.ILOAD, primaryLocal);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
                visitor.visitVarInsn(Opcodes.ILOAD, 5);
                visitor.visitVarInsn(Opcodes.ISTORE, primaryLocal);
            }
            default -> {
                visitor.visitVarInsn(Opcodes.ILOAD, primaryLocal);
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, secondarySlot);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
                buildArithmeticOperation(visitor, operation, arithmeticSemantics);
                visitor.visitVarInsn(Opcodes.ISTORE, primaryLocal);
            }
        }
    }

    public static void buildMixedOperationSecondaryPromoted(
            MethodVisitor visitor,
            int primarySlot,
            int secondaryLocal,
            String operation,
            LoweredUnit.ArithmeticSemantics arithmeticSemantics
    ) {
        switch (operation) {
            case "=" -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, primarySlot);
                visitor.visitVarInsn(Opcodes.ILOAD, secondaryLocal);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
            }
            case "><" -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, primarySlot);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
                visitor.visitVarInsn(Opcodes.ISTORE, 5);
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, primarySlot);
                visitor.visitVarInsn(Opcodes.ILOAD, secondaryLocal);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
                visitor.visitVarInsn(Opcodes.ILOAD, 5);
                visitor.visitVarInsn(Opcodes.ISTORE, secondaryLocal);
            }
            default -> {
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, primarySlot);
                visitor.visitVarInsn(Opcodes.ALOAD, 0);
                pushInt(visitor, primarySlot);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
                visitor.visitVarInsn(Opcodes.ILOAD, secondaryLocal);
                buildArithmeticOperation(visitor, operation, arithmeticSemantics);
                visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
            }
        }
    }

    public static void buildReturnValue(MethodVisitor visitor, int returnValue) {
        visitor.visitTypeInsn(Opcodes.NEW, OUTCOME_INTERNAL);
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitFieldInsn(Opcodes.GETSTATIC, OUTCOME_MODE_INTERNAL, "RETURN", "L" + OUTCOME_MODE_INTERNAL + ";");
        pushInt(visitor, returnValue);
        visitor.visitInsn(Opcodes.ICONST_M1);
        visitor.visitInsn(Opcodes.ICONST_M1);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, OUTCOME_INTERNAL, "<init>", "(L" + OUTCOME_MODE_INTERNAL + ";III)V", false);
        visitor.visitInsn(Opcodes.ARETURN);
    }

    public static void buildCompleted(MethodVisitor visitor) {
        visitor.visitTypeInsn(Opcodes.NEW, OUTCOME_INTERNAL);
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitFieldInsn(Opcodes.GETSTATIC, OUTCOME_MODE_INTERNAL, "COMPLETE", "L" + OUTCOME_MODE_INTERNAL + ";");
        visitor.visitInsn(Opcodes.ICONST_0);
        visitor.visitInsn(Opcodes.ICONST_M1);
        visitor.visitInsn(Opcodes.ICONST_M1);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, OUTCOME_INTERNAL, "<init>", "(L" + OUTCOME_MODE_INTERNAL + ";III)V", false);
        visitor.visitInsn(Opcodes.ARETURN);
    }

    public static void buildSuspend(MethodVisitor visitor, int bindingId, int nextState) {
        visitor.visitTypeInsn(Opcodes.NEW, OUTCOME_INTERNAL);
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitFieldInsn(Opcodes.GETSTATIC, OUTCOME_MODE_INTERNAL, "SUSPEND", "L" + OUTCOME_MODE_INTERNAL + ";");
        visitor.visitInsn(Opcodes.ICONST_0);
        pushInt(visitor, bindingId);
        pushInt(visitor, nextState);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, OUTCOME_INTERNAL, "<init>", "(L" + OUTCOME_MODE_INTERNAL + ";III)V", false);
        visitor.visitInsn(Opcodes.ARETURN);
    }

    public static void buildSuspendPrefetchedMacro(MethodVisitor visitor, int planId, int nextState) {
        visitor.visitTypeInsn(Opcodes.NEW, OUTCOME_INTERNAL);
        visitor.visitInsn(Opcodes.DUP);
        visitor.visitFieldInsn(Opcodes.GETSTATIC, OUTCOME_MODE_INTERNAL, "SUSPEND_PREFETCH", "L" + OUTCOME_MODE_INTERNAL + ";");
        visitor.visitInsn(Opcodes.ICONST_0);
        pushInt(visitor, planId);
        pushInt(visitor, nextState);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, OUTCOME_INTERNAL, "<init>", "(L" + OUTCOME_MODE_INTERNAL + ";III)V", false);
        visitor.visitInsn(Opcodes.ARETURN);
    }

    public static void buildPrefetchMacroLine(MethodVisitor visitor, int planId) {
        pushInt(visitor, planId);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PREFETCH_RUNTIME_INTERNAL, "prefetch", "(I)V", false);
    }

    public static void buildInvokePrefetchedMacro(MethodVisitor visitor, int planId) {
        pushInt(visitor, planId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                PREFETCH_RUNTIME_INTERNAL,
                "invokePrefetchedMacro",
                "(ILjava/lang/Object;" + CONTEXT_DESC + COMMAND_FRAME_DESC + ")V",
                false
        );
    }

    public static void buildLoadTier2MacroArguments(MethodVisitor visitor, int planId, int localIndex) {
        pushInt(visitor, planId);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                PREFETCH_RUNTIME_INTERNAL,
                "loadArgumentsForTier2",
                "(I)" + Type.getDescriptor(net.minecraft.nbt.NbtCompound.class),
                false
        );
        visitor.visitVarInsn(Opcodes.ASTORE, localIndex);
    }

    public static void buildInvokeExpandedMacroNoProfile(MethodVisitor visitor, int planId) {
        pushInt(visitor, planId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                PREFETCH_RUNTIME_INTERNAL,
                "invokeExpandedMacroNoProfile",
                "(ILjava/lang/Object;" + CONTEXT_DESC + COMMAND_FRAME_DESC + ")V",
                false
        );
    }

    private static void buildBinaryOperation(
            MethodVisitor visitor,
            int primarySlot,
            int secondarySlot,
            String operation,
            LoweredUnit.ArithmeticSemantics arithmeticSemantics
    ) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, primarySlot);

        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, primarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);

        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, secondarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);

        switch (operation) {
            case "+=", "-=", "*=", "/=", "%=", "<", ">" -> buildArithmeticOperation(visitor, operation, arithmeticSemantics);
            default -> throw new IllegalArgumentException("Unsupported binary operation: " + operation);
        }

        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    private static void buildSwap(MethodVisitor visitor, int primarySlot, int secondarySlot) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, primarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitVarInsn(Opcodes.ISTORE, 5);

        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, secondarySlot);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "getSlotValue", "(I)I", false);
        visitor.visitVarInsn(Opcodes.ISTORE, 6);

        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, primarySlot);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);

        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        pushInt(visitor, secondarySlot);
        visitor.visitVarInsn(Opcodes.ILOAD, 5);
        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME_INTERNAL, "setSlotValue", "(II)V", false);
    }

    public static void buildInvokeBoundCommand(MethodVisitor visitor, int bindingId) {
        pushInt(visitor, bindingId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "invokeBoundCommand",
                "(ILjava/lang/Object;" + CONTEXT_DESC + COMMAND_FRAME_DESC + ")V",
                false
        );
    }

    public static void buildInvokeActionFallback(MethodVisitor visitor, int bindingId) {
        pushInt(visitor, bindingId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "invokeActionFallback",
                "(ILjava/lang/Object;" + CONTEXT_DESC + COMMAND_FRAME_DESC + ")V",
                false
        );
    }

    private static void buildArithmeticOperation(
            MethodVisitor visitor,
            String operation,
            LoweredUnit.ArithmeticSemantics arithmeticSemantics
    ) {
        switch (operation) {
            case "+=" -> visitor.visitInsn(Opcodes.IADD);
            case "-=" -> visitor.visitInsn(Opcodes.ISUB);
            case "*=" -> visitor.visitInsn(Opcodes.IMUL);
            case "/=" -> buildFloorDivide(visitor, arithmeticSemantics);
            case "%=" -> buildFloorRemainder(visitor, arithmeticSemantics);
            case "<" -> visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
            case ">" -> visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
            default -> throw new IllegalArgumentException("Unsupported arithmetic operation: " + operation);
        }
    }

    private static void buildFloorDivide(MethodVisitor visitor, LoweredUnit.ArithmeticSemantics arithmeticSemantics) {
        storeOperands(visitor);
        buildDivisionByZeroCheck(visitor, arithmeticSemantics);
        visitor.visitVarInsn(Opcodes.ILOAD, 5);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitInsn(Opcodes.IDIV);
        if (arithmeticSemantics == LoweredUnit.ArithmeticSemantics.TRUNCATING_SAFE) {
            return;
        }
        visitor.visitVarInsn(Opcodes.ISTORE, 7);
        visitor.visitVarInsn(Opcodes.ILOAD, 5);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitInsn(Opcodes.IXOR);
        Label signsMatch = new Label();
        visitor.visitJumpInsn(Opcodes.IFGE, signsMatch);
        visitor.visitVarInsn(Opcodes.ILOAD, 5);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitInsn(Opcodes.IREM);
        visitor.visitJumpInsn(Opcodes.IFEQ, signsMatch);
        visitor.visitIincInsn(7, -1);
        visitor.visitLabel(signsMatch);
        visitor.visitVarInsn(Opcodes.ILOAD, 7);
    }

    private static void buildFloorRemainder(MethodVisitor visitor, LoweredUnit.ArithmeticSemantics arithmeticSemantics) {
        storeOperands(visitor);
        buildDivisionByZeroCheck(visitor, arithmeticSemantics);
        visitor.visitVarInsn(Opcodes.ILOAD, 5);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitInsn(Opcodes.IREM);
        if (arithmeticSemantics == LoweredUnit.ArithmeticSemantics.TRUNCATING_SAFE) {
            return;
        }
        visitor.visitVarInsn(Opcodes.ISTORE, 7);
        visitor.visitVarInsn(Opcodes.ILOAD, 5);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitInsn(Opcodes.IXOR);
        Label done = new Label();
        visitor.visitJumpInsn(Opcodes.IFGE, done);
        visitor.visitVarInsn(Opcodes.ILOAD, 7);
        visitor.visitJumpInsn(Opcodes.IFEQ, done);
        visitor.visitVarInsn(Opcodes.ILOAD, 7);
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitInsn(Opcodes.IADD);
        visitor.visitVarInsn(Opcodes.ISTORE, 7);
        visitor.visitLabel(done);
        visitor.visitVarInsn(Opcodes.ILOAD, 7);
    }

    private static void storeOperands(MethodVisitor visitor) {
        visitor.visitVarInsn(Opcodes.ISTORE, 6);
        visitor.visitVarInsn(Opcodes.ISTORE, 5);
    }

    private static void buildDivisionByZeroCheck(MethodVisitor visitor, LoweredUnit.ArithmeticSemantics arithmeticSemantics) {
        if (arithmeticSemantics != LoweredUnit.ArithmeticSemantics.VANILLA) {
            return;
        }
        Label divisorNonZero = new Label();
        visitor.visitVarInsn(Opcodes.ILOAD, 6);
        visitor.visitJumpInsn(Opcodes.IFNE, divisorNonZero);
        visitor.visitFieldInsn(
                Opcodes.GETSTATIC,
                Type.getInternalName(BaselineBytecodeOps.class),
                "DIVISION_BY_ZERO_EXCEPTION",
                Type.getDescriptor(SimpleCommandExceptionType.class)
        );
        visitor.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(SimpleCommandExceptionType.class),
                "create",
                "()" + Type.getDescriptor(com.mojang.brigadier.exceptions.CommandSyntaxException.class),
                false
        );
        visitor.visitInsn(Opcodes.ATHROW);
        visitor.visitLabel(divisorNonZero);
    }

    public static void pushInt(MethodVisitor visitor, int value) {
        switch (value) {
            case -1 -> visitor.visitInsn(Opcodes.ICONST_M1);
            case 0 -> visitor.visitInsn(Opcodes.ICONST_0);
            case 1 -> visitor.visitInsn(Opcodes.ICONST_1);
            case 2 -> visitor.visitInsn(Opcodes.ICONST_2);
            case 3 -> visitor.visitInsn(Opcodes.ICONST_3);
            case 4 -> visitor.visitInsn(Opcodes.ICONST_4);
            case 5 -> visitor.visitInsn(Opcodes.ICONST_5);
            default -> {
                if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                    visitor.visitIntInsn(Opcodes.BIPUSH, value);
                } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                    visitor.visitIntInsn(Opcodes.SIPUSH, value);
                } else {
                    visitor.visitLdcInsn(value);
                }
            }
        }
    }
}
