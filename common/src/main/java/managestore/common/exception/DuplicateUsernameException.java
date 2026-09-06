package managestore.common.exception;

/**
 * The requested login username is already registered to an account.
 *
 * <p>A failed <em>login</em> deliberately hides whether a username exists, for
 * security. But account <em>creation</em> is admin-only, so it has to say so. An
 * admin cannot fix the conflict without being told what the conflict actually is.
 */
public class DuplicateUsernameException extends InvalidRequestException {

    private static final long serialVersionUID = 1L;

    private final String username;

    public DuplicateUsernameException(String username) {
        super("Username already taken: " + username);
        this.username = username;
    }

    public String getUsername() {
        return username;
    }
}
