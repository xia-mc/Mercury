package asia.lira.mcfunctionplus.command;

import asia.lira.mcfunctionplus.McFunctionPlus;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.scoreboard.*;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import static net.minecraft.server.command.CommandManager.literal;


public class CommandHandler implements CommandRegistrationCallback {
    public static final ScoreHolder RAX = ScoreHolder.fromName("rax");
    public static final ScoreHolder R0 = ScoreHolder.fromName("r0");
    public static final Identifier STORAGE = Identifier.of("std", "vm");

    private static NbtList getHeap() {
        DataCommandStorage storage = McFunctionPlus.SERVER.getDataCommandStorage();
        return storage.get(STORAGE).getList("heap", NbtElement.INT_TYPE);
    }

    @Override
    public void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher,
                         CommandRegistryAccess access, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("mcfp")
                .then(literal("syscall").executes(context -> {
                    ServerScoreboard scoreboard = McFunctionPlus.SERVER.getScoreboard();
                    ScoreboardObjective vmRegs = scoreboard.getNullableObjective("vm_regs");
                    ScoreboardScore rax = (ScoreboardScore) scoreboard.getScore(RAX, vmRegs);
                    if (rax == null) {
                        return -1;
                    }
                    int id = rax.getScore();

                    switch (id) {
                        case 0 -> {  // int getAPIVersion()
                            rax.setScore(McFunctionPlus.API_VERSION);
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
                            long value = System.nanoTime();
                            int low = (int) (value & 0xFFFFFFFFL);
                            int high = (int) ((value >>> 32) & 0xFFFFFFFFL);
                            heap.setElement(addr, NbtInt.of(low));
                            heap.setElement(addr + 1, NbtInt.of(high));
                            return 1;
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
                            McFunctionPlus.SERVER.getPlayerManager().broadcast(Text.literal(
                                    builder.toString()
                            ), false);
                            return 1;
                        }
                        default -> {
                            return -1;
                        }
                    }
                }))
        );
    }
}
