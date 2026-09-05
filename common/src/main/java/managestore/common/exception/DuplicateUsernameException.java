package managestore.common.exception;

/**
 * The requested login username is already registered to an account.
 *
 * <p>Unlike a failed <em>login</em>, which deliberately refuses to reveal whether a
 * username exists, account <em>creation</em> is admin-only and has to say so — an
 * admin cannot resolve the conflict without being told what it is.
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
