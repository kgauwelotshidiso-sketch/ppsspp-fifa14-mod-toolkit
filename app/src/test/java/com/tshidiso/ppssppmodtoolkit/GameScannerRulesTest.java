package com.tshidiso.ppssppmodtoolkit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GameScannerRulesTest {
    @Test
    public void fifa14NamesAndIdsAreRecognized() {
        assertTrue(GameScanner.looksLikeFifa14Name("FIFA 14 Legacy Edition.cso"));
        assertTrue(GameScanner.looksLikeFifa14Name("fifa_14.iso"));
        assertTrue(GameScanner.containsKnownTitleId("PSP_GAME/ULUS_10655/PARAM.SFO"));
        assertTrue(GameScanner.containsKnownTitleId("ULES-01586"));
        assertFalse(GameScanner.looksLikeFifa14Name("FIFA Street 2.iso"));
        assertFalse(GameScanner.containsKnownTitleId("ULES-01234"));
    }

    @Test
    public void moddingCandidatesAreFiltered() {
        assertTrue(GameScanner.isModdingCandidate("PSP_GAME/USRDIR/fifa.db"));
        assertTrue(GameScanner.isModdingCandidate("data0.big"));
        assertTrue(GameScanner.isModdingCandidate("textures/player.rx3"));
        assertTrue(GameScanner.isModdingCandidate("locale.ini"));
        assertFalse(GameScanner.isModdingCandidate("EBOOT.BIN"));
        assertFalse(GameScanner.isModdingCandidate("cover.jpg"));
        assertFalse(GameScanner.isModdingCandidate(null));
    }

    @Test
    public void dangerousZipPathsAreRejected() {
        assertTrue(GameScanner.isUnsafeArchivePath("../fifa.db"));
        assertTrue(GameScanner.isUnsafeArchivePath("PSP_GAME/../../outside.bin"));
        assertTrue(GameScanner.isUnsafeArchivePath("/absolute/path/fifa.db"));
        assertTrue(GameScanner.isUnsafeArchivePath("C:/Windows/file.db"));
        assertTrue(GameScanner.isUnsafeArchivePath("~user/file.db"));
        assertFalse(GameScanner.isUnsafeArchivePath("PSP_GAME/USRDIR/fifa.db"));
        assertFalse(GameScanner.isUnsafeArchivePath("assets/data0.big"));
    }

    @Test
    public void zipSignaturesAreRecognized() {
        assertTrue(GameScanner.isZipMagic(new byte[]{'P', 'K', 3, 4}));
        assertTrue(GameScanner.isZipMagic(new byte[]{'P', 'K', 5, 6}));
        assertTrue(GameScanner.isZipMagic(new byte[]{'P', 'K', 7, 8}));
        assertFalse(GameScanner.isZipMagic(new byte[]{'C', 'I', 'S', 'O'}));
        assertFalse(GameScanner.isZipMagic(new byte[]{'P', 'K'}));
    }
}
