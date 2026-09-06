package managestore.common.exception;

/**
 * A single named field failed validation. Examples: a blank full name, a personal ID
 * whose check digit does not add up, a phone number in the wrong shape, a password
 * that does not meet the policy.
 *
 * <p>The field name is stored as its own field on the exception, not just stuck as a
 * prefix in the message text. That is what lets a form do better than show a generic
 * popup — it can put the error right next to <em>the input that caused it</em>. The
 * server still validates everything itself, no matter what the UI already checked.
 */
public class ValidationException extends InvalidRequestException {

    private static final long serialVersionUID = 1L;

    private final String fieldName;

    /**
     * @param fieldName the field as a person would name it ("Full name", "Phone"),
     *                  not the variable or column it maps to
     * @param reason    what is wrong with it, already phrased for the user to read
     */
    public ValidationException(String fieldName, String reason) {
        super(reason);
        this.fieldName = fieldName;
    }

    public String getFieldName() {
        return fieldName;
    }
}
