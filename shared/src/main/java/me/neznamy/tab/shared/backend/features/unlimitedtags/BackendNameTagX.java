package me.neznamy.tab.shared.backend.features.unlimitedtags;

import lombok.Getter;
import lombok.NonNull;
import me.neznamy.tab.shared.TabConstants;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.types.GameModeListener;
import me.neznamy.tab.shared.platform.TabPlayer;
import me.neznamy.tab.shared.backend.BackendTabPlayer;
import me.neznamy.tab.shared.backend.EntityData;
import me.neznamy.tab.shared.features.nametags.unlimited.NameTagX;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class BackendNameTagX extends NameTagX implements GameModeListener {

    /** Vehicle manager reference */
    @Getter private final VehicleRefresher vehicleManager = new VehicleRefresher(this);

    /** Packet Listener reference */
    protected final PacketListener packetListener = new PacketListener(this);

    public BackendNameTagX() {
        super(BackendArmorStandManager::new);
        TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.UNLIMITED_NAME_TAGS_VEHICLE_REFRESHER, vehicleManager);
        TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.UNLIMITED_NAME_TAGS_PACKET_LISTENER, packetListener);
    }

    /**
     * Starts task checking for player visibility to hide armor stands of invisible players.
     */
    private void startVisibilityRefreshTask() {
        TAB.getInstance().getCPUManager().startRepeatingMeasuredTask(500, this, TabConstants.CpuUsageCategory.REFRESHING_NAME_TAG_VISIBILITY, () -> {

            for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
                if (isPlayerDisabled(p)) continue;
                getArmorStandManager(p).updateVisibility(false);
            }
        });
    }

    @Override
    public BackendArmorStandManager getArmorStandManager(TabPlayer player) {
        return (BackendArmorStandManager) armorStandManagerMap.get(player);
    }

    @Override
    public void load() {
        super.load();
        for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
            if (isPlayerDisabled(all)) continue;
            for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
                spawnArmorStands(viewer, all);
            }
        }
        startVisibilityRefreshTask();
    }

    @Override
    public void unload() {
        super.unload();
        unregisterListener();
    }

    @Override
    public void onJoin(TabPlayer connectedPlayer) {
        super.onJoin(connectedPlayer);
        if (isPlayerDisabled(connectedPlayer)) return;
        for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
            spawnArmorStands(viewer, connectedPlayer);
            spawnArmorStands(connectedPlayer, viewer);
        }
    }

    @Override
    public boolean isOnBoat(TabPlayer player) {
        return vehicleManager != null && vehicleManager.isOnBoat(player);
    }

    /**
     * Spawns armor stands of target player to viewer if all requirements are met.
     * These include players being in the same world, distance being less than 48 blocks
     * and target player being visible to viewer.
     *
     * @param   viewer
     *          Player viewing armor stands
     * @param   target
     *          Target player with armor stands
     */
    private void spawnArmorStands(@NonNull TabPlayer viewer, @NonNull TabPlayer target) {
        if (viewer.getVersion().getMinorVersion() < 8) return;
        if (target == viewer || isPlayerDisabled(target)) return;
        if (!areInSameWorld(viewer, target)) return;
        if (getDistance(viewer, target) <= 48 && canSee(viewer, target) && !target.isVanished())
            getArmorStandManager(target).spawn((BackendTabPlayer) viewer);
    }

    @Override
    public void onQuit(TabPlayer disconnectedPlayer) {
        super.onQuit(disconnectedPlayer);
        for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
            getArmorStandManager(all).unregisterPlayer((BackendTabPlayer) disconnectedPlayer);
        }
        armorStandManagerMap.get(disconnectedPlayer).destroy();
        armorStandManagerMap.remove(disconnectedPlayer); // WeakHashMap doesn't clear this due to value referencing the key
    }

    @Override
    public void resumeArmorStands(TabPlayer player) {
        if (isPlayerDisabled(player)) return;
        for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
            spawnArmorStands(viewer, player);
        }
    }

    @Override
    public void setNameTagPreview(TabPlayer player, boolean status) {
        if (status) {
            getArmorStandManager(player).spawn((BackendTabPlayer) player);
        } else {
            getArmorStandManager(player).destroy((BackendTabPlayer) player);
        }
    }

    @Override
    public void pauseArmorStands(TabPlayer player) {
        getArmorStandManager(player).destroy();
    }

    @Override
    public void updateNameTagVisibilityView(TabPlayer player) {
        for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
            getArmorStandManager(all).updateVisibility(true);
        }
    }

    @Override
    public void onWorldChange(TabPlayer p, String from, String to) {
        super.onWorldChange(p, from, to);
        if (isUnlimitedDisabled(p.getServer(), to)) {
            getDisabledUnlimitedPlayers().add(p);
            updateTeamData(p);
        } else if (getDisabledUnlimitedPlayers().remove(p)) {
            updateTeamData(p);
        }
        if (isPreviewingNametag(p)) {
            getArmorStandManager(p).spawn((BackendTabPlayer) p);
        }
        //for some reason this is needed for some users
        for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
            if (viewer.getWorld().equals(from)) {
                getArmorStandManager(p).destroy((BackendTabPlayer) viewer);
            }
        }
    }

    @Override
    public void onGameModeChange(TabPlayer player) {
        for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
            getArmorStandManager(player).updateMetadata((BackendTabPlayer) viewer);
        }
    }

    public int getEntityId(@NonNull TabPlayer player) {
        return getEntityId(player.getPlayer());
    }

    /**
     * Returns flat distance between two players ignoring Y value
     *
     * @param   player1
     *          first player
     * @param   player2
     *          second player
     * @return  flat distance in blocks
     */
    public abstract double getDistance(@NonNull TabPlayer player1, @NonNull TabPlayer player2);

    public abstract boolean areInSameWorld(@NonNull TabPlayer player1, @NonNull TabPlayer player2);

    public abstract boolean canSee(@NonNull TabPlayer viewer, @NonNull TabPlayer target);

    public abstract void unregisterListener();

    public abstract @NotNull List<Integer> getPassengers(@NonNull Object vehicle);

    public abstract @Nullable Object getVehicle(@NonNull TabPlayer player);

    public abstract int getEntityId(@NonNull Object entity);

    public abstract @NotNull String getEntityType(@NonNull Object entity);

    public abstract boolean isSneaking(@NonNull TabPlayer player);

    public abstract boolean isSwimming(@NonNull TabPlayer player);

    public abstract boolean isGliding(@NonNull TabPlayer player);

    public abstract boolean isSleeping(@NonNull TabPlayer player);

    public abstract @NotNull Object getArmorStandType();

    public abstract double getX(@NonNull TabPlayer player);

    public abstract double getY(@NonNull Object entity);

    public abstract double getZ(@NonNull TabPlayer player);

    public abstract EntityData createDataWatcher(@NonNull TabPlayer viewer, byte flags, @NonNull String displayName, boolean nameVisible);
}
