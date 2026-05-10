package asia.lira.mercury.impl;

import asia.lira.mercury.impl.cache.MacroArgumentProvider;
import asia.lira.mercury.mixin.accessor.MixinMacroFixedLineAccessor;
import asia.lira.mercury.mixin.accessor.MixinMacroLineInvoker;
import asia.lira.mercury.mixin.accessor.MixinMacroVariableLineAccessor;
import asia.lira.mercury.object.LongShardedSLRUCache;
import asia.lira.mercury.stat.FastMacroStats;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.nbt.*;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings({"unchecked"})
public final class FastMacro<T extends AbstractServerCommandSource<T>> implements CommandFunction<T> {
    private static final DecimalFormat DECIMAL_FORMAT = Util.make(new DecimalFormat("#"), (format) -> {
        format.setMaximumFractionDigits(15);
        format.setDecimalFormatSymbols(DecimalFormatSymbols.getInstance(Locale.US));
    });

    private static final int CACHE_SIZE = 512;
    private static final LongShardedSLRUCache<ExpandedMacro<?>> CACHE = new LongShardedSLRUCache<>(CACHE_SIZE, 25);
    public final List<String> varNames;
    public final Identifier id;
    public final List<?> lines;
    private final long thisHashCode = (((long) System.identityHashCode(this)) << 32);

    // buffer
    private final NbtElement[] argBuffer;

    public FastMacro(Identifier id, @NotNull List<?> lines, @NotNull List<String> varNames) {
        this.id = id;
        this.lines = lines;
        this.varNames = varNames;
        this.argBuffer = new NbtElement[varNames.size()];
    }

    public static String toString(@NotNull NbtElement nbt) {
        return switch (nbt) {
            case NbtFloat nbtFloat -> DECIMAL_FORMAT.format(nbtFloat.floatValue());
            case NbtDouble nbtDouble -> DECIMAL_FORMAT.format(nbtDouble.doubleValue());
            case NbtByte nbtByte -> String.valueOf(nbtByte.byteValue());
            case NbtShort nbtShort -> String.valueOf(nbtShort.shortValue());
            case NbtLong nbtLong -> String.valueOf(nbtLong.longValue());
            default -> nbt.asString();
        };
    }

    public Identifier id() {
        return this.id;
    }

    @Contract("null, _ -> fail")
    public @NotNull Procedure<T> withMacroReplaced(@Nullable NbtCompound arguments, CommandDispatcher<T> dispatcher) throws MacroException {
        if (arguments == null) {
            throw new MacroException(Text.translatable("commands.function.error.missing_arguments", Text.of(id)));
        } else {
            long key = hashKey(arguments);
            ExpandedMacro<T> procedure = (ExpandedMacro<T>) CACHE.get(key);
            if (procedure == null) {
                // cache miss
                procedure = this.withMacroReplaced(key, dispatcher);
                CACHE.putUnsafe(key, procedure);
                FastMacroStats.getInstance().recordMiss();
            } else {
                FastMacroStats.getInstance().recordHit();
            }
            return procedure;
        }
    }

    public @NotNull Procedure<T> withMacroReplaced(@NotNull MacroArgumentProvider argumentProvider, CommandDispatcher<T> dispatcher) throws MacroException {
        long key = hashKey(argumentProvider);
        ExpandedMacro<T> procedure = (ExpandedMacro<T>) CACHE.get(key);
        if (procedure == null) {
            procedure = this.withMacroReplaced(key, dispatcher);
            CACHE.putUnsafe(key, procedure);
            FastMacroStats.getInstance().recordMiss();
        } else {
            FastMacroStats.getInstance().recordHit();
        }
        return procedure;
    }

    private long hashKey(NbtCompound arguments) throws MacroException {
        int hashCode = 1;
        for (int i = 0, varNamesSize = varNames.size(); i < varNamesSize; i++) {
            String varName = varNames.get(i);
            NbtElement value = arguments.get(varName);
            if (value == null) {
                throw new MacroException(Text.translatable("commands.function.error.missing_argument", Text.of(id), varName));
            }
            argBuffer[i] = value;
            hashCode = 31 * hashCode + value.hashCode();
        }
        return thisHashCode | (hashCode & 0xffffffffL);
    }

