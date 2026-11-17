package at.esoren.vystormveins;

import java.util.*;

/**
 * Mining claims: a player reserves the ore vein they found for a while. Each claim is a set of block positions with an
 * owner and an end time; one claim per player (a new one replaces the old), no block in two claims. Expired claims are
 * ignored and dropped on the next change. In memory only – claims end with a restart. Pure data, server thread.
 */
final class MiningClaims {
    /** A claimed vein; {@code blocks} are packed positions ({@link #key}). */
    record Claim(UUID owner, String ownerName, UUID world, String family, long until, Set<Long> blocks) {}

    private final Map<UUID, Claim> byOwner = new HashMap<>();
    private final Map<UUID, Map<Long, UUID>> owners = new HashMap<>();

    static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }

    /** Result of {@link #claim}: {@code ok}, {@code taken} (with the other owner's claim) or {@code empty}. */
    record Result(String code, Claim claim) {}

    /** Claims {@code blocks} for {@code owner} until {@code until}; refuses when any of them belongs to someone else. */
    Result claim(UUID owner, String ownerName, UUID world, String family, Collection<Long> blocks, long now, long until) {
        if (blocks.isEmpty()) return new Result("empty", null);
        prune(now);
        Map<Long, UUID> map = owners.getOrDefault(world, Map.of());
        for (long block : blocks) {
            UUID other = map.get(block);
            if (other != null && !other.equals(owner)) return new Result("taken", byOwner.get(other));
        }
        release(owner);
        Claim claim = new Claim(owner, ownerName, world, family, until, Set.copyOf(blocks));
        byOwner.put(owner, claim);
        Map<Long, UUID> worldMap = owners.computeIfAbsent(world, w -> new HashMap<>());
        for (long block : blocks) worldMap.put(block, owner);
        return new Result("ok", claim);
    }

    /** The live claim covering a block, if any. */
    Optional<Claim> at(UUID world, long block, long now) {
        UUID owner = owners.getOrDefault(world, Map.of()).get(block);
        Claim claim = owner == null ? null : byOwner.get(owner);
        return claim == null || claim.until() <= now ? Optional.empty() : Optional.of(claim);
    }

    Optional<Claim> of(UUID owner, long now) {
        Claim claim = byOwner.get(owner);
        return claim == null || claim.until() <= now ? Optional.empty() : Optional.of(claim);
    }

    /** The owner mined one of their blocks: it leaves the claim; returns the blocks left (0 = claim ended). */
    int mined(UUID world, long block) {
        Map<Long, UUID> map = owners.get(world);
        UUID owner = map == null ? null : map.remove(block);
        Claim claim = owner == null ? null : byOwner.get(owner);
        if (claim == null) return 0;
        Set<Long> rest = new HashSet<>(claim.blocks());
        rest.remove(block);
        if (rest.isEmpty()) { byOwner.remove(owner); return 0; }
        byOwner.put(owner, new Claim(owner, claim.ownerName(), claim.world(), claim.family(), claim.until(), Set.copyOf(rest)));
        return rest.size();
    }

    boolean release(UUID owner) {
        Claim claim = byOwner.remove(owner);
        if (claim == null) return false;
        Map<Long, UUID> map = owners.get(claim.world());
        if (map != null) { for (long block : claim.blocks()) map.remove(block, owner); if (map.isEmpty()) owners.remove(claim.world()); }
        return true;
    }

    /** Drops expired claims. */
    void prune(long now) {
        for (UUID owner : List.copyOf(byOwner.keySet())) if (byOwner.get(owner).until() <= now) release(owner);
    }

    int size() { return byOwner.size(); }
    void clear() { byOwner.clear(); owners.clear(); }
}
