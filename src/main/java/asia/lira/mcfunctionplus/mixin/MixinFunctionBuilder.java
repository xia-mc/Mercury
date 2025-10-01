package asia.lira.mcfunctionplus.mixin;

import asia.lira.mcfunctionplus.impl.FastMacro;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.ExpandedMacro;
import net.minecraft.server.function.Macro;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(targets = "net.minecraft.server.function.FunctionBuilder")
public class MixinFunctionBuilder<T extends AbstractServerCommandSource<T>> {

    @Shadow private @Nullable List<Macro.Line<T>> macroLines;

    @Shadow @Final private List<String> usedVariables;

    @Shadow private @Nullable List<SourcedCommandAction<T>> actions;

    /**
     * @author xia__mc
     * @reason to redirect the Macro to FastMacro by overwriting the NEW bytecode
     */
    @Overwrite
    public CommandFunction<T> toCommandFunction(Identifier id) {
        return this.macroLines != null ? new FastMacro<>(id, this.macroLines, this.usedVariables) : new ExpandedMacro<>(id, this.actions);
    }
}
