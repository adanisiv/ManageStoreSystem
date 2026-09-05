package managestore.common.exception;

/**
 * Root of the domain exceptions that mean <em>the caller asked for something
 * malformed</em> — a negative quantity, a blank name, a username that is already
 * taken. The defining trait is that the request can be made valid by correcting
 * the input and sending it again, so the client can always show the message next
 * to the field that caused it.
 *
 * <p>It extends {@link IllegalArgumentException} deliberately. Every subclass is
 * therefore still an {@code IllegalArgumentException}, so the handlers that catch
 * that type keep working unchanged, and callers who only care that "the input was
 * bad" do not have to know the specific subclass exists. Code that <em>does</em>
 * care — a form that wants to highlight one field, a test that wants to prove the
 * exact failure — can catch the precise type instead of matching on message text.
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
