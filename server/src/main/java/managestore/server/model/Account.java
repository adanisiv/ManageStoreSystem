package managestore.server.model;

/**
 * Login credentials for one {@link managestore.common.model.Employee}, kept
 * server-side only and never sent to clients — deliberately separate from
 * the Employee profile (separation of concerns: "who this person is" vs.
 * "how they authenticate").
 */
public class Account {

    private final String employeeNumber;
    private final String username;
    // The password itself is never stored — only its salted hash, plus the
    // random salt used when hashing it, so two identical passwords never
    // produce the same stored value and a leaked hash can't be reversed easily.
    private String passwordHash;
    private String passwordSalt;

    public Account(String employeeNumber, String username, String passwordHash, String passwordSalt) {
        this.employeeNumber = employeeNumber;
        this.username = username;
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    // Lets a password change (hash + salt together) update this same Account
    // instance instead of requiring a brand-new object to be constructed.
    public void setPassword(String passwordHash, String passwordSalt) {
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
    }
}
