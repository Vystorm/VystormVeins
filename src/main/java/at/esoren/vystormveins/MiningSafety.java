package at.esoren.vystormveins;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.configuration.ConfigurationSection;

/** Checks the immediate surroundings again before each automatic break; never loads chunks. */
final class MiningSafety {
    record Options(boolean enabled, boolean lava, boolean water, boolean fallingBlocks) {
        static final Options DEFAULTS = new Options(false, true, false, true);
        static Options read(ConfigurationSection config) {
            if (config.contains("mining-safety") && !config.isConfigurationSection("mining-safety"))
                throw new IllegalArgumentException("mining-safety must be a section");
            for (String key : new String[]{"enabled", "lava", "water", "falling-blocks"}) {
                String path = "mining-safety." + key;
                if (config.contains(path) && !config.isBoolean(path))
                    throw new IllegalArgumentException(path + " must be true or false");
            }
            return new Options(config.getBoolean("mining-safety.enabled", false),
                    config.getBoolean("mining-safety.lava", true), config.getBoolean("mining-safety.water", false),
                    config.getBoolean("mining-safety.falling-blocks", true));
        }
    }
    record Cell(boolean lava, boolean water, boolean gravity) {}
    interface Lookup { Cell cell(int x, int y, int z); }
    enum Hazard { LAVA, WATER, FALLING, UNLOADED }
    private static final int[][] FACES = {{0,1,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};

    private MiningSafety() {}

    /** Add only our keys, keeping old installations opt-in and preserving all existing choices. */
    static boolean migrate(ConfigurationSection config) {
        Options.read(config);
        boolean changed = false;
        for (String key : new String[]{"enabled", "lava", "water", "falling-blocks"}) {
            String path = "mining-safety." + key;
            if (!config.contains(path)) {
                config.set(path, key.equals("lava") || key.equals("falling-blocks"));
                changed = true;
            }
        }
        return changed;
    }

    static Lookup world(World world) {
        return world(world, Material::hasGravity);
    }

    static Lookup world(World world, java.util.function.Predicate<Material> gravity) {
        return (x, y, z) -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
            var block = world.getBlockAt(x, y, z);
            Material material = block.getType();
            boolean water = material == Material.WATER || material == Material.BUBBLE_COLUMN
                    || block.getBlockData() instanceof Waterlogged wet && wet.isWaterlogged();
            return new Cell(material == Material.LAVA, water, gravity.test(material));
        };
    }

    /** Liquids below cannot flow into the opening. Only the block directly above can fall into it. */
    static Hazard check(Lookup lookup, int x, int y, int z, int maxY, Options options) {
        if (!options.enabled() || !(options.lava() || options.water() || options.fallingBlocks())) return null;
        for (int[] face : FACES) {
            boolean above = face[1] == 1;
            if (y + face[1] >= maxY || (!above && !options.lava() && !options.water())) continue;
            Cell cell = lookup.cell(x + face[0], y + face[1], z + face[2]);
            if (cell == null) return Hazard.UNLOADED;
            if (options.lava() && cell.lava()) return Hazard.LAVA;
            if (options.water() && cell.water()) return Hazard.WATER;
            if (above && options.fallingBlocks() && cell.gravity()) return Hazard.FALLING;
        }
        return null;
    }
}
