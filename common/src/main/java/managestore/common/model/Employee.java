package managestore.common.model;

import java.util.Objects;

/**
 * A network employee. This class does not store login credentials on
 * purpose. Login is a security concern, handled by the server's
 * AuthService. This class only holds plain profile data.
 */
public class Employee {

    private final String employeeNumber;
    private String fullName;
    private String personalId;
    private String phone;
    private String accountNumber; // bank account used for payroll, not a login/username
    private String branchId;
    private Role role;

    public Employee(String employeeNumber, String fullName, String personalId,
                     String phone, String accountNumber, String branchId, Role role) {
        this.employeeNumber = Objects.requireNonNull(employeeNumber);
        this.fullName = Objects.requireNonNull(fullName);
        this.personalId = Objects.requireNonNull(personalId);
        this.phone = phone;
        this.accountNumber = accountNumber;
        this.branchId = branchId;
        this.role = Objects.requireNonNull(role);
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPersonalId() {
        return personalId;
    }

    public void setPersonalId(String personalId) {
        this.personalId = personalId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getBranchId() {
        return branchId;
    }

    public void setBranchId(String branchId) {
        this.branchId = branchId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Employee)) return false;
        Employee employee = (Employee) o;
        return employeeNumber.equals(employee.employeeNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(employeeNumber);
    }

    @Override
    public String toString() {
        return "Employee{" + employeeNumber + ", " + fullName + ", " + role + ", branch=" + branchId + "}";
    }
}
