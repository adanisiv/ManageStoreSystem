package managestore.server.service;

/**
 * Loose validation for an employee's payroll bank account number. There is
 * no single universal account-number format the way there is for an Israeli
 * personal ID, so — same philosophy as {@link PhoneValidator} — this only
 * catches obvious garbage (a single stray character, punctuation with no
 * digits in it) rather than pretending to validate against a real bank's
 * numbering scheme.
 */
public final class AccountNumberValidator {

    private static final int MIN_LENGTH = 4;
    private static final int MAX_LENGTH = 20;

    private AccountNumberValidator() {
    }

    /** @return null if the account number is plausible, otherwise a human-readable reason it isn't. */
    public static String validate(String accountNumber) {
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            return "Account # is required";
        }
        String trimmed = accountNumber.trim();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            return "Account # must be " + MIN_LENGTH + "-" + MAX_LENGTH + " characters";
        }
        // Letters, digits, and dashes only — covers real formats like "ACC-1" or
        // "IL620108000000099999999" without pinning down one specific bank's layout.
        if (!trimmed.matches("[A-Za-z0-9-]+")) {
            return "Account # may only contain letters, digits, and dashes";
        }
        // An account number that's entirely dashes/letters with no digit at all is not a
        // real account number, whatever else it looks like.
        if (!trimmed.matches(".*\\d.*")) {
            return "Account # must contain at least one digit";
        }
        return null;
    }

    public static boolean isValid(String accountNumber) {
        return validate(accountNumber) == null;
    }
}
