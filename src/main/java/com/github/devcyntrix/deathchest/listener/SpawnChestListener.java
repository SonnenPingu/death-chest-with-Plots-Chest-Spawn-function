package com.github.devcyntrix.deathchest.listener;

import com.github.devcyntrix.deathchest.DeathChestModel;
import com.github.devcyntrix.deathchest.DeathChestPlugin;
import com.github.devcyntrix.deathchest.api.event.DeathChestSpawnEvent;
import com.github.devcyntrix.deathchest.api.event.PreDeathChestSpawnEvent;
import com.github.devcyntrix.deathchest.config.ChangeDeathMessageOptions;
import com.github.devcyntrix.deathchest.config.DeathChestConfig;
import com.github.devcyntrix.deathchest.config.NoExpirationPermission;
import com.github.devcyntrix.deathchest.config.ThiefProtectionOptions;
import com.github.devcyntrix.deathchest.controller.LastSafeLocationController;
import me.clip.placeholderapi.PlaceholderAPI;
import org.apache.commons.text.StringSubstitutor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public class SpawnChestListener implements Listener {

    private final DeathChestPlugin plugin;

    public SpawnChestListener(DeathChestPlugin plugin) {
        this.plugin = plugin;
    }

    private final Set<Player> set = Collections.newSetFromMap(new WeakHashMap<>());

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        set.remove(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        set.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        plugin.debug(0, "Spawning death chest...");

        Player player = event.getEntity();
        plugin.debug(1, "Checking multiple deaths at once...");
        if (set.contains(player)) {
            event.getDrops().clear();
            return;
        }
        set.add(player);

        World world = player.getWorld();
        DeathChestConfig config = plugin.getDeathChestConfig();

        plugin.debug(1, "Checking keep inventory...");
        if (event.getKeepInventory())
            return;

        plugin.debug(1, "Removing air and blacklisted items...");
        List<ItemStack> drops = event.getDrops();
        boolean removed = drops
                .removeIf(itemStack -> itemStack == null || itemStack.getType().isAir() || itemStack.getAmount() <= 0 || !plugin.getBlacklist().isValidItem(itemStack));
        if (removed) {
            plugin.debug(2, "Inventory has been updated.");
        }

        if (drops.isEmpty()) {
            plugin.debug(1, "Clearing drops because the inventory was empty after removing blacklisted items");
            return;
        }

        ItemStack[] items = event.getDrops().toArray(ItemStack[]::new);

        plugin.debug(1, "Checking world filter...");
        if (!config.worldFilterConfig().test(player.getWorld()))
            return;

        plugin.debug(1, "Getting expiration time...");
        Duration expiration = config.chestOptions().expiration();
        if (expiration == null)
            expiration = Duration.ofSeconds(-1);

        plugin.debug(1, "Checking no expiration permission...");
        NoExpirationPermission permission = config.chestOptions().noExpirationPermission();
        boolean expires = !permission.enabled() || !player.hasPermission(permission.permission());
        long createdAt = System.currentTimeMillis();
        long expireAt = !expiration.isNegative() && !expiration.isZero() && expires ? createdAt + expiration.toMillis() : -1;
        if (expireAt <= 0) {
            plugin.debug(1, "The chest will never expire");
        } else {
            plugin.debug(1, "The chest will expire at " + new Date(expireAt));
        }

        LastSafeLocationController controller = plugin.getLastSafeLocationController();
        controller.updatePosition(player);
        Location lastSafePos = controller.getPosition(player);

        if (lastSafePos == null) {
            lastSafePos = player.getLocation().getBlock().getLocation().clone();
        } else if (player.getLocation().distanceSquared(lastSafePos) >= 20 * 20) {
            lastSafePos = player.getLocation().getBlock().getLocation().clone();
            int it = 0;
            while (lastSafePos.getBlock().isEmpty() && lastSafePos.getBlockY() >= world.getMinHeight() && it <= 20) {
                lastSafePos = lastSafePos.subtract(0, 1, 0);
                it++;
            }
            if (!lastSafePos.getBlock().isEmpty())
                lastSafePos.add(0, 1, 0);
        }

        lastSafePos.setY(Math.min(Math.max(lastSafePos.getBlockY(), world.getMinHeight()), world.getMaxHeight() - 1));

        plugin.debug(1, "Checking protection service...");
        boolean hasOverride = player.hasPermission("deathchest.override.plots");
        if (!hasOverride && !plugin.getProtectionService().canBuild(player, lastSafePos, Material.CHEST)) {
            plugin.debug(1, "Protection service denied build permission and no override permission present");
            return;
        } else if (hasOverride) {
            plugin.debug(1, "Override permission present, skipping protection check");
        }

        ChangeDeathMessageOptions changeDeathMessageOptions = config.changeDeathMessageOptions();
        if (changeDeathMessageOptions.enabled()) {
            plugin.debug(1, "Changing death message...");
            if (changeDeathMessageOptions.message() != null && lastSafePos.getWorld() != null) {
                StringSubstitutor substitution = new StringSubstitutor(Map.of("x", lastSafePos.getBlockX(), "y", lastSafePos.getBlockY(), "z", lastSafePos.getBlockZ(), "world", lastSafePos.getWorld().getName(), "player_name", player.getName(), "player_display_name", player.getDisplayName()));

                event.setDeathMessage(Arrays.stream(changeDeathMessageOptions.message()).map(substitution::replace).map(s -> {
                    if (DeathChestPlugin.isPlaceholderAPIEnabled()) {
                        return PlaceholderAPI.setPlaceholders(player, s);
                    }
                    return s;
                }).collect(Collectors.joining("\n")));
            } else {
                event.setDeathMessage(null);
            }
        }

        try {
            ThiefProtectionOptions thiefProtectionOptions = config.chestOptions().thiefProtectionOptions();
            boolean protectedChest = thiefProtectionOptions.enabled() && player.hasPermission(thiefProtectionOptions.permission()) && config.worldChestProtectionFilter().test(lastSafePos.getWorld());
            plugin.debug(1, "Protected chest: %s".formatted(protectedChest));

            PreDeathChestSpawnEvent preSpawn = new PreDeathChestSpawnEvent(player, lastSafePos, protectedChest, createdAt, expireAt, items);
            Bukkit.getPluginManager().callEvent(preSpawn);

            if (preSpawn.isCancelled())
                return;

            lastSafePos = preSpawn.getLocation();
            protectedChest = preSpawn.isProtectedChest();
            createdAt = preSpawn.getCreatedAt();
            expireAt = preSpawn.getExpireAt();
            items = preSpawn.getItems();

            DeathChestModel deathChest = plugin.createDeathChest(lastSafePos, createdAt, expireAt, player, protectedChest, items);

            DeathChestSpawnEvent deathChestSpawnEvent = new DeathChestSpawnEvent(player, deathChest);
            Bukkit.getPluginManager().callEvent(deathChestSpawnEvent);

            plugin.debug(1, "Clearing drops...");
            event.getDrops().clear();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
