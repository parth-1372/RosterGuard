package com.rosterguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rosterguard.PostgresIntegrationTest;
import com.rosterguard.domain.HrEvent;
import com.rosterguard.domain.HrEventType;
import com.rosterguard.domain.HrProfile;
import com.rosterguard.repository.EmployeeRecord;
import com.rosterguard.repository.EmployeeRepository;
import com.rosterguard.service.EventResult.Outcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Concurrency evidence: the atomic conditional upsert never lets an older version win. */
class EmployeeConcurrencyIT extends PostgresIntegrationTest {

    @Autowired
    EmployeeSyncService sync;
    @Autowired
    TransportFieldService transport;
    @Autowired
    EmployeeRepository employees;

    private static HrEvent version(int v) {
        return new HrEvent(HrEventType.UPDATE, "E1", (long) v, null,
                new HrProfile("name-" + v, "e1@example.test", "+919800000001", "M1", "BLR", "S" + v));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    @Test
    void shuffledVersionsRacingOnANewEmployeeAlwaysEndAtTheHighestVersion() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM employee WHERE tenant_id = ?", T1);
            List<Integer> order = new ArrayList<>();
            for (int v = 1; v <= 40; v++) {
                order.add(v);
            }
            Collections.shuffle(order, new Random(round)); // deterministic per round

            List<Callable<EventResult>> tasks = new ArrayList<>();
            for (int v : order) {
                tasks.add(() -> sync.process(T1, version(v)));
            }
            List<EventResult> results = runConcurrently(tasks, 16);

            assertThat(results).extracting(EventResult::outcome)
                    .doesNotContain(Outcome.ERROR, Outcome.REJECTED, Outcome.CONFLICT_HELD);
            EmployeeRecord e = employees.find(T1, "E1").orElseThrow();
            assertThat(e.sourceVersion()).isEqualTo(40);
            assertThat(e.name()).isEqualTo("name-40");
            assertThat(e.shiftCode()).isEqualTo("S40");
            assertThat(employees.countReviews(T1, "E1")).isZero();
        }
    }

    @Test
    void concurrentHrAndTransportUpdatesDoNotClobberEachOther() throws Exception {
        sync.process(T1, version(1));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int v = 2; v <= 21; v++) {
            int ver = v;
            tasks.add(() -> sync.process(T1, version(ver)));
            tasks.add(() -> {
                transport.update(T1, "E1", "Gate " + ver, "Assist " + ver);
                return null;
            });
        }
        runConcurrently(tasks, 16);

        EmployeeRecord e = employees.find(T1, "E1").orElseThrow();
        assertThat(e.sourceVersion()).isEqualTo(21);           // HR side: highest wins
        assertThat(e.name()).isEqualTo("name-21");
        assertThat(e.transportVersion()).isEqualTo(20);        // every transport edit counted once
        assertThat(e.pickupPoint()).startsWith("Gate ");       // transport data survived HR writes
        assertThat(e.specialAssistance()).startsWith("Assist ");
    }
}
