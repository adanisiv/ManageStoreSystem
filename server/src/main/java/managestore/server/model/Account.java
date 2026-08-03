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

    public void setPassword(String passwordHash, String passwordSalt) {
        this.passwordHash = passwordHash;
        this.passwordSalt = passwordSalt;
    }
}
