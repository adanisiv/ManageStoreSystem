package managestore.server.model;

/**
 * Login credentials for one {@link managestore.common.model.Employee}. This
 * stays on the server only and is never sent to clients. It is kept separate
 * from the Employee profile on purpose: Employee is "who this person is,"
 * and Account is "how they log in."
 */
public class Account {

    private final String employeeNumber;
    private final String username;
    // We never store the actual password. We only store its hash (a scrambled
    // version made using the salt below) and the random salt used to make
    // that hash. Because of the random salt, two employees with the same
    // password get different stored values. This also makes it much harder
    // for someone to recover the real password if the stored hash ever leaks.
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

    // Updates the password hash and salt on this same Account object,
    // instead of forcing the caller to build a brand-new Account just to
    // change the password.
    public void setPassword(String passwordHash, String passwordSalt) {
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
    }
}
