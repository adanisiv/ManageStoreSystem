package managestore.server.service;

/**
 * Loose validation for a person's full name: there is no algorithm that can
 * confirm a string is a real name the way {@link PersonalIdValidator} can
 * confirm a checksum, so this only rules out input that plainly isn't a name
 * at all — a single character, something with no letters in it ("12345",
 * "----"), or an implausibly long string pasted into the field by mistake.
 * Deliberately permissive about which letters count, so names in Hebrew or
 * any other script, and names with hyphens or apostrophes, are all accepted.
 */
public final class FullNameValidator {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 80;

    private FullNameValidator() {
    }

    /** @return null if the name is plausible, otherwise a human-readable reason it isn't. */
    public static String validate(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return "Full name is required";
        }
        String trimmed = fullName.trim();
        if (trimmed.length() < MIN_LENGTH) {
            return "Full name must be at least " + MIN_LENGTH + " characters";
        }
        if (trimmed.length() > MAX_LENGTH) {
            return "Full name must be at most " + MAX_LENGTH + " characters";
        }
        // A name should contain at least one letter from some alphabet — this rejects
        // "12345" or "!!!" without imposing any particular script or character set.
        if (!containsLetter(trimmed)) {
            return "Full name must contain at least one letter";
        }
        return null;
    }

    public static boolean isValid(String fullName) {
        return validate(fullName) == null;
    }

    private static boolean containsLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isLetter(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
