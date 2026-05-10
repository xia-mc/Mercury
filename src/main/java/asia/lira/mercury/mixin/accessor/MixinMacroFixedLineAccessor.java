package asia.lira.mercury.mixin.accessor;

import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.server.function.Macro$FixedLine")
public interface MixinMacroFixedLineAccessor<T extends AbstractServerCommandSource<T>> {
    @Accessor("action")
    SourcedCommandAction<T> mercury$getAction();
}
