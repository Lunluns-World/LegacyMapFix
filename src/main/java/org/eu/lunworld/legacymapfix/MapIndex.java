package org.eu.lunworld.legacymapfix;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the SHA-256 of a map's vanilla-ordered pixels to a map id on this server.
 * <ul>
 *     <li>{@code existing}: locked, non-blank maps already in the world's data folder (rebuilt on startup)</li>
 *     <li>{@code created}: maps this plugin created (persisted), so copies of the same picture share one id</li>
 * </ul>
 */
public final class MapIndex {

    private static final Pattern MAP_FILE = Pattern.compile("map_(\\d+)\\.dat");

    private final Map<String, Integer> existing = new ConcurrentHashMap<>();
    private final Map<String, Integer> created = new ConcurrentHashMap<>();
    private final File createdFile;
    private final Logger logger;

    public MapIndex(File dataFolder, Logger logger) {
        this.createdFile = new File(dataFolder, "created-maps.yml");
        this.logger = logger;
    }

    public void scanExisting(Path mapDataDir) {
        long start = System.currentTimeMillis();
        int scanned = 0, indexed = 0;
        if (Files.isDirectory(mapDataDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(mapDataDir, "map_*.dat")) {
                for (Path file : files) {
                    Matcher m = MAP_FILE.matcher(file.getFileName().toString());
                    if (!m.matches()) continue;
                    scanned++;
                    CanvasDecoder.MapFile map = CanvasDecoder.readMapFile(file);
                    if (map == null || !map.locked() || CanvasDecoder.isBlank(map.colors())) continue;
                    existing.putIfAbsent(CanvasDecoder.sha256(map.colors()), Integer.parseInt(m.group(1)));
                    indexed++;
                }
            } catch (IOException e) {
                logger.warning("Failed to scan map files in " + mapDataDir + ": " + e.getMessage());
            }
        } else {
            logger.warning("Map data folder not found: " + mapDataDir);
        }
        logger.info("Indexed " + indexed + " locked maps out of " + scanned + " map files in "
                + (System.currentTimeMillis() - start) + " ms");
    }

    public Integer findExisting(String sha) {
        return existing.get(sha);
    }

    public Integer findCreated(String sha) {
        return created.get(sha);
    }

    public void loadCreated() {
        if (!createdFile.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(createdFile);
        for (String key : yaml.getKeys(false)) {
            created.put(key, yaml.getInt(key));
        }
    }

    public synchronized void putCreated(String sha, int mapId) {
        created.put(sha, mapId);
        YamlConfiguration yaml = new YamlConfiguration();
        created.forEach(yaml::set);
        try {
            yaml.save(createdFile);
        } catch (IOException e) {
            logger.warning("Failed to save " + createdFile.getName() + ": " + e.getMessage());
        }
    }

    public int existingCount() {
        return existing.size();
    }

    public int createdCount() {
        return created.size();
    }
}
