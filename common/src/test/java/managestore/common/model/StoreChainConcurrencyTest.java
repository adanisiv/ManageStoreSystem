package managestore.common.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves {@link StoreChain#addProductIfAbsent} is genuinely atomic under real concurrent load
 * — many threads racing to add the same new SKU at once, the way two shift managers'
 * PRODUCT_ADD_REQUESTs could actually land on two different ClientHandler threads at nearly
 * the same instant.
 *
 * <p>Before {@code addProductIfAbsent} existed, {@code ClientHandler.handleProductAddRequest}
 * did this as two separate calls: {@code getProduct(sku) != null} to check, then
 * {@code addProduct(product)} to write, with nothing holding a lock across the gap between
 * them. Every thread here starts from the exact same released latch on purpose, to make that
 * gap as likely to be hit as real concurrent requests could ever make it.
 */
class StoreChainConcurrencyTest {

    private static final int RACING_THREADS = 64;

    @Test
    @Timeout(10)
    void concurrentAddsOfTheSameNewSkuOnlyOneEverWins() throws Exception {
        StoreChain storeChain = new StoreChain();
        ExecutorService pool = Executors.newFixedThreadPool(RACING_THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < RACING_THREADS; i++) {
                // Every racer builds its OWN Product instance for the same SKU (a different
                // object each time, the way each ClientHandler thread would from its own
                // request), so a false "win" from two threads sharing one object reference
                // can't happen — only the map's own atomicity can make this pass.
                Product candidate = new Product("SKU-RACE", "Racer " + i, "Tops", 10.0 + i);
                futures.add(pool.submit(() -> {
                    startGate.await();
                    if (storeChain.addProductIfAbsent(candidate)) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            // Every racer is now submitted and parked on startGate.await(). Only now do we
            // release them all at once, so as many as possible genuinely race each other
            // instead of running one after another.
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, winners.get(), "exactly one of " + RACING_THREADS
                + " concurrent adds for the same new SKU must win — the rest must be told it already exists");
    }
}
