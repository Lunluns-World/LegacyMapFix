package org.eu.lunworld.legacymapfix;

import de.tr7zw.changeme.nbtapi.NBT;
import de.tr7zw.changeme.nbtapi.iface.ReadableNBT;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Detects locked maps saved in HuskSync's old item format and turns them into real maps on this server.
 * <p>
 * Old format (before HuskSync 3.8): {@code custom_data} holds {@code husksync:persisted_locked_map}
 * with a {@code canvas_data} byte array and a separate {@code id_mappings} compound.
 * HuskSync 3.8+ expects {@code origin}/{@code id} inside {@code husksync:persisted_locked_map};
 * when they are missing it neither applies nor re-saves the map, so the item is stuck.
 * <p>
 * After conversion the item is a plain locked map with no HuskSync data, which HuskSync 3.8+
 * then persists in its new format the next time the player's data is saved.
 */
public final class LegacyMapConverter {

    static final String MAP_DATA_KEY = "husksync:persisted_locked_map";
    static final String LEGACY_ROOT_CANVAS_KEY = "husksync:canvas_data";
    static final String CANVAS_KEY = "canvas_data";
    static final String ORIGIN_KEY = "origin";
    static final String ID_MAPPINGS_KEY = "id_mappings";

    private final LegacyMapFixPlugin plugin;
    private final MapIndex index;
    private final File backupDir;
    private final File logFile;
    private final Set<String> bakeAttempted = ConcurrentHashMap.newKeySet();

    public LegacyMapConverter(LegacyMapFixPlugin plugin, MapIndex index) {
        this.plugin = plugin;
        this.index = index;
        this.backupDir = new File(plugin.getDataFolder(), "canvas-backups");
        this.logFile = new File(plugin.getDataFolder(), "conversions.log");
        //noinspection ResultOfMethodCallIgnored
        backupDir.mkdirs();
    }

