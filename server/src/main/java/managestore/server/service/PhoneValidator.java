package managestore.server.service;

/**
 * Loose validation for an Israeli phone number: after stripping spaces and
 * dashes (people type "050-1234567" or "050 1234567" or "0501234567"
 * interchangeably), it must start with 0 and be 9-10 digits long — landline
 * numbers are 9 (area code + 7 digits) and mobile numbers are 10 (05X + 7
 * digits). Deliberately not stricter than that (e.g. not pinning exact
 * area/prefix codes): the goal is catching obvious garbage like "asdf" or
 * "123", not being the source of truth on real-world numbering plans.
 */
public final class PhoneValidator {

    private PhoneValidator() {
    }

    /** @return null if the phone number is plausible, otherwise a human-readable reason it isn't. */
    public static String validate(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return "Phone number is required";
        }
        String digitsOnly = phone.replaceAll("[\\s-]", "");
        if (!digitsOnly.matches("0\\d{8,9}")) {
            return "Phone number must start with 0 and have 9-10 digits total, e.g. 050-1234567";
        }
        return null;
    }

    public static boolean isValid(String phone) {
        return validate(phone) == null;
    }
}
