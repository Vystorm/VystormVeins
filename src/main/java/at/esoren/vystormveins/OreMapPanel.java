package at.esoren.vystormveins;

import Vystorm.vystorm_core.panel.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Pure rendering of an ore scan; panel classes are loaded only after the capability check. */
final class OreMapPanel implements OreMapMenu.Screen {
    static final String NAME = "ore-map";
    record Row(String id, Component title, Component subtitle, Material icon, boolean loaded,
               Map<String, Integer> ores) {}
    record Overview(List<Row> rows, Map<String, String> families, String filter, String selected,
                    int fromY, int toY, long cooldownSeconds, MyMining mine) {
        Overview(List<Row> rows, Map<String, String> families, String filter, String selected, int fromY, int toY, long cooldownSeconds) {
            this(rows, families, filter, selected, fromY, toY, cooldownSeconds, null);
        }
    }
    @FunctionalInterface interface Texts { Component text(String key, Object... args); }
    interface Actions {
        Result check(Player player);
        Result select(Player player, String id);
        Result filter(Player player, String family);
        Result describe(Player player, String id);
        void rescan(Player player);
        /** "My mining" (1.24.0): the vein mining switch, as {@code /veinmine}. */
        Result veinMining(Player player, boolean on);
        /** "Release my claim" (1.24.0), as {@code /veinclaim release}. */
        Result release(Player player);
    }

    private final VeinsPlugin plugin;
    private final OreMapMenu menu;
    private final Panels panels;
    private final Map<UUID, OreMapMenu.View> views = new HashMap<>();
    private final Map<UUID, String> selected = new HashMap<>();

    OreMapPanel(VeinsPlugin plugin, OreMapMenu menu, PanelsInterface panelsPart) {
        this.plugin = plugin;
        this.menu = menu;
        panels = panelsPart.of(plugin);
        // /vmenu keeps its existing entry through OreMapMenu.open so it performs a fresh scan first.
        panels.register(NAME, PanelSpec.builder(plugin.texts().ref("map-menu-title"))
                .access(p -> plugin.isEnabled() && plugin.oreMap().enabled() && p.hasPermission("vystormveins.map"))
                .render(this::render)
                .fallback(p -> {
                    OreMapMenu.View view = views.get(p.getUniqueId());
                    if (view == null) menu.openChest(p); else menu.showChest(p, view);
                }).build());
    }

    @Override public boolean open(Player player, OreMapMenu.View view) {
        views.put(player.getUniqueId(), view);
        selected.put(player.getUniqueId(), Integer.toString(OreMap.CENTRE));
        return panels.open(player, NAME) != PanelOpenResult.NONE;
    }

    @Override public void forget(UUID player) { views.remove(player); selected.remove(player); }
    @Override public void clear() { views.clear(); selected.clear(); }

    Panel render(PanelContext ctx) {
        Player player = ctx.player();
        OreMapMenu.View view = views.get(player.getUniqueId());
        Texts texts = (key, args) -> plugin.texts().component(player, key, args);
        if (view == null || !menu.allowed(player, view)) {
            return Panel.page(texts.text("map-menu-title")).child(Ui.text(texts.text("map-panel-expired")))
                    .footer(Ui.button("refresh").label(texts.text("map-refresh")).onClick(a -> {
                        Result checked = access(a.player());
                        if (checked.success()) a.closeAndThen(menu::open);
                        return checked;
                    }));
        }
        return panel(overview(player, view, texts), texts, actions(view));
    }

    private Overview overview(Player player, OreMapMenu.View view, Texts texts) {
        List<Row> rows = new ArrayList<>();
        for (int slot = 0; slot < view.tiles.size(); slot++) {
            OreMap.Tile tile = view.tiles.get(slot);
            String shown = tile.shown(view.filter);
            Component subtitle = !tile.loaded() ? texts.text("map-unloaded")
                    : shown == null ? texts.text(slot == OreMap.CENTRE ? "map-here-empty" : "map-empty")
                    : texts.text(slot == OreMap.CENTRE ? "map-here" : "map-tile", plugin.oreName(player, shown), tile.count(shown));
            Map<String, Integer> ores = new LinkedHashMap<>();
            tile.ores().forEach((family, count) -> ores.put(plugin.oreName(player, family), count));
            rows.add(new Row(Integer.toString(slot), texts.text("map-tile-chunk", tile.chunkX(), tile.chunkZ()), subtitle,
                    !tile.loaded() ? Material.BLACK_STAINED_GLASS_PANE : shown == null ? Material.MAP : OreMap.icon(shown),
                    tile.loaded(), ores));
        }
        Map<String, String> families = new LinkedHashMap<>();
        OreMap.families(view.tiles).forEach(family -> families.put(family, plugin.oreName(player, family)));
        return new Overview(rows, families, view.filter, selected.get(player.getUniqueId()), view.fromY, view.toY,
                plugin.oreMap().cooldownMillis() / 1000, MyMining.of(plugin, player));
    }

