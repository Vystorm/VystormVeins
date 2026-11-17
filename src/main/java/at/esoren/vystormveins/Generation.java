package at.esoren.vystormveins;

import at.esoren.vystormveins.engine.*;
import java.util.Map;
import java.util.LinkedHashMap;
import org.bukkit.Material;
import org.bukkit.Keyed;
import org.bukkit.World;
import org.bukkit.generator.LimitedRegion;

/** Paper adapters; never access a World from a population worker. */
public final class Generation {
    private Generation() {}
    private static final Map<String, String> BASE = Map.ofEntries(
            Map.entry("minecraft:coal_ore", "minecraft:stone"), Map.entry("minecraft:iron_ore", "minecraft:stone"),
            Map.entry("minecraft:copper_ore", "minecraft:stone"), Map.entry("minecraft:gold_ore", "minecraft:stone"),
            Map.entry("minecraft:redstone_ore", "minecraft:stone"), Map.entry("minecraft:lapis_ore", "minecraft:stone"),
            Map.entry("minecraft:diamond_ore", "minecraft:stone"), Map.entry("minecraft:emerald_ore", "minecraft:stone"),
            Map.entry("minecraft:deepslate_coal_ore", "minecraft:deepslate"), Map.entry("minecraft:deepslate_iron_ore", "minecraft:deepslate"),
            Map.entry("minecraft:deepslate_copper_ore", "minecraft:deepslate"), Map.entry("minecraft:deepslate_gold_ore", "minecraft:deepslate"),
            Map.entry("minecraft:deepslate_redstone_ore", "minecraft:deepslate"), Map.entry("minecraft:deepslate_lapis_ore", "minecraft:deepslate"),
            Map.entry("minecraft:deepslate_diamond_ore", "minecraft:deepslate"), Map.entry("minecraft:deepslate_emerald_ore", "minecraft:deepslate"),
            Map.entry("minecraft:ancient_debris", "minecraft:netherrack"), Map.entry("minecraft:nether_quartz_ore", "minecraft:netherrack"), Map.entry("minecraft:nether_gold_ore", "minecraft:netherrack"));
    public record Result(int removed, int placed) {}
    public record OreCell(int x, int y, int z, String material, String deposit) {
        public OreCell(int x,int y,int z,String material) { this(x,y,z,material,""); }
    }
    public static Result run(BlockVolume real, long seed, int cx, int cz, Settings settings, boolean preview) {
        return run(real, seed, cx, cz, settings, preview, cells -> {});
    }
    public static Result run(BlockVolume real, long seed, int cx, int cz, Settings settings, boolean preview,
            java.util.function.Consumer<java.util.List<OreCell>> generated) {
        return run(real,seed,cx,cz,settings,preview,generated,null);
    }
    /** Optional material totals describe the final plan, including preview, without extra world reads. */
    public static Result run(BlockVolume real, long seed, int cx, int cz, Settings settings, boolean preview,
            java.util.function.Consumer<java.util.List<OreCell>> generated, java.util.function.Consumer<Map<String,Integer>> summary) {
        // Plan against the unchanged terrain. Replacement switches cannot change vein
        // RNG, eligibility or paths; only the independent vanilla-removal pass changes.
        record Position(int x,int y,int z) {}
        Map<Position,String> planned=new LinkedHashMap<>();
        Map<Position,String> deposits=new LinkedHashMap<>();
        BlockVolume planning=new BlockVolume() {
            public int minY() {return real.minY();}
            public int maxY() {return real.maxY();}
            public String material(int x,int y,int z) {return real.material(x,y,z);}
            public String biome(int x,int y,int z) {return real.biome(x,y,z);}
            public void place(int x,int y,int z,String material) {planned.put(new Position(x,y,z),material);}
            public void place(int x,int y,int z,String material,String deposit) {
                place(x,y,z,material); deposits.put(new Position(x,y,z),deposit);
            }
        };
        int placed=VeinGenerator.generate(planning,seed,cx,cz,settings.rules(),false);
        if(summary!=null) {
            Map<String,Integer> counts=new java.util.TreeMap<>();
            planned.values().forEach(material->counts.merge(material,1,Integer::sum));
            summary.accept(java.util.Collections.unmodifiableMap(counts));
        }
        int removed=0;
        if(settings.replaceVanilla()) {
            for(int y=real.minY();y<real.maxY();y++)for(int x=cx*16;x<cx*16+16;x++)for(int z=cz*16;z<cz*16+16;z++) {
                String material=real.material(x,y,z);
                if(!settings.replaceNatural().contains(material))continue;
                String base=BASE.get(material);
                if(base!=null) {removed++;if(!preview)real.place(x,y,z,base);}
            }
        }
        if(!preview)planned.forEach((p,material)->real.place(p.x(),p.y(),p.z(),material));
        if(!preview && !planned.isEmpty()) generated.accept(planned.entrySet().stream()
                .map(e -> new OreCell(e.getKey().x(),e.getKey().y(),e.getKey().z(),e.getValue(),deposits.get(e.getKey()))).toList());
        return new Result(removed,placed);
    }
    public static BlockVolume region(LimitedRegion region, int min, int max) {
        return new BlockVolume() {
            public int minY() { return min; }
            public int maxY() { return max; }
            public String material(int x,int y,int z) { return region.getType(x,y,z).getKey().toString(); }
            public String biome(int x,int y,int z) { return ((Keyed) region.getBiome(x,y,z)).getKey().toString(); }
            public void place(int x,int y,int z,String m) { region.setType(x,y,z, Material.matchMaterial(m)); }
        };
    }
    public static BlockVolume world(World world) {
        return new BlockVolume() {
            public int minY() { return world.getMinHeight(); }
            public int maxY() { return world.getMaxHeight(); }
            public String material(int x,int y,int z) { return world.getBlockAt(x,y,z).getType().getKey().toString(); }
            public String biome(int x,int y,int z) { return ((Keyed) world.getBiome(x,y,z)).getKey().toString(); }
            public void place(int x,int y,int z,String m) { world.getBlockAt(x,y,z).setType(Material.matchMaterial(m), false); }
        };
    }
}
