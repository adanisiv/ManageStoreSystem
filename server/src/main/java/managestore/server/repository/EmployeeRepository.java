package managestore.server.repository;

import managestore.common.model.Employee;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository {

    // Looks up a single employee by their unique employee number; empty if none exists.
    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    // Returns every stored employee.
    List<Employee> findAll();

    // Upsert: creates the employee if their number is new, otherwise overwrites the existing record.
    void save(Employee employee);

    /** No-op if no employee with that number exists. */
    void delete(String employeeNumber);
}
