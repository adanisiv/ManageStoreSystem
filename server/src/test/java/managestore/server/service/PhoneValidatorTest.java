package managestore.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhoneValidatorTest {

    @Test
    void acceptsAMobileNumberWithDashes() {
        assertNull(PhoneValidator.validate("050-1234567"));
    }

    @Test
    void acceptsTheSameNumberWithoutDashes() {
        assertNull(PhoneValidator.validate("0501234567"));
    }

    @Test
    void acceptsALandlineLengthNumber() {
        assertNull(PhoneValidator.validate("03-1234567"));
    }

    @Test
    void rejectsGarbageInput() {
        assertNotNull(PhoneValidator.validate("asdf"));
    }

    @Test
    void rejectsANumberNotStartingWithZero() {
        assertNotNull(PhoneValidator.validate("501234567"));
    }

    @Test
    void rejectsTooShortAndTooLongNumbers() {
        assertNotNull(PhoneValidator.validate("0501234"));
        assertNotNull(PhoneValidator.validate("050123456789"));
    }

    @Test
    void rejectsBlankOrNullInput() {
        assertNotNull(PhoneValidator.validate(""));
        assertNotNull(PhoneValidator.validate(null));
    }
}
