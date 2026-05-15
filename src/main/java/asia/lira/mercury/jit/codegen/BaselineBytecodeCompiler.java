package asia.lira.mercury.jit.codegen;

import asia.lira.mercury.impl.cache.InstalledMacroSpecialization;
import asia.lira.mercury.impl.cache.MacroOptimizationCoordinator;
import asia.lira.mercury.jit.runtime.BaselineExecutionEngine;
import asia.lira.mercury.jit.runtime.ExecutionFrame;
import asia.lira.mercury.jit.pipeline.LoweredUnit;
import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import asia.lira.mercury.jit.specialized.api.SpecializedEmitContext;
import asia.lira.mercury.jit.specialized.core.SpecializedCommandRegistry;
import asia.lira.mercury.jit.specialized.impl.data.DataModifyStoragePlan;
import asia.lira.mercury.jit.specialized.impl.execute.ExecutePlan;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BaselineBytecodeCompiler {
    private static final String EXECUTION_FRAME_DESC = Type.getDescriptor(ExecutionFrame.class);
    private static final String OUTCOME_DESC = Type.getDescriptor(BaselineExecutionEngine.ExecutionOutcome.class);
    public static final String REQUIRED_SLOTS_FIELD = "REQUIRED_SLOTS";
    private static final int SPLIT_METHOD_BUDGET = 24_000;
    private static final int SPLIT_CHUNK_BUDGET = 8_000;
    private static final int SPLIT_HELPER_TEMP_ARG_COUNT = 3;

    private BaselineBytecodeCompiler() {
    }

    public static CompiledClassData compile(
            LoweredUnit unit,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Set<net.minecraft.util.Identifier> classesWithSharedRequiredSlots
    ) {
        String internalName = internalNameFor(unit.entryId());
        byte[] classBytes = generateClass(unit, internalName, internalNames, callSiteCounts, requiredSlotsById, classesWithSharedRequiredSlots);
        return new CompiledClassData(unit.entryId(), internalName, classBytes, unit.requiredSlots());
    }

    public static String internalNameFor(net.minecraft.util.Identifier id) {
        String namespace = sanitize(id.getNamespace());
        String path = sanitize(id.getPath());
        return "asia/lira/mercury/jit/Generated_" + namespace + "_" + path + "_" + Integer.toHexString(id.hashCode());
    }

    private static byte[] generateClass(
            LoweredUnit unit,
            String internalName,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Set<net.minecraft.util.Identifier> classesWithSharedRequiredSlots
    ) {
        Map<Integer, SpecializedFieldSpec> specializedFields = collectSpecializedFields(unit);
        Map<String, Tier2DispatchFieldSpec> tier2DispatchFields = collectTier2DispatchFields(unit);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);

        boolean emitRequiredSlotsField = classesWithSharedRequiredSlots.contains(unit.entryId());
        if (emitRequiredSlotsField) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, REQUIRED_SLOTS_FIELD, "[I", null, null).visitEnd();
        }
        for (SpecializedFieldSpec fieldSpec : specializedFields.values()) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, fieldSpec.fieldName(), fieldSpec.planDescriptor(), null, null).visitEnd();
        }
        for (Tier2DispatchFieldSpec fieldSpec : tier2DispatchFields.values()) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, fieldSpec.fieldName(), fieldSpec.descriptor(), null, null).visitEnd();
        }

        emitConstructor(writer);
        if (emitRequiredSlotsField || !specializedFields.isEmpty() || !tier2DispatchFields.isEmpty()) {
            emitClassInitializer(writer, internalName, unit.requiredSlots(), emitRequiredSlotsField, specializedFields, tier2DispatchFields);
        }
        emitInvoke(writer, unit, internalNames, callSiteCounts, requiredSlotsById, specializedFields, tier2DispatchFields);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitClassInitializer(
            ClassWriter writer,
            String internalName,
            int[] requiredSlots,
            boolean emitRequiredSlotsField,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields
    ) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        visitor.visitCode();
        if (emitRequiredSlotsField) {
            BaselineBytecodeOps.buildIntArray(visitor, requiredSlots);
            visitor.visitFieldInsn(Opcodes.PUTSTATIC, internalName, REQUIRED_SLOTS_FIELD, "[I");
        }
        for (SpecializedFieldSpec fieldSpec : specializedFields.values()) {
            BaselineBytecodeOps.pushInt(visitor, fieldSpec.specializedId());
            visitor.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(SpecializedCommandRegistry.class),
                    "requirePlan",
                    "(I)" + Type.getDescriptor(SpecializedPlan.class),
                    false
            );
            visitor.visitTypeInsn(Opcodes.CHECKCAST, fieldSpec.planInternalName());
            visitor.visitFieldInsn(Opcodes.PUTSTATIC, internalName, fieldSpec.fieldName(), fieldSpec.planDescriptor());
        }
        for (Tier2DispatchFieldSpec fieldSpec : tier2DispatchFields.values()) {
            BaselineBytecodeOps.pushInt(visitor, fieldSpec.planId());
            visitor.visitLdcInsn(fieldSpec.guardSignature());
            visitor.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(MacroOptimizationCoordinator.class),
                    "requireInstalled",
                    "(ILjava/lang/String;)" + Type.getDescriptor(InstalledMacroSpecialization.class),
                    false
            );
            visitor.visitFieldInsn(Opcodes.PUTSTATIC, internalName, fieldSpec.fieldName(), fieldSpec.descriptor());
        }
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitInvoke(
            ClassWriter writer,
            LoweredUnit unit,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields
    ) {
        if (shouldSplitInvoke(unit)) {
            emitSplitInvoke(writer, unit, internalNames, callSiteCounts, requiredSlotsById, specializedFields, tier2DispatchFields);
            return;
        }

        String descriptor = "("
                + EXECUTION_FRAME_DESC
                + "Ljava/lang/Object;"
                + Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class)
                + Type.getDescriptor(net.minecraft.command.Frame.class)
                + "I"
                + ")"
                + OUTCOME_DESC;
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", descriptor, null, new String[]{"java/lang/Throwable"});
        visitor.visitCode();

        for (Map.Entry<Integer, Integer> promoted : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildInitializePromotedSlot(visitor, promoted.getKey(), promoted.getValue());
        }

        Label loopStart = new Label();
        Label defaultLabel = new Label();
        Label[] stateLabels = new Label[unit.blocks().size()];
        for (int i = 0; i < stateLabels.length; i++) {
            stateLabels[i] = new Label();
        }

        visitor.visitLabel(loopStart);
        visitor.visitVarInsn(Opcodes.ILOAD, 4);
            visitor.visitTableSwitchInsn(0, stateLabels.length - 1, defaultLabel, stateLabels);

        for (int i = 0; i < unit.blocks().size(); i++) {
            visitor.visitLabel(stateLabels[i]);
            emitBlock(visitor, unit, unit.blocks().get(i), loopStart, internalNames, callSiteCounts, requiredSlotsById, specializedFields, tier2DispatchFields, internalNameFor(unit.entryId()));
        }

        visitor.visitLabel(defaultLabel);
        spillAllPromoted(visitor, unit);
        BaselineBytecodeOps.buildCompleted(visitor);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static boolean shouldSplitInvoke(LoweredUnit unit) {
        return estimateInvokeSize(unit) > SPLIT_METHOD_BUDGET;
    }

    private static int estimateInvokeSize(LoweredUnit unit) {
        int size = 64 + unit.blocks().size() * 12 + unit.promotedSlotLocals().size() * 12;
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                size += estimateInstructionSize(instruction, unit);
            }
            size += estimateTerminatorSize(block.terminator(), unit);
        }
        return size;
    }

    private static int estimateInstructionSize(LoweredUnit.LoweredInstruction instruction, LoweredUnit unit) {
        int spillReloadCost = 0;
        if (instruction instanceof LoweredUnit.CallInstruction callInstruction) {
            spillReloadCost = 10 * (callInstruction.spillBeforeSlots().length + callInstruction.reloadAfterSlots().length);
        } else if (instruction instanceof LoweredUnit.ReflectiveBridgeInstruction reflectiveBridgeInstruction) {
            spillReloadCost = 10 * (reflectiveBridgeInstruction.spillBeforeSlots().length + reflectiveBridgeInstruction.reloadAfterSlots().length);
        } else if (instruction instanceof LoweredUnit.ActionBridgeInstruction actionBridgeInstruction) {
            spillReloadCost = 10 * (actionBridgeInstruction.spillBeforeSlots().length + actionBridgeInstruction.reloadAfterSlots().length);
        } else if (instruction instanceof LoweredUnit.SpecializedInstruction specializedInstruction) {
            spillReloadCost = 10 * (specializedInstruction.spillBeforeSlots().length + specializedInstruction.reloadAfterSlots().length);
        } else if (instruction instanceof LoweredUnit.PrefetchedMacroCallInstruction prefetchedMacroCallInstruction) {
            spillReloadCost = 10 * (prefetchedMacroCallInstruction.spillBeforeSlots().length + prefetchedMacroCallInstruction.reloadAfterSlots().length);
        } else if (instruction instanceof LoweredUnit.Tier2MacroDispatchInstruction tier2MacroDispatchInstruction) {
            spillReloadCost = 10 * (tier2MacroDispatchInstruction.spillBeforeSlots().length + tier2MacroDispatchInstruction.reloadAfterSlots().length)
                    + 80 * tier2MacroDispatchInstruction.targets().size();
        }

        return switch (instruction) {
            case LoweredUnit.SetConstInstruction setConst -> unit.isPromoted(setConst.slotId()) ? 8 : 24;
            case LoweredUnit.AddConstInstruction addConst -> unit.isPromoted(addConst.slotId()) ? 12 : 40;
            case LoweredUnit.GetInstruction get -> unit.isPromoted(get.slotId()) ? 6 : 18;
            case LoweredUnit.ResetInstruction ignored -> 16;
            case LoweredUnit.OperationInstruction operation -> {
                int base = operation.operation().equals("/") || operation.operation().equals("%") ? 120 : 64;
                yield base;
            }
            case LoweredUnit.CallInstruction ignored -> 64 + spillReloadCost;
            case LoweredUnit.ReflectiveBridgeInstruction ignored -> 48 + spillReloadCost;
            case LoweredUnit.ActionBridgeInstruction ignored -> 48 + spillReloadCost;
            case LoweredUnit.SpecializedInstruction ignored -> 256 + spillReloadCost;
            case LoweredUnit.PrefetchMacroLineInstruction ignored -> 16;
            case LoweredUnit.PrefetchedMacroCallInstruction ignored -> 48 + spillReloadCost;
            case LoweredUnit.Tier2MacroDispatchInstruction ignored -> 96 + spillReloadCost;
        };
    }

    private static int estimateTerminatorSize(LoweredUnit.LoweredTerminator terminator, LoweredUnit unit) {
        int promotedCost = 10 * unit.promotedSlotLocals().size();
        return switch (terminator) {
            case LoweredUnit.CompleteTerminator ignored -> 32 + promotedCost;
            case LoweredUnit.ReturnValueTerminator ignored -> 36 + promotedCost;
            case LoweredUnit.JumpLocalTerminator ignored -> 12;
            case LoweredUnit.JumpExternalTerminator jumpExternal -> 72 + 10 * jumpExternal.spillBeforeSlots().length;
            case LoweredUnit.SuspendActionTerminator suspendAction -> 36 + 10 * suspendAction.spillBeforeSlots().length;
            case LoweredUnit.SuspendPrefetchedMacroTerminator suspendPrefetchedMacro -> 36 + 10 * suspendPrefetchedMacro.spillBeforeSlots().length;
            case LoweredUnit.Tier2MacroDispatchTerminator tier2MacroDispatchTerminator ->
                    96 + 80 * tier2MacroDispatchTerminator.targets().size() + 10 * tier2MacroDispatchTerminator.spillBeforeSlots().length;
        };
    }

    private static void emitSplitInvoke(
            ClassWriter writer,
            LoweredUnit unit,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields
    ) {
        List<InvokeChunk> chunks = splitIntoChunks(unit);
        int[] blockEntryChunks = blockEntryChunks(unit, chunks);
        String helperDescriptor = splitHelperDescriptor(unit);
        String invokeDescriptor = invokeDescriptor();
        String ownerInternalName = internalNameFor(unit.entryId());

        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", invokeDescriptor, null, new String[]{"java/lang/Throwable"});
        visitor.visitCode();
        for (Map.Entry<Integer, Integer> promoted : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildInitializePromotedSlot(visitor, promoted.getKey(), promoted.getValue());
        }

        Label defaultLabel = new Label();
        Label[] stateLabels = new Label[unit.blocks().size()];
        for (int i = 0; i < stateLabels.length; i++) {
            stateLabels[i] = new Label();
        }
        visitor.visitVarInsn(Opcodes.ILOAD, 4);
        visitor.visitTableSwitchInsn(0, stateLabels.length - 1, defaultLabel, stateLabels);
        for (int i = 0; i < stateLabels.length; i++) {
            visitor.visitLabel(stateLabels[i]);
            emitTailCallSplitChunk(visitor, ownerInternalName, helperDescriptor, chunkName(blockEntryChunks[i]), unit, i);
        }
        visitor.visitLabel(defaultLabel);
        spillAllPromoted(visitor, unit);
        BaselineBytecodeOps.buildCompleted(visitor);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();

        for (int i = 0; i < chunks.size(); i++) {
            emitSplitChunk(writer, ownerInternalName, unit, chunks, i, blockEntryChunks, helperDescriptor, internalNames, callSiteCounts, requiredSlotsById, specializedFields, tier2DispatchFields);
        }
    }

    private static String invokeDescriptor() {
        return "("
                + EXECUTION_FRAME_DESC
                + "Ljava/lang/Object;"
                + Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class)
                + Type.getDescriptor(net.minecraft.command.Frame.class)
                + "I"
                + ")"
                + OUTCOME_DESC;
    }

    private static String splitHelperDescriptor(LoweredUnit unit) {
        StringBuilder descriptor = new StringBuilder("(")
                .append(EXECUTION_FRAME_DESC)
                .append("Ljava/lang/Object;")
                .append(Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class))
                .append(Type.getDescriptor(net.minecraft.command.Frame.class))
                .append("I");
        for (int i = 0; i < SPLIT_HELPER_TEMP_ARG_COUNT; i++) {
            descriptor.append('I');
        }
        for (int ignored : unit.promotedSlotLocals().values()) {
            descriptor.append('I');
        }
        return descriptor.append(')').append(OUTCOME_DESC).toString();
    }

    private static List<InvokeChunk> splitIntoChunks(LoweredUnit unit) {
        List<InvokeChunk> chunks = new ArrayList<>();
        for (int blockIndex = 0; blockIndex < unit.blocks().size(); blockIndex++) {
            LoweredUnit.LoweredBlock block = unit.blocks().get(blockIndex);
            int start = 0;
            int cost = 0;
            for (int i = 0; i < block.instructions().size(); i++) {
                int instructionCost = estimateInstructionSize(block.instructions().get(i), unit);
                if (i > start && cost + instructionCost > SPLIT_CHUNK_BUDGET) {
                    chunks.add(new InvokeChunk(blockIndex, start, i));
                    start = i;
                    cost = 0;
                }
                cost += instructionCost;
            }
            chunks.add(new InvokeChunk(blockIndex, start, block.instructions().size()));
        }
        return chunks;
    }

    private static int[] blockEntryChunks(LoweredUnit unit, List<InvokeChunk> chunks) {
        int[] blockEntryChunks = new int[unit.blocks().size()];
        java.util.Arrays.fill(blockEntryChunks, -1);
        for (int i = 0; i < chunks.size(); i++) {
            int blockIndex = chunks.get(i).blockIndex();
            if (blockEntryChunks[blockIndex] == -1) {
                blockEntryChunks[blockIndex] = i;
            }
        }
        return blockEntryChunks;
    }

    private static void emitSplitChunk(
            ClassWriter writer,
            String ownerInternalName,
            LoweredUnit unit,
            List<InvokeChunk> chunks,
            int chunkIndex,
            int[] blockEntryChunks,
            String helperDescriptor,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields
    ) {
        InvokeChunk chunk = chunks.get(chunkIndex);
        LoweredUnit.LoweredBlock block = unit.blocks().get(chunk.blockIndex());
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, chunkName(chunkIndex), helperDescriptor, null, new String[]{"java/lang/Throwable"});
        visitor.visitCode();
        for (int i = chunk.startInstruction(); i < chunk.endInstruction(); i++) {
            emitInstruction(visitor, unit, block.instructions().get(i), internalNames, callSiteCounts, requiredSlotsById, specializedFields, tier2DispatchFields, ownerInternalName);
        }

        boolean hasNextChunkInBlock = chunkIndex + 1 < chunks.size() && chunks.get(chunkIndex + 1).blockIndex() == chunk.blockIndex();
        if (hasNextChunkInBlock) {
            emitTailCallSplitChunk(visitor, ownerInternalName, helperDescriptor, chunkName(chunkIndex + 1), unit, chunk.blockIndex());
        } else {
            emitSplitTerminator(visitor, unit, block.terminator(), ownerInternalName, helperDescriptor, blockEntryChunks, internalNames, callSiteCounts, requiredSlotsById, tier2DispatchFields);
        }
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitTailCallSplitChunk(
            MethodVisitor visitor,
            String ownerInternalName,
            String helperDescriptor,
            String chunkName,
            LoweredUnit unit,
            int state
    ) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        BaselineBytecodeOps.pushInt(visitor, state);
        for (int i = 0; i < SPLIT_HELPER_TEMP_ARG_COUNT; i++) {
            visitor.visitInsn(Opcodes.ICONST_0);
        }
        for (int localIndex : unit.promotedSlotLocals().values()) {
            visitor.visitVarInsn(Opcodes.ILOAD, localIndex);
        }
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, ownerInternalName, chunkName, helperDescriptor, false);
        visitor.visitInsn(Opcodes.ARETURN);
    }

    private static void emitInstruction(
            MethodVisitor visitor,
            LoweredUnit unit,
            LoweredUnit.LoweredInstruction instruction,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields,
            String ownerInternalName
    ) {
        switch (instruction) {
            case LoweredUnit.SetConstInstruction setConst ->
                    emitSetConst(visitor, unit, setConst.slotId(), setConst.value());
            case LoweredUnit.AddConstInstruction addConst ->
                    emitAddConst(visitor, unit, addConst.slotId(), addConst.delta());
            case LoweredUnit.GetInstruction get ->
                    emitGet(visitor, unit, get.slotId());
            case LoweredUnit.ResetInstruction reset ->
                    BaselineBytecodeOps.buildReset(visitor, reset.slotId());
            case LoweredUnit.OperationInstruction operation ->
                    emitOperation(visitor, unit, operation.primarySlot(), operation.secondarySlot(), operation.operation(), operation.arithmeticSemantics());
            case LoweredUnit.CallInstruction call ->
                    emitDirectInvoke(visitor, unit, call.targetFunction(), call.bindingId(), call.spillBeforeSlots(), call.reloadAfterSlots(), internalNames, callSiteCounts, requiredSlotsById, false);
            case LoweredUnit.ReflectiveBridgeInstruction reflectiveBridge ->
                    emitReflectiveBridge(visitor, unit, reflectiveBridge.bindingId(), reflectiveBridge.spillBeforeSlots(), reflectiveBridge.reloadAfterSlots());
            case LoweredUnit.ActionBridgeInstruction actionBridge ->
                    emitActionBridge(visitor, unit, actionBridge.bindingId(), actionBridge.spillBeforeSlots(), actionBridge.reloadAfterSlots());
            case LoweredUnit.SpecializedInstruction specializedInstruction ->
                    emitSpecialized(visitor, unit, specializedInstruction.specializedId(), specializedInstruction.spillBeforeSlots(), specializedInstruction.reloadAfterSlots(), specializedFields, ownerInternalName);
            case LoweredUnit.PrefetchMacroLineInstruction prefetchMacroLineInstruction ->
                    BaselineBytecodeOps.buildPrefetchMacroLine(visitor, prefetchMacroLineInstruction.planId());
            case LoweredUnit.PrefetchedMacroCallInstruction prefetchedMacroCallInstruction ->
                    emitPrefetchedMacroCall(visitor, unit, prefetchedMacroCallInstruction.planId(), prefetchedMacroCallInstruction.spillBeforeSlots(), prefetchedMacroCallInstruction.reloadAfterSlots());
            case LoweredUnit.Tier2MacroDispatchInstruction tier2MacroDispatchInstruction ->
                    emitTier2MacroDispatch(visitor, unit, tier2MacroDispatchInstruction.planId(), tier2MacroDispatchInstruction.spillBeforeSlots(), tier2MacroDispatchInstruction.reloadAfterSlots(), tier2MacroDispatchInstruction.targets(), tier2DispatchFields, ownerInternalName);
        }
    }

    private static void emitSplitTerminator(
            MethodVisitor visitor,
            LoweredUnit unit,
            LoweredUnit.LoweredTerminator terminator,
            String ownerInternalName,
            String helperDescriptor,
            int[] blockEntryChunks,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields
    ) {
        switch (terminator) {
            case LoweredUnit.CompleteTerminator ignored -> {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildCompleted(visitor);
            }
            case LoweredUnit.ReturnValueTerminator returnValue -> {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildReturnValue(visitor, returnValue.returnValue());
            }
            case LoweredUnit.JumpLocalTerminator jumpLocal ->
                    emitTailCallSplitChunk(visitor, ownerInternalName, helperDescriptor, chunkName(blockEntryChunks[jumpLocal.targetBlockIndex()]), unit, jumpLocal.targetBlockIndex());
            case LoweredUnit.JumpExternalTerminator jumpExternal -> {
                spillAllPromoted(visitor, unit);
                emitDirectInvoke(visitor, unit, jumpExternal.targetFunction(), jumpExternal.bindingId(), jumpExternal.spillBeforeSlots(), new int[0], internalNames, callSiteCounts, requiredSlotsById, true);
            }
            case LoweredUnit.SuspendActionTerminator suspendAction -> {
                spillPromotedSlots(visitor, unit, suspendAction.spillBeforeSlots());
                BaselineBytecodeOps.buildSuspend(visitor, suspendAction.bindingId(), suspendAction.continuationBlockIndex());
            }
            case LoweredUnit.SuspendPrefetchedMacroTerminator suspendPrefetchedMacro -> {
                spillPromotedSlots(visitor, unit, suspendPrefetchedMacro.spillBeforeSlots());
                BaselineBytecodeOps.buildSuspendPrefetchedMacro(visitor, suspendPrefetchedMacro.planId(), suspendPrefetchedMacro.continuationBlockIndex());
            }
            case LoweredUnit.Tier2MacroDispatchTerminator tier2MacroDispatchTerminator ->
                    emitSplitTier2MacroDispatchTerminator(visitor, unit, ownerInternalName, helperDescriptor, tier2MacroDispatchTerminator, tier2DispatchFields, blockEntryChunks);
        }
    }

    private static void emitSplitTier2MacroDispatchTerminator(
            MethodVisitor visitor,
            LoweredUnit unit,
            String ownerInternalName,
            String helperDescriptor,
            LoweredUnit.Tier2MacroDispatchTerminator terminator,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields,
            int[] blockEntryChunks
    ) {
        spillPromotedSlots(visitor, unit, terminator.spillBeforeSlots());
        BaselineBytecodeOps.buildPrefetchMacroLine(visitor, terminator.planId());
        BaselineBytecodeOps.buildLoadTier2MacroArguments(visitor, terminator.planId(), 7);
        for (LoweredUnit.Tier2DispatchTarget target : terminator.targets()) {
            Label next = new Label();
            Tier2DispatchFieldSpec fieldSpec = tier2DispatchFields.get(dispatchKey(terminator.planId(), target.guardSignature()));
            visitor.visitFieldInsn(Opcodes.GETSTATIC, ownerInternalName, fieldSpec.fieldName(), fieldSpec.descriptor());
            visitor.visitVarInsn(Opcodes.ALOAD, 7);
            visitor.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(InstalledMacroSpecialization.class),
                    "matches",
                    "(" + Type.getDescriptor(net.minecraft.nbt.NbtCompound.class) + ")Z",
                    false
            );
            visitor.visitJumpInsn(Opcodes.IFEQ, next);
            BaselineBytecodeOps.buildInlineRequiredSlots(visitor, target.requiredSlots());
            BaselineBytecodeOps.buildStaticInvoke(visitor, target.targetInternalName(), false);
            emitTailCallSplitChunk(visitor, ownerInternalName, helperDescriptor, chunkName(blockEntryChunks[terminator.continuationBlockIndex()]), unit, terminator.continuationBlockIndex());
            visitor.visitLabel(next);
        }
        BaselineBytecodeOps.buildSuspendPrefetchedMacro(visitor, terminator.planId(), terminator.continuationBlockIndex());
    }

    private static String chunkName(int chunkIndex) {
        return "invoke$chunk" + chunkIndex;
    }

    private record InvokeChunk(int blockIndex, int startInstruction, int endInstruction) {
    }

    private static void emitBlock(
            MethodVisitor visitor,
            LoweredUnit unit,
            LoweredUnit.LoweredBlock block,
            Label loopStart,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields,
            String ownerInternalName
    ) {
        for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
            switch (instruction) {
                case LoweredUnit.SetConstInstruction setConst ->
                        emitSetConst(visitor, unit, setConst.slotId(), setConst.value());
                case LoweredUnit.AddConstInstruction addConst ->
                        emitAddConst(visitor, unit, addConst.slotId(), addConst.delta());
                case LoweredUnit.GetInstruction get ->
                        emitGet(visitor, unit, get.slotId());
                case LoweredUnit.ResetInstruction reset ->
                        BaselineBytecodeOps.buildReset(visitor, reset.slotId());
                case LoweredUnit.OperationInstruction operation ->
                        emitOperation(visitor, unit, operation.primarySlot(), operation.secondarySlot(), operation.operation(), operation.arithmeticSemantics());
                case LoweredUnit.CallInstruction call ->
                        emitDirectInvoke(visitor, unit, call.targetFunction(), call.bindingId(), call.spillBeforeSlots(), call.reloadAfterSlots(), internalNames, callSiteCounts, requiredSlotsById, false);
                case LoweredUnit.ReflectiveBridgeInstruction reflectiveBridge ->
                        emitReflectiveBridge(visitor, unit, reflectiveBridge.bindingId(), reflectiveBridge.spillBeforeSlots(), reflectiveBridge.reloadAfterSlots());
                case LoweredUnit.ActionBridgeInstruction actionBridge ->
                        emitActionBridge(visitor, unit, actionBridge.bindingId(), actionBridge.spillBeforeSlots(), actionBridge.reloadAfterSlots());
                case LoweredUnit.SpecializedInstruction specializedInstruction ->
                        emitSpecialized(visitor, unit, specializedInstruction.specializedId(), specializedInstruction.spillBeforeSlots(), specializedInstruction.reloadAfterSlots(), specializedFields, ownerInternalName);
                case LoweredUnit.PrefetchMacroLineInstruction prefetchMacroLineInstruction ->
                        BaselineBytecodeOps.buildPrefetchMacroLine(visitor, prefetchMacroLineInstruction.planId());
                case LoweredUnit.PrefetchedMacroCallInstruction prefetchedMacroCallInstruction ->
                        emitPrefetchedMacroCall(visitor, unit, prefetchedMacroCallInstruction.planId(), prefetchedMacroCallInstruction.spillBeforeSlots(), prefetchedMacroCallInstruction.reloadAfterSlots());
                case LoweredUnit.Tier2MacroDispatchInstruction tier2MacroDispatchInstruction ->
                        emitTier2MacroDispatch(visitor, unit, tier2MacroDispatchInstruction.planId(), tier2MacroDispatchInstruction.spillBeforeSlots(), tier2MacroDispatchInstruction.reloadAfterSlots(), tier2MacroDispatchInstruction.targets(), tier2DispatchFields, ownerInternalName);
            }
        }

        switch (block.terminator()) {
            case LoweredUnit.CompleteTerminator ignored -> {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildCompleted(visitor);
            }
            case LoweredUnit.ReturnValueTerminator returnValue -> {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildReturnValue(visitor, returnValue.returnValue());
            }
            case LoweredUnit.JumpLocalTerminator jumpLocal -> {
                BaselineBytecodeOps.pushInt(visitor, jumpLocal.targetBlockIndex());
                visitor.visitVarInsn(Opcodes.ISTORE, 4);
                visitor.visitJumpInsn(Opcodes.GOTO, loopStart);
            }
            case LoweredUnit.JumpExternalTerminator jumpExternal -> {
                spillAllPromoted(visitor, unit);
                emitDirectInvoke(visitor, unit, jumpExternal.targetFunction(), jumpExternal.bindingId(), jumpExternal.spillBeforeSlots(), new int[0], internalNames, callSiteCounts, requiredSlotsById, true);
            }
            case LoweredUnit.SuspendActionTerminator suspendAction -> {
                spillPromotedSlots(visitor, unit, suspendAction.spillBeforeSlots());
                BaselineBytecodeOps.buildSuspend(visitor, suspendAction.bindingId(), suspendAction.continuationBlockIndex());
            }
            case LoweredUnit.SuspendPrefetchedMacroTerminator suspendPrefetchedMacro -> {
                spillPromotedSlots(visitor, unit, suspendPrefetchedMacro.spillBeforeSlots());
                BaselineBytecodeOps.buildSuspendPrefetchedMacro(visitor, suspendPrefetchedMacro.planId(), suspendPrefetchedMacro.continuationBlockIndex());
            }
            case LoweredUnit.Tier2MacroDispatchTerminator tier2MacroDispatchTerminator ->
                    emitTier2MacroDispatchTerminator(visitor, unit, loopStart, tier2MacroDispatchTerminator, tier2DispatchFields, ownerInternalName);
        }
    }

    private static void emitSetConst(MethodVisitor visitor, LoweredUnit unit, int slotId, int value) {
        if (unit.isPromoted(slotId)) {
            BaselineBytecodeOps.buildStorePromotedLocal(visitor, unit.localIndexFor(slotId), value);
        } else {
            BaselineBytecodeOps.buildSetConst(visitor, slotId, value);
        }
    }

    private static void emitAddConst(MethodVisitor visitor, LoweredUnit unit, int slotId, int delta) {
        if (unit.isPromoted(slotId)) {
            BaselineBytecodeOps.buildAddPromotedLocal(visitor, unit.localIndexFor(slotId), delta);
        } else {
            BaselineBytecodeOps.buildAddConst(visitor, slotId, delta);
        }
    }

    private static void emitGet(MethodVisitor visitor, LoweredUnit unit, int slotId) {
        if (unit.isPromoted(slotId)) {
            BaselineBytecodeOps.buildGetPromotedLocal(visitor, unit.localIndexFor(slotId));
        } else {
            BaselineBytecodeOps.buildGet(visitor, slotId);
        }
    }

    private static void emitOperation(
            MethodVisitor visitor,
            LoweredUnit unit,
            int primarySlot,
            int secondarySlot,
            String operation,
            LoweredUnit.ArithmeticSemantics arithmeticSemantics
    ) {
        if (unit.isPromoted(primarySlot) && unit.isPromoted(secondarySlot)) {
            BaselineBytecodeOps.buildPromotedOperation(visitor, unit.localIndexFor(primarySlot), unit.localIndexFor(secondarySlot), operation, arithmeticSemantics);
        } else if (unit.isPromoted(primarySlot)) {
            BaselineBytecodeOps.buildMixedOperationPrimaryPromoted(visitor, unit.localIndexFor(primarySlot), secondarySlot, operation, arithmeticSemantics);
        } else if (unit.isPromoted(secondarySlot)) {
            BaselineBytecodeOps.buildMixedOperationSecondaryPromoted(visitor, primarySlot, unit.localIndexFor(secondarySlot), operation, arithmeticSemantics);
        } else {
            BaselineBytecodeOps.buildOperation(visitor, primarySlot, secondarySlot, operation, arithmeticSemantics);
        }
    }

    private static void emitDirectInvoke(
            MethodVisitor visitor,
            LoweredUnit unit,
            net.minecraft.util.Identifier targetFunction,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            boolean returnOutcome
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);

        String targetInternalName = internalNames.get(targetFunction);
        if (targetInternalName == null) {
            if (bindingId < 0) {
                throw new IllegalStateException("Missing fallback binding for unresolved call target " + targetFunction + " in " + unit.entryId());
            }
            BaselineBytecodeOps.buildInvokeActionFallback(visitor, bindingId);
            if (returnOutcome) {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildCompleted(visitor);
            }
            reloadPromotedSlots(visitor, unit, reloadAfterSlots);
            return;
        }

        int[] requiredSlots = requiredSlotsById.getOrDefault(targetFunction, new int[0]);
        int callSites = callSiteCounts.getOrDefault(targetFunction, 0);
        if (shouldInlineRequiredSlots(requiredSlots.length, callSites)) {
            BaselineBytecodeOps.buildInlineRequiredSlots(visitor, requiredSlots);
        } else {
            BaselineBytecodeOps.buildEnsureLoadedFromStaticField(visitor, targetInternalName, REQUIRED_SLOTS_FIELD);
        }
        BaselineBytecodeOps.buildStaticInvoke(visitor, targetInternalName, returnOutcome);
        if (!returnOutcome) {
            reloadPromotedSlots(visitor, unit, reloadAfterSlots);
        }
    }

    private static void emitReflectiveBridge(
            MethodVisitor visitor,
            LoweredUnit unit,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        BaselineBytecodeOps.buildInvokeBoundCommand(visitor, bindingId);
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static void emitActionBridge(
            MethodVisitor visitor,
            LoweredUnit unit,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        BaselineBytecodeOps.buildInvokeActionFallback(visitor, bindingId);
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static void emitPrefetchedMacroCall(
            MethodVisitor visitor,
            LoweredUnit unit,
            int planId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        BaselineBytecodeOps.buildInvokePrefetchedMacro(visitor, planId);
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static void emitTier2MacroDispatch(
            MethodVisitor visitor,
            LoweredUnit unit,
            int planId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            java.util.List<LoweredUnit.Tier2DispatchTarget> targets,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields,
            String ownerInternalName
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        BaselineBytecodeOps.buildPrefetchMacroLine(visitor, planId);
        BaselineBytecodeOps.buildLoadTier2MacroArguments(visitor, planId, 7);
        Label fallback = new Label();
        Label done = new Label();
        for (LoweredUnit.Tier2DispatchTarget target : targets) {
            Label next = new Label();
            Tier2DispatchFieldSpec fieldSpec = tier2DispatchFields.get(dispatchKey(planId, target.guardSignature()));
            visitor.visitFieldInsn(Opcodes.GETSTATIC, ownerInternalName, fieldSpec.fieldName(), fieldSpec.descriptor());
            visitor.visitVarInsn(Opcodes.ALOAD, 7);
            visitor.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(InstalledMacroSpecialization.class),
                    "matches",
                    "(" + Type.getDescriptor(net.minecraft.nbt.NbtCompound.class) + ")Z",
                    false
            );
            visitor.visitJumpInsn(Opcodes.IFEQ, next);
            BaselineBytecodeOps.buildInlineRequiredSlots(visitor, target.requiredSlots());
            BaselineBytecodeOps.buildStaticInvoke(visitor, target.targetInternalName(), false);
            visitor.visitJumpInsn(Opcodes.GOTO, done);
            visitor.visitLabel(next);
        }
        visitor.visitLabel(fallback);
        BaselineBytecodeOps.buildInvokeExpandedMacroNoProfile(visitor, planId);
        visitor.visitLabel(done);
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static void emitTier2MacroDispatchTerminator(
            MethodVisitor visitor,
            LoweredUnit unit,
            Label loopStart,
            LoweredUnit.Tier2MacroDispatchTerminator terminator,
            Map<String, Tier2DispatchFieldSpec> tier2DispatchFields,
            String ownerInternalName
    ) {
        spillPromotedSlots(visitor, unit, terminator.spillBeforeSlots());
        BaselineBytecodeOps.buildPrefetchMacroLine(visitor, terminator.planId());
        BaselineBytecodeOps.buildLoadTier2MacroArguments(visitor, terminator.planId(), 7);
        for (LoweredUnit.Tier2DispatchTarget target : terminator.targets()) {
            Label next = new Label();
            Tier2DispatchFieldSpec fieldSpec = tier2DispatchFields.get(dispatchKey(terminator.planId(), target.guardSignature()));
            visitor.visitFieldInsn(Opcodes.GETSTATIC, ownerInternalName, fieldSpec.fieldName(), fieldSpec.descriptor());
            visitor.visitVarInsn(Opcodes.ALOAD, 7);
            visitor.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(InstalledMacroSpecialization.class),
                    "matches",
                    "(" + Type.getDescriptor(net.minecraft.nbt.NbtCompound.class) + ")Z",
                    false
            );
            visitor.visitJumpInsn(Opcodes.IFEQ, next);
            BaselineBytecodeOps.buildInlineRequiredSlots(visitor, target.requiredSlots());
            BaselineBytecodeOps.buildStaticInvoke(visitor, target.targetInternalName(), false);
            BaselineBytecodeOps.pushInt(visitor, terminator.continuationBlockIndex());
            visitor.visitVarInsn(Opcodes.ISTORE, 4);
            visitor.visitJumpInsn(Opcodes.GOTO, loopStart);
            visitor.visitLabel(next);
        }
        BaselineBytecodeOps.buildSuspendPrefetchedMacro(visitor, terminator.planId(), terminator.continuationBlockIndex());
    }

    private static void emitSpecialized(
            MethodVisitor visitor,
            LoweredUnit unit,
            int specializedId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            String ownerInternalName
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        SpecializedFieldSpec fieldSpec = specializedFields.get(specializedId);
        if (fieldSpec == null) {
            throw new IllegalStateException("Missing specialized field for plan " + specializedId + " in " + unit.entryId());
        }
        fieldSpec.plan().emitBytecode(new SpecializedEmitContext(visitor, ownerInternalName, fieldSpec.fieldName(), fieldSpec.planDescriptor()));
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static Map<Integer, SpecializedFieldSpec> collectSpecializedFields(LoweredUnit unit) {
        Map<Integer, SpecializedFieldSpec> fieldSpecs = new LinkedHashMap<>();
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                if (!(instruction instanceof LoweredUnit.SpecializedInstruction specializedInstruction)) {
                    continue;
                }
                fieldSpecs.computeIfAbsent(specializedInstruction.specializedId(), BaselineBytecodeCompiler::specializedFieldSpec);
            }
        }
        return fieldSpecs;
    }

    private static Map<String, Tier2DispatchFieldSpec> collectTier2DispatchFields(LoweredUnit unit) {
        Map<String, Tier2DispatchFieldSpec> fieldSpecs = new LinkedHashMap<>();
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                if (instruction instanceof LoweredUnit.Tier2MacroDispatchInstruction tier2MacroDispatchInstruction) {
                    for (LoweredUnit.Tier2DispatchTarget target : tier2MacroDispatchInstruction.targets()) {
                        fieldSpecs.computeIfAbsent(dispatchKey(tier2MacroDispatchInstruction.planId(), target.guardSignature()), ignored ->
                                new Tier2DispatchFieldSpec(
                                        "TIER2_MACRO_" + tier2MacroDispatchInstruction.planId() + "_" + Integer.toHexString(target.guardSignature().hashCode()),
                                        tier2MacroDispatchInstruction.planId(),
                                        target.guardSignature(),
                                        Type.getDescriptor(InstalledMacroSpecialization.class)
                                ));
                    }
                }
            }
            if (block.terminator() instanceof LoweredUnit.Tier2MacroDispatchTerminator tier2MacroDispatchTerminator) {
                for (LoweredUnit.Tier2DispatchTarget target : tier2MacroDispatchTerminator.targets()) {
                    fieldSpecs.computeIfAbsent(dispatchKey(tier2MacroDispatchTerminator.planId(), target.guardSignature()), ignored ->
                            new Tier2DispatchFieldSpec(
                                    "TIER2_MACRO_" + tier2MacroDispatchTerminator.planId() + "_" + Integer.toHexString(target.guardSignature().hashCode()),
                                    tier2MacroDispatchTerminator.planId(),
                                    target.guardSignature(),
                                    Type.getDescriptor(InstalledMacroSpecialization.class)
                            ));
                }
            }
        }
        return fieldSpecs;
    }

    private static String dispatchKey(int planId, String guardSignature) {
        return planId + "::" + guardSignature;
    }

    private static SpecializedFieldSpec specializedFieldSpec(int specializedId) {
        SpecializedPlan plan = SpecializedCommandRegistry.requirePlan(specializedId);
        if (plan instanceof ExecutePlan) {
            return new SpecializedFieldSpec(
                    specializedId,
                    "SPECIALIZED_PLAN_" + specializedId,
                    Type.getDescriptor(ExecutePlan.class),
                    Type.getInternalName(ExecutePlan.class),
                    plan
            );
        }
        if (plan instanceof DataModifyStoragePlan) {
            return new SpecializedFieldSpec(
                    specializedId,
                    "SPECIALIZED_PLAN_" + specializedId,
                    Type.getDescriptor(DataModifyStoragePlan.class),
                    Type.getInternalName(DataModifyStoragePlan.class),
                    plan
            );
        }
        throw new IllegalStateException("Unsupported specialized plan type " + plan.getClass().getName());
    }

    private static void spillAllPromoted(MethodVisitor visitor, LoweredUnit unit) {
        for (Map.Entry<Integer, Integer> entry : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildSpillPromotedSlot(visitor, entry.getKey(), entry.getValue());
        }
    }

    private static void spillPromotedSlots(MethodVisitor visitor, LoweredUnit unit, int[] slotIds) {
        for (int slotId : slotIds) {
            if (unit.isPromoted(slotId)) {
                BaselineBytecodeOps.buildSpillPromotedSlot(visitor, slotId, unit.localIndexFor(slotId));
            }
        }
    }

    private static void reloadPromotedSlots(MethodVisitor visitor, LoweredUnit unit, int[] slotIds) {
        for (int slotId : slotIds) {
            if (unit.isPromoted(slotId)) {
                BaselineBytecodeOps.buildReloadPromotedSlot(visitor, slotId, unit.localIndexFor(slotId));
            }
        }
    }

    public static boolean shouldInlineRequiredSlots(int slotCount, int callSites) {
        if (slotCount == 0) {
            return true;
        }
        if (callSites <= 1) {
            return true;
        }
        return 2 * slotCount * callSites - (3 * slotCount + 2 * callSites) < 10;
    }

    private static String sanitize(String input) {
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    public record CompiledClassData(
            net.minecraft.util.Identifier id,
            String internalName,
            byte[] classBytes,
            int[] requiredSlots
    ) {
    }

    private record SpecializedFieldSpec(
            int specializedId,
            String fieldName,
            String planDescriptor,
            String planInternalName,
            SpecializedPlan plan
    ) {
    }

    private record Tier2DispatchFieldSpec(
            String fieldName,
            int planId,
            String guardSignature,
            String descriptor
    ) {
    }
}
