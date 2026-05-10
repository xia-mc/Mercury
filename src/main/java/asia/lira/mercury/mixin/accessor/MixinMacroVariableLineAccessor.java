package asia.lira.mercury.mixin.accessor;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.command.MacroInvocation;
import net.minecraft.server.command.AbstractServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.function.Macro$VariableLine")
public interface MixinMacroVariableLineAccessor<T extends AbstractServerCommandSource<T>> {
    @Accessor("invocation")
    MacroInvocation mercury$getInvocation();

    @Accessor("variableIndices")
    IntList mercury$getVariableIndices();
}
