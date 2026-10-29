package com.rosterguard.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanonicalHasherTest {

    private static HrProfile profile(String name, String email, String phone) {
        return new HrProfile(name, email, phone, "M1", "BLR", "DAY");
    }

    @Test
    void hashIsSha256HexOf64Chars() {
        assertThat(CanonicalHasher.rosterHash(profile("A", "a@x.test", "+919800000001")))
                .hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void hashIsDeterministic() {
        HrProfile p = profile("Asha", "a@x.test", "+919800000001");
        assertThat(CanonicalHasher.rosterHash(p)).isEqualTo(CanonicalHasher.rosterHash(p));
    }

    @Test
    void canonicalisationIgnoresWhitespaceAndEmailCase() {
        HrProfile a = profile(" Asha ", "A@X.test", "+91 98000-00001");
        HrProfile b = profile("Asha", "a@x.test", "+919800000001");
        assertThat(CanonicalHasher.rosterHash(a)).isEqualTo(CanonicalHasher.rosterHash(b));
    }

    @Test
    void differentFieldChangesTheHash() {
        HrProfile a = profile("Asha", "a@x.test", "+919800000001");
        HrProfile b = profile("Asha", "a@x.test", "+919800000002");
        assertThat(CanonicalHasher.rosterHash(a)).isNotEqualTo(CanonicalHasher.rosterHash(b));
    }

    @Test
    void lengthPrefixPreventsFieldBoundaryCollisions() {
        HrProfile a = new HrProfile("ab", "c@x.test", "+919800000001", null, "BLR", null);
        HrProfile b = new HrProfile("a", "bc@x.test", "+919800000001", null, "BLR", null);
        assertThat(CanonicalHasher.rosterHash(a)).isNotEqualTo(CanonicalHasher.rosterHash(b));
    }

    @Test
    void nullAndEmptyAreTheSame_butDifferFromAValue() {
        HrProfile nullShift = new HrProfile("A", "a@x.test", "+919800000001", null, "BLR", null);
        HrProfile blankShift = new HrProfile("A", "a@x.test", "+919800000001", null, "BLR", "  ");
        HrProfile withShift = new HrProfile("A", "a@x.test", "+919800000001", null, "BLR", "DAY");
        assertThat(CanonicalHasher.rosterHash(nullShift)).isEqualTo(CanonicalHasher.rosterHash(blankShift));
        assertThat(CanonicalHasher.rosterHash(nullShift)).isNotEqualTo(CanonicalHasher.rosterHash(withShift));
    }

    @Test
    void payloadHashDistinguishesEventTypeAndRosterHashIsADifferentDomain() {
        HrProfile p = profile("Asha", "a@x.test", "+919800000001");
        assertThat(CanonicalHasher.payloadHash(HrEventType.CREATE, p))
                .isNotEqualTo(CanonicalHasher.payloadHash(HrEventType.UPDATE, p))
                .isNotEqualTo(CanonicalHasher.rosterHash(p));
    }

    @Test
    void deactivatePayloadIgnoresProfileContents() {
        assertThat(CanonicalHasher.payloadHash(HrEventType.DEACTIVATE, profile("A", "a@x.test", "+919800000001")))
                .isEqualTo(CanonicalHasher.payloadHash(HrEventType.DEACTIVATE, HrProfile.EMPTY));
    }
}
