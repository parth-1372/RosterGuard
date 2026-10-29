package com.rosterguard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VersionPolicyTest {

    private static final String H1 = "a".repeat(64);
    private static final String H2 = "b".repeat(64);

    @Test
    void newEmployeeIsApplied() {
        assertThat(VersionPolicy.decide(null, null, 1, H1)).isEqualTo(VersionDecision.APPLY);
    }

    @Test
    void higherVersionIsApplied() {
        assertThat(VersionPolicy.decide(6L, H1, 7, H2)).isEqualTo(VersionDecision.APPLY);
    }

    @Test
    void lowerVersionIsStaleEvenIfPayloadDiffers() {
        assertThat(VersionPolicy.decide(7L, H1, 6, H2)).isEqualTo(VersionDecision.STALE);
    }

    @Test
    void sameVersionSamePayloadIsDuplicate() {
        assertThat(VersionPolicy.decide(7L, H1, 7, H1)).isEqualTo(VersionDecision.DUPLICATE);
    }

    @Test
    void sameVersionDifferentPayloadIsConflict() {
        assertThat(VersionPolicy.decide(7L, H1, 7, H2)).isEqualTo(VersionDecision.CONFLICT);
    }
}
