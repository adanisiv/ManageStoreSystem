package managestore.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FullNameValidatorTest {

    @Test
    void acceptsAnOrdinaryName() {
        assertNull(FullNameValidator.validate("Dana Cohen"));
    }

    @Test
    void acceptsANameWithAHyphenOrApostrophe() {
        assertNull(FullNameValidator.validate("Mary-Jane O'Brien"));
    }

    @Test
    void acceptsANameInAnotherScript() {
        assertNull(FullNameValidator.validate("דנה כהן"));
    }

    @Test
    void rejectsPureDigits() {
        assertNotNull(FullNameValidator.validate("12345"));
    }

    @Test
    void rejectsPurePunctuation() {
        assertNotNull(FullNameValidator.validate("!!!!"));
    }

    @Test
    void rejectsASingleCharacter() {
        assertNotNull(FullNameValidator.validate("A"));
    }

    @Test
    void rejectsAnImplausiblyLongString() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            tooLong.append('a');
        }
        assertNotNull(FullNameValidator.validate(tooLong.toString()));
    }

    @Test
    void rejectsBlankOrNullInput() {
        assertNotNull(FullNameValidator.validate(""));
        assertNotNull(FullNameValidator.validate("   "));
        assertNotNull(FullNameValidator.validate(null));
    }
}
