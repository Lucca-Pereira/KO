package com.lucca.ko.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {
    @Test fun parsesLeadingV() = assertEquals(listOf(0, 14, 0), VersionCompare.parse("v0.14.0"))

    @Test fun ignoresPreReleaseSuffix() = assertEquals(listOf(1, 2, 3), VersionCompare.parse("1.2.3-beta+5"))

    @Test fun garbageIsNull() {
        assertNull(VersionCompare.parse("latest"))
        assertNull(VersionCompare.compare("x", "1.0"))
    }

    @Test fun comparesNumericallyNotLexically() {
        assertTrue(VersionCompare.isNewer("v0.14.0", "0.9.2"))
        assertTrue(VersionCompare.isNewer("0.15.0", "0.14.9"))
        assertTrue(VersionCompare.isNewer("1.0.0", "0.99.99"))
    }

    @Test fun equalAndOlderAreNotNewer() {
        assertFalse(VersionCompare.isNewer("v0.14.0", "0.14.0"))
        assertFalse(VersionCompare.isNewer("0.13.0", "0.14.0"))
    }

    @Test fun missingPartsCountAsZero() {
        assertEquals(0, VersionCompare.compare("1.2", "1.2.0"))
        assertTrue(VersionCompare.isNewer("1.2.1", "1.2"))
    }
}
