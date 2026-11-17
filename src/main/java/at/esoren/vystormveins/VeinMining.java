package at.esoren.vystormveins;

import java.util.*;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/** Personal vein mining preference and the search for connected ore blocks. */
final class VeinMining {
    private static final NamespacedKey DISABLED = new NamespacedKey("vystormveins", "vein-mining-disabled");

    /** Stored with the player, so the choice survives reconnects and server restarts. */
    static boolean playerEnabled(Player player) {
        return player.getPersistentDataContainer().getOrDefault(DISABLED, PersistentDataType.BYTE, (byte) 0) == 0;
    }
    static boolean toggle(Player player) {
        boolean enabled = !playerEnabled(player);
        if (enabled) player.getPersistentDataContainer().remove(DISABLED);
        else player.getPersistentDataContainer().set(DISABLED, PersistentDataType.BYTE, (byte) 1);
        return enabled;
    }

    /** A missing section keeps vein mining off, so updated servers do not change their gameplay on their own. */
    record Options(boolean enabled,boolean sneak,int maxBlocks,boolean respectRegrowth,boolean protectTool,Set<String> excluded,boolean collectDrops) {
        static final Options DEFAULTS=new Options(false,true,32,true,true,Set.of(),true);
        Options { excluded=Set.copyOf(excluded); }
        static Options read(ConfigurationSection config) {
            return new Options(config.getBoolean("vein-mining.enabled",DEFAULTS.enabled),
                    config.getBoolean("vein-mining.sneak",DEFAULTS.sneak),
                    Math.clamp(config.getInt("vein-mining.max-blocks",DEFAULTS.maxBlocks),1,128),
                    config.getBoolean("vein-mining.respect-regrowth",DEFAULTS.respectRegrowth),
                    config.getBoolean("vein-mining.protect-tool",DEFAULTS.protectTool),
                    Prospecting.Options.families("vein-mining.excluded-ores",config.getStringList("vein-mining.excluded-ores")),
                    config.getBoolean("vein-mining.collect-drops",DEFAULTS.collectDrops));
        }
    }
    /** Furthest a vein may reach from the broken block on any axis. */
    static final int REACH=16;
    private static final int[][] NEIGHBOURS;
    static {
        List<int[]> around=new ArrayList<>();
        // Faces first, then edges, then corners: the closest blocks of a vein go first.
        for(int ring=1;ring<=3;ring++)
            for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++) for(int dz=-1;dz<=1;dz++)
                if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)==ring) around.add(new int[]{dx,dy,dz});
        NEIGHBOURS=around.toArray(int[][]::new);
    }
    private VeinMining() {}

    /**
     * Ore blocks of the same family connected to the origin (also diagonally), nearest first, without the origin
     * itself. At most {@code max} blocks, never further than {@link #REACH} from the origin and never outside the world.
     */
    static List<int[]> collect(Prospecting.Lookup lookup,int ox,int oy,int oz,String family,Set<String> extraOres,int max,int minY,int maxY) {
        List<int[]> result=new ArrayList<>();
        if(family==null || max<=0) return result;
        Set<Long> seen=new HashSet<>();
        seen.add(pack(0,0,0));
        ArrayDeque<int[]> queue=new ArrayDeque<>();
        queue.add(new int[]{ox,oy,oz});
        while(!queue.isEmpty() && result.size()<max) {
            int[] at=queue.remove();
            for(int[] d:NEIGHBOURS) {
                int x=at[0]+d[0], y=at[1]+d[1], z=at[2]+d[2];
                if(Math.abs(x-ox)>REACH || Math.abs(y-oy)>REACH || Math.abs(z-oz)>REACH || y<minY || y>=maxY) continue;
                if(!seen.add(pack(x-ox,y-oy,z-oz))) continue;
                if(!family.equals(Prospecting.family(lookup.material(x,y,z),extraOres))) continue;
                int[] found={x,y,z};
                result.add(found); queue.add(found);
                if(result.size()>=max) break;
            }
        }
        return result;
    }
    private static long pack(int dx,int dy,int dz) { return ((long)(dx+64)<<16)|((long)(dy+64)<<8)|(dz+64); }

    /** True when one more use would break the tool (protect-tool): fewer than two uses left. */
    static boolean toolAtLimit(int damage,int maxDurability) { return maxDurability>0 && maxDurability-damage<=1; }
}
