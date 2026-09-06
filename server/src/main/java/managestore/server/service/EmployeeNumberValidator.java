package managestore.server.service;

/**
 * Loose validation for an employee number: organizations number their staff
 * however they like ("E1", "ADMIN-1", a plain sequential integer), so this
 * doesn't assume any one convention. It only rejects an implausibly long
 * value and characters that could never be part of a real employee code —
 * not digits, letters, and dashes. {@link managestore.server.net.ClientHandler}
 * separately checks the number isn't already assigned to someone else.
 */
public final class EmployeeNumberValidator {

    private static final int MAX_LENGTH = 20;

    private EmployeeNumberValidator() {
    }

    /** @return null if the employee number is plausible, otherwise a human-readable reason it isn't. */
    public static String validate(String employeeNumber) {
        if (employeeNumber == null || employeeNumber.trim().isEmpty()) {
            return "Employee # is required";
        }
        String trimmed = employeeNumber.trim();
        if (trimmed.length() > MAX_LENGTH) {
            return "Employee # must be at most " + MAX_LENGTH + " characters";
        }
        if (!trimmed.matches("[A-Za-z0-9-]+")) {
            return "Employee # may only contain letters, digits, and dashes";
        }
        return null;
    }

    public static boolean isValid(String employeeNumber) {
        return validate(employeeNumber) == null;
    }
}
