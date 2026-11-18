package at.esoren.vystormveins;

import Vystorm.vystorm_core._uiMethods.commands.CommandInterface;
import Vystorm.vystorm_core.commands.CommandSpec;
import java.util.List;

/**
 * {@code /vveins}, {@code /veinmap} and {@code /veinmine} as Vystorm Core command trees: names, root aliases, usage and
 * help come from {@code commands.<command>.*} in the language files ({@code /erzadern vorschau}, {@code /矿脉 预览});
 * English always works. Veins only soft-depends on Core, so the plugin.yml commands stay and Core adopts them: every
 * node has its own handler, the old executor in {@link VeinsPlugin} only runs when Core is missing or older than 0.64.0.
 * Only class that touches Core's command API; loaded only when Core is installed.
 */
final class VeinsCommands {
    private VeinsCommands() {}

    /** The command trees; {@code p} may be {@code null} (or a mock) in tests. */
    static List<CommandSpec> specs(VeinsPlugin p) { return specs(p, CoreLink.liveCommands()); }

    /**
     * The command trees. {@code live} (Core 0.65.0 or newer): switched nodes ask their switch at every completion;
     * otherwise a node whose switch is off at build time is hidden (it then follows a switch after a restart).
     */
    static List<CommandSpec> specs(VeinsPlugin p, boolean live) {
        return List.of(
                CommandSpec.command("vveins").permission("vystormveins.admin")
                        .executes(ctx -> p.status(ctx.sender()))
                        .sub("status", s -> s.executes(ctx -> p.status(ctx.sender())))
                        .sub("reload", s -> s.executes(ctx -> p.reloadCommand(ctx.sender())))
                        .sub("preview", s -> gate(s, p, live, Feature.CHUNK_PREVIEW).playerOnly().executes(ctx -> p.preview(ctx.player())))
                        .sub("apply", s -> gate(s, p, live, Feature.CHUNK_PREVIEW).playerOnly().executes(ctx -> p.apply(ctx.player())))
                        .sub("cancel", s -> gate(s, p, live, Feature.CHUNK_PREVIEW).playerOnly().executes(ctx -> p.cancel(ctx.player())))
                        .build(),
                gate(CommandSpec.command("veinmap"), p, live, Feature.ORE_MAP).permission("vystormveins.map").aliases("oremap", "erzkarte").playerOnly()
                        .executes(ctx -> p.veinMap(ctx.sender()))
                        .build(),
                CommandSpec.command("veinmine").permission("vystormveins.veinmine").playerOnly()
                        .executes(ctx -> p.toggleVeinMining(ctx.sender()))
                        .build(),
                gate(CommandSpec.command("veinjournal"), p, live, Feature.JOURNAL).permission("vystormveins.journal").playerOnly()
                        .executes(ctx -> p.journal(ctx.sender()))
                        .build(),
                gate(CommandSpec.command("veinclaim"), p, live, Feature.CLAIMS).permission("vystormveins.claim").aliases("schuerfrecht").playerOnly()
                        .executes(ctx -> p.claimCommand(ctx.sender(), false))
                        .sub("release", s -> s.executes(ctx -> p.claimCommand(ctx.sender(), true)))
                        .build());
    }

    /**
     * Lists a command in help and tab completion only while its switch is on; while off it still answers the off
     * message. {@code live}: asked at every completion ({@code visibleWhen}, Core 0.65.0), so a switch applies at once;
     * otherwise read once when the tree is built.
     */
    static CommandSpec.Builder gate(CommandSpec.Builder node, VeinsPlugin p, boolean live, Feature feature) {
        if (p == null) return node;
        if (live) return node.visibleWhen(() -> VeinsPlugin.enabled(p, feature));
        return VeinsPlugin.enabled(p, feature) ? node : node.hidden();
    }

    /** Hands the plugin.yml commands to Core. @param commands Core's command manager, {@code core.command()} */
    static void adopt(VeinsPlugin plugin, CommandInterface commands) {
        for (CommandSpec spec : specs(plugin)) commands.adopt(plugin, null, spec);
    }
}
