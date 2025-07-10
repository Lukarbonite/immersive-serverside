package nl.theepicblock.immersive_cursedness;

import com.mojang.brigadier.Command;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ImmersiveCursedness implements ModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("ImmersiveCursedness");
    public static Thread cursednessThread;
    public static CursednessServer cursednessServer;

    @Override
    public void onInitialize() {
        AutoConfig.register(IC_Config.class, JanksonConfigSerializer::new);

        ServerLifecycleEvents.SERVER_STARTED.register(minecraftServer -> {
            cursednessServer = new CursednessServer(minecraftServer);
            cursednessThread = new Thread(cursednessServer, "Immersive Cursedness Thread");
            cursednessThread.start();
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(minecraftServer -> {
            if (cursednessServer != null) {
                cursednessServer.stop();
            }
            if (cursednessThread != null) {
                try {
                    cursednessThread.join(); // Wait for the thread to terminate
                } catch (InterruptedException e) {
                    LOGGER.error("Failed to cleanly stop the Immersive Cursedness thread.", e);
                }
            }
        });

        // This is the key to thread safety: run all world-mutating logic on the main server thread.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (cursednessServer != null) {
                cursednessServer.tickMainThread();
                cursednessServer.executeQueuedTasks();
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(CommandManager.literal("portal")
                .then(CommandManager.literal("toggle").executes((context) -> {
                    PlayerInterface pi = (PlayerInterface) context.getSource().getPlayer();
                    if (pi == null) return 0;

                    boolean newState = !pi.immersivecursedness$getEnabled();
                    pi.immersivecursedness$setEnabled(newState);

                    String feedback = "Immersive Portals have been " + (newState ? "enabled" : "disabled");
                    context.getSource().sendFeedback(() -> Text.literal(feedback), false);

                    if (!newState && cursednessServer != null) {
                        // If disabling, purge the cache immediately
                        PlayerManager manager = cursednessServer.getManager(context.getSource().getPlayer());
                        if (manager != null) {
                            manager.purgeCache();
                        }
                    }
                    return Command.SINGLE_SUCCESS;
                }))));
    }
}