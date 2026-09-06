package managestore.common.exception;

/**
 * Root of the domain exceptions that mean <em>the request was well-formed, but the
 * store is not in a state that allows it right now</em>. Examples: selling five
 * shirts when only four are left, or registering a customer whose ID is already on
 * file. Nothing about the input itself is wrong — sending the identical request
 * again could succeed later, once stock arrives or the conflicting record is gone.
 *
 * <p>That is why this is a separate root from {@link InvalidRequestException}
 * instead of one flat list of exceptions: the two groups call for different
 * responses. Invalid input asks the user to fix what they typed. A state conflict
 * asks them to do something else, or wait.
 *
 * <p>It extends {@link IllegalStateException}, so existing handlers that catch that
 * type keep working. Only the code that wants the precise reason needs to name it.
 */
public abstract class StoreStateException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    protected StoreStateException(String message) {
        super(message);
    }
}
