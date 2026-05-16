package asia.lira.mercury.command;

import asia.lira.mercury.Mercury;
import asia.lira.mercury.impl.cache.MacroPrefetchExporter;
import asia.lira.mercury.impl.cache.MacroOptimizationExporter;
import asia.lira.mercury.ir.FunctionIrDumper;
import asia.lira.mercury.ir.FunctionIrExporter;
import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.jit.dump.BaselineClassExporter;
import asia.lira.mercury.jit.dump.JitPreparationExporter;
import asia.lira.mercury.jit.runtime.BaselineExecutionEngine;
import asia.lira.mercury.jit.runtime.MercuryJitRuntime;
import asia.lira.mercury.jit.runtime.SynchronizationRuntime;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.scoreboard.*;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ReloadCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static net.minecraft.server.command.CommandManager.literal;


public class CommandHandler implements CommandRegistrationCallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("Mercury");
    public static final ScoreHolder RAX = ScoreHolder.fromName("rax");
    public static final ScoreHolder R0 = ScoreHolder.fromName("r0");
    public static final Identifier STORAGE = Identifier.of("std", "vm");

    private static NbtList getHeap() {
        DataCommandStorage storage = Mercury.SERVER.getDataCommandStorage();
        return storage.get(STORAGE).getList("heap", NbtElement.INT_TYPE);
    }

    private static boolean hasHeapRange(NbtList heap, int address, int length) {
        return address >= 0 && length >= 0 && address <= heap.size() - length;
    }

    private static int sendLines(ServerCommandSource source, List<String> lines) {
        for (String line : lines) {
            source.sendFeedback(() -> Text.literal(line), false);
        }
        return lines.size();
    }

    private static int dumpRegistry(ServerCommandSource source) {
        FunctionIrRegistry registry = FunctionIrRegistry.getInstance();
        return sendLines(source, FunctionIrDumper.dumpRegistrySummary(registry, 20));
    }

    private static int dumpAll(ServerCommandSource source) {
        try {
            FunctionIrRegistry registry = FunctionIrRegistry.getInstance();
            FunctionIrExporter.ExportResult parsedResult = FunctionIrExporter.exportParsed(
                    registry,
                    source.getServer().getRunDirectory()
            );
            FunctionIrExporter.ExportResult semanticResult = FunctionIrExporter.exportSemantic(
                    registry,
                    source.getServer().getRunDirectory()
            );
            JitPreparationExporter.ExportResult preparedResult = JitPreparationExporter.exportPrepared(
                    source.getServer().getRunDirectory()
            );
            MacroPrefetchExporter.ExportResult prefetchResult = MacroPrefetchExporter.export(
                    source.getServer().getRunDirectory()
            );
            MacroOptimizationExporter.ExportResult macroOptimizationResult = MacroOptimizationExporter.export(
                    source.getServer().getRunDirectory()
            );
            BaselineClassExporter.ExportResult classResult = BaselineClassExporter.exportClasses(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported parsed=" + parsedResult.exportedCount()
                            + " to " + parsedResult.outputDirectory()
                            + ", semantic=" + semanticResult.exportedCount()
                            + " to " + semanticResult.outputDirectory()
                            + ", prepared=" + preparedResult.exportedCount()
                            + " to " + preparedResult.outputDirectory()
                            + ", prefetch candidates=" + prefetchResult.candidateCount()
                            + " active=" + prefetchResult.activeCount()
                            + " to " + prefetchResult.outputDirectory()
                            + ", macroProfile=" + macroOptimizationResult.profileCount()
                            + " specialization=" + macroOptimizationResult.specializationCount()
                            + " tier2=" + macroOptimizationResult.tier2Count()
                            + " to " + macroOptimizationResult.outputDirectory()
                            + ", classes=" + classResult.exportedCount()
                            + " to " + classResult.outputDirectory()
            ), false);
            return parsedResult.exportedCount() + semanticResult.exportedCount() + preparedResult.exportedCount() + prefetchResult.candidateCount() + macroOptimizationResult.profileCount() + classResult.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export IR: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpParsed(ServerCommandSource source) {
        try {
            FunctionIrExporter.ExportResult result = FunctionIrExporter.exportParsed(
                    FunctionIrRegistry.getInstance(),
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " parsed IR files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export parsed IR: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpSemantic(ServerCommandSource source) {
        try {
            FunctionIrExporter.ExportResult result = FunctionIrExporter.exportSemantic(
                    FunctionIrRegistry.getInstance(),
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " semantic IR files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export semantic IR: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpPrepared(ServerCommandSource source) {
        try {
            JitPreparationExporter.ExportResult result = JitPreparationExporter.exportPrepared(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " prepared JIT files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export prepared JIT: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpClasses(ServerCommandSource source) {
        try {
            BaselineClassExporter.ExportResult result = BaselineClassExporter.exportClasses(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " generated class files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export generated classes: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpPrefetch(ServerCommandSource source) {
        try {
            MacroPrefetchExporter.ExportResult result = MacroPrefetchExporter.export(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported prefetch candidates=" + result.candidateCount()
                            + " active=" + result.activeCount()
                            + " to " + result.outputDirectory()
            ), false);
            return result.candidateCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export prefetch dump: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpMacroOptimization(ServerCommandSource source) {
        try {
            MacroOptimizationExporter.ExportResult result = MacroOptimizationExporter.export(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported macroProfile=" + result.profileCount()
                            + " specialization=" + result.specializationCount()
                            + " tier2=" + result.tier2Count()
                            + " to " + result.outputDirectory()
            ), false);
            return result.profileCount() + result.specializationCount() + result.tier2Count();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export macro optimization dump: " + e.getMessage()));
            return 0;
        }
    }

    private static int showStats(ServerCommandSource source) {
        long hits = BaselineExecutionEngine.DIRECT_CALLD_HITS.get();
        long fallbacks = BaselineExecutionEngine.DIRECT_CALLD_FALLBACKS.get();
        source.sendFeedback(() -> Text.literal(
                "directCalld: hits=" + hits + " fallbacks=" + fallbacks
        ), false);
        return 1;
    }

    private static int switchJit(ServerCommandSource source, boolean enabled) {
        if (SynchronizationRuntime.getInstance().hasActiveFrames()) {
            source.sendError(Text.literal(
                    enabled
                            ? "Cannot enable Mercury JIT while a compiled frame is active"
                            : "Cannot disable Mercury JIT while a compiled frame is active"
            ));
            return -1;
        }

        if (MercuryJitRuntime.isEnabled() == enabled) {
            source.sendFeedback(() -> Text.literal(
                    enabled ? "Mercury JIT is already enabled" : "Mercury JIT is already disabled"
            ), false);
            return 1;
        }

        MercuryJitRuntime.setEnabled(enabled);
        ReloadCommand.tryReloadDataPacks(source.getServer().getDataPackManager().getEnabledIds(), source);
        source.sendFeedback(() -> Text.literal(
                enabled
                        ? "Mercury JIT enabled; reloading datapacks"
                        : "Mercury JIT disabled; reloading datapacks"
        ), true);
        return 1;
    }

    @Override
    public void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher,
                         CommandRegistryAccess access, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("mercury")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("dump")
                        .executes(context -> dumpAll(context.getSource()))
                        .then(literal("parsed")
                                .executes(context -> dumpParsed(context.getSource()))
                        )
                        .then(literal("semantic")
                                .executes(context -> dumpSemantic(context.getSource()))
                        )
                        .then(literal("prepared")
                                .executes(context -> dumpPrepared(context.getSource()))
                        )
                        .then(literal("classes")
                                .executes(context -> dumpClasses(context.getSource()))
                        )
                        .then(literal("prefetch")
                                .executes(context -> dumpPrefetch(context.getSource()))
                        )
                        .then(literal("macro-optimization")
                                .executes(context -> dumpMacroOptimization(context.getSource()))
                        )
                )
                .then(literal("jit")
                        .then(literal("enable")
                                .executes(context -> switchJit(context.getSource(), true))
                        )
                        .then(literal("disable")
                                .executes(context -> switchJit(context.getSource(), false))
                        )
                )
                .then(literal("stats")
                        .executes(context -> showStats(context.getSource()))
                )
        );

        Command<ServerCommandSource> syscallHandler = context -> {
            ServerScoreboard scoreboard = Mercury.SERVER.getScoreboard();
            ScoreboardObjective vmRegs = scoreboard.getNullableObjective("vm_regs");
            if (vmRegs == null) {
                return -1;
            }
            ScoreboardScore rax = (ScoreboardScore) scoreboard.getScore(RAX, vmRegs);
            if (rax == null) {
                return -1;
            }
            int id = rax.getScore();

            switch (id) {
                case 0 -> {  // int getAPIVersion()
                    rax.setScore(Mercury.API_VERSION);
                    return 1;
                }
                case 1 -> {  // void nanoTimes(Int64 *result)
                    ReadableScoreboardScore r0 = scoreboard.getScore(R0, vmRegs);
                    if (r0 == null) {
                        return -1;
                    }
                    NbtList heap = getHeap();
                    // typedef struct {
                    //     int low;
                    //     int high;
                    // } Int64;
                    int addr = r0.getScore();
                    if (!hasHeapRange(heap, addr, 2)) {
                        return -1;
                    }
                    long value = System.nanoTime();
                    int low = (int) (value & 0xFFFFFFFFL);
                    int high = (int) ((value >>> 32) & 0xFFFFFFFFL);
                    heap.setElement(addr, NbtInt.of(low));
                    heap.setElement(addr + 1, NbtInt.of(high));
                    return 1;
                }
                case 2 -> {  // void puts(const char *string)
                    ReadableScoreboardScore r0 = scoreboard.getScore(R0, vmRegs);
                    if (r0 == null) {
                        return -1;
                    }
                    NbtList heap = getHeap();
                    int addr = r0.getScore();
                    if (!hasHeapRange(heap, addr, 1)) {
                        return -1;
                    }
                    StringBuilder builder = new StringBuilder();
                    while (addr < heap.size()) {
                        char c = (char) heap.getInt(addr);
                        if (c == 0) {
                            String text = builder.toString();
                            LOGGER.info("[benchmark] {}", text);
                            Mercury.SERVER.getPlayerManager().broadcast(Text.literal(text), false);
                            return 1;
                        }
                        builder.append(c);
                        addr++;
                    }
                    return -1;
                }
                default -> {
                    return -1;
                }
            }
        };
        dispatcher.register(literal("syscall")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(syscallHandler)
        );
        dispatcher.register(literal("mcfp")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("syscall")
                        .executes(syscallHandler)
                )
        );
    }
}
