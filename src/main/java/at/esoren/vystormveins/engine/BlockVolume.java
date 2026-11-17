package at.esoren.vystormveins.engine;

/**
 * The bounded block access needed by the generation engine. Coordinates are
 * absolute; {@code maxY()} is exclusive.
 */
public interface BlockVolume {
    int minY();

    int maxY();

    String material(int x, int y, int z);

    String biome(int x, int y, int z);

    void place(int x, int y, int z, String material);
    default void place(int x, int y, int z, String material, String deposit) { place(x,y,z,material); }
}
