package at.esoren.vystormveins;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The ranking {@code veins:mined} in Core's placeholders (Core 0.67.0, capability {@code placeholder-rankings}): the
 * ore blocks a player mined, as the mining journal counts them, read from {@link MinedTotals} (memory). Only names and
 * numbers are shown, never positions.
 *
 * <p>This is the only class that names Core's ranking types. It is reached only through {@link CoreLink} (with Core)
 * and after {@code CoreApi.has("placeholder-rankings")}: an older Core lacks the types, and without Core the plugin
 * never touches it.
 */
final class VeinsRankings {
    static final String MINED = "mined";

    private VeinsRankings() {}

    /** Declares the ranking when {@code on}, otherwise removes it (switch turned off by a reload). */
    static void apply(Plugin plugin, PlaceholderInterface registry, boolean on, Supplier<MinedTotals> totals, UnaryOperator<String> ref) {
        if (!on) {
            registry.namespace(VeinsPlaceholders.NAMESPACE).ifPresent(ns -> ns.removeRanking(MINED));
            return;
        }
        registry.register(plugin, VeinsPlaceholders.NAMESPACE).ranking(MINED,
                RankingInfo.of(ref.apply("rankings.mined.label"), ref.apply("rankings.mined.description")),
                n -> {
                    MinedTotals current = totals.get();
                    return current == null ? List.of() : current.top(n).stream()
                            .map(s -> new RankingSource.Row(s.player(), s.name(), s.mined())).toList();
                });
    }
}
