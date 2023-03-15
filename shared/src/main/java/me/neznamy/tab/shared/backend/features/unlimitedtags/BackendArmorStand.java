package me.neznamy.tab.shared.backend.features.unlimitedtags;

import lombok.Getter;
import me.neznamy.tab.api.*;
import me.neznamy.tab.api.chat.EnumChatFormat;
import me.neznamy.tab.api.chat.IChatBaseComponent;
import me.neznamy.tab.api.protocol.TabPacket;
import me.neznamy.tab.shared.backend.protocol.PacketPlayOutEntityMetadata;
import me.neznamy.tab.shared.backend.protocol.PacketPlayOutEntityTeleport;
import me.neznamy.tab.shared.backend.protocol.PacketPlayOutSpawnEntityLiving;

import java.util.UUID;

public class BackendArmorStand implements ArmorStand {

    /** Entity id counter to pick unique entity ID for each armor stand */
    private static int idCounter = 2000000000;

    /** NameTag feature */
    protected final BackendNameTagX manager;

    /** Armor stand manager which this armor stand belongs to */
    protected final BackendArmorStandManager asm;

    /** Owner of the armor stand */
    protected final TabPlayer owner;

    /** Offset in blocks, 0 for original height */
    @Getter protected double offset;

    /** If offset is static, or dynamic based on other armor stands */
    @Getter private final boolean staticOffset;

    /** Entity ID of this armor stand */
    @Getter protected final int entityId = idCounter++;

    /** Unique ID of this armor stand */
    protected final UUID uuid = UUID.randomUUID();

    /** Armor stand visibility to use in metadata */
    protected boolean visible;

    /** Visibility flag to compare against for previous value, including invisibility potion compensation */
    private boolean visibleWPotion;

    /** Refresh property dedicated to this armor stand */
    @Getter protected final Property property;

    /**
     * Constructs new instance with given parameters.
     *
     * @param   asm
     *          Armor stand manager which this armor stand belongs to
     * @param   owner
     *          Owner of the armor stand
     * @param   propertyName
     *          Name of refresh property to use
     * @param   yOffset
     *          Offset in blocks
     * @param   staticOffset
     *          {@code true} if offset is static, {@code false} if not
     */
    public BackendArmorStand(BackendNameTagX feature, BackendArmorStandManager asm, TabPlayer owner, String propertyName, double yOffset, boolean staticOffset) {
        this.manager = feature;
        this.asm = asm;
        this.owner = owner;
        this.staticOffset = staticOffset;
        this.offset = yOffset;
        this.property = owner.getProperty(propertyName);
        visible = calculateVisibility();
        visibleWPotion = visible && !owner.hasInvisibilityPotion();
    }

    @Override
    public void setOffset(double offset) {
        if (this.offset == offset) return;
        this.offset = offset;
        for (TabPlayer all : asm.getNearbyPlayers()) {
            sendTeleportPacket(all);
        }
    }

    @Override
    public void refresh() {
        visible = calculateVisibility();
        visibleWPotion = visible && !owner.hasInvisibilityPotion();
        updateMetadata();
    }

    @Override
    public void updateVisibility(boolean force) {
        boolean visibility = calculateVisibility() && !owner.hasInvisibilityPotion(); //trigger packet send but don't save, so it can be reverted if viewer is spectator
        if (visibleWPotion != visibility || force) {
            refresh();
        }
    }

    @Override
    public void teleport() {
        for (TabPlayer all : asm.getNearbyPlayers()) {
            sendTeleportPacket(all);
        }
    }

    @Override
    public void teleport(TabPlayer viewer) {
        if (!asm.isNearby(viewer) && viewer != owner) {
            asm.spawn(viewer);
        } else {
            sendTeleportPacket(viewer);
        }
    }

    @Override
    public void spawn(TabPlayer viewer) {
        for (TabPacket packet : getSpawnPackets(viewer)) {
            viewer.sendCustomPacket(packet);
        }
    }

    /**
     * Returns general visibility rule for everyone with limited info
     *
     * @return  {@code true} if armor stand should be visible, {@code false} if not
     */
    public boolean calculateVisibility() {
        if (manager.isArmorStandsAlwaysVisible()) return true;
        if (owner.isDisguised() || manager.isOnBoat(owner)) return false;
        return owner.getGamemode() != 3 && !manager.hasHiddenNametag(owner) && property.get().length() > 0;
    }

    /**
     * Returns {@code true} if display name is in fact empty,
     * for example only containing color codes, {@code false} if not.
     *
     * @param   displayName
     *          string to check
     * @return  {@code true} if it's empty, {@code false} if not
     */
    protected boolean isNameVisiblyEmpty(String displayName) {
        if (displayName.length() == 0) return true;
        if (!displayName.startsWith(EnumChatFormat.COLOR_STRING) && !displayName.startsWith("&") && !displayName.startsWith("#")) return false;
        String text = IChatBaseComponent.fromColoredText(displayName).toRawText();
        if (text.contains(" ")) text = text.replace(" ", "");
        return text.length() == 0;
    }

