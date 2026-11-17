package at.esoren.vystormveins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RegrowthOptionsTest {
    @Test void defaultAndTheOldGermanTextUseTheLanguageFiles() {
        assertTrue(RegrowthOptions.languageText("default"));
        assertTrue(RegrowthOptions.languageText("[VystormVeins] Achtung: 80% dieser Erzader sind abgebaut!"));
        assertFalse(RegrowthOptions.languageText("80% mined, leave {minimum}"));
        assertFalse(RegrowthOptions.languageText(""));
    }
}
