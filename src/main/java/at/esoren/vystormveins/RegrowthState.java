package at.esoren.vystormveins;

import java.util.*;

/** Persisted original cells; a mined or player-replaced cell never counts as a seed. */
final class RegrowthState {
    List<Vein> veins = new ArrayList<>();
    static final class Cell {
        int x,y,z;
        String material;
        String deposit;
        // 0 = original/live, 1 = mined (may regrow into air), 2 = blocked/replaced.
        int state;
        Cell(Generation.OreCell cell) { x=cell.x(); y=cell.y(); z=cell.z(); material=cell.material(); deposit=cell.deposit(); }
        String key() { return x+","+y+","+z; }
    }
    static final class Vein {
        String world;
        String deposit;
        List<Cell> cells = new ArrayList<>();
        boolean exhausted;
        boolean warned;
        long nextGrowth;
        int cursor;
        int required() { return (cells.size()+9)/10; }
        void checkSeeds() {
            if (cells.stream().filter(c -> c.state==0).count() < required()) exhausted=true;
        }
        /** True when mining this live cell would leave fewer than the required 10% and exhaust the vein. */
        boolean lastSeed(Cell cell) {
            return !exhausted && cell.state==0 && cells.stream().filter(c -> c.state==0).count()<=required();
        }
        boolean warningDue() {
            long removed=cells.stream().filter(c -> c.state!=0).count();
            if(removed*5<cells.size()*4L) return false;
            if(warned) return false;
            warned=true;
            return true;
        }
    }
    static String family(String material) { return material.replace("minecraft:deepslate_", "minecraft:"); }
    /** Face-connected components of the same ore family within the generated chunk. */
    static List<Vein> partition(String world,List<Generation.OreCell> source) {
        Map<String,Cell> remaining = new LinkedHashMap<>();
        for(var raw:source) { Cell c=new Cell(raw); remaining.put(c.key(),c); }
        List<Vein> result=new ArrayList<>();
        while(!remaining.isEmpty()) {
            Cell first=remaining.values().iterator().next(); remaining.remove(first.key());
            Vein vein=new Vein(); vein.world=world; vein.deposit=first.deposit;
            ArrayDeque<Cell> queue=new ArrayDeque<>(); queue.add(first);
            while(!queue.isEmpty()) {
                Cell c=queue.remove(); vein.cells.add(c);
                for(int[] d:new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}}) {
                    String key=(c.x+d[0])+","+(c.y+d[1])+","+(c.z+d[2]);
                    Cell other=remaining.get(key);
                    if(other!=null && Objects.equals(other.deposit,c.deposit) && family(other.material).equals(family(c.material))) {
                        remaining.remove(key); queue.add(other);
                    }
                }
            }
            result.add(vein);
        }
        return result;
    }
}
