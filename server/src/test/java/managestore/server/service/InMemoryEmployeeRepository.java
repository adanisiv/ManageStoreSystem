package managestore.server.service;

import managestore.common.model.Employee;
import managestore.server.repository.EmployeeRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryEmployeeRepository implements EmployeeRepository {

    private final Map<String, Employee> byEmployeeNumber = new HashMap<>();

    @Override
    public Optional<Employee> findByEmployeeNumber(String employeeNumber) {
        return Optional.ofNullable(byEmployeeNumber.get(employeeNumber));
    }

    @Override
    public List<Employee> findAll() {
        return new java.util.ArrayList<>(byEmployeeNumber.values());
    }

    @Override
    public void save(Employee employee) {
        byEmployeeNumber.put(employee.getEmployeeNumber(), employee);
    }

    @Override
    public void delete(String employeeNumber) {
        byEmployeeNumber.remove(employeeNumber);
    }
}
