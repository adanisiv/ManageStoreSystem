package managestore.server.repository;

import managestore.common.model.Employee;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository {

    // Looks up a single employee by their unique employee number. Returns empty if none exists.
    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    // Returns every stored employee.
    List<Employee> findAll();

    // Saves the employee. If their number is new, this creates a new record.
    // If the number already exists, this overwrites that record.
    void save(Employee employee);

    /** Does nothing if no employee with that number exists. */
    void delete(String employeeNumber);
}
