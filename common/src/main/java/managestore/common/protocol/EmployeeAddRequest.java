package managestore.common.protocol;

/** role must match a {@link managestore.common.model.Role} enum name (e.g. "CASHIER"). Admin-only action. */
public class EmployeeAddRequest {

    private final String employeeNumber;
    private final String fullName;
    private final String personalId;
    private final String phone;
    private final String accountNumber;
    private final String branchId;
    private final String role;
    private final String username;
    private final String password;

    public EmployeeAddRequest(String employeeNumber, String fullName, String personalId, String phone,
                               String accountNumber, String branchId, String role, String username, String password) {
        this.employeeNumber = employeeNumber;
        this.fullName = fullName;
        this.personalId = personalId;
        this.phone = phone;
        this.accountNumber = accountNumber;
        this.branchId = branchId;
        this.role = role;
        this.username = username;
        this.password = password;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPersonalId() {
        return personalId;
    }

    public String getPhone() {
        return phone;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getBranchId() {
        return branchId;
    }

    public String getRole() {
        return role;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
