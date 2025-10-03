package asia.lira.mercury;

import asia.lira.mercury.command.CommandHandler;
import asia.lira.mercury.impl.IInterpreter;
import asia.lira.mercury.impl.InterpreterCompiler;
import asia.lira.mercury.stat.JMXIntegration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

public class Mercury implements ModInitializer {
    public static final int API_VERSION = 1;
    public static MinecraftServer SERVER;
    public static IInterpreter<ServerCommandSource> interpreter;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(new CommandHandler());
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);

        JMXIntegration.initialize();
    }

    private void onServerStarting(MinecraftServer server) {
        SERVER = server;
        try {
            interpreter = new InterpreterCompiler<>(server.getCommandFunctionManager().getDispatcher()).compile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
