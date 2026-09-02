package managestore.server.service;

import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.protocol.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTest {

    private AuthService authService;
    private Employee employee;

    @BeforeEach
    void setUp() {
        authService = new AuthService(new InMemoryAccountRepository(), new InMemoryEmployeeRepository());
        employee = new Employee("E1", "Dana Cohen", "123456789", "050-1111111", "ACC-1", "BRANCH-1", Role.CASHIER);
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        authService.createAccount(employee, "dana", "secret123");

        LoginResponse response = authService.login("dana", "secret123");

        assertTrue(response.isSuccess());
        assertEquals("Dana Cohen", response.getEmployee().getFullName());
    }

    @Test
    void loginFailsWithWrongPassword() {
        authService.createAccount(employee, "dana", "secret123");

        LoginResponse response = authService.login("dana", "wrongPassword");

        assertFalse(response.isSuccess());
    }

    @Test
    void loginFailsForUnknownUsername() {
        LoginResponse response = authService.login("nobody", "whatever1");

        assertFalse(response.isSuccess());
    }

    @Test
    void createAccountRejectsPasswordViolatingPolicy() {
        assertThrows(IllegalArgumentException.class, () -> authService.createAccount(employee, "dana", "abc"));
    }

    @Test
    void createAccountRejectsDuplicateUsername() {
        authService.createAccount(employee, "dana", "secret123");
        Employee other = new Employee("E2", "Other Person", "987654321", "050-2222222", "ACC-2", "BRANCH-1", Role.SELLER);

        assertThrows(IllegalArgumentException.class, () -> authService.createAccount(other, "dana", "secret456"));
    }

    @Test
    void deleteAccountRemovesBothTheEmployeeAndItsLoginSoTheyCantLogInAgain() {
        authService.createAccount(employee, "dana", "secret123");

        authService.deleteAccount("E1");

        LoginResponse response = authService.login("dana", "secret123");
        assertFalse(response.isSuccess(), "a deleted employee's account should no longer be able to log in");
    }

    @Test
    void deleteAccountFreesTheUsernameForReuse() {
        authService.createAccount(employee, "dana", "secret123");
        authService.deleteAccount("E1");

        Employee replacement = new Employee("E2", "New Hire", "987654321", "050-2222222", "ACC-2", "BRANCH-1", Role.SELLER);
        // Should not throw "username already taken" -- the old account is gone, not just orphaned.
        authService.createAccount(replacement, "dana", "newSecret1");

        assertTrue(authService.login("dana", "newSecret1").isSuccess());
    }

    @Test
    void deleteAccountIsANoOpForAnUnknownEmployeeNumber() {
        authService.createAccount(employee, "dana", "secret123");

        authService.deleteAccount("NO-SUCH-EMPLOYEE");

        assertTrue(authService.login("dana", "secret123").isSuccess(), "deleting an unrelated employee number must not affect anyone else");
    }
}
