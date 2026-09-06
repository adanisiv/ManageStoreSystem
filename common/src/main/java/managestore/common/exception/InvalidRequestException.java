package managestore.common.exception;

/**
 * Root of the domain exceptions that mean <em>the caller asked for something
 * malformed</em>. Examples: a negative quantity, a blank name, a username that is
 * already taken. What these all have in common is that the request can be made
 * valid just by correcting the input and sending it again. So the client can always
 * show the error message right next to the field that caused it.
 *
 * <p>It extends {@link IllegalArgumentException} on purpose. That means every
 * subclass is still an {@code IllegalArgumentException}, so existing handlers that
 * catch that type keep working without any changes. Callers who only care that "the
 * input was bad" do not need to know the specific subclass exists. Code that
 * <em>does</em> care — a form that wants to highlight one field, a test that wants
 * to check the exact failure — can catch the precise type instead of matching on
 * message text.
 *
 * @see StoreStateException for the other half: valid input, wrong moment
 */
public abstract class InvalidRequestException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    protected InvalidRequestException(String message) {
        super(message);
    }

    protected InvalidRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
