package managestore.common.exception;

/**
 * An admin tried to register an employee number that is already in use.
 *
 * <p>Employee numbers are the identity employees log in and are logged against, so a
 * duplicate is refused rather than silently overwriting the existing record.
 */
public class DuplicateEmployeeException extends InvalidRequestException {

    private static final long serialVersionUID = 1L;

    private final String employeeNumber;

    public DuplicateEmployeeException(String employeeNumber) {
        super("Employee number already exists: " + employeeNumber);
        this.employeeNumber = employeeNumber;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }
}
