package at.esoren.vystormveins;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The values of Vystorm Veins in Core's placeholder registry, namespace {@code veins} (Vystorm Core 0.65.0 or newer;
 * loaded only through {@link CoreLink}). The journal lives on the player, claims and regrowth in plain maps: every value
 * except the family count is answered on the server thread only. A value of a switched-off feature is empty.
 *
 * <pre>
 * %vystorm_veins_journal_total%     {veins:journal_total}       all ore discoveries of the player
 * %vystorm_veins_journal_diamond%   {veins:journal_diamond}     discoveries of one family (_tier: its tier 0-4)
 * journal_discovered, journal_families, journal_collection_tier, claim_family, claim_blocks_left, veins_tracked,
 * veins_renewable
 * </pre>
 */
final class VeinsPlaceholders {
    static final String NAMESPACE = "veins";

    /** What the values read, at call time. */
    interface Source {
        boolean on(Feature feature);
        long[] journal(Player player);
        Optional<MiningClaims.Claim> claim(UUID player);
        /** Regrowth counters (tracked, renewable, original blocks), or null without regrowth. */
        int[] regrowth();
    }

    private VeinsPlaceholders() {}

    static Source source(VeinsPlugin plugin) {
        return new Source() {
            @Override public boolean on(Feature feature) { return plugin.on(feature); }
            @Override public long[] journal(Player player) { return plugin.journalCounts(player); }
            @Override public Optional<MiningClaims.Claim> claim(UUID player) { return plugin.claimOf(player); }
            @Override public int[] regrowth() {
                Regrowth tracker = plugin.regrowth();
                return tracker == null || !tracker.enabled() ? null : tracker.counts();
            }
        };
    }

    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        ns.player("journal_total", describe.apply("placeholder.journal_total"), player -> {
            if (!source.on(Feature.JOURNAL)) return "";
            long total = 0;
            for (long value : source.journal(player)) total = value > Long.MAX_VALUE - total ? Long.MAX_VALUE : total + value;
            return total;
        });
        ns.player("journal_discovered", describe.apply("placeholder.journal_discovered"), player ->
                source.on(Feature.JOURNAL) ? MiningJournal.discovered(source.journal(player)) : "");
        ns.add(PlaceholderValue.global("journal_families", () -> source.on(Feature.JOURNAL) ? MiningJournal.FAMILIES.size() : "")
                .asyncSafe().description(describe.apply("placeholder.journal_families")));
        ns.player("journal_collection_tier", describe.apply("placeholder.journal_collection_tier"), player ->
                source.on(Feature.JOURNAL) ? MiningJournal.collectionTier(MiningJournal.discovered(source.journal(player))) : "");
        for (int i = 0; i < MiningJournal.FAMILIES.size(); i++) {
            int index = i;
            String family = MiningJournal.FAMILIES.get(i);
            ns.player("journal_" + family, describe.apply("placeholder.journal_family"), player ->
                    source.on(Feature.JOURNAL) ? source.journal(player)[index] : "");
            ns.player("journal_" + family + "_tier", describe.apply("placeholder.journal_family_tier"), player ->
                    source.on(Feature.JOURNAL) ? MiningJournal.tier(source.journal(player)[index]) : "");
        }
        ns.value("claim_family", describe.apply("placeholder.claim_family"), viewer ->
                source.on(Feature.CLAIMS) ? source.claim(viewer.getUniqueId()).map(MiningClaims.Claim::family).orElse("") : "");
        ns.value("claim_blocks_left", describe.apply("placeholder.claim_blocks_left"), viewer ->
                source.on(Feature.CLAIMS) ? source.claim(viewer.getUniqueId()).map(claim -> (Object) claim.blocks().size()).orElse("") : "");
        ns.add(PlaceholderValue.global("veins_tracked", () -> {
            int[] counts = source.on(Feature.REGROWTH) ? source.regrowth() : null;
            return counts == null ? "" : counts[0];
        }).cacheTicks(20).description(describe.apply("placeholder.veins_tracked")));
        ns.add(PlaceholderValue.global("veins_renewable", () -> {
            int[] counts = source.on(Feature.REGROWTH) ? source.regrowth() : null;
            return counts == null ? "" : counts[1];
        }).cacheTicks(20).description(describe.apply("placeholder.veins_renewable")));
        return ns;
    }
}
