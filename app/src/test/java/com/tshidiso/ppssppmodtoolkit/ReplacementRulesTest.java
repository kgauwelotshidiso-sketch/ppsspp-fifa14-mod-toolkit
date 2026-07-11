package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReplacementRulesTest {
    private static final String HASH_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void assetIndexParsesQuotedPathsAndPrioritizesFifaDatabase() {
        String text = "path,size_bytes,sha256\n"
                + "data/archive.big,200," + HASH_A + "\n"
                + "\"data,main/fifa.db\",100," + HASH_B + "\n";

        List<AssetRecord> records = ReplacementRules.parseAssetIndex(text);

        assertEquals(2, records.size());
        assertEquals("data,main/fifa.db", records.get(0).getPath());
        assertEquals("Database", records.get(0).getCategory());
    }

    @Test
    public void querySupportsExtensionCategoryAndSizeFilters() {
        AssetRecord database = new AssetRecord("data/fifa.db", 2L * 1024L * 1024L, HASH_A);
        assertTrue(ReplacementRules.matchesQuery(database, "ext:db type:database min:1MB max:3MB"));
        assertFalse(ReplacementRules.matchesQuery(database, "ext:big"));
        assertFalse(ReplacementRules.matchesQuery(database, "max:1MB"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void parentTraversalPathIsRejected() {
        ReplacementRules.requireSafeRelativePath("data/../fifa.db");
    }

    @Test
    public void workspaceRequirementIncludesRollbackStageAndSafetyMargin() {
        long mib = 1024L * 1024L;
        assertEquals(32L * mib + 10L * mib + 2L * 5L * mib,
                ReplacementRules.requiredWorkspaceBytes(10L * mib, 5L * mib));
        assertTrue(ReplacementRules.hasEnoughSpace(60L * mib, 52L * mib));
        assertFalse(ReplacementRules.hasEnoughSpace(-1L, 52L * mib));
    }

    @Test
    public void assetAndWorkingManifestsUpdateOnlySelectedPath() {
        String assets = "path,size_bytes,sha256\n"
                + "data/fifa.db,10," + HASH_A + "\n"
                + "data/other.big,20," + HASH_A + "\n";
        String manifest = "path,size_bytes,sha256,modding_candidate,source_kind,source_offset_bytes\n"
                + "data/fifa.db,10," + HASH_A + ",true,protected_original,-1\n";

        String updatedAssets = ReplacementRules.updateAssetIndex(
                assets, "data/fifa.db", 15L, HASH_B
        );
        String updatedManifest = ReplacementRules.updateWorkingManifest(
                manifest, "data/fifa.db", 15L, HASH_B
        );

        assertTrue(updatedAssets.contains("data/fifa.db,15," + HASH_B));
        assertTrue(updatedAssets.contains("data/other.big,20," + HASH_A));
        assertTrue(updatedManifest.contains("data/fifa.db,15," + HASH_B));
    }

    @Test
    public void machineHashManifestUsesBase64UrlPathKey() {
        String text = "# base64url_path\tsize_bytes\tsha256\n"
                + "ZGF0YS9maWZhLmRi\t10\t" + HASH_A + "\n";

        String updated = ReplacementRules.updateWorkingHashes(
                text, "data/fifa.db", 12L, HASH_B
        );

        assertTrue(updated.contains("ZGF0YS9maWZhLmRi\t12\t" + HASH_B));
    }

    @Test(expected = IllegalArgumentException.class)
    public void staleOrDuplicateAssetIndexTargetIsRejectedDuringUpdate() {
        String duplicate = "path,size_bytes,sha256\n"
                + "data/fifa.db,10," + HASH_A + "\n"
                + "data/fifa.db,11," + HASH_A + "\n";
        ReplacementRules.updateAssetIndex(duplicate, "data/fifa.db", 12L, HASH_B);
    }
}
