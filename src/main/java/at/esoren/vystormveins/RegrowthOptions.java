package at.esoren.vystormveins;

import org.bukkit.configuration.ConfigurationSection;

record RegrowthOptions(boolean enabled,long intervalMillis,boolean sound,String message) {
    static final String DEFAULT_MESSAGE="default";
    /** {@code default} or the German text of versions before 1.15.0: the warning comes from the language files. */
    static boolean languageText(String message) { return "default".equalsIgnoreCase(message.strip()) || message.startsWith("[VystormVeins] Achtung"); }
    static RegrowthOptions read(ConfigurationSection config,String path,RegrowthOptions fallback) {
        return new RegrowthOptions(config.getBoolean(path+".enabled",fallback.enabled),
                Math.clamp(config.getLong(path+".interval-seconds",fallback.intervalMillis/1000),1,86400)*1000,
                config.getBoolean(path+".warning-sound",fallback.sound),
                config.getString(path+".warning-message",fallback.message));
    }
    static RegrowthOptions defaults(ConfigurationSection config) {
        return read(config,"regrowth",new RegrowthOptions(false,60000,true,DEFAULT_MESSAGE));
    }
}
