package at.esoren.vystormveins;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;

/**
 * "My mining" in the ore map (1.24.0): the personal vein mining switch, the mining journal and the own mining claim,
 * until now only {@code /veinmine}, {@code /veinjournal} and {@code /veinclaim [release]}. A snapshot without Core
 * types; the buttons run the commands' own methods.
 *
 * @param veinMining the player's vein mining preference, null without {@code vystormveins.veinmine}
 * @param serverOff  vein mining is switched off on the server (the preference is kept)
 * @param journal    the journal, null when it is off or the player lacks {@code vystormveins.journal}
 * @param claims     claims are on and the player has {@code vystormveins.claim}
 * @param claim      the active claim, null when there is none
 */
record MyMining(Boolean veinMining, boolean serverOff, Journal journal, boolean claims, Claim claim) {
    /** One ore family of the journal: name in the player's language, discoveries, tier and the next goal (null = complete). */
    record Line(String ore, long count, int tier, Long next) {}
    record Journal(long total, int discovered, int families, int collectionTier, int goals, List<Line> lines) {}
    record Claim(int blocks, String ore, long minutes) {}

    boolean empty() { return veinMining == null && journal == null && !claims; }

    static MyMining of(VeinsPlugin plugin, Player player) {
        Boolean vein = player.hasPermission("vystormveins.veinmine") ? VeinMining.playerEnabled(player) : null;
        boolean serverOff = plugin.veinMining() == null || !plugin.veinMining().enabled();
        Journal journal = null;
        if (VeinsPlugin.enabled(plugin, Feature.JOURNAL) && player.hasPermission("vystormveins.journal")) {
            long[] values = plugin.journalCounts(player);
            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < values.length; i++) {
                int tier = MiningJournal.tier(values[i]);
                lines.add(new Line(plugin.oreName(player, MiningJournal.FAMILIES.get(i)), values[i], tier,
                        tier == MiningJournal.GOALS.length ? null : MiningJournal.GOALS[tier]));
            }
            int found = MiningJournal.discovered(values);
            journal = new Journal(MiningJournal.total(values), found, MiningJournal.FAMILIES.size(), MiningJournal.collectionTier(found),
                    MiningJournal.GOALS.length, List.copyOf(lines));
        }
        boolean claims = plugin.claimsEnabled() && player.hasPermission("vystormveins.claim");
        Claim claim = null;
        if (claims) {
            long now = System.currentTimeMillis();
            claim = plugin.claimOf(player.getUniqueId()).map(c -> new Claim(c.blocks().size(), plugin.oreName(player, c.family()),
                    Math.max(1, (c.until() - now + 59_999) / 60_000))).orElse(null);
        }
        return new MyMining(vein, serverOff, journal, claims, claim);
    }

    /** The switch in the menu: runs {@code /veinmine} when the wanted state differs. */
    static void setVeinMining(VeinsPlugin plugin, Player player, boolean on) {
        if (!player.hasPermission("vystormveins.veinmine")) { plugin.say(player, "denied"); return; }
        if (VeinMining.playerEnabled(player) != on) plugin.toggleVeinMining(player);
    }

    /** "Release my claim": {@code /veinclaim release}. */
    static void release(VeinsPlugin plugin, Player player) {
        if (!player.hasPermission("vystormveins.claim")) { plugin.say(player, "denied"); return; }
        plugin.claimCommand(player, true);
    }
}
