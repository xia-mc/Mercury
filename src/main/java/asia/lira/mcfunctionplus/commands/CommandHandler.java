package asia.lira.mcfunctionplus.commands;

import asia.lira.mcfunctionplus.McFunctionPlus;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import static net.minecraft.server.command.CommandManager.literal;


public class CommandHandler implements CommandRegistrationCallback {
    public static final ScoreHolder RAX = ScoreHolder.fromName("rax");
    public static final ScoreHolder R0 = ScoreHolder.fromName("r0");
    public static final Identifier HEAP = Identifier.of("std:vm", "heap");

    @Override
    public void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher,
                         CommandRegistryAccess access, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("mcfp")
                .then(literal("syscall").executes(context -> {
                    ServerScoreboard scoreboard = McFunctionPlus.SERVER.getScoreboard();
                    ScoreboardObjective vmRegs = scoreboard.getNullableObjective("vm_regs");
                    ReadableScoreboardScore rax = scoreboard.getScore(RAX, vmRegs);
                    if (rax == null) {
                        return -1;
                    }
                    int id = rax.getScore();

                    switch (id) {
                        case 0 -> {  // int getAPIVersion()
                            return McFunctionPlus.API_VERSION;
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
                            long value = System.nanoTime();
                            int low = (int) (value & 0xFFFFFFFFL);
                            int high = (int) ((value >>> 32) & 0xFFFFFFFFL);
                            heap.setElement(addr, NbtInt.of(low));
                            heap.setElement(addr + 1, NbtInt.of(high));
                            return 0;
                        }
                        case 2 -> {  // void print(const char *string)
                            ReadableScoreboardScore r0 = scoreboard.getScore(R0, vmRegs);
                            if (r0 == null) {
                                return -1;
                            }
                            NbtList heap = getHeap();
                            int addr = r0.getScore();
                            StringBuilder builder = new StringBuilder();
                            char c;
                            while ((c = (char) heap.getInt(addr)) != 0) {
                                builder.append(c);
                                addr++;
                            }
                            McFunctionPlus.SERVER.sendMessage(Text.literal(builder.toString()));
                            return 0;
                        }
                        default -> {
                            return -1;
                        }
                    }
                }))
        );
    }

    private static NbtList getHeap() {
        DataCommandStorage storage = McFunctionPlus.SERVER.getDataCommandStorage();
        return storage.get(HEAP).getList("value", NbtElement.INT_TYPE);
    }
}