    private Result access(Player player) {
        if (!player.hasPermission("vystormveins.map")) return Result.error(plugin.word(player, "denied"));
        if (!plugin.oreMap().enabled()) return Result.error(plugin.word(player, "map-off"));
        return plugin.isEnabled() && player.isOnline() ? Result.ok() : Result.error(plugin.word(player, "map-panel-expired"));
    }

    private Actions actions(OreMapMenu.View view) {
        return new Actions() {
            public Result check(Player player) { return access(player); }
            private Result current(Player player) {
                Result checked = check(player);
                if (!checked.success()) return checked;
                return views.get(player.getUniqueId()) == view && menu.allowed(player, view)
                        ? Result.ok() : Result.error(plugin.word(player, "map-panel-expired"));
            }
            private int slot(String id) {
                try { int slot = Integer.parseInt(id); return slot >= 0 && slot < view.tiles.size() ? slot : -1; }
                catch (NumberFormatException invalid) { return -1; }
            }
            public Result select(Player player, String id) {
                Result checked = current(player);
                if (!checked.success()) return checked;
                if (slot(id) < 0) return Result.error(plugin.word(player, "map-panel-expired"));
                selected.put(player.getUniqueId(), id);
                return Result.ok();
            }
            public Result filter(Player player, String family) {
                Result checked = current(player);
                if (!checked.success()) return checked;
                if (!family.equals("all") && !OreMap.families(view.tiles).contains(family))
                    return Result.error(plugin.word(player, "map-panel-expired"));
                view.filter = family.equals("all") ? null : family;
                return Result.ok();
            }
            public Result describe(Player player, String id) {
                Result checked = current(player);
                if (!checked.success()) return checked;
                int slot = slot(id);
                if (slot < 0) return Result.error(plugin.word(player, "map-panel-expired"));
                menu.describe(player, view, slot);
                return Result.ok();
            }
            public void rescan(Player player) { menu.open(player); }
            public Result veinMining(Player player, boolean on) {
                Result checked = check(player);
                if (!checked.success()) return checked;
                MyMining.setVeinMining(plugin, player, on);
                return Result.ok();
            }
            public Result release(Player player) {
                Result checked = check(player);
                if (!checked.success()) return checked;
                MyMining.release(plugin, player);
                return Result.ok();
            }
        };
    }

    /** The "My mining" tab (1.24.0). */
    static Ui.Box mine(MyMining m, Texts texts, Actions actions) {
        Ui.Box box = Ui.col().gap(6).id("my-mining");
        if (m.veinMining() != null) {
            box.child(Ui.toggle("vein-mining", texts.text("mine-vein")).value(m.veinMining()).description(texts.text("mine-vein-text"))
                    .onChange((a, on) -> {
                        Result result = actions.veinMining(a.player(), on);
                        if (result.success()) a.refresh();
                        return result;
                    }));
            if (m.serverOff()) box.child(Ui.text(texts.text("veinmine-server-off")).id("vein-mining-off").style(Ui.TextStyle.MUTED));
        }
        if (m.journal() != null) {
            MyMining.Journal j = m.journal();
            box.child(Ui.heading(texts.text("mine-journal")).level(3));
            box.child(Ui.text(texts.text("journal-header", j.total(), j.discovered(), j.families())).id("journal-total"));
            Ui.Kv rows = Ui.kv().id("journal");
            for (MyMining.Line line : j.lines())
                rows.row(line.ore(), texts.text("mine-journal-row", line.count(), line.tier(), j.goals(),
                        line.next() == null ? texts.text("journal-complete") : line.next()));
            box.child(rows);
            box.child(Ui.text(texts.text("journal-goals", j.collectionTier())).style(Ui.TextStyle.MUTED));
        }
        if (m.claims()) {
            box.child(Ui.heading(texts.text("mine-claim")).level(3));
            box.child(Ui.text(m.claim() == null ? texts.text("claim-none")
                    : texts.text("mine-claim-status", m.claim().blocks(), m.claim().ore(), m.claim().minutes())).id("claim-status"));
            box.child(Ui.button("release-claim").label(texts.text("mine-release")).icon(Ui.item(Material.IRON_SHOVEL)).danger()
                    .disabled(m.claim() == null)
                    .confirm(texts.text("mine-release"), m.claim() == null ? texts.text("claim-none")
                            : texts.text("mine-release-confirm", m.claim().blocks(), m.claim().ore()))
                    .onClick(a -> {
                        Result result = actions.release(a.player());
                        if (result.success()) a.refresh();
                        return result;
                    }));
        }
        return box;
    }

