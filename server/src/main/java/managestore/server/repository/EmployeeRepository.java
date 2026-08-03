package managestore.server.repository;

import managestore.common.model.Employee;

import java.util.List;
import java.util.Optional;

public interface EmployeeRepository {

    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    List<Employee> findAll();

    void save(Employee employee);
}
