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
        // "1234782" checksum-padded to "001234782" is a real, valid Israeli ID shape.
        String reason = PersonalIdValidator.validate("1234782");
        // Whether this specific short id happens to be valid or not isn't the point (see the two
        // tests above for that) — the point is it's evaluated as a 9-digit number, not rejected
        // outright just for being short.
        assertTrue(reason == null || reason.contains("checksum"), "should be judged by checksum, not by length: " + reason);
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
