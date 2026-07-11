package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ExtractionRulesTest {
    @Test
    public void extractionIncludesAtLeast256MiBSafetyMargin() {
        long oneGiB = 1024L * 1024L * 1024L;
        assertEquals(
                oneGiB + 256L * 1024L * 1024L,
                ExtractionRules.requiredForOriginalExtraction(oneGiB)
        );
    }

    @Test
    public void csvEscapesCommasQuotesAndNewlines() {
        assertEquals("plain", ExtractionRules.csv("plain"));
        assertEquals("\"a,b\"", ExtractionRules.csv("a,b"));
        assertEquals("\"a\"\"b\"", ExtractionRules.csv("a\"b"));
        assertEquals("\"a\nb\"", ExtractionRules.csv("a\nb"));
    }

    @Test
    public void unknownSpaceDoesNotCauseFalseRejection() {
        assertTrue(ExtractionRules.hasEnoughSpace(-1L, 100L));
        assertTrue(ExtractionRules.hasEnoughSpace(100L, -1L));
        assertFalse(ExtractionRules.hasEnoughSpace(99L, 100L));
    }
}