    private long hashKey(MacroArgumentProvider argumentProvider) throws MacroException {
        int hashCode = 1;
        for (int i = 0, varNamesSize = varNames.size(); i < varNamesSize; i++) {
            NbtElement value = argumentProvider.resolveArgument(varNames.get(i), i);
            if (value == null) {
                throw new MacroException(Text.translatable("commands.function.error.missing_argument", Text.of(id), varNames.get(i)));
            }
            argBuffer[i] = value;
            hashCode = 31 * hashCode + value.hashCode();
        }
        return thisHashCode | (hashCode & 0xffffffffL);
    }

    public @NotNull MaterializedMacro<T> materialize(@Nullable NbtCompound arguments, CommandDispatcher<T> dispatcher) throws MacroException {
        if (arguments == null) {
            throw new MacroException(Text.translatable("commands.function.error.missing_arguments", Text.of(id)));
        }
        long key = hashKey(arguments);
        return materialize(key, dispatcher);
    }

    public @NotNull MaterializedMacro<T> materialize(@NotNull MacroArgumentProvider argumentProvider, CommandDispatcher<T> dispatcher) throws MacroException {
        long key = hashKey(argumentProvider);
        return materialize(key, dispatcher);
    }

    @Contract("_, _ -> new")
    public @NotNull ExpandedMacro<T> withMacroReplaced(long uniqueId, CommandDispatcher<T> dispatcher) throws MacroException {
        return materialize(uniqueId, dispatcher).procedure();
    }

    private @NotNull MaterializedMacro<T> materialize(long uniqueId, CommandDispatcher<T> dispatcher) throws MacroException {
        NbtElement[] arguments = argBuffer;
        SourcedCommandAction<T>[] list = new SourcedCommandAction[lines.size()];
        List<String> sourceLines = new ArrayList<>(lines.size());

        for (int i = 0, linesSize = lines.size(); i < linesSize; i++) {
            Object line = lines.get(i);
            if (line instanceof MixinMacroFixedLineAccessor<?> fixedLine) {
                SourcedCommandAction<T> action = ((MixinMacroFixedLineAccessor<T>) fixedLine).mercury$getAction();
                list[i] = action;
                sourceLines.add(stringifyAction(action));
                continue;
            }

            MixinMacroLineInvoker<T> lineInvoker = (MixinMacroLineInvoker<T>) line;
            List<String> values = lineInvoker.mercury$getDependentVariables().intStream()
                    .mapToObj(index -> arguments[index])
                    .map(FastMacro::toString)
                    .toList();
            list[i] = lineInvoker.mercury$instantiate(values, dispatcher, id);
            sourceLines.add(rebuildVariableSource(line, values));
        }

        ExpandedMacro<T> procedure = new ExpandedMacro<>(
                id.withPath((path) -> path + "/" + uniqueId),
                Arrays.asList(list)
        );
        return new MaterializedMacro<>(procedure, List.of(list), List.copyOf(sourceLines), uniqueId);
    }

    private static <T extends AbstractServerCommandSource<T>> String rebuildVariableSource(Object line, List<String> values) {
        var accessor = (MixinMacroVariableLineAccessor<T>) line;
        var invocation = accessor.mercury$getInvocation();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < invocation.segments().size(); i++) {
            builder.append(invocation.segments().get(i));
            if (i < values.size()) {
                builder.append(values.get(i));
            }
        }
        return builder.toString();
    }

    private static <T extends AbstractServerCommandSource<T>> String stringifyAction(SourcedCommandAction<T> action) {
        if (action instanceof net.minecraft.command.SingleCommandAction.Sourced<?> sourced) {
            return ((asia.lira.mercury.mixin.accessor.MixinSingleCommandActionAccessor<T>) sourced).mercury$getCommand();
        }
        return action.toString();
    }

    public record MaterializedMacro<T extends AbstractServerCommandSource<T>>(
            ExpandedMacro<T> procedure,
            List<SourcedCommandAction<T>> actions,
            List<String> sourceLines,
            long uniqueId
    ) {
    }
}
