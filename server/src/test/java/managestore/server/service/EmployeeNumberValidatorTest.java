package managestore.server.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmployeeNumberValidatorTest {

    @Test
    void acceptsAShortAlphanumericCode() {
        assertNull(EmployeeNumberValidator.validate("E1"));
    }

    @Test
    void acceptsACodeWithADash() {
        assertNull(EmployeeNumberValidator.validate("ADMIN-1"));
    }

    @Test
    void rejectsPunctuationOtherThanADash() {
        assertNotNull(EmployeeNumberValidator.validate("@@@@"));
    }

    @Test
    void rejectsAnImplausiblyLongValue() {
        assertNotNull(EmployeeNumberValidator.validate("E123456789012345678901"));
    }

    @Test
    void rejectsBlankOrNullInput() {
        assertNotNull(EmployeeNumberValidator.validate(""));
        assertNotNull(EmployeeNumberValidator.validate("   "));
        assertNotNull(EmployeeNumberValidator.validate(null));
    }
}
