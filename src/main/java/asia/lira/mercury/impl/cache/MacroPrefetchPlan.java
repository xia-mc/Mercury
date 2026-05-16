package asia.lira.mercury.impl.cache;

import asia.lira.mercury.impl.FastMacro;
import asia.lira.mercury.jit.codegen.BaselineBytecodeOps;
import org.objectweb.asm.MethodVisitor;

import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;

public record MacroPrefetchPlan(
        int id,
        MacroPrefetchKey key,
        MacroCallsiteKey callsiteKey,
        Identifier ownerFunctionId,
        int nodeIndex,
        int bindingId,
        FastMacro<?> macro,
        Identifier macroFunctionId,
        Identifier storageId,
        String storagePathExpression,
        NbtPathArgumentType.NbtPath storagePath,
        List<String> argumentNames,
        Map<String, String> observedFieldSources,
        String generatedMacroSummary,
        int functionDispatchArgIndex
) {
    public MacroPrefetchPlan {
        argumentNames = List.copyOf(argumentNames);
        observedFieldSources = Map.copyOf(observedFieldSources);
    }

    public void emitPrefetchBytecode(MethodVisitor visitor) {
        BaselineBytecodeOps.pushInt(visitor, id);
        visitor.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESTATIC,
                org.objectweb.asm.Type.getInternalName(MacroPrefetchRuntime.class),
                "prefetch",
                "(I)V",
                false
        );
    }

    public void emitInvokeBytecode(MethodVisitor visitor) {
        BaselineBytecodeOps.pushInt(visitor, id);
        visitor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        visitor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 2);
        visitor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 3);
        visitor.visitMethodInsn(
                org.objectweb.asm.Opcodes.INVOKESTATIC,
                org.objectweb.asm.Type.getInternalName(MacroPrefetchRuntime.class),
                "invokePrefetchedMacro",
                "(ILjava/lang/Object;"
                        + org.objectweb.asm.Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class)
                        + org.objectweb.asm.Type.getDescriptor(net.minecraft.command.Frame.class)
                        + ")V",
                false
        );
    }
}
