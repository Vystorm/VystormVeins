package at.esoren.vystormveins.engine;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable rule describing one kind of chunk-local deposit. */
public record VeinRule(
        String id,
        String ore,
        String deepOre,
        Set<String> hosts,
        Set<String> biomes,
        int minY,
        int maxY,
        int attempts,
        int size,
        double chance) {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public VeinRule {
        id = requireText(id, "id");
        ore = requireKey(ore, "ore");
        if (deepOre != null) {
            deepOre = requireKey(deepOre, "deepOre");
        }
        hosts = immutableKeys(hosts, "hosts");
        biomes = immutableKeys(biomes, "biomes");
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must be less than or equal to maxY");
        }
        if (attempts < 0 || attempts > 64) {
            throw new IllegalArgumentException("attempts must be between 0 and 64");
        }
        if (size < 1 || size > 256) {
            throw new IllegalArgumentException("size must be between 1 and 256");
        }
        if (!Double.isFinite(chance) || chance < 0.0 || chance > 1.0) {
            throw new IllegalArgumentException("chance must be finite and between 0 and 1");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireKey(String value, String field) {
        requireText(value, field);
        if (!KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a namespaced key");
        }
        return value;
    }

    private static Set<String> immutableKeys(Set<String> values, String field) {
        Objects.requireNonNull(values, field);
        return values.stream().map(value -> requireKey(value, field)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
