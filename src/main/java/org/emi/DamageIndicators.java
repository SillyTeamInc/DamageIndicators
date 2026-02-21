package org.emi;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import org.bukkit.util.Vector;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DamageIndicators extends JavaPlugin implements Listener {
    private static final long DISPLAY_DURATION_TICKS = 20;
    private static final long DAMAGE_RESET_TIME_MS = 2000;
    private static final double DISPLAY_OFFSET_Y = 1.0;
    private static final double DISPLAY_OFFSET_FORWARD = 0.6;
    private static final double PROJECTILE_OFFSET_BACKWARD = 1.5;

    public void info(String message) {
        getLogger().info(message);
    }

    public void warn(String message) {
        getLogger().warning(message);
    }

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        info("DamageIndicators is enabled!");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        HandlerList.unregisterAll((Plugin) this);
        HandlerList.unregisterAll((Listener) this);

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof TextDisplay display && display.hasMetadata("damageIndicator")) {
                    try {
                        display.remove();
                    } catch (Exception e) {
                        warn("Failed to remove damage indicator during shutdown: " + e.getMessage());
                    }
                }
            }
        }

        info("DamageIndicators by " + getDescription().getAuthors() + " is now disabled!");
    }

    private final java.util.Map<UUID, BukkitTask> displayKillTasks = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, BukkitTask> displayRepeatingTasks = new ConcurrentHashMap<>();

    private void cancelTaskForDisplay(UUID displayUuid, boolean repeatingOnly) {
        if (!repeatingOnly) {
            BukkitTask task = displayKillTasks.remove(displayUuid);
            if (task != null) {
                task.cancel();
            }
        }
        BukkitTask repeatingTask = displayRepeatingTasks.remove(displayUuid);
        if (repeatingTask != null) {
            repeatingTask.cancel();
        }
    }


    public TextDisplay getOrSpawnDamageIndicator(LivingEntity defender, Player attacker, Location damageLocation) {
        if (defender.hasMetadata("lastDisplay_" + attacker.getUniqueId())) {
            UUID displayUuid = defender.getMetadata("lastDisplay_" + attacker.getUniqueId()).stream().findFirst().map(m -> (UUID) m.value()).orElse(null);
            if (displayUuid != null) {
                TextDisplay existingDisplay = defender.getWorld().getEntity(displayUuid) instanceof TextDisplay display ? display : null;
                if (existingDisplay != null) {
                    cancelTaskForDisplay(existingDisplay.getUniqueId(), false);
                    existingDisplay.teleportAsync(damageLocation);
                    existingDisplay.setMetadata("creationTime", new FixedMetadataValue(this, System.currentTimeMillis()));
                    return existingDisplay;
                }
            }
        }

        TextDisplay display = (TextDisplay) defender.getWorld().spawnEntity(damageLocation, EntityType.TEXT_DISPLAY, CreatureSpawnEvent.SpawnReason.CUSTOM, entity -> {
            if (entity instanceof TextDisplay textDisplay) {
                textDisplay.setMetadata("damageIndicator", new FixedMetadataValue(this, true));
            }
            entity.setVisibleByDefault(false);
        });
        display.setMetadata("creationTime", new FixedMetadataValue(this, System.currentTimeMillis()));
        return display;
    }

    private void cleanupDisplay(TextDisplay display, LivingEntity defender, UUID attackerUuid) {
        try {
            if (display.isValid())
                display.remove();
        } catch (Exception e) {
            warn("Failed to remove damage indicator: " + e.getMessage());
        }

        if (defender.isValid()) {
            defender.removeMetadata("lastDamageDealt_" + attackerUuid, this);
            defender.removeMetadata("lastDamageTime_" + attackerUuid, this);
            defender.removeMetadata("lastDisplay_" + attackerUuid, this);
        }

        cancelTaskForDisplay(display.getUniqueId(), false);
    }

    public Location calculateDamageLocation(LivingEntity defender, Player attacker, boolean isProjectile) {
        Location baseLocation = defender.getLocation().clone().add(0, DISPLAY_OFFSET_Y, 0);
        Vector direction = calculateDamageDirection(defender, attacker);
        if (!isProjectile) {
            baseLocation.add(direction.clone().multiply(DISPLAY_OFFSET_FORWARD));
        } else {
            baseLocation.subtract(direction.clone().multiply(PROJECTILE_OFFSET_BACKWARD));
        }
        return baseLocation;
    }

    public Vector calculateDamageDirection(LivingEntity defender, Player attacker) {
        Vector direction = attacker.getLocation().clone().toVector().subtract(defender.getLocation().toVector());
        if (direction.lengthSquared() > 0) {
            direction.normalize();
        } else {
            direction = new Vector(0, 0, 0);
        }
        return direction;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if ((event.getDamager() instanceof Player || event.getDamager() instanceof Projectile) && event.getEntity() instanceof LivingEntity defender) {
            if (event.getDamager() instanceof Projectile && !(((Projectile) event.getDamager()).getShooter() instanceof Player)) {
                return;
            }

            Player attacker = event.getDamager() instanceof Player ? // if damager is player
                    (Player) event.getDamager() : // get player directly
                    (Player) ((Projectile) event.getDamager()).getShooter(); // if damager is projectile, get shooter and cast to player
            if (attacker == null) return;
            boolean isProjectileDamage = event.getDamager() instanceof Projectile;
            double damageDealt = event.getFinalDamage();

            if (damageDealt <= 0) {
                return;
            }

            Location damageLocation = calculateDamageLocation(defender, attacker, isProjectileDamage);
            Vector direction = calculateDamageDirection(defender, attacker);

            if (defender.hasMetadata("lastDamageDealt_" + attacker.getUniqueId())) {
                long lastDamageTime = defender.getMetadata("lastDamageTime_" + attacker.getUniqueId()).stream().findFirst().map(MetadataValue::asLong).orElse(0L);
                if (System.currentTimeMillis() - lastDamageTime < DAMAGE_RESET_TIME_MS) {
                    damageDealt += defender.getMetadata("lastDamageDealt_" + attacker.getUniqueId()).stream().findFirst().map(MetadataValue::asDouble).orElse(0.0);

                }
            }

            TextDisplay display = getOrSpawnDamageIndicator(defender, attacker, damageLocation);

            defender.setMetadata("lastDamageDealt_" + attacker.getUniqueId(), new FixedMetadataValue(this, damageDealt));
            defender.setMetadata("lastDamageTime_" + attacker.getUniqueId(), new FixedMetadataValue(this, System.currentTimeMillis()));
            defender.setMetadata("lastDisplay_" + attacker.getUniqueId(), new FixedMetadataValue(this, display.getUniqueId()));

            if (isProjectileDamage) {
                display.setMetadata("projectileDamage", new FixedMetadataValue(this, true));
            } else {
                display.removeMetadata("projectileDamage", this);
            }
            display.setMetadata("defenderUuid", new FixedMetadataValue(this, defender.getUniqueId()));
            display.setMetadata("attackerUuid", new FixedMetadataValue(this, attacker.getUniqueId()));
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setDefaultBackground(false);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setPersistent(false);
            display.setMetadata("damageIndicator", new FixedMetadataValue(this, true));
            display.setVisibleByDefault(false);
            display.setTextOpacity((byte) 255);
            display.setTeleportDuration(1);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            attacker.showEntity(this, display);

            display.setRotation((float) Math.toDegrees(Math.atan2(direction.getZ(), direction.getX())) - 90, 0);
            display.text(Component.text("-" + String.format("%.1f", damageDealt) + "❤", event.isCritical() ? NamedTextColor.DARK_RED : NamedTextColor.RED));

            displayKillTasks.put(display.getUniqueId(), Bukkit.getScheduler().runTaskLater(this, () -> cleanupDisplay(display, defender, attacker.getUniqueId()), DAMAGE_RESET_TIME_MS / 50));
            // this is just for fading, the kill is handled by the above task.
            displayRepeatingTasks.put(display.getUniqueId(), Bukkit.getScheduler().runTaskTimer(this, () -> {
                long creationTime = display.getMetadata("creationTime").stream().findFirst().map(MetadataValue::asLong).orElse(0L);
                long elapsedTicks = (System.currentTimeMillis() - creationTime) / 50;
                if (elapsedTicks >= DISPLAY_DURATION_TICKS) {
                    int opacity = (int) Math.max(0, 255 - ((elapsedTicks - DISPLAY_DURATION_TICKS) * 255 / 8));
                    display.setTextOpacity((byte) opacity);
                    if (opacity == 0) {
                        cancelTaskForDisplay(display.getUniqueId(), true);
                    }
                }
            }, 0L, 1L));
        }
    }

    @EventHandler
    public void onServerTick(ServerTickStartEvent event) {
        // TODO: store world along side display uuid.
        List<UUID> displayUuids = new ArrayList<>(displayRepeatingTasks.keySet());
        for (World world : Bukkit.getWorlds()) {
            for (UUID textUuid : displayUuids) {
                Entity entity = world.getEntity(textUuid);
                if (entity instanceof TextDisplay display && display.hasMetadata("damageIndicator")) {
                    UUID defenderUuid = display.getMetadata("defenderUuid").stream().findFirst().map(m -> (UUID) m.value()).orElse(null);
                    UUID attackerUuid = display.getMetadata("attackerUuid").stream().findFirst().map(m -> (UUID) m.value()).orElse(null);
                    if (defenderUuid == null || attackerUuid == null) {
                        continue;
                    }
                    Entity defender = world.getEntity(defenderUuid);
                    Entity attacker = world.getEntity(attackerUuid);
                    if (defender != null && attacker != null && defender.isValid() && attacker.isValid() && !display.hasMetadata("projectileDamage")) {
                        Location damageLocation = calculateDamageLocation((LivingEntity) defender, (Player) attacker, false);
                        display.setTeleportDuration(1);
                        display.teleportAsync(damageLocation);
                    }
                }
            }
        }
    }
}
