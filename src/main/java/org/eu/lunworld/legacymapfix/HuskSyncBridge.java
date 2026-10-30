package org.eu.lunworld.legacymapfix;

import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection access to HuskSync's map storage, so we can read the pixels HuskSync has for a map
 * without compiling against HuskSync. All methods are blocking (database calls): call off the main thread.
 */
public final class HuskSyncBridge {

    private final Object database;
    private final Object dataAdapter;
    private final Class<?> adaptableMapDataClass;
    private final String serverName;
    private final Method getBoundMapId;
    private final Method getMapData;
    private final Method fromBytes;
    private final Field adaptableDataField;

    private HuskSyncBridge(Plugin husk) throws ReflectiveOperationException {
        ClassLoader loader = husk.getClass().getClassLoader();
        this.serverName = (String) husk.getClass().getMethod("getServerName").invoke(husk);
        this.database = husk.getClass().getMethod("getDatabase").invoke(husk);
        this.dataAdapter = husk.getClass().getMethod("getDataAdapter").invoke(husk);
        this.adaptableMapDataClass = Class.forName("net.william278.husksync.maps.AdaptableMapData", true, loader);

        this.getBoundMapId = database.getClass().getMethod("getBoundMapId", String.class, int.class, String.class);
        this.getMapData = database.getClass().getMethod("getMapData", String.class, int.class);
        this.fromBytes = dataAdapter.getClass().getMethod("fromBytes", byte[].class, Class.class);
        this.adaptableDataField = adaptableMapDataClass.getDeclaredField("data");
        this.adaptableDataField.setAccessible(true);
    }

    /** Returns null if HuskSync is missing or its internals don't match what we expect. */
    public static HuskSyncBridge tryCreate(Plugin husk, java.util.logging.Logger logger) {
        if (husk == null || !husk.isEnabled()) return null;
        try {
            return new HuskSyncBridge(husk);
        } catch (Throwable t) {
            logger.warning("HuskSync map bridge unavailable (" + t + "); baking HuskSync maps is disabled.");
            return null;
        }
    }

    public String serverName() {
        return serverName;
    }

    /** The local map id HuskSync bound the origin map to on this server, or -1. */
    public int boundMapId(String originServer, int originId) throws ReflectiveOperationException {
        return (int) getBoundMapId.invoke(database, originServer, originId, serverName);
    }

    /**
     * The raw map NBT (gzip, HuskSync pixel order) HuskSync stored for the origin map, or null.
     * Same layout as legacy {@code canvas_data}, so {@link CanvasDecoder} reads it.
     */
    public byte[] mapNbt(String originServer, int originId) throws ReflectiveOperationException {
        // HuskSync 3.8.x returns Map.Entry<byte[], Boolean> (key = data); newer versions return byte[].
        Object result = getMapData.invoke(database, originServer, originId);
        byte[] stored;
        if (result == null) {
            return null;
        } else if (result instanceof byte[] bytes) {
            stored = bytes;
        } else if (result instanceof java.util.Map.Entry<?, ?> entry && entry.getKey() instanceof byte[] bytes) {
            stored = bytes;
        } else {
            throw new IllegalStateException("Unexpected getMapData result: " + result.getClass().getName());
        }
        Object adaptable = fromBytes.invoke(dataAdapter, stored, adaptableMapDataClass);
        return (byte[]) adaptableDataField.get(adaptable);
    }
}
