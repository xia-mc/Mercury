package asia.lira.mercury.mixin.accessor;

import com.mojang.brigadier.CommandDispatcher;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.MacroException;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(targets = "net.minecraft.server.function.Macro$Line")
public interface MixinMacroLineInvoker<T extends AbstractServerCommandSource<T>> {
    @Invoker("getDependentVariables")
    IntList mercury$getDependentVariables();

    @Invoker("instantiate")
    SourcedCommandAction<T> mercury$instantiate(List<String> args, CommandDispatcher<T> dispatcher, Identifier id) throws MacroException;
}
