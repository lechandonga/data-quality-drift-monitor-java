package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitRequest;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.AbstractApiTest;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.BusinessCoverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@BusinessCoverage("幂等与并发：重复/并发重复提交结论一致且不重复计数；并发多批画像不丢失不覆盖")
class IdempotencyConcurrencyTest extends AbstractApiTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyConcurrencyTest.class);

    @Autowired
    private Store store;

    private BatchService service;
    private final Clock fixed = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        store.resetForTesting();
        service = new BatchService(store, objectMapper, fixed);
    }

    private String content(long id) {
        return "{\"id\":" + id + ",\"name\":\"n" + id + "\",\"age\":30,"
                + "\"level\":\"A\",\"event_time\":\"2026-10-06T09:30:00Z\"}";
    }

    private SubmitRequest request(String content) {
        return new SubmitRequest("orders", "jsonl", null, null, content,
                null, standardContract("orders"), standardRules());
    }

    @Test
    @DisplayName("同一批数据串行重复提交：返回同一 batchId，结论一致，仅计数一次")
    void sequentialDuplicate() {
        SubmitRequest req = request(content(1));
        var firstResult = service.submit(req);
        var secondResult = service.submit(req);
        var thirdResult = service.submit(req);
        BatchVerdict first = firstResult.verdict();
        BatchVerdict second = secondResult.verdict();
        BatchVerdict third = thirdResult.verdict();

        assertThat(firstResult.duplicate()).isFalse();
        assertThat(secondResult.duplicate()).isTrue();
        assertThat(thirdResult.duplicate()).isTrue();
        assertThat(first.batchId).isEqualTo(second.batchId).isEqualTo(third.batchId);
        assertThat(second.status).isEqualTo(first.status);
        assertThat(second.submittedAt).isEqualTo(first.submittedAt);
        assertThat(store.listVerdicts("orders")).hasSize(1);
        assertThat(store.getBaseline("orders").batchIds).hasSize(1);
    }

    @Test
    @DisplayName("并发重复提交同一内容：仅生成一个批次，所有调用结论一致")
    void concurrentDuplicateSubmission() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitResult> results
                = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(service.submit(request(content(7))));
                } catch (Exception e) {
                    log.error("并发提交异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(results).hasSize(threads);
        Set<String> batchIds = new java.util.HashSet<>(results.stream().map(v -> v.verdict().batchId).toList());
        assertThat(batchIds).hasSize(1);
        assertThat(results).allMatch(v -> v.verdict().status.equals("ACCEPTED"));
        long duplicates = results.stream().filter(com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitResult::duplicate).count();
        assertThat(duplicates).isEqualTo(threads - 1);
        assertThat(store.listVerdicts("orders")).hasSize(1);
        assertThat(store.getBaseline("orders").batchIds).hasSize(1);
    }

    @Test
    @DisplayName("并发提交不同批次：每批独立计数，画像/基线批次不丢失、不覆盖")
    void concurrentDistinctBatches() throws Exception {
        int batches = 24;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(batches);
        List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 1; i <= batches; i++) {
            long id = i;
            pool.submit(() -> {
                try {
                    start.await();
                    var v = service.submit(request(content(id))).verdict();
                    if (!"ACCEPTED".equals(v.status)) {
                        errors.add("批次 " + id + " 状态异常: " + v.status);
                    }
                } catch (Exception e) {
                    errors.add("批次 " + id + " 异常: " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(store.listVerdicts("orders")).hasSize(batches);
        assertThat(store.getBaseline("orders").batchIds).hasSize(batches);
        long baselineRows = store.getBaseline("orders").profile.get("id").totalCount;
        assertThat(baselineRows).isEqualTo(batches);
    }
}
