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
    static List<CommandSpec> specs(VeinsPlugin p) {
        return List.of(
                CommandSpec.command("vveins").permission("vystormveins.admin")
                        .executes(ctx -> p.status(ctx.sender()))
                        .sub("status", s -> s.executes(ctx -> p.status(ctx.sender())))
                        .sub("reload", s -> s.executes(ctx -> p.reloadCommand(ctx.sender())))
                        .sub("preview", s -> s.playerOnly().executes(ctx -> p.preview(ctx.player())))
                        .sub("apply", s -> s.playerOnly().executes(ctx -> p.apply(ctx.player())))
                        .sub("cancel", s -> s.playerOnly().executes(ctx -> p.cancel(ctx.player())))
                        .build(),
                CommandSpec.command("veinmap").permission("vystormveins.map").aliases("oremap", "erzkarte").playerOnly()
                        .executes(ctx -> p.veinMap(ctx.sender()))
                        .build(),
                CommandSpec.command("veinmine").permission("vystormveins.veinmine").playerOnly()
                        .executes(ctx -> p.toggleVeinMining(ctx.sender()))
                        .build(),
                CommandSpec.command("veinjournal").permission("vystormveins.journal").playerOnly()
                        .executes(ctx -> p.journal(ctx.sender()))
                        .build(),
                CommandSpec.command("veinclaim").permission("vystormveins.claim").aliases("schuerfrecht").playerOnly()
                        .executes(ctx -> p.claimCommand(ctx.sender(), false))
                        .sub("release", s -> s.executes(ctx -> p.claimCommand(ctx.sender(), true)))
                        .build());
    }

    /** Hands the plugin.yml commands to Core. @param commands Core's command manager, {@code core.command()} */
    static void adopt(VeinsPlugin plugin, CommandInterface commands) {
        for (CommandSpec spec : specs(plugin)) commands.adopt(plugin, null, spec);
    }
}