    /** Returns the raw canvas_data bytes if the item is a map in the old format, otherwise null. */
    public byte[] readLegacyCanvas(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) return null;
        return NBT.get(item, nbt -> {
            if (nbt.hasTag(MAP_DATA_KEY)) {
                ReadableNBT data = nbt.getCompound(MAP_DATA_KEY);
                if (data == null || data.hasTag(ORIGIN_KEY)) return null; // new format: HuskSync handles it
                if (data.hasTag(CANVAS_KEY)) return data.getByteArray(CANVAS_KEY);
                return nbt.hasTag(LEGACY_ROOT_CANVAS_KEY) ? nbt.getByteArray(LEGACY_ROOT_CANVAS_KEY) : null;
            }
            return nbt.hasTag(LEGACY_ROOT_CANVAS_KEY) ? nbt.getByteArray(LEGACY_ROOT_CANVAS_KEY) : null;
        });
    }

    /**
     * Converts every legacy map in the array, descending into shulker boxes and bundles.
     * Items are changed in place; the caller must write the array back if it came from a copy.
     *
     * @return number of maps converted
     */
    public int convertAll(ItemStack[] items, String context) {
        int converted = 0;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir()) continue;
            converted += convertItem(item, context + " slot " + i);
        }
        return converted;
    }

    /** Converts one item (and anything nested inside it). Returns number of maps converted. */
    public int convertItem(ItemStack item, String context) {
        if (item == null || !item.hasItemMeta()) return 0;
        if (item.getType() == Material.FILLED_MAP) {
            if (convertMap(item, context)) return 1;
            scheduleBake(item, context);
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState() && bsm.getBlockState() instanceof Container box) {
            ItemStack[] contents = box.getInventory().getContents();
            int n = convertAll(contents, context + " > " + item.getType().name());
            if (n > 0) {
                box.getInventory().setContents(contents);
                bsm.setBlockState(box);
                item.setItemMeta(bsm);
            }
            return n;
        }
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            List<ItemStack> contents = new ArrayList<>(bundle.getItems());
            ItemStack[] array = contents.toArray(ItemStack[]::new);
            int n = convertAll(array, context + " > BUNDLE");
            if (n > 0) {
                bundle.setItems(List.of(array));
                item.setItemMeta(bundle);
            }
            return n;
        }
        return 0;
    }

    /**
     * For a map in HuskSync's new format whose origin is another server: HuskSync only paints it here
     * at certain moments (inventory sync), so in item frames it often shows blank or as an icon.
     * Write the origin's pixels into this server's (blank) bound map file so vanilla can show it
     * by itself. Only touches the map HuskSync bound for exactly this origin, and only if it is blank.
     */
    private void scheduleBake(ItemStack item, String context) {
        HuskSyncBridge bridge = plugin.huskSyncBridge();
        if (bridge == null || !plugin.getConfig().getBoolean("bake-husksync-maps", true)) return;
        if (!(item.getItemMeta() instanceof MapMeta meta) || !meta.hasMapView() || meta.getMapView() == null) return;
        int localId = meta.getMapView().getId();

        Object[] origin = NBT.get(item, nbt -> {
            if (!nbt.hasTag(MAP_DATA_KEY)) return null;
            ReadableNBT data = nbt.getCompound(MAP_DATA_KEY);
            if (data == null || !data.hasTag(ORIGIN_KEY) || !data.hasTag("id")) return null;
            return new Object[]{data.getString(ORIGIN_KEY), data.getInteger("id")};
        });
        if (origin == null) return;
        String originServer = (String) origin[0];
        int originId = (Integer) origin[1];
        if (originServer.equals(bridge.serverName())) return; // origin server shows its own real map
        if (!bakeAttempted.add(originServer + ":" + originId + ">" + localId)) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int bound = bridge.boundMapId(originServer, originId);
                if (bound != localId) return; // HuskSync hasn't bound it to this id; leave it to HuskSync
                byte[] nbt = bridge.mapNbt(originServer, originId);
                if (nbt == null) return;
                byte[] stored = CanvasDecoder.extractColors(CanvasDecoder.gunzipLenient(nbt));
                if (CanvasDecoder.isBlank(stored)) return;
                byte[] colors = plugin.getConfig().getBoolean("canvas-transposed", true)
                        ? CanvasDecoder.transpose(stored) : stored;

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        MapView view = Bukkit.getMap(localId);
                        if (view == null || !CanvasDecoder.isBlank(MapWriter.readColors(view))) return;
                        view.setTrackingPosition(false);
                        view.setUnlimitedTracking(false);
                        view.setLocked(true);
                        MapWriter.writeColors(view, colors);
                        log(String.format("BAKED | map #%d | from %s #%d | %s", localId, originServer, originId, context));
                    } catch (ReflectiveOperationException e) {
                        plugin.getLogger().log(Level.WARNING, "Could not bake map #" + localId, e);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Could not read HuskSync data for "
                        + originServer + " #" + originId + " (" + context + ")", e);
            }
        });
    }

    private boolean convertMap(ItemStack item, String context) {
        byte[] canvasRaw = readLegacyCanvas(item);
        if (canvasRaw == null) return false;

        Resolution res;
        try {
            res = resolve(canvasRaw, true);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not convert legacy map (" + context + ")", e);
            return false;
        }
        if (res == null) {
            plugin.getLogger().warning("Skipped legacy map with blank or unreadable canvas (" + context + ")");
            return false;
        }

        NBT.modify(item, nbt -> {
            nbt.removeKey(MAP_DATA_KEY);
            nbt.removeKey(ID_MAPPINGS_KEY);
            nbt.removeKey(LEGACY_ROOT_CANVAS_KEY);
        });
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(res.view());
        item.setItemMeta(meta);

        log(String.format("%s | map #%d | %s | canvas %s | %s",
                res.mode(), res.view().getId(), res.orientation(), res.canvasSha().substring(0, 16), context));
        return true;
    }

    /**
     * Works out which real map a legacy canvas should point to, creating one if {@code create} is true.
     * Returns null for blank/unreadable canvases.
     */
    public Resolution resolve(byte[] canvasRaw, boolean create) throws IOException, ReflectiveOperationException {
        byte[] canvasNbt = CanvasDecoder.gunzipLenient(canvasRaw);
        byte[] canvasColors = CanvasDecoder.extractColors(canvasNbt);
        if (CanvasDecoder.isBlank(canvasColors)) return null;
        int scale = CanvasDecoder.readScale(canvasNbt);
        String canvasSha = CanvasDecoder.sha256(canvasColors);
        backupCanvas(canvasSha, canvasRaw);

        boolean transposedFirst = plugin.getConfig().getBoolean("canvas-transposed", true);
        byte[] transposed = CanvasDecoder.transpose(canvasColors);
        byte[] preferred = transposedFirst ? transposed : canvasColors;
        byte[] alternate = transposedFirst ? canvasColors : transposed;
        String preferredSha = CanvasDecoder.sha256(preferred);
        String alternateSha = CanvasDecoder.sha256(alternate);
        String preferredName = transposedFirst ? "transposed" : "as-stored";
        String alternateName = transposedFirst ? "as-stored" : "transposed";

        if (plugin.getConfig().getBoolean("reuse-existing-maps", true)) {
            Integer id = index.findExisting(preferredSha);
            String orientation = preferredName;
            if (id == null) {
                id = index.findExisting(alternateSha);
                orientation = alternateName;
            }
            MapView view = id == null ? null : Bukkit.getMap(id);
            if (view != null) return new Resolution(view, "EXISTING", orientation, canvasSha);
        }

        Integer createdId = index.findCreated(preferredSha);
        MapView createdView = createdId == null ? null : Bukkit.getMap(createdId);
        if (createdView != null) return new Resolution(createdView, "REUSED", preferredName, canvasSha);

        if (!create) return new Resolution(null, "WOULD_CREATE", preferredName, canvasSha);

        World world = Bukkit.getWorlds().get(0);
        MapView view = Bukkit.createMap(world);
        // Only affects the tooltip / cursor scale; locked pixels are shown as-is regardless.
        view.setScale(scaleOf(scale));
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        MapWriter.writeColors(view, preferred);
        index.putCreated(preferredSha, view.getId());
        return new Resolution(view, "CREATED", preferredName, canvasSha);
    }

    private static MapView.Scale scaleOf(int scale) {
        return switch (scale) {
            case 1 -> MapView.Scale.CLOSE;
            case 2 -> MapView.Scale.NORMAL;
            case 3 -> MapView.Scale.FAR;
            case 4 -> MapView.Scale.FARTHEST;
            default -> MapView.Scale.CLOSEST;
        };
    }

    private void backupCanvas(String sha, byte[] raw) {
        File file = new File(backupDir, sha + ".gz");
        if (file.exists()) return;
        try {
            Files.write(file.toPath(), raw);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to back up canvas " + sha + ": " + e.getMessage());
        }
    }

    private synchronized void log(String line) {
        plugin.getLogger().info("Converted legacy map: " + line);
        try (PrintWriter out = new PrintWriter(new FileWriter(logFile, true))) {
            out.println(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + " | " + line);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write conversions.log: " + e.getMessage());
        }
    }

    public record Resolution(MapView view, String mode, String orientation, String canvasSha) {
    }
}
