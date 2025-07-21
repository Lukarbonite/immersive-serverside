package nl.theepicblock.immersive_cursedness;

import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class CursednessServer implements Runnable {
    private final MinecraftServer server;
    private final IC_Config icConfig;
    private volatile boolean isServerActive = true;
    private long nextTick;
    private int tickCount;

    private final Map<ServerPlayerEntity, PlayerManager> playerManagers = new ConcurrentHashMap<>();
    private final Queue<Runnable> taskQueue = new ConcurrentLinkedQueue<>();

    public CursednessServer(MinecraftServer server) {
        this.server = server;
        this.icConfig = AutoConfig.getConfigHolder(IC_Config.class).getConfig();
    }

    @Override
    public void run() {
        ImmersiveCursedness.LOGGER.info("Starting Immersive Cursedness thread");
        while (isServerActive) {
            long currentTime = System.currentTimeMillis();
            if (currentTime < nextTick) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(Math.max(1, nextTick - currentTime));
                } catch (InterruptedException ignored) {}
                continue;
            }

            try {
                tickAsync();
            } catch (Exception e) {
                ImmersiveCursedness.LOGGER.warn("Exception occurred whilst ticking the Immersive Cursedness thread. This is probably not bad unless it's spamming your console", e);
            }

            int portalHz = server.getOverworld().getGameRules().getInt(ImmersiveCursedness.PORTAL_HZ);
            if (portalHz <= 0) {
                portalHz = 1;
            }
            long delay = 1000 / portalHz;
            nextTick = System.currentTimeMillis() + delay;
        }
        ImmersiveCursedness.LOGGER.info("Immersive Cursedness thread stopped.");
    }

    public void stop() {
        isServerActive = false;
    }

    private void tickAsync() {
        tickCount++;
        // Tick player managers in parallel on the helper thread
        playerManagers.forEach((player, manager) -> {
            try {
                manager.tickAsync(tickCount);
            } catch (Exception e) {
                ImmersiveCursedness.LOGGER.error("Failed to tick player manager for " + player.getName().getString(), e);
            }
        });
    }

    public void tickMainThread() {
        // Sync player list and prepare data for the async thread, all on the main server thread
        syncPlayerManagers();
        playerManagers.forEach((player, manager) -> {
            try {
                manager.tickMainThread(tickCount);
            } catch (Exception e) {
                ImmersiveCursedness.LOGGER.error("Failed to prepare tick for player manager for " + player.getName().getString(), e);
            }
        });
    }

    private void syncPlayerManagers() {
        List<ServerPlayerEntity> playerList = server.getPlayerManager().getPlayerList();

        // Remove managers for players who have logged off
        playerManagers.entrySet().removeIf(entry -> {
            ServerPlayerEntity player = entry.getKey();
            if (!playerList.contains(player)) {
                entry.getValue().onRemoved();
                return true;
            }
            return false;
        });

        // Add managers for new players
        for (ServerPlayerEntity player : playerList) {
            playerManagers.computeIfAbsent(player, p -> new PlayerManager(p, icConfig, this));
        }
    }

    /**
     * Executes all pending tasks on the main server thread.
     */
    public void executeQueuedTasks() {
        Runnable task;
        while ((task = taskQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                ImmersiveCursedness.LOGGER.error("Error executing a queued task.", e);
            }
        }
    }

    /**
     * Queues a task to be run on the main server thread.
     */
    public void addTask(Runnable task) {
        taskQueue.add(task);
    }

    @Nullable
    public PlayerManager getManager(ServerPlayerEntity player) {
        return playerManagers.get(player);
    }
}