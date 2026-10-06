package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.AdminService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitRequest;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.AbstractApiTest;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.BusinessCoverage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@BusinessCoverage("本地持久化与跨重启复现：历史结论、规则版本与基线从 JSON 文件恢复后仍一致")
class PersistenceReproductionTest extends AbstractApiTest {

    @Autowired
    private Store store;
    @Autowired
    private AdminService adminService;

    private final Clock fixed = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);

    private String row(long id) {
        return "{\"id\":" + id + ",\"name\":\"user\",\"age\":30,\"level\":\"A\","
                + "\"event_time\":\"2026-10-06T09:30:00Z\"}";
    }

    @Test
    @DisplayName("新 Store 从磁盘重新加载后，历史批次结论、规则快照与基线批次完全一致")
    void reloadFromDiskReproducesHistory() {
        store.resetForTesting();
        BatchService service1 = new BatchService(store, objectMapper, fixed);
        adminService.registerContract(standardContract("persist"));
        adminService.registerRules("persist", standardRules());

        BatchVerdict v1 = service1.submit(new SubmitRequest("persist", "jsonl", null,
                null, row(1), null, null, null)).verdict();
        BatchVerdict v2 = service1.submit(new SubmitRequest("persist", "jsonl", null,
                null, row(1) + "\n" + row(2), null, null, null)).verdict();
        assertThat(v1.status).isEqualTo("ACCEPTED");
        assertThat(v2.status).isEqualTo("ACCEPTED");

        Store reloaded = new Store(objectMapper, "target/dq-test-store");
        reloaded.init();

        BatchVerdict restored1 = reloaded.findVerdict("persist", v1.batchId);
        assertThat(restored1).isNotNull();
        assertThat(restored1.status).isEqualTo("ACCEPTED");
        assertThat(restored1.rulesVersion).isEqualTo("v1");
        assertThat(restored1.rulesSnapshot.rangeRules().get(0).max()).isEqualTo(120.0);
        assertThat(restored1.contractSnapshot.fields()).hasSize(5);

        assertThat(reloaded.getBaseline("persist").batchIds)
                .containsExactly(v1.batchId, v2.batchId);
        assertThat(reloaded.getBaseline("persist").profile.get("id").totalCount).isEqualTo(3);

        BatchService service2 = new BatchService(reloaded, objectMapper, fixed);
        BatchVerdict duplicate = service2.submit(new SubmitRequest("persist", "jsonl", null,
                null, row(1), null, null, null)).verdict();
        assertThat(duplicate.batchId).isEqualTo(v1.batchId);
        assertThat(reloaded.listVerdicts("persist")).hasSize(2);
    }
}
