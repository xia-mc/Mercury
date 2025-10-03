package asia.lira.mercury.impl;

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
import java.util.List;
import java.util.Locale;

import static net.minecraft.server.function.Macro.Line;

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
    public final List<Line<T>> lines;
    private final long thisHashCode = (((long) System.identityHashCode(this)) << 32);

    // buffer
    private final NbtElement[] argBuffer;

    public FastMacro(Identifier id, @NotNull List<Line<T>> lines, @NotNull List<String> varNames) {
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

    @Contract("_, _ -> new")
    public @NotNull ExpandedMacro<T> withMacroReplaced(long uniqueId, CommandDispatcher<T> dispatcher) throws MacroException {
        NbtElement[] arguments = argBuffer;
        SourcedCommandAction<T>[] list = new SourcedCommandAction[lines.size()];

        for (int i = 0, linesSize = lines.size(); i < linesSize; i++) {
            Line<T> line = lines.get(i);
            if (line instanceof Macro.FixedLine<T> fixedLine) {
                list[i] = fixedLine.action;
                continue;
            }

            Macro.VariableLine<T> variableLine = (Macro.VariableLine<T>) line;
            assert !variableLine.getDependentVariables().isEmpty();
            list[i] = line.instantiate(
                    variableLine.getDependentVariables().intStream()
                            .mapToObj(index -> arguments[index])
                            .map(FastMacro::toString)
                            .toList(),
                    dispatcher, id
            );
        }

        return new ExpandedMacro<>(
                id.withPath((path) -> path + "/" + uniqueId),
                Arrays.asList(list)
        );
    }
}
