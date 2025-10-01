package asia.lira.mcfunctionplus;

import asia.lira.mcfunctionplus.command.CommandHandler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

public class McFunctionPlus implements ModInitializer {
    public static final int API_VERSION = 1;
    public static MinecraftServer SERVER;

    @Override
    public void onInitialize() {

        CommandRegistrationCallback.EVENT.register(new CommandHandler());
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
    }

    private void onServerStarting(MinecraftServer server) {
        SERVER = server;
    }
}
