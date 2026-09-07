package managestore.server.service;

import managestore.common.model.Employee;
import managestore.server.repository.EmployeeRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trivial in-memory stand-in so service tests don't need a real file. Backed by a
 * ConcurrentHashMap (not a plain HashMap) so this double is also safe to exercise from
 * concurrency tests, the same way the real {@link JsonFileEmployeeRepository} is.
 */
public class InMemoryEmployeeRepository implements EmployeeRepository {

    private final Map<String, Employee> byEmployeeNumber = new ConcurrentHashMap<>();

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
    public boolean saveIfAbsent(Employee employee) {
        // putIfAbsent is the ConcurrentHashMap's own atomic check-and-set, so no separate lock
        // is needed here the way JsonFileEmployeeRepository needs one to also guard persist().
        return byEmployeeNumber.putIfAbsent(employee.getEmployeeNumber(), employee) == null;
    }

    @Override
    public void delete(String employeeNumber) {
        byEmployeeNumber.remove(employeeNumber);
    }
}
