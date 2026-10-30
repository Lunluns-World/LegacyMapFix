package org.eu.lunworld.legacymapfix;

import org.bukkit.map.MapView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Writes pixels into the persistent (saved-to-disk) colour data of a real map.
 * <p>
 * Bukkit has no API for this: renderers only change what is sent to players, not the
 * map_N.dat contents. Paper runs Mojang-mapped since 1.20.5, so CraftMapView#worldMap
 * (MapItemSavedData) and its {@code colors} / {@code setColor} members are reached by name.
 */
public final class MapWriter {

    private MapWriter() {
    }

    public static void writeColors(MapView view, byte[] vanillaColors) throws ReflectiveOperationException {
        Field worldMapField = findField(view.getClass(), "worldMap");
        worldMapField.setAccessible(true);
        Object savedData = worldMapField.get(view);

        Method setColor = findMethod(savedData.getClass(), "setColor", int.class, int.class, byte.class);
        if (setColor != null) {
            setColor.setAccessible(true);
            for (int z = 0; z < 128; z++) {
                for (int x = 0; x < 128; x++) {
                    setColor.invoke(savedData, x, z, vanillaColors[x + z * 128]);
                }
            }
        } else {
            Field colorsField = findField(savedData.getClass(), "colors");
            colorsField.setAccessible(true);
            byte[] colors = (byte[]) colorsField.get(savedData);
            System.arraycopy(vanillaColors, 0, colors, 0, CanvasDecoder.MAP_SIZE);
        }

        // Make sure the map is written to disk on the next save even if setColor was unavailable.
        Method setDirty = findMethod(savedData.getClass(), "setDirty");
        if (setDirty != null) {
            setDirty.setAccessible(true);
            setDirty.invoke(savedData);
        }
    }

    /** A copy of the map's persistent colour data (vanilla order). */
    public static byte[] readColors(MapView view) throws ReflectiveOperationException {
        Field worldMapField = findField(view.getClass(), "worldMap");
        worldMapField.setAccessible(true);
        Object savedData = worldMapField.get(view);
        Field colorsField = findField(savedData.getClass(), "colors");
        colorsField.setAccessible(true);
        return ((byte[]) colorsField.get(savedData)).clone();
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
