package managestore.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalIdValidatorTest {

    @Test
    void acceptsAValidChecksum() {
        // 1*1 + 2*2 + 3*1 + 4*2(=8) + 5*1 + 6*2(=12->3) + 7*1 + 8*2(=16->7) + 2*1 = 40, a multiple of 10.
        assertNull(PersonalIdValidator.validate("123456782"));
        assertTrue(PersonalIdValidator.isValid("123456782"));
    }

    @Test
    void rejectsAWrongChecksumDigit() {
        // Same 8 digits, wrong final check digit (39, not a multiple of 10).
        assertNotNull(PersonalIdValidator.validate("123456781"));
        assertFalse(PersonalIdValidator.isValid("123456781"));
    }

    @Test
    void zeroPadsShorterIdsBeforeChecking() {
        // "1234780" is 7 digits; padded to "001234780" its weighted sum is 30, a multiple of 10,
        // so it must be accepted. The two leading zeros contribute nothing themselves, but they
        // shift every real digit into a different weighting position — which is exactly why the
        // padding has to happen before the checksum runs, not after.
        assertNull(PersonalIdValidator.validate("1234780"));
        assertTrue(PersonalIdValidator.isValid("1234780"));
    }

    @Test
    void aShortIdWithABadChecksumIsStillRejected() {
        // Same 7-digit id as above with the last digit changed: padded sum is 31, so it fails.
        // Together with the test above this pins down that a short id is *evaluated*, not
        // waved through for being short and not rejected for it either.
        assertNotNull(PersonalIdValidator.validate("1234781"));
        assertFalse(PersonalIdValidator.isValid("1234781"));
    }

    @Test
    void rejectsNonNumericInput() {
        assertNotNull(PersonalIdValidator.validate("abcdefghi"));
    }

    @Test
    void rejectsBlankOrNullInput() {
        assertNotNull(PersonalIdValidator.validate(""));
        assertNotNull(PersonalIdValidator.validate(null));
    }

    @Test
    void rejectsTooManyDigits() {
        assertNotNull(PersonalIdValidator.validate("1234567890"));
    }
}
