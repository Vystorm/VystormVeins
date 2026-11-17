package at.esoren.vystormveins;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Pure ore search for the prospecting rod; world access goes through {@link Lookup}. */
final class Prospecting {
    /** Returns the namespaced block key at a position, or null when the position is not loaded. */
    @FunctionalInterface interface Lookup {
        String material(int x, int y, int z);
    }
    record Options(boolean enabled, Material item, boolean sneak, int radius, long cooldownMillis, boolean particles, int lines, Set<String> hidden) {
        static final Options DEFAULTS = new Options(true, Material.AMETHYST_SHARD, true, 10, 8000, true, 3, Set.of());
        Options {
            hidden = Set.copyOf(hidden);
        }
        static Options read(ConfigurationSection config) {
            String name = config.getString("prospecting.item", DEFAULTS.item.name());
            Material item = Material.matchMaterial(name);
            if (item == null || item.isLegacy() || item == Material.AIR) throw new IllegalArgumentException("prospecting.item: unknown item " + name);
            return new Options(config.getBoolean("prospecting.enabled", DEFAULTS.enabled), item,
                    config.getBoolean("prospecting.sneak", DEFAULTS.sneak),
                    Math.clamp(config.getInt("prospecting.radius", DEFAULTS.radius), 2, 16),
                    Math.clamp(config.getLong("prospecting.cooldown-seconds", DEFAULTS.cooldownMillis / 1000), 0, 3600) * 1000,
                    config.getBoolean("prospecting.particles", DEFAULTS.particles),
                    Math.clamp(config.getInt("prospecting.max-lines", DEFAULTS.lines), 1, 11),
                    families("prospecting.hidden-ores", config.getStringList("prospecting.hidden-ores")));
        }
        /** Ore list from config: vanilla family names stay as they are; any other entry must be a block (custom deposit ores). */
        static Set<String> families(String path, List<String> entries) {
            Set<String> result = new HashSet<>();
            for (String entry : entries) {
                String name = entry.strip().toLowerCase(Locale.ROOT);
                if (NaturalReplacement.familyNames().contains(name)) {
                    result.add(name);
                    continue;
                }
                Material block = Material.matchMaterial(name);
                if (block == null || block.isLegacy() || block == Material.AIR) throw new IllegalArgumentException(path + ": unknown ore " + entry);
                String key = block.getKey().toString();
                String family = family(key, Set.of(key));
                result.add(family);
            }
            return result;
        }
    }
    /** Nearest block of one ore family plus how many blocks of that family lie in range. */
    record Find(String family, int dx, int dy, int dz, int count) {
        double distance() {
            return Math.sqrt((double)dx * dx + (double)dy * dy + (double)dz * dz);
        }
    }
    private static final List<String> COMPASS = List.of("S", "SW", "W", "NW", "N", "NE", "E", "SE");
    private Prospecting() {}

    /** Ore family of a block key, or null for non-ore blocks: deepslate and normal variants share one family. */
    static String family(String key, Set<String> extraOres) {
        if (key == null) return null;
        String path = key.startsWith("minecraft:") ? key.substring(10) : null;
        if (path != null) {
            if (path.equals("ancient_debris")) return "ancient_debris";
            if (path.endsWith("_ore")) {
                String base = path.substring(0, path.length() - 4);
                if (base.startsWith("deepslate_")) base = base.substring(10);
                return base;
            }
        }
        return extraOres.contains(key) ? key : null;
    }

    /** Scans a cube around the origin, nearest block first per family; sorted by distance. */
    static List<Find> scan(Lookup lookup, int ox, int oy, int oz, int radius, int minY, int maxY, Set<String> extraOres) {
        Map<String, int[]> nearest = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (int dy = -radius; dy <= radius; dy++) {
            int y = oy + dy;
            if (y < minY || y >= maxY) continue;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                String family = family(lookup.material(ox + dx, y, oz + dz), extraOres);
                if (family == null) continue;
                counts.merge(family, 1, Integer::sum);
                int[] best = nearest.get(family);
                if (best == null || dx * dx + dy * dy + dz * dz < best[0] * best[0] + best[1] * best[1] + best[2] * best[2])
                    nearest.put(family, new int[]{dx, dy, dz});
            }
        }
        List<Find> result = new ArrayList<>();
        nearest.forEach((family, d) -> result.add(new Find(family, d[0], d[1], d[2], counts.get(family))));
        result.sort(Comparator.comparingDouble(Find::distance).thenComparing(Find::family));
        return result;
    }

    /** Drops families the admin hid from the rod (prospecting.hidden-ores); order is kept. */
    static List<Find> visible(List<Find> finds, Set<String> hidden) {
        if (hidden.isEmpty()) return finds;
        return finds.stream().filter(find -> !hidden.contains(find.family())).toList();
    }

    /** Unit direction from the eye to the centre of a fixed target block, or null when the eye is inside it. */
    static double[] towards(double eyeX, double eyeY, double eyeZ, int targetX, int targetY, int targetZ) {
        double x = targetX + 0.5 - eyeX, y = targetY + 0.5 - eyeY, z = targetZ + 0.5 - eyeZ;
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 0.01 ? null : new double[]{x / length, y / length, z / length, length};
    }

    /** Eight-way compass heading (north is -Z) or null when the target is straight above or below. */
    static String heading(int dx, int dz) {
        if (dx == 0 && dz == 0) return null;
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        return COMPASS.get(Math.floorMod((int)Math.round(angle / 45.0), 8));
    }

    /** Vertical hint: "up", "down" or "level" within two blocks. */
    static String vertical(int dy) {
        return dy > 2 ? "up" : dy < -2 ? "down" : "level";
    }
}
