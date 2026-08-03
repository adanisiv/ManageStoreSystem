package managestore.server.service;

/**
 * Password rules the Admin screen enforces when creating/editing an
 * employee account. Kept as its own class (rather than inline checks in
 * AuthService) so the policy itself is easy to point to and explain, and so
 * it could later be made configurable without touching auth logic.
 */
public class PasswordPolicy {

    private final int minLength;
    private final boolean requireDigit;
    private final boolean requireLetter;

    public PasswordPolicy(int minLength, boolean requireDigit, boolean requireLetter) {
        this.minLength = minLength;
        this.requireDigit = requireDigit;
        this.requireLetter = requireLetter;
    }

    public static PasswordPolicy standard() {
        return new PasswordPolicy(6, true, true);
    }

    /** @return null if the password satisfies the policy, otherwise a human-readable reason it doesn't. */
    public String validate(String password) {
        if (password == null || password.length() < minLength) {
            return "Password must be at least " + minLength + " characters";
        }
        if (requireDigit && password.chars().noneMatch(Character::isDigit)) {
            return "Password must contain at least one digit";
        }
        if (requireLetter && password.chars().noneMatch(Character::isLetter)) {
            return "Password must contain at least one letter";
        }
        return null;
    }

    public boolean isValid(String password) {
        return validate(password) == null;
    }
}
