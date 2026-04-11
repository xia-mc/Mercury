package asia.lira.mercury.jit.specialized.impl.data;

import asia.lira.mercury.jit.pipeline.CommandPatterns;
import asia.lira.mercury.jit.specialized.api.SpecializationAnalyzer;
import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import com.mojang.brigadier.StringReader;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;

public final class DataModifyStorageAnalyzer implements SpecializationAnalyzer {
    @Override
    public @Nullable SpecializedPlan analyze(String sourceText) {
        Matcher matcher = CommandPatterns.DATA_MODIFY_STORAGE_SET_VALUE.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.SET_VALUE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        StringNbtReader.parse(matcher.group(3)),
                        null,
                        null
                );
            } catch (Exception exception) {
                return null;
            }
        }

        matcher = CommandPatterns.DATA_MODIFY_STORAGE_SET_FROM.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.SET_FROM_STORAGE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        null,
                        Identifier.of(matcher.group(3)),
                        parsePath(matcher.group(4))
                );
            } catch (Exception exception) {
                return null;
            }
        }

        matcher = CommandPatterns.DATA_MODIFY_STORAGE_MERGE_VALUE.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.MERGE_VALUE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        StringNbtReader.parse(matcher.group(3)),
                        null,
                        null
                );
            } catch (Exception exception) {
                return null;
            }
        }

        matcher = CommandPatterns.DATA_MODIFY_STORAGE_MERGE_FROM.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.MERGE_FROM_STORAGE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        null,
                        Identifier.of(matcher.group(3)),
                        parsePath(matcher.group(4))
                );
            } catch (Exception exception) {
                return null;
            }
        }

        return null;
    }

    private static NbtPathArgumentType.NbtPath parsePath(String expression) throws Exception {
        return NbtPathArgumentType.nbtPath().parse(new StringReader(expression));
    }
}
