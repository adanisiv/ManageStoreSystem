package managestore.common.exception;

/**
 * Root of the domain exceptions that mean <em>the request was well-formed, but
 * the store is not in a state that allows it right now</em> — selling five shirts
 * when four are left, registering a customer whose ID is already on file. Nothing
 * about the input is wrong; re-sending the identical request could succeed later,
 * once stock arrives or the conflicting record is gone.
 *
 * <p>That distinction is the reason this is a separate root from
 * {@link InvalidRequestException} rather than one flat list of exceptions: the two
 * groups call for different responses. Invalid input asks the user to fix what they
 * typed; a state conflict asks them to do something else, or wait.
 *
 * <p>It extends {@link IllegalStateException}, so existing handlers that catch that
 * type keep working and only the code that wants the precise reason has to name it.
 */
public abstract class StoreStateException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    protected StoreStateException(String message) {
        super(message);
    }
}
