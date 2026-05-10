package asia.lira.mercury.mixin;

import asia.lira.mercury.impl.FastMacro;
import asia.lira.mercury.ir.FunctionActionCaptureRegistry;
import asia.lira.mercury.ir.FunctionIrCompiler;
import asia.lira.mercury.ir.FunctionParseCaptureRegistry;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.ExpandedMacro;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(targets = "net.minecraft.server.function.FunctionBuilder")
public class MixinFunctionBuilder<T extends AbstractServerCommandSource<T>> {

    @Shadow private @Nullable List<?> macroLines;

    @Shadow @Final private List<String> usedVariables;

    @Shadow private @Nullable List<SourcedCommandAction<T>> actions;

    /**
     * @author xia__mc
     * @reason to redirect the Macro to FastMacro by overwriting the NEW bytecode
     */
    @Overwrite
    public CommandFunction<T> toCommandFunction(Identifier id) {
        FunctionParseCaptureRegistry.getInstance().capture(
                FunctionIrCompiler.compile(id, this.actions, this.macroLines, this.usedVariables)
        );
        if (this.actions != null) {
            FunctionActionCaptureRegistry.getInstance().capture(id, this.actions);
        }
        if (this.macroLines != null) {
            return new FastMacro<>(id, this.macroLines, this.usedVariables);
        }

        return new ExpandedMacro<>(id, this.actions);
    }
}