    static Panel panel(Overview data, Texts texts, Actions actions) {
        Row chosen = data.rows().stream().filter(row -> row.id().equals(data.selected())).findFirst()
                .orElse(data.rows().isEmpty() ? null : data.rows().getFirst());
        Ui.ListView list = Ui.list("chunks").items(data.rows(), row -> Ui.listItem(row.id(), row.title())
                        .subtitle(row.subtitle()).icon(Ui.item(row.icon())))
                .selected(chosen == null ? null : chosen.id()).onSelect((a, id) -> {
                    Result result = actions.select(a.player(), id);
                    if (result.success()) a.refresh();
                    return result;
                });
        Ui.Box detail = Ui.col().grow(3).gap(6).id("detail");
        if (chosen != null) {
            detail.children(Ui.heading(chosen.title()).level(2), Ui.badge(chosen.subtitle()).id("chunk-status"));
            if (!chosen.loaded()) detail.child(Ui.text(texts.text("map-chunk-unloaded")));
            else if (chosen.ores().isEmpty()) detail.child(Ui.text(texts.text("map-none")));
            else {
                Ui.Kv counts = Ui.kv().id("ore-counts");
                chosen.ores().forEach((name, count) -> counts.row(name, Integer.toString(count)));
                detail.child(counts);
            }
            detail.child(Ui.button("direction").label(texts.text("map-panel-direction")).icon(Ui.item(Material.COMPASS))
                    .disabled(!chosen.loaded()).onClick(a -> actions.describe(a.player(), chosen.id())));
        }
        String filterName = data.filter() == null ? null : data.families().get(data.filter());
        Ui.Select filter = Ui.select("filter", texts.text("map-panel-filter"))
                .option("all", texts.text("map-filter-all")).value(filterName == null ? "all" : data.filter())
                .onChange((a, family) -> {
                    Result result = actions.filter(a.player(), family);
                    if (result.success()) a.refresh();
                    return result;
                });
        data.families().forEach((id, name) -> filter.option(id, name, Ui.item(OreMap.icon(id))));
        Ui.Box map = Ui.col().gap(6).children(Ui.text(texts.text("map-info-band", data.fromY(), data.toY())), filter,
                Ui.row().gap(8).children(Ui.col().grow(2).scrollY(300).child(list), detail));
        Ui.Element<?> body = data.mine() == null || data.mine().empty() ? map
                : Ui.tabs("views").tab("map", texts.text("mine-tab-map"), map).tab("mine", texts.text("mine-tab"), mine(data.mine(), texts, actions));
        return Panel.page(texts.text("map-menu-title")).subtitle(texts.text("map-panel-subtitle"))
                .icon(Ui.item(Material.FILLED_MAP)).size(Panel.Size.LARGE)
                .children(body)
                .footer(Ui.button("refresh").label(texts.text("map-refresh")).icon(Ui.item(Material.SPYGLASS)).primary()
                                .tooltip(texts.text("map-refresh-hint", data.cooldownSeconds())).onClick(a -> {
                                    Result checked = actions.check(a.player());
                                    if (checked.success()) a.closeAndThen(actions::rescan);
                                    return checked;
                                }),
                        Ui.button("close").label(texts.text("map-close")).ghost().onClick(a -> {
                            a.closeAndThen(p -> { }); return Result.ok();
                        }));
    }
}
