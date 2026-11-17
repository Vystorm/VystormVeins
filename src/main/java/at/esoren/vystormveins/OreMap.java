package at.esoren.vystormveins;

import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Pure part of the ore map: grid layout, per-chunk ore counts and the ore filter. */
final class OreMap {
    /** A missing section keeps the map off, so updated servers do not reveal more than before on their own. */
    record Options(boolean enabled,int depth,long cooldownMillis) {
        static final Options DEFAULTS=new Options(false,8,30000);
        static Options read(ConfigurationSection config) {
            return new Options(config.getBoolean("ore-map.enabled",DEFAULTS.enabled),
                    Math.clamp(config.getInt("ore-map.depth",DEFAULTS.depth),1,32),
                    Math.clamp(config.getLong("ore-map.cooldown-seconds",DEFAULTS.cooldownMillis/1000),0,3600)*1000);
        }
    }
    /** Ore counts of one chunk; {@code loaded == false} means the chunk was not loaded and not scanned. */
    record Tile(int chunkX,int chunkZ,boolean loaded,Map<String,Integer> ores) {
        Tile { ores=Collections.unmodifiableMap(new LinkedHashMap<>(ores)); }
        int count(String family) { return family==null?ores.values().stream().mapToInt(Integer::intValue).sum():ores.getOrDefault(family,0); }
        /** The family shown on the tile: the filtered one, or the most frequent one; null when there is nothing to show. */
        String shown(String filter) {
            if(filter!=null) return ores.containsKey(filter)?filter:null;
            return ores.isEmpty()?null:ores.keySet().iterator().next();
        }
    }
    static final int COLUMNS=9, ROWS=5, CENTRE=COLUMNS*ROWS/2;
    private OreMap() {}

    /** Chunk offset {dx, dz} of a grid slot; north (-Z) is the top row, the player's chunk is the centre slot. */
    static int[] offset(int slot) { return new int[]{slot%COLUMNS-COLUMNS/2, slot/COLUMNS-ROWS/2}; }

    /**
     * Counts ore blocks per family in one chunk between {@code fromY} and {@code toY} (both inclusive, clamped to the
     * world), most frequent first. Hidden families are left out. Coordinates passed to the lookup are chunk-local.
     */
    static Map<String,Integer> tally(Prospecting.Lookup chunk,int fromY,int toY,int minY,int maxY,Set<String> extraOres,Set<String> hidden) {
        Map<String,Integer> counts=new HashMap<>();
        for(int y=Math.max(fromY,minY);y<=Math.min(toY,maxY-1);y++)
            for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                String family=Prospecting.family(chunk.material(x,y,z),extraOres);
                if(family!=null && !hidden.contains(family)) counts.merge(family,1,Integer::sum);
            }
        List<Map.Entry<String,Integer>> sorted=new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String,Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        Map<String,Integer> result=new LinkedHashMap<>();
        for(var entry:sorted) result.put(entry.getKey(),entry.getValue());
        return result;
    }

    /** Families present anywhere on the map, in a stable order for the filter button. */
    static List<String> families(Collection<Tile> tiles) {
        TreeSet<String> result=new TreeSet<>();
        for(Tile tile:tiles) result.addAll(tile.ores().keySet());
        return List.copyOf(result);
    }

    /** Next filter: all ores (null), then each family, then all ores again; {@code step} -1 goes backwards. */
    static String cycle(List<String> families,String current,int step) {
        List<String> ring=new ArrayList<>(); ring.add(null); ring.addAll(families);
        int index=ring.indexOf(current);
        if(index<0) index=0;
        return ring.get(Math.floorMod(index+step,ring.size()));
    }

    /** Stack size of a tile: the block count, at least 1 and at most 64. */
    static int amount(int count) { return Math.clamp(count,1,64); }

    /** Item shown for an ore family; custom deposit ores show their own block when it is an item. */
    static Material icon(String family) {
        return switch(family) {
            case "coal" -> Material.COAL;
            case "iron" -> Material.RAW_IRON;
            case "copper" -> Material.RAW_COPPER;
            case "gold" -> Material.RAW_GOLD;
            case "redstone" -> Material.REDSTONE;
            case "lapis" -> Material.LAPIS_LAZULI;
            case "diamond" -> Material.DIAMOND;
            case "emerald" -> Material.EMERALD;
            case "nether_gold" -> Material.GOLD_NUGGET;
            case "nether_quartz" -> Material.QUARTZ;
            case "ancient_debris" -> Material.ANCIENT_DEBRIS;
            default -> {
                Material block=Material.matchMaterial(family);
                yield block!=null && block.isItem() && !block.isAir()?block:Material.STONE;
            }
        };
    }
}
