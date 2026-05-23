package asia.lira.mercury.jit.codegen.ops;

import asia.lira.mercury.impl.cache.MacroPrefetchRuntime;
import asia.lira.mercury.jit.codegen.BaselineBytecodeOps;
import asia.lira.mercury.jit.runtime.BaselineExecutionEngine;
import asia.lira.mercury.jit.runtime.ExecutionFrame;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ASM emitters for macro-prefetch invocation, expansion and tier-2 dispatch. These ops all
 * route through {@link MacroPrefetchRuntime} static dispatch entries; the JIT method signature
 * is assumed to be {@code (ExecutionFrame frame, Object source, CommandExecutionContext ctx, Frame cmdFrame)}
 * so locals 0-3 are at fixed positions.
 */
public final class MacroOps {
    private static final String FRAME_INTERNAL = Type.getInternalName(ExecutionFrame.class);
    private static final String RUNTIME_INTERNAL = Type.getInternalName(BaselineExecutionEngine.class);
    private static final String PREFETCH_RUNTIME_INTERNAL = Type.getInternalName(MacroPrefetchRuntime.class);
    private static final String CONTEXT_DESC = Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class);
    private static final String COMMAND_FRAME_DESC = Type.getDescriptor(net.minecraft.command.Frame.class);

    private MacroOps() {
    }

    public static void buildPrefetchMacroLine(MethodVisitor visitor, int planId) {
        BaselineBytecodeOps.pushInt(visitor, planId);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, PREFETCH_RUNTIME_INTERNAL, "prefetch", "(I)V", false);
    }

    public static void buildInvokePrefetchedMacro(MethodVisitor visitor, int planId) {
        BaselineBytecodeOps.pushInt(visitor, planId);
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

    public static void buildFlushFrame(MethodVisitor visitor) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "flushFrame",
                "(L" + FRAME_INTERNAL + ";)V",
                false
        );
    }

    public static void buildInvokePrefetchedMacroDirect(MethodVisitor visitor, int planId) {
        BaselineBytecodeOps.pushInt(visitor, planId);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitVarInsn(Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                PREFETCH_RUNTIME_INTERNAL,
                "invokePrefetchedMacroDirect",
                "(ILjava/lang/Object;" + CONTEXT_DESC + COMMAND_FRAME_DESC + ")V",
                false
        );
    }

    public static void buildLoadTier2MacroArguments(MethodVisitor visitor, int planId, int localIndex) {
        BaselineBytecodeOps.pushInt(visitor, planId);
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
        BaselineBytecodeOps.pushInt(visitor, planId);
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

    public static void buildRecordInlinePrefetchHit(MethodVisitor visitor) {
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                RUNTIME_INTERNAL,
                "recordInlinePrefetchHit",
                "()V",
                false
        );
    }
}
