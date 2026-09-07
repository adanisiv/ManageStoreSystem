package managestore.server.service;

import managestore.common.model.Employee;
import managestore.common.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves {@link AuthService#createAccount} is safe when two admins (or one admin double
 * clicking, or a retried request) submit for the same username or the same employee number
 * at close to the same instant — which two different ClientHandler threads make a real
 * possibility, not just a theoretical one.
 *
 * <p>The old code checked {@code accountRepository.findByUsername(username).isPresent()} (or,
 * separately in {@code ClientHandler}, {@code employeeRepository.findByEmployeeNumber(...)})
 * and only wrote afterward, as two separate calls with no lock held across the gap between
 * them. Racing many threads released from one shared latch at once — the same technique used
 * for the equivalent StoreChain/product-catalog race — is what would have exposed that gap.
 */
class AuthServiceConcurrencyTest {

    private static final int RACING_THREADS = 32;

    private InMemoryAccountRepository accountRepository;
    private InMemoryEmployeeRepository employeeRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        accountRepository = new InMemoryAccountRepository();
        employeeRepository = new InMemoryEmployeeRepository();
        authService = new AuthService(accountRepository, employeeRepository);
    }

    @Test
    @Timeout(10)
    void concurrentCreateAccountsForTheSameUsernameOnlyOneEverWins() throws Exception {
        // Every racer has its own, otherwise-unique employee number, so employee-number
        // uniqueness can never be what blocks a racer — only the shared username can.
        AtomicInteger winners = race(RACING_THREADS, i -> employee("E" + i), i -> "sharedUsername");

        assertEquals(1, winners.get(), "exactly one concurrent createAccount for the same username must win");
        // The whole point of the rollback in createAccount is that a rejected username doesn't
        // strand an orphaned employee record with no login account attached to it.
        assertEquals(1, employeeRepository.findAll().size(),
                "every rejected attempt's employee record must be rolled back, not left orphaned");
    }

    @Test
    @Timeout(10)
    void concurrentCreateAccountsForTheSameEmployeeNumberOnlyOneEverWins() throws Exception {
        // Every racer has its own, otherwise-unique username, so only the shared employee
        // number can be what blocks a racer.
        AtomicInteger winners = race(RACING_THREADS, i -> employee("SHARED-E1"), i -> "user" + i);

        assertEquals(1, winners.get(), "exactly one concurrent createAccount for the same employee number must win");
    }

    private AtomicInteger race(int threadCount, IntFunction<Employee> employeeForRacer,
                                IntFunction<String> usernameForRacer) throws Exception {
        AtomicInteger winners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                Employee racerEmployee = employeeForRacer.apply(i);
                String racerUsername = usernameForRacer.apply(i);
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        authService.createAccount(racerEmployee, racerUsername, "secret123");
                        winners.incrementAndGet();
                    } catch (IllegalArgumentException expectedForLosers) {
                        // DuplicateUsernameException or DuplicateEmployeeException — the expected
                        // outcome for every racer except the one that actually wins.
                    }
                    return null;
                }));
            }
            // Every racer is now submitted and parked on startGate.await(). Only now release
            // them all at once, so as many as possible genuinely race each other.
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        return winners;
    }

    private static Employee employee(String number) {
        return new Employee(number, "Racer " + number, "id-" + number, "050-0", "acc-" + number, "BRANCH-1", Role.SELLER);
    }
}