    /**
     * Gets Y to add based on player's pose, armor stand offset and viewer
     *
     * @param   sleeping
     *          Whether player is sleeping or not
     * @param   sneaking
     *          Whether player is sneaking or not
     * @param   viewer
     *          Viewer of the armor stand
     * @return  Y to add to player's location
     */
    public double getYAdd(boolean sleeping, boolean sneaking, TabPlayer viewer) {
        double y = getOffset();
        if (!sleeping) {
            if (sneaking) {
                if (viewer.getVersion().getMinorVersion() >= 15) {
                    y += 1.37;
                } else if (viewer.getVersion().getMinorVersion() >= 9) {
                    y += 1.52;
                } else {
                    y += 1.7;
                }
            } else {
                y += viewer.getVersion().getMinorVersion() >= 9 ? 1.8 : 1.84; // Normal
            }
        } else {
            y += viewer.getVersion().getMinorVersion() >= 9 ? 0.2 : 0.26; // Sleeping
        }
        if (viewer.getVersion().getMinorVersion() == 8 && !manager.isMarkerFor18x()) y -= 2;
        return y;
    }

    /**
     * Updates armor stand's metadata for everyone
     */
    public void updateMetadata() {
        for (TabPlayer viewer : asm.getNearbyPlayers()) {
            viewer.sendCustomPacket(new PacketPlayOutEntityMetadata(entityId, createDataWatcher(property.getFormat(viewer), viewer)));
        }
    }

    public boolean shouldBeInvisibleFor(TabPlayer viewer, String displayName) {
        return isNameVisiblyEmpty(displayName) || !manager.canSee(viewer, owner) ||
                manager.hasHiddenNametag(owner, viewer) || manager.hasHiddenNameTagVisibilityView(viewer) ||
                (owner.hasInvisibilityPotion() && viewer.getGamemode() != 3);
    }

    public TabPacket[] getSpawnPackets(TabPlayer viewer) {
        visible = calculateVisibility();
        Object dataWatcher = createDataWatcher(property.getFormat(viewer), viewer);
        if (TabAPI.getInstance().getServerVersion().getMinorVersion() >= 15) {
            return new TabPacket[] {
                    new PacketPlayOutSpawnEntityLiving(entityId, uuid, manager.getArmorStandType(),
                            manager.getX(owner), getYLocation(viewer), manager.getZ(owner), 0, 0, null),
                    new PacketPlayOutEntityMetadata(entityId, dataWatcher)
            };
        } else {
            return new TabPacket[] {
                    new PacketPlayOutSpawnEntityLiving(entityId, uuid, manager.getArmorStandType(),
                            manager.getX(owner), getYLocation(viewer), manager.getZ(owner), 0, 0, dataWatcher),
            };
        }
    }

    public void sendTeleportPacket(TabPlayer viewer) {
        viewer.sendCustomPacket(new PacketPlayOutEntityTeleport(entityId, manager.getX(owner), getYLocation(viewer),
                        manager.getZ(owner), 0, 0));
    }

    /**
     * Creates data watcher with specified display name for viewer
     *
     * @param   displayName
     *          armor stand name
     * @param   viewer
     *          player to apply checks against
     * @return  DataWatcher for viewer
     */
    public Object createDataWatcher(String displayName, TabPlayer viewer) {
        byte flags = (byte) (asm.isSneaking() ? 34 : 32);
        boolean nameVisible = !shouldBeInvisibleFor(viewer, displayName) && visible;
        boolean marker = viewer.getVersion().getMinorVersion() > 8 || manager.isMarkerFor18x();
        return manager.createDataWatcher(viewer, flags, displayName, nameVisible, marker);
    }

    /**
     * Returns Y location where armor stand should be at time of calling.
     * This takes into account everything that affects height, including
     * viewer's game version.
     *
     * @param   viewer
     *          Player looking at the armor stand
     * @return  Location where armor stand should be for specified viewer
     */
    public double getYLocation(TabPlayer viewer) {
        double y = manager.getY(owner.getPlayer());
        //1.14+ server sided bug
        Object vehicle = manager.getVehicle(owner);
        if (vehicle != null) {
            String type = manager.getEntityType(vehicle);
            double vehicleY = manager.getY(vehicle);
            if (type.contains("horse")) { //covering all 3 horse types
                y = vehicleY + 0.85;
            }
            if (type.equals("donkey")) { //1.11+
                y = vehicleY + 0.525;
            }
            if (type.equals("pig")) {
                y = vehicleY + 0.325;
            }
            if (type.equals("strider")) { //1.16+
                y = vehicleY + 1.15;
            }
        } else {
            //1.13+ swimming or 1.9+ flying with elytra
            if (manager.isSwimming(owner) || manager.isGliding(owner)) {
                y = manager.getY(owner.getPlayer())-1.22;
            }
        }
        y += getYAdd(manager.isSleeping(owner), asm.isSneaking(), viewer);
        return y;
    }
}
