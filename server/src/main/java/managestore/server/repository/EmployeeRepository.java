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

    /**
     * Saves the employee only if no record with their number already exists — the check and
     * the save happen as one atomic step, not two separate calls a concurrent request could
     * interleave with. Use this (not a separate find-then-save) whenever "this number must be
     * new" matters, which is every caller that ever rejects a duplicate.
     *
     * @return true if saved; false if an employee with that number already existed (nothing changed).
     */
    boolean saveIfAbsent(Employee employee);

    /** Does nothing if no employee with that number exists. */
    void delete(String employeeNumber);
}
