package asia.lira.mercury.jit.specialized.impl.execute;

import asia.lira.mercury.jit.pipeline.CommandPatterns;
import asia.lira.mercury.jit.specialized.api.SpecializationAnalyzer;
import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import com.mojang.brigadier.StringReader;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.command.argument.NumberRangeArgumentType;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.predicate.NumberRange;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExecuteAnalyzer implements SpecializationAnalyzer {
    private static final Pattern EXECUTE_IF_SCORE_COMPARE_PATTERN = Pattern.compile("^if\\s+score\\s+(\\S+)\\s+(\\S+)\\s*(=|<|<=|>|>=)\\s*(\\S+)\\s+(\\S+)(?:\\s+(.*))?$");
    private static final Pattern EXECUTE_IF_SCORE_MATCHES_PATTERN = Pattern.compile("^if\\s+score\\s+(\\S+)\\s+(\\S+)\\s+matches\\s+(\\S+)(?:\\s+(.*))?$");
    private static final Pattern EXECUTE_STORE_SCORE_PATTERN = Pattern.compile("^store\\s+(result|success)\\s+score\\s+(\\S+)\\s+(\\S+)(?:\\s+(.*))?$");
    private static final Pattern EXECUTE_STORE_STORAGE_PATTERN = Pattern.compile("^store\\s+(result|success)\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+(byte|short|int|long|float|double)\\s+(-?(?:\\d+|\\d+\\.\\d+|\\.\\d+))(?:\\s+(.*))?$");

    @Override
    public @Nullable SpecializedPlan analyze(String sourceText) {
        if (!sourceText.startsWith("execute ")) {
            return null;
        }

        String remaining = sourceText.substring("execute ".length()).trim();
        List<ExecuteModifier> modifiers = new ArrayList<>();

        while (!remaining.startsWith("run ")) {
            Matcher storeScore = EXECUTE_STORE_SCORE_PATTERN.matcher(remaining);
            if (storeScore.matches()) {
                if (!isLiteralScoreTarget(storeScore.group(2), storeScore.group(3))) {
                    return null;
                }
                modifiers.add(new StoreScoreModifier(
                        "result".equals(storeScore.group(1)),
                        storeScore.group(2),
                        storeScore.group(3)
                ));
                remaining = tail(storeScore.group(4));
                continue;
            }

            Matcher storeStorage = EXECUTE_STORE_STORAGE_PATTERN.matcher(remaining);
            if (storeStorage.matches()) {
                try {
                    modifiers.add(new StoreStorageModifier(
                            "result".equals(storeStorage.group(1)),
                            Identifier.of(storeStorage.group(2)),
                            parsePath(storeStorage.group(3)),
                            storeStorage.group(4),
                            Double.parseDouble(storeStorage.group(5))
                    ));
                } catch (Exception exception) {
                    return null;
                }
                remaining = tail(storeStorage.group(6));
                continue;
            }

            Matcher compare = EXECUTE_IF_SCORE_COMPARE_PATTERN.matcher(remaining);
            if (compare.matches()) {
                if (!isLiteralScoreTarget(compare.group(1), compare.group(2))
                        || !isLiteralScoreTarget(compare.group(4), compare.group(5))) {
                    return null;
                }
                modifiers.add(new IfScoreCompareModifier(
                        compare.group(1),
                        compare.group(2),
                        compare.group(3),
                        compare.group(4),
                        compare.group(5)
                ));
                remaining = tail(compare.group(6));
                continue;
            }

            Matcher matches = EXECUTE_IF_SCORE_MATCHES_PATTERN.matcher(remaining);
            if (matches.matches()) {
                if (!isLiteralScoreTarget(matches.group(1), matches.group(2))) {
                    return null;
                }
                try {
                    modifiers.add(new IfScoreMatchesModifier(
                            matches.group(1),
                            matches.group(2),
                            parseRange(matches.group(3))
                    ));
                } catch (Exception exception) {
                    return null;
                }
                remaining = tail(matches.group(4));
                continue;
            }

            return null;
        }

        ExecuteTerminal terminal = parseTerminal(remaining.substring("run ".length()).trim());
        if (terminal == null) {
            return null;
        }
        return new ExecutePlan(sourceText, List.copyOf(modifiers), terminal);
    }

    private static String tail(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static @Nullable ExecuteTerminal parseTerminal(String sourceText) {
        Matcher matcher = CommandPatterns.SCOREBOARD_SET.matcher(sourceText);
        if (matcher.matches()) {
            if (!isLiteralScoreTarget(matcher.group(1), matcher.group(2))) {
                return null;
            }
            return new ScoreTerminalPlan(sourceText, ScoreTerminalPlan.Operation.SET, matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)), null, null, null);
        }

        matcher = CommandPatterns.SCOREBOARD_ADD.matcher(sourceText);
        if (matcher.matches()) {
            if (!isLiteralScoreTarget(matcher.group(1), matcher.group(2))) {
                return null;
            }
            return new ScoreTerminalPlan(sourceText, ScoreTerminalPlan.Operation.ADD, matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)), null, null, null);
        }

        matcher = CommandPatterns.SCOREBOARD_REMOVE.matcher(sourceText);
        if (matcher.matches()) {
            if (!isLiteralScoreTarget(matcher.group(1), matcher.group(2))) {
                return null;
            }
            return new ScoreTerminalPlan(sourceText, ScoreTerminalPlan.Operation.ADD, matcher.group(1), matcher.group(2), -Integer.parseInt(matcher.group(3)), null, null, null);
        }

        matcher = CommandPatterns.SCOREBOARD_GET.matcher(sourceText);
        if (matcher.matches()) {
            if (!isLiteralScoreTarget(matcher.group(1), matcher.group(2))) {
                return null;
            }
            return new ScoreTerminalPlan(sourceText, ScoreTerminalPlan.Operation.GET, matcher.group(1), matcher.group(2), 0, null, null, null);
        }

        matcher = CommandPatterns.SCOREBOARD_RESET.matcher(sourceText);
        if (matcher.matches()) {
            if (!isLiteralScoreTarget(matcher.group(1), matcher.group(2))) {
                return null;
            }
            return new ScoreTerminalPlan(sourceText, ScoreTerminalPlan.Operation.RESET, matcher.group(1), matcher.group(2), 0, null, null, null);
        }

        matcher = CommandPatterns.SCOREBOARD_OPERATION.matcher(sourceText);
        if (matcher.matches()) {
            if (!isLiteralScoreTarget(matcher.group(1), matcher.group(2))
                    || !isLiteralScoreTarget(matcher.group(4), matcher.group(5))) {
                return null;
            }
            return new ScoreTerminalPlan(sourceText, ScoreTerminalPlan.Operation.SCORE_OPERATION, matcher.group(1), matcher.group(2), 0, matcher.group(4), matcher.group(5), matcher.group(3));
        }

        matcher = CommandPatterns.DATA_MODIFY_STORAGE_SET_VALUE.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataStorageTerminalPlan(
                        sourceText,
                        DataStorageTerminalPlan.Operation.SET_VALUE,
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
                return new DataStorageTerminalPlan(
                        sourceText,
                        DataStorageTerminalPlan.Operation.SET_FROM_STORAGE,
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
                return new DataStorageTerminalPlan(
                        sourceText,
                        DataStorageTerminalPlan.Operation.MERGE_VALUE,
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
                return new DataStorageTerminalPlan(
                        sourceText,
                        DataStorageTerminalPlan.Operation.MERGE_FROM_STORAGE,
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

    private static NumberRange.IntRange parseRange(String expression) throws Exception {
        return NumberRangeArgumentType.intRange().parse(new StringReader(expression));
    }

    private static boolean isLiteralScoreTarget(String holder, String objective) {
        return isSingleLiteralScoreHolder(holder) && isLiteralScoreObjective(objective);
    }

    private static boolean isSingleLiteralScoreHolder(String token) {
        // TODO: Support selector holders by preserving or rebuilding Brigadier parsing and
        // invoking ScoreHolderArgumentType.getScoreboardScoreHolders with the current source.
        return isLiteralScoreToken(token) && token.charAt(0) != '@';
    }

    private static boolean isLiteralScoreObjective(String token) {
        return isLiteralScoreToken(token);
    }

    private static boolean isLiteralScoreToken(String token) {
        return token != null && !token.isEmpty() && token.charAt(0) != '@' && !token.startsWith("$(") && !"*".equals(token);
    }
}
