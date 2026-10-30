package org.eu.lunworld.legacymapfix;

import de.tr7zw.changeme.nbtapi.NBT;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class LegacyMapFixPlugin extends JavaPlugin implements Listener, TabCompleter {

    private static final String HUSKSYNC_SYNC_EVENT = "net.william278.husksync.event.BukkitSyncCompleteEvent";

    private MapIndex index;
    private LegacyMapConverter converter;
    private boolean huskSyncHooked;
    private HuskSyncBridge huskSyncBridge;

    HuskSyncBridge huskSyncBridge() {
        return huskSyncBridge;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!NBT.preloadApi()) {
            getLogger().severe("NBT-API does not support this server version; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        index = new MapIndex(getDataFolder(), getLogger());
        index.loadCreated();
        index.scanExisting(Bukkit.getWorlds().get(0).getWorldFolder().toPath().resolve("data"));
        converter = new LegacyMapConverter(this, index);

        getServer().getPluginManager().registerEvents(this, this);
        huskSyncHooked = hookHuskSync();
        huskSyncBridge = HuskSyncBridge.tryCreate(getServer().getPluginManager().getPlugin("HuskSync"), getLogger());
        if (huskSyncBridge != null) {
            getLogger().info("HuskSync map bridge ready (this server: " + huskSyncBridge.serverName() + ").");
        }

        var cmd = getCommand("legacymapfix");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }

        // Chunks loaded before this plugin enabled (spawn chunks, startup loads) never fire
        // EntitiesLoadEvent for us, so sweep the frames that are already loaded.
        Bukkit.getScheduler().runTask(this, () -> {
            if (!getConfig().getBoolean("auto-convert.on-entities-load", false)) return;
            int frames = 0, converted = 0;
            for (World world : Bukkit.getWorlds()) {
                for (ItemFrame frame : world.getEntitiesByClass(ItemFrame.class)) {
                    frames++;
                    if (convertFrame(frame)) converted++;
                }
            }
            getLogger().info("Startup sweep: converted " + converted + " legacy maps in " + frames + " loaded item frames.");
        });
    }

    // ---------------------------------------------------------------- automatic triggers

    /** Convert after HuskSync has applied the player's data, so HuskSync can't overwrite our changes. */
    @SuppressWarnings("unchecked")
    private boolean hookHuskSync() {
        Plugin husk = getServer().getPluginManager().getPlugin("HuskSync");
        if (husk == null || !husk.isEnabled()) {
            getLogger().info("HuskSync not found; converting player inventories on join instead.");
            return false;
        }
        try {
            Class<? extends Event> eventClass = (Class<? extends Event>)
                    Class.forName(HUSKSYNC_SYNC_EVENT, true, husk.getClass().getClassLoader());
            Listener holder = new Listener() {};
            getServer().getPluginManager().registerEvent(eventClass, holder, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (!eventClass.isInstance(event)) return;
                        Player player = playerFromHuskEvent(event);
                        if (player != null && getConfig().getBoolean("auto-convert.on-sync-complete", false)) {
                            Bukkit.getScheduler().runTaskLater(this, () -> convertPlayer(player, "sync"), 1L);
                        }
                    }, this, true);
            getLogger().info("Hooked into HuskSync sync-complete event.");
            return true;
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Could not hook HuskSync; falling back to join event.", t);
            return false;
        }
    }

    private Player playerFromHuskEvent(Object event) {
        try {
            Object user = event.getClass().getMethod("getUser").invoke(event);
            try {
                Method getPlayer = user.getClass().getMethod("getPlayer");
                return (Player) getPlayer.invoke(user);
            } catch (NoSuchMethodException e) {
                UUID uuid = (UUID) user.getClass().getMethod("getUuid").invoke(user);
                return Bukkit.getPlayer(uuid);
            }
        } catch (ReflectiveOperationException e) {
            getLogger().log(Level.WARNING, "Could not read player from HuskSync event", e);
            return null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (huskSyncHooked || !getConfig().getBoolean("auto-convert.on-sync-complete", false)) return;
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> convertPlayer(player, "join"), 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!getConfig().getBoolean("auto-convert.on-inventory-open", false)) return;
        Inventory top = event.getView().getTopInventory();
        convertInventory(top, "open " + event.getView().getType() + " by " + event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!getConfig().getBoolean("auto-convert.on-entities-load", false)) return;
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame frame) convertFrame(frame);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!getConfig().getBoolean("auto-convert.on-pickup", false)) return;
        ItemStack stack = event.getItem().getItemStack();
        if (converter.convertItem(stack, "pickup by " + event.getEntity().getName()) > 0) {
            event.getItem().setItemStack(stack);
        }
    }

    // ---------------------------------------------------------------- conversion helpers

    int convertPlayer(Player player, String reason) {
        if (!player.isOnline()) return 0;
        int n = convertInventory(player.getInventory(), reason + " inventory of " + player.getName());
        n += convertInventory(player.getEnderChest(), reason + " ender chest of " + player.getName());
        if (n > 0) player.updateInventory();
        return n;
    }

    private int convertInventory(Inventory inventory, String context) {
        ItemStack[] contents = inventory.getContents();
        int n = converter.convertAll(contents, context);
        if (n > 0) inventory.setContents(contents);
        return n;
    }

    private boolean convertFrame(ItemFrame frame) {
        ItemStack item = frame.getItem();
        String ctx = "item frame at " + frame.getWorld().getName() + " "
                + frame.getLocation().getBlockX() + "," + frame.getLocation().getBlockY() + ","
                + frame.getLocation().getBlockZ();
        if (converter.convertItem(item, ctx) > 0) {
            frame.setItem(item, false);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        switch (sub) {
            case "inspect" -> inspect(sender);
            case "fix" -> {
                if (!(sender instanceof Player p)) return msg(sender, "&c只能由玩家執行。");
                ItemStack hand = p.getInventory().getItemInMainHand();
                int n = converter.convertItem(hand, "manual fix by " + p.getName());
                if (n > 0) p.getInventory().setItemInMainHand(hand);
                msg(sender, n > 0 ? "&a已轉換 " + n + " 張地圖。" : "&e手上的物品不是舊格式地圖。");
            }
            case "scan" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1])
                        : sender instanceof Player p ? p : null;
                if (target == null) return msg(sender, "&c找不到玩家。");
                int n = convertPlayer(target, "manual scan");
                msg(sender, "&a" + target.getName() + "：轉換了 " + n + " 張地圖（背包＋末影箱）。");
            }
            case "frames" -> {
                if (!(sender instanceof Player p)) return msg(sender, "&c只能由玩家執行。");
                double radius = 64;
                if (args.length > 1) {
                    try {
                        radius = Math.min(256, Math.max(1, Double.parseDouble(args[1])));
                    } catch (NumberFormatException e) {
                        return msg(sender, "&c半徑必須是數字。");
                    }
                }
                int frames = 0, converted = 0;
                for (Entity e : p.getNearbyEntities(radius, radius, radius)) {
                    if (e instanceof ItemFrame frame) {
                        frames++;
                        if (convertFrame(frame)) converted++;
                    }
                }
                msg(sender, "&a半徑 " + (int) radius + " 格內共 " + frames + " 個展示框，轉換了 " + converted + " 張舊格式地圖。"
                        + (huskSyncBridge != null ? "\n&7空白的 HuskSync 地圖會在背景烘焙，結果記錄在 conversions.log（BAKED）。" : ""));
            }
            case "stats" -> msg(sender, "&7已索引的既有上鎖地圖：&f" + index.existingCount()
                    + "&7，本插件建立的地圖：&f" + index.createdCount()
                    + "&7，HuskSync 掛勾：&f" + huskSyncHooked);
            case "reload" -> {
                reloadConfig();
                msg(sender, "&a設定已重新載入。");
            }
            default -> msg(sender, "&7/" + label + " inspect &8- &f檢查手上的地圖\n"
                    + "&7/" + label + " fix &8- &f轉換手上的地圖\n"
                    + "&7/" + label + " scan [玩家] &8- &f轉換背包和末影箱\n"
                    + "&7/" + label + " frames [半徑] &8- &f轉換周圍展示框裡的地圖（預設 64，最多 256）\n"
                    + "&7/" + label + " stats &8- &f統計\n"
                    + "&7/" + label + " reload &8- &f重新載入設定");
        }
        return true;
    }

    private void inspect(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            msg(sender, "&c只能由玩家執行。");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        byte[] canvas = converter.readLegacyCanvas(hand);
        if (canvas == null) {
            msg(sender, "&e手上的物品不是舊格式地圖（不需要轉換，或不是地圖）。");
            return;
        }
        try {
            LegacyMapConverter.Resolution res = converter.resolve(canvas, false);
            if (res == null) {
                msg(sender, "&c舊格式地圖，但 canvas_data 是空白或無法讀取。");
                return;
            }
            String target = res.view() == null ? "會建立新地圖" : "會使用地圖 #" + res.view().getId();
            msg(sender, "&a舊格式地圖&7（canvas " + res.canvasSha().substring(0, 16) + "）\n"
                    + "&7處理方式：&f" + res.mode() + "&7，方向：&f" + res.orientation() + "\n&7" + target);
        } catch (Exception e) {
            msg(sender, "&c讀取失敗：" + e.getMessage());
        }
    }

    private boolean msg(CommandSender sender, String text) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', text));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("inspect", "fix", "scan", "frames", "stats", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("scan")) return null; // player names
        return List.of();
    }
}
