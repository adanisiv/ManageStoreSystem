package managestore.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AccountNumberValidatorTest {

    @Test
    void acceptsTheDemoStyleAccountNumber() {
        assertNull(AccountNumberValidator.validate("ACC-1"));
    }

    @Test
    void acceptsALongerAlphanumericAccountNumber() {
        assertNull(AccountNumberValidator.validate("IL62010800000009999"));
    }

    @Test
    void rejectsPureLettersWithNoDigit() {
        assertNotNull(AccountNumberValidator.validate("ASAACA"));
    }

    @Test
    void rejectsASingleCharacter() {
        assertNotNull(AccountNumberValidator.validate("A"));
    }

    @Test
    void rejectsPunctuationOutsideDashes() {
        assertNotNull(AccountNumberValidator.validate("ACC#1234"));
    }

    @Test
    void rejectsAnImplausiblyLongValue() {
        assertNotNull(AccountNumberValidator.validate("ACC-123456789012345678"));
    }

    @Test
    void rejectsBlankOrNullInput() {
        assertNotNull(AccountNumberValidator.validate(""));
        assertNotNull(AccountNumberValidator.validate(null));
    }
}
