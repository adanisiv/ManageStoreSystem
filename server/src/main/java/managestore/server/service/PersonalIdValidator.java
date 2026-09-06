package managestore.server.service;

/**
 * Validates a personal ID (ת.ז) using the standard Israeli ID checksum.
 * This is a Luhn-style check digit, the same kind of checksum used on credit
 * card numbers. It always runs over 9 digits — a shorter input is padded
 * with leading zeros first.
 *
 * <p>Here is the algorithm: double every second digit, counting from the
 * left. If a doubled digit is 10 or more, add its own two digits together
 * (which is the same as subtracting 9 from it). Add up all 9 resulting
 * digits. The ID is valid only if that total is a multiple of 10.
 *
 * <p>This class is kept separate, the same way {@link PasswordPolicy} is:
 * it has one {@code validate} method that returns null when the input is
 * valid, or a human-readable reason when it isn't. That makes the rule easy
 * to point to, explain, and test on its own, apart from where it's used
 * (the Admin/Customer add-request handlers in
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
        // Must be purely digits, and no more than 9 of them (a full Israeli ID is 9 digits).
        if (!id.matches("\\d{1,9}")) {
            return "Personal ID must be 1-9 digits";
        }
        // Left-pad shorter IDs with zeros so the checksum always runs over exactly 9 digits,
        // matching a full-length ID number.
        String padded = pad(id);
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            // Pull out the digit at this position (as an int, not a char).
            int digit = padded.charAt(i) - '0';
            // This is the alternating Luhn weighting: digits at even positions (0, 2, 4...)
            // are used as-is. Digits at odd positions (1, 3, 5...) are doubled.
            int weighted = digit * (i % 2 == 0 ? 1 : 2);
            // Doubling a digit can give a two-digit number, e.g. 8*2=16. In that case we
            // need to add its digits together (1+6=7). Subtracting 9 gives the same result
            // (16-9=7) without needing a second loop to split the digits apart.
            sum += weighted < 10 ? weighted : weighted - 9;
        }
        // The checksum passes when the total of all these digits is a multiple of 10.
        return sum % 10 == 0 ? null : "Personal ID checksum is invalid (not a real Israeli ID number)";
    }

    public static boolean isValid(String id) {
        return validate(id) == null;
    }

    /**
     * Returns the full 9-digit, zero-padded form of an ID that already passed {@link #validate}.
     *
     * <p>The same person's ID can arrive as "12345678" or "012345678" — both pass the checksum,
     * since padding happens before it runs either way. But if each string were stored as typed,
     * they would land under two different keys in a map (like {@code CustomerDirectory}'s), and
     * the same person could end up registered twice. Always storing and looking up the padded
     * form fixes that: every valid ID normalizes to the same 9-character string.
     */
    public static String normalize(String id) {
        return pad(id);
    }

    private static String pad(String id) {
        StringBuilder sb = new StringBuilder();
        // Prepend one '0' for every digit short of the required 9-digit length.
        for (int i = id.length(); i < 9; i++) {
            sb.append('0');
        }
        // Then append the original (unpadded) digits after the leading zeros.
        return sb.append(id).toString();
    }
}
