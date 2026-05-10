package asia.lira.mercury.ir;

import asia.lira.mercury.mixin.accessor.MixinCommandContextAccessor;
import asia.lira.mercury.mixin.accessor.MixinMacroFixedLineAccessor;
import asia.lira.mercury.mixin.accessor.MixinMacroVariableLineAccessor;
import asia.lira.mercury.mixin.accessor.MixinSingleCommandActionAccessor;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ContextChain;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.command.MacroInvocation;
import net.minecraft.command.SingleCommandAction;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FunctionIrCompiler {
    private FunctionIrCompiler() {
    }

    public static <T extends AbstractServerCommandSource<T>> FunctionIrRegistry.ParsedFunctionIr compile(
            Identifier id,
            @Nullable List<SourcedCommandAction<T>> actions,
            @Nullable List<?> macroLines,
            List<String> usedVariables
    ) {
        List<FunctionIrRegistry.ParseNode> nodes = new ArrayList<>();

        if (macroLines != null) {
            for (Object line : macroLines) {
                if (line instanceof MixinMacroFixedLineAccessor<?> fixedLine) {
                    nodes.add(compileAction(((MixinMacroFixedLineAccessor<T>) fixedLine).mercury$getAction()));
                    continue;
                }

                nodes.add(compileMacroTemplate(line));
            }
        } else if (actions != null) {
            for (int i = 0; i < actions.size(); i++) {
                nodes.add(compileAction(actions.get(i)));
            }
        }

        return new FunctionIrRegistry.ParsedFunctionIr(id, macroLines != null, List.copyOf(usedVariables), List.copyOf(nodes));
    }

    private static <T extends AbstractServerCommandSource<T>> FunctionIrRegistry.ParseNode compileAction(SourcedCommandAction<T> action) {
        if (!(action instanceof SingleCommandAction.Sourced<?> sourcedAction)) {
            return new FunctionIrRegistry.CommandParseNode(
                    action.toString(),
                    List.of(),
                    List.of(),
                    new FunctionIrRegistry.CommandBindingIr(List.of(), action.getClass().getName(), false, false),
                    FunctionIrRegistry.ControlFlowKind.OPAQUE,
                    null,
                    List.of("unsupported-action:" + action.getClass().getName())
            );
        }

        MixinSingleCommandActionAccessor<T> accessor = (MixinSingleCommandActionAccessor<T>) sourcedAction;
        String commandText = accessor.mercury$getCommand();
        ContextChain<T> contextChain = accessor.mercury$getContextChain();

        List<CommandContext<T>> contexts = flatten(contextChain);
        List<FunctionIrRegistry.ContextLayerIr> layers = contexts.stream()
                .map(FunctionIrCompiler::captureLayer)
                .toList();

        CommandContext<T> finalContext = contexts.isEmpty() ? null : contexts.get(contexts.size() - 1);
        List<FunctionIrRegistry.ParsedArgumentIr> arguments = finalContext == null ? List.of() : captureArguments(finalContext);
        List<String> rootPath = finalContext == null ? List.of() : capturePath(finalContext);
        List<String> leadingPath = contexts.isEmpty() ? rootPath : capturePath(contexts.getFirst());
        Command<?> executable = finalContext == null ? null : finalContext.getCommand();
        FunctionIrRegistry.ControlFlowKind controlFlowKind = classify(leadingPath, commandText);
        Identifier targetFunctionId = findFunctionTarget(commandText, controlFlowKind);

        List<String> notes = new ArrayList<>();
        if (finalContext != null && finalContext.getRedirectModifier() != null) {
            notes.add("redirect");
        }
        if (finalContext != null && finalContext.isForked()) {
            notes.add("fork");
        }

        return new FunctionIrRegistry.CommandParseNode(
                commandText,
                layers,
                arguments,
                new FunctionIrRegistry.CommandBindingIr(rootPath, executable == null ? "<null>" : executable.getClass().getName(), executable != null, false),
                controlFlowKind,
                targetFunctionId,
                List.copyOf(notes)
        );
    }

    private static <T extends AbstractServerCommandSource<T>> FunctionIrRegistry.MacroTemplateParseNode compileMacroTemplate(Object line) {
        MixinMacroVariableLineAccessor<T> accessor = (MixinMacroVariableLineAccessor<T>) line;
        MacroInvocation invocation = accessor.mercury$getInvocation();
        IntList indices = accessor.mercury$getVariableIndices();

        List<Integer> dependentSlots = new ArrayList<>(indices.size());
        indices.forEach((int index) -> dependentSlots.add(index));

        return new FunctionIrRegistry.MacroTemplateParseNode(
                rebuildTemplate(invocation),
                List.copyOf(invocation.segments()),
                List.copyOf(invocation.variables()),
                List.copyOf(dependentSlots)
        );
    }

    private static <S> List<CommandContext<S>> flatten(ContextChain<S> chain) {
        List<CommandContext<S>> contexts = new ArrayList<>();
        ContextChain<S> current = chain;
        int guard = 0;
        while (current != null && guard++ < 32) {
            contexts.add(current.getTopContext());
            current = current.nextStage();
        }
        return contexts;
    }

    private static <S> FunctionIrRegistry.ContextLayerIr captureLayer(CommandContext<S> context) {
        List<String> path = capturePath(context);
        return new FunctionIrRegistry.ContextLayerIr(
                Math.max(0, path.size() - 1),
                path,
                context.getRange().getStart(),
                context.getRange().getEnd(),
                context.isForked(),
                context.getRedirectModifier() != null,
                context.getCommand() != null
        );
    }

    private static <S> List<FunctionIrRegistry.ParsedArgumentIr> captureArguments(CommandContext<S> context) {
        Map<String, ParsedArgument<S, ?>> arguments = ((MixinCommandContextAccessor<S>) context).mercury$getArguments();
        List<FunctionIrRegistry.ParsedArgumentIr> out = new ArrayList<>(arguments.size());

        for (Map.Entry<String, ParsedArgument<S, ?>> entry : arguments.entrySet()) {
            ParsedArgument<S, ?> parsedArgument = entry.getValue();
            Object result = parsedArgument.getResult();
            int start = parsedArgument.getRange().getStart();
            int end = parsedArgument.getRange().getEnd();
            String parsedSlice = safeSlice(context.getInput(), start, end);
            String preview = result == null ? "null" : abbreviate(String.valueOf(result), 120);
            String valueType = result == null ? "null" : result.getClass().getName();

            out.add(new FunctionIrRegistry.ParsedArgumentIr(
                    entry.getKey(),
                    start,
                    end,
                    parsedSlice,
                    valueType,
                    preview,
                    true
            ));
        }

        return List.copyOf(out);
    }

    private static <S> List<String> capturePath(CommandContext<S> context) {
        List<String> path = new ArrayList<>(context.getNodes().size());
        for (ParsedCommandNode<S> node : context.getNodes()) {
            path.add(node.getNode().getName());
        }
        return List.copyOf(path);
    }

    private static FunctionIrRegistry.ControlFlowKind classify(List<String> path, String commandText) {
        if (path.isEmpty()) {
            return FunctionIrRegistry.ControlFlowKind.OPAQUE;
        }

        String root = path.getFirst();
        return switch (root) {
            case "execute" -> commandText.contains(" run function ") || commandText.endsWith(" run function")
                    ? FunctionIrRegistry.ControlFlowKind.EXECUTE_RUN_FUNCTION
                    : FunctionIrRegistry.ControlFlowKind.EXECUTE;
            case "function" -> FunctionIrRegistry.ControlFlowKind.FUNCTION;
            case "return" -> commandText.startsWith("return run function ")
                    ? FunctionIrRegistry.ControlFlowKind.RETURN_RUN_FUNCTION
                    : FunctionIrRegistry.ControlFlowKind.RETURN;
            default -> FunctionIrRegistry.ControlFlowKind.NONE;
        };
    }

    private static @Nullable Identifier findFunctionTarget(String commandText, FunctionIrRegistry.ControlFlowKind controlFlowKind) {
        String prefix = switch (controlFlowKind) {
            case FUNCTION -> "function ";
            case RETURN_RUN_FUNCTION -> "return run function ";
            case EXECUTE_RUN_FUNCTION -> " run function ";
            default -> null;
        };
        if (prefix == null) {
            return null;
        }

        int start = controlFlowKind == FunctionIrRegistry.ControlFlowKind.EXECUTE_RUN_FUNCTION
                ? commandText.lastIndexOf(prefix)
                : commandText.indexOf(prefix);
        if (start < 0) {
            return null;
        }

        int idStart = start + prefix.length();
        if (idStart >= commandText.length()) {
            return null;
        }

        int idEnd = commandText.indexOf(' ', idStart);
        String rawId = idEnd < 0 ? commandText.substring(idStart) : commandText.substring(idStart, idEnd);

        try {
            return Identifier.of(rawId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String rebuildTemplate(MacroInvocation invocation) {
        StringBuilder builder = new StringBuilder();
        int variableCount = invocation.variables().size();
        for (int i = 0; i < variableCount; i++) {
            builder.append(invocation.segments().get(i));
            builder.append("$(").append(invocation.variables().get(i)).append(')');
        }
        if (invocation.segments().size() > variableCount) {
            builder.append(invocation.segments().getLast());
        }
        return builder.toString();
    }

    private static String safeSlice(String input, int start, int end) {
        if (input == null) {
            return "";
        }
        int safeStart = Math.max(0, Math.min(start, input.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, input.length()));
        return input.substring(safeStart, safeEnd);
    }

    private static String abbreviate(String input, int maxLength) {
        if (input.length() <= maxLength) {
            return input;
        }
        return input.substring(0, maxLength - 3) + "...";
    }
}
