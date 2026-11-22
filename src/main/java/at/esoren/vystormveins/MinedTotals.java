package at.esoren.vystormveins;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The ore blocks every player mined, as the mining journal counts them, for the ranking {@code veins:mined} (1.23.0).
 * The journal itself lives on the player and cannot be read for offline players, so this keeps a copy per UUID with the
 * last known name: updated when the journal credits a block and when a player joins (then the journal's total from
 * older versions comes in too). It only grows: a lower journal value never lowers it.
 *
 * <p>No Core types: the plugin creates it only with Vystorm Core, which stores it as {@code rankings.json}. Changes are
 * written at most once a minute and when the plugin stops ({@link #saveIfDirty}), never on every block. Server thread.
 */
final class MinedTotals {
    /** The file content (Gson): {@code players.<uuid>.name} and {@code .mined}. */
    static final class State {
        Map<String, Entry> players = new HashMap<>();
    }

    static final class Entry {
        String name;
        long mined;
    }

    record Standing(UUID player, String name, long mined) {}

    private final Map<UUID, Entry> players = new HashMap<>();
    private final Consumer<State> saver;
    private boolean dirty;

    MinedTotals(Consumer<State> saver) { this.saver = saver; }

    /** Reads a stored state; malformed players are skipped. */
    static MinedTotals of(State state, Consumer<State> saver) {
        MinedTotals totals = new MinedTotals(saver);
        if (state != null && state.players != null) state.players.forEach((key, entry) -> {
            if (entry == null) return;
            UUID id;
            try { id = UUID.fromString(key); } catch (IllegalArgumentException malformed) { return; }
            Entry copy = new Entry();
            copy.name = entry.name;
            copy.mined = Math.max(0, entry.mined);
            totals.players.put(id, copy);
        });
        return totals;
    }

    /** The journal of {@code player} now holds {@code total}; a new name or a higher total marks the data for saving. */
    void update(UUID player, String name, long total) {
        if (player == null) return;
        Entry entry = players.get(player);
        if (entry == null) {
            if (total <= 0) return;   // players who never mined ore are not stored
            entry = new Entry();
            players.put(player, entry);
            dirty = true;
        }
        if (name != null && !name.equals(entry.name)) { entry.name = name; dirty = true; }
        if (total > entry.mined) { entry.mined = total; dirty = true; }
    }

    long mined(UUID player) {
        Entry entry = players.get(player);
        return entry == null ? 0 : entry.mined;
    }

    /** The best {@code limit} players, most ore first, ties by name. */
    List<Standing> top(int limit) {
        List<Standing> standings = new ArrayList<>();
        players.forEach((id, entry) -> {
            if (entry.mined > 0) standings.add(new Standing(id, entry.name == null ? id.toString().substring(0, 8) : entry.name, entry.mined));
        });
        standings.sort(Comparator.comparingLong(Standing::mined).reversed().thenComparing(Standing::name, String.CASE_INSENSITIVE_ORDER));
        return standings.size() <= limit ? standings : List.copyOf(standings.subList(0, limit));
    }

    State state() {
        State state = new State();
        players.forEach((id, entry) -> {
            Entry copy = new Entry();
            copy.name = entry.name;
            copy.mined = entry.mined;
            state.players.put(id.toString(), copy);
        });
        return state;
    }

    boolean dirty() { return dirty; }

    /** Writes the data when something changed (the minute timer and the plugin's stop). */
    void saveIfDirty() {
        if (!dirty) return;
        saver.accept(state());
        dirty = false;
    }
}
