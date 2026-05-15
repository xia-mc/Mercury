package asia.lira.mercury.jit.codegen;

import asia.lira.mercury.jit.pipeline.LoweredUnit;
import net.minecraft.util.Identifier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BaselineBytecodeCompilerRegressionTest {
    private BaselineBytecodeCompilerRegressionTest() {
    }

    public static void main(String[] args) {
        assertLargeScoreboardAddFunctionSplits();
        assertSmallFunctionStaysSingleInvoke();
    }

    private static void assertLargeScoreboardAddFunctionSplits() {
        LoweredUnit unit = scoreboardAddUnit("large_scoreboard_add", 20_000, true);
        BaselineBytecodeCompiler.CompiledClassData classData = BaselineBytecodeCompiler.compile(
                unit,
                Map.of(),
                Map.of(),
                Map.of(unit.entryId(), unit.requiredSlots()),
                Set.of()
        );

        MethodCounts counts = countMethods(classData.classBytes());
        if (counts.chunkMethods == 0) {
            throw new AssertionError("Expected large scoreboard-add function to emit split helper chunks");
        }
        if (counts.invokeMethods != 1) {
            throw new AssertionError("Expected exactly one public invoke method, found " + counts.invokeMethods);
        }
    }

    private static void assertSmallFunctionStaysSingleInvoke() {
        LoweredUnit unit = scoreboardAddUnit("small_scoreboard_add", 16, true);
        BaselineBytecodeCompiler.CompiledClassData classData = BaselineBytecodeCompiler.compile(
                unit,
                Map.of(),
                Map.of(),
                Map.of(unit.entryId(), unit.requiredSlots()),
                Set.of()
        );

        MethodCounts counts = countMethods(classData.classBytes());
        if (counts.chunkMethods != 0) {
            throw new AssertionError("Expected small function to stay in the unsplit invoke path");
        }
        if (counts.invokeMethods != 1) {
            throw new AssertionError("Expected exactly one public invoke method, found " + counts.invokeMethods);
        }
    }

    private static LoweredUnit scoreboardAddUnit(String path, int instructionCount, boolean promoted) {
        Identifier id = Identifier.of("mercury_test", path);
        List<LoweredUnit.LoweredInstruction> instructions = new ArrayList<>(instructionCount);
        for (int i = 0; i < instructionCount; i++) {
            instructions.add(new LoweredUnit.AddConstInstruction(0, 1, "scoreboard players add #x obj 1"));
        }
        LoweredUnit.LoweredBlock block = new LoweredUnit.LoweredBlock(
                id,
                instructions,
                new LoweredUnit.CompleteTerminator()
        );
        return new LoweredUnit(
                id,
                List.of(block),
                0,
                new int[]{0},
                promoted ? Map.of(0, 8) : Map.of()
        );
    }

    private static MethodCounts countMethods(byte[] classBytes) {
        MethodCounts counts = new MethodCounts();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (name.equals("invoke")) {
                    counts.invokeMethods++;
                } else if (name.startsWith("invoke$chunk")) {
                    counts.chunkMethods++;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return counts;
    }

    private static final class MethodCounts {
        private int invokeMethods;
        private int chunkMethods;
    }
}
