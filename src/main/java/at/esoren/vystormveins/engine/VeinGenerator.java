package at.esoren.vystormveins.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;

/** Deterministic, bounded, chunk-local deposit placement. */
public final class VeinGenerator {
    private static final int[][] STEPS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private VeinGenerator() {
    }

    /**
     * Plans deposits in one 16 by 16 chunk. In preview mode the same reads and
     * planning occur, but the volume is left unchanged.
     *
     * @return number of distinct cells that would be written
     */
    public static int generate(BlockVolume volume, long worldSeed, int chunkX, int chunkZ,
            List<VeinRule> rules, boolean preview) {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(rules, "rules");
        int worldMin = volume.minY();
        int worldMax = volume.maxY();
        if (worldMin >= worldMax) {
            throw new IllegalArgumentException("volume minY must be less than maxY");
        }
        int startX = Math.multiplyExact(chunkX, 16);
        int startZ = Math.multiplyExact(chunkZ, 16);
        int endX = Math.addExact(startX, 15);
        int endZ = Math.addExact(startZ, 15);

        Map<Cell, String> planned = new HashMap<>();
        Map<Cell, String> deposits = new HashMap<>();
        for (VeinRule rule : rules) {
            Objects.requireNonNull(rule, "rules must not contain null");
            int lowY = Math.max(worldMin, rule.minY());
            int highY = Math.min(worldMax - 1, rule.maxY());
            if (lowY > highY || rule.attempts() == 0 || rule.chance() == 0.0 || rule.hosts().isEmpty()) {
                continue;
            }
            SplittableRandom random = new SplittableRandom(ruleSeed(worldSeed, chunkX, chunkZ, rule));
            for (int attempt = 0; attempt < rule.attempts(); attempt++) {
                if (random.nextDouble() >= rule.chance()) {
                    continue;
                }
                int x = startX + random.nextInt(16);
                int y = random.nextInt(lowY, highY + 1);
                int z = startZ + random.nextInt(16);
                Cell origin = new Cell(x, y, z);
                if (!eligible(volume, planned, origin, rule)) {
                    continue;
                }
                put(planned, origin, outputMaterial(rule, y));
                deposits.putIfAbsent(origin, rule.id());

                for (int placed = 1; placed < rule.size(); placed++) {
                    List<Cell> choices = new ArrayList<>(6);
                    for (int[] step : STEPS) {
                        long candidateX = (long) x + step[0];
                        long candidateY = (long) y + step[1];
                        long candidateZ = (long) z + step[2];
                        if (candidateX < startX || candidateX > endX
                                || candidateZ < startZ || candidateZ > endZ
                                || candidateY < lowY || candidateY > highY) {
                            continue;
                        }
                        int nx = (int) candidateX;
                        int ny = (int) candidateY;
                        int nz = (int) candidateZ;
                        Cell candidate = new Cell(nx, ny, nz);
                        if (!planned.containsKey(candidate) && eligible(volume, planned, candidate, rule)) {
                            choices.add(candidate);
                        }
                    }
                    if (choices.isEmpty()) {
                        break;
                    }
                    Cell next = choices.get(random.nextInt(choices.size()));
                    put(planned, next, outputMaterial(rule, next.y));
                    deposits.putIfAbsent(next, rule.id());
                    x = next.x;
                    y = next.y;
                    z = next.z;
                }
            }
        }

        if (!preview) {
            planned.forEach((cell, material) -> volume.place(cell.x, cell.y, cell.z, material, deposits.get(cell)));
        }
        return planned.size();
    }

    private static boolean eligible(BlockVolume volume, Map<Cell, String> planned, Cell cell, VeinRule rule) {
        String current = planned.get(cell);
        if (current == null) {
            current = Objects.requireNonNull(volume.material(cell.x, cell.y, cell.z), "volume material");
        }
        if (!rule.hosts().contains(current)) {
            return false;
        }
        if (!rule.biomes().isEmpty()) {
            String biome = Objects.requireNonNull(volume.biome(cell.x, cell.y, cell.z), "volume biome");
            if (!rule.biomes().contains(biome)) {
                return false;
            }
        }
        return true;
    }

    private static void put(Map<Cell, String> planned, Cell cell, String material) {
        planned.putIfAbsent(cell, material);
    }

    private static String outputMaterial(VeinRule rule, int y) {
        return y < 0 && rule.deepOre() != null ? rule.deepOre() : rule.ore();
    }

    private static long ruleSeed(long worldSeed, int chunkX, int chunkZ, VeinRule rule) {
        long value = mix(worldSeed ^ 0x9e3779b97f4a7c15L);
        value = mix(value ^ chunkX);
        value = mix(value ^ Integer.toUnsignedLong(chunkZ));
        value = mix(value ^ stableHash(rule.id()));
        value = mix(value ^ stableHash(rule.ore()));
        value = mix(value ^ (rule.deepOre() == null ? 0L : stableHash(rule.deepOre())));
        value = mix(value ^ rule.minY());
        value = mix(value ^ ((long) rule.maxY() << 32));
        value = mix(value ^ rule.attempts() ^ ((long) rule.size() << 16));
        value = mix(value ^ Double.doubleToLongBits(rule.chance()));
        for (String host : rule.hosts().stream().sorted().toList()) {
            value = mix(value ^ stableHash(host));
        }
        for (String biome : rule.biomes().stream().sorted().toList()) {
            value = mix(value ^ stableHash(biome));
        }
        return value;
    }

    private static long stableHash(String text) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < text.length(); i++) {
            hash ^= text.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private record Cell(int x, int y, int z) {
    }
}
