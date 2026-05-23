package asia.lira.mercury.jit.codegen.ops;

import asia.lira.mercury.jit.codegen.BaselineBytecodeOps;
import asia.lira.mercury.jit.runtime.BaselineExecutionEngine;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * ASM emitters for the per-command exception isolation try/catch blocks that align JIT-compiled
 * functions with vanilla {@code FixedCommandAction} semantics.
 *
 * <p>{@link CommandSyntaxException} catches mirror vanilla's per-command try/catch around each
 * command. {@link net.minecraft.server.function.MacroException} catches translate macro
 * instantiation failures into vanilla {@code FunctionCommand.INSTANTIATION_FAILURE_EXCEPTION}
 * via {@link BaselineExecutionEngine#handleMacroException}.
 *
 * <p>Usage pattern (both styles):
 * <pre>
 *   Label tryStart = new Label(), tryEnd = new Label(), catchStart = new Label(), afterCatch = new Label();
 *   buildRegisterCommandSyntaxExceptionHandler(visitor, tryStart, tryEnd, catchStart);
 *   visitor.visitLabel(tryStart);
 *   // ... emit the command invocation ...
 *   visitor.visitLabel(tryEnd);
 *   visitor.visitJumpInsn(GOTO, afterCatch);
 *   visitor.visitLabel(catchStart);
 *   buildInvokeHandleCommandException(visitor);
 *   visitor.visitLabel(afterCatch);
 * </pre>
 */
public final class ExceptionOps {
    private ExceptionOps() {
    }

    public static void buildRegisterCommandSyntaxExceptionHandler(
            MethodVisitor visitor,
            Label tryStart,
            Label tryEnd,
            Label catchStart
    ) {
        visitor.visitTryCatchBlock(
                tryStart, tryEnd, catchStart,
                Type.getInternalName(CommandSyntaxException.class)
        );
    }

    public static void buildInvokeHandleCommandException(MethodVisitor visitor) {
        // stack: [CommandSyntaxException]
        visitor.visitVarInsn(Opcodes.ALOAD, 1);  // source
        visitor.visitInsn(Opcodes.SWAP);           // source, exception
        visitor.visitVarInsn(Opcodes.ALOAD, 2);  // context
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(BaselineExecutionEngine.class),
                "handleCommandSyntaxException",
                "(Ljava/lang/Object;"
                        + Type.getDescriptor(CommandSyntaxException.class)
                        + Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class)
                        + ")V",
                false
        );
    }

    public static void buildRegisterMacroExceptionHandler(
            MethodVisitor visitor,
            Label tryStart,
            Label tryEnd,
            Label catchStart
    ) {
        visitor.visitTryCatchBlock(
                tryStart, tryEnd, catchStart,
                Type.getInternalName(net.minecraft.server.function.MacroException.class)
        );
    }

    public static void buildInvokeHandleMacroException(MethodVisitor visitor, int planId) {
        // stack: [MacroException]
        visitor.visitVarInsn(Opcodes.ALOAD, 1);  // source
        visitor.visitInsn(Opcodes.SWAP);           // source, exception
        visitor.visitVarInsn(Opcodes.ALOAD, 2);  // context
        BaselineBytecodeOps.pushInt(visitor, planId);
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(BaselineExecutionEngine.class),
                "handleMacroException",
                "(Ljava/lang/Object;"
                        + Type.getDescriptor(net.minecraft.server.function.MacroException.class)
                        + Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class)
                        + "I)V",
                false
        );
    }
}
