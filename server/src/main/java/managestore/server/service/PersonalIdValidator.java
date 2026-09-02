package managestore.server.service;

/**
 * Validates a personal ID (ת.ז) using the standard Israeli ID checksum: a
 * Luhn-style check digit over 9 digits (shorter input is zero-padded on the
 * left). Every second digit (from the left) is doubled; any doubled value
 * of 10 or more has its own digits summed (equivalently, 9 is subtracted).
 * The number is valid iff the total is a multiple of 10.
 *
 * <p>Kept as its own class, mirroring {@link PasswordPolicy}: a
 * {@code validate} method returning null-if-valid / a reason otherwise, so
 * the rule itself is easy to point to, explain, and unit test in isolation
 * from where it's enforced (the Admin/Customer add-request handlers in
 * {@link managestore.server.net.ClientHandler}).
 */
public final class PersonalIdValidator {

    private PersonalIdValidator() {
    }

    /** @return null if the ID satisfies the checksum, otherwise a human-readable reason it doesn't. */
    public static String validate(String id) {
        if (id == null || id.trim().isEmpty()) {
            return "Personal ID is required";
        }
        if (!id.matches("\\d{1,9}")) {
            return "Personal ID must be 1-9 digits";
        }
        String padded = pad(id);
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = padded.charAt(i) - '0';
            int weighted = digit * (i % 2 == 0 ? 1 : 2);
            sum += weighted < 10 ? weighted : weighted - 9;
        }
        return sum % 10 == 0 ? null : "Personal ID checksum is invalid (not a real Israeli ID number)";
    }

    public static boolean isValid(String id) {
        return validate(id) == null;
    }

    private static String pad(String id) {
        StringBuilder sb = new StringBuilder();
        for (int i = id.length(); i < 9; i++) {
            sb.append('0');
        }
        return sb.append(id).toString();
    }
}
