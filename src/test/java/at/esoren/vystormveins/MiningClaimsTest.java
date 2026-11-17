package at.esoren.vystormveins;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MiningClaimsTest {
    private static final UUID WORLD = UUID.randomUUID(), A = UUID.randomUUID(), B = UUID.randomUUID();
    private static final List<Long> VEIN = List.of(MiningClaims.key(1, 10, 1), MiningClaims.key(2, 10, 1), MiningClaims.key(2, 11, 1));

    @Test
    void givenClaim_whenOthersLook_thenTheyFindTheOwnerUntilItExpires() {
        MiningClaims c = new MiningClaims();
        assertEquals("ok", c.claim(A, "Alex", WORLD, "iron", VEIN, 0, 1000).code());
        assertEquals(A, c.at(WORLD, MiningClaims.key(2, 11, 1), 500).orElseThrow().owner());
        assertTrue(c.at(WORLD, MiningClaims.key(2, 11, 1), 1000).isEmpty(), "expired at its end time");
        assertTrue(c.at(UUID.randomUUID(), MiningClaims.key(2, 11, 1), 500).isEmpty(), "other world");
    }

    @Test
    void givenOverlap_whenSomeoneElseClaims_thenTakenWithTheOwnersClaim() {
        MiningClaims c = new MiningClaims();
        c.claim(A, "Alex", WORLD, "iron", VEIN, 0, 1000);
        MiningClaims.Result result = c.claim(B, "Bo", WORLD, "iron", List.of(MiningClaims.key(2, 10, 1)), 10, 2000);
        assertEquals("taken", result.code());
        assertEquals("Alex", result.claim().ownerName());
        assertEquals("ok", c.claim(B, "Bo", WORLD, "iron", List.of(MiningClaims.key(2, 10, 1)), 1000, 2000).code(), "after expiry it is free");
    }

    @Test
    void givenSecondClaim_whenSameOwner_thenTheOldOneIsReplaced() {
        MiningClaims c = new MiningClaims();
        c.claim(A, "Alex", WORLD, "iron", VEIN, 0, 1000);
        c.claim(A, "Alex", WORLD, "gold", List.of(MiningClaims.key(50, 10, 50)), 0, 1000);
        assertTrue(c.at(WORLD, VEIN.getFirst(), 1).isEmpty());
        assertEquals("gold", c.of(A, 1).orElseThrow().family());
        assertEquals(1, c.size());
    }

    @Test
    void givenOwnerMining_whenAllBlocksAreGone_thenTheClaimEnds() {
        MiningClaims c = new MiningClaims();
        c.claim(A, "Alex", WORLD, "iron", VEIN, 0, 1000);
        assertEquals(2, c.mined(WORLD, VEIN.get(0)));
        assertEquals(1, c.mined(WORLD, VEIN.get(1)));
        assertEquals(0, c.mined(WORLD, VEIN.get(2)));
        assertTrue(c.of(A, 1).isEmpty());
        assertEquals(0, c.mined(WORLD, VEIN.get(2)));
    }

    @Test
    void givenRelease_whenReleased_thenFreeAgain() {
        MiningClaims c = new MiningClaims();
        c.claim(A, "Alex", WORLD, "iron", VEIN, 0, 1000);
        assertTrue(c.release(A));
        assertFalse(c.release(A));
        assertEquals("ok", c.claim(B, "Bo", WORLD, "iron", VEIN, 0, 1000).code());
        assertEquals("empty", c.claim(A, "Alex", WORLD, "iron", List.of(), 0, 1000).code());
    }

    @Test
    void givenNegativeCoordinates_whenPacked_thenKeysDiffer() {
        assertNotEquals(MiningClaims.key(-1, -64, -1), MiningClaims.key(1, -64, 1));
        assertNotEquals(MiningClaims.key(-1, 10, 0), MiningClaims.key(0, 10, -1));
    }
}
