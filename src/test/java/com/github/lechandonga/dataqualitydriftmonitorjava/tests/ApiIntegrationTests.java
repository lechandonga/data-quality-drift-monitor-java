package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 业务覆盖：本地接口 —— CSV/JSONL 提交、历史报告查询、规则热更新。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTests {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTests.class);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService ruleService;

    @AfterEach
    void restoreDefaults() {
        DefaultRules.restore(ruleService);
    }

    @Test
    void submitCsvQueryReportAndUpdateRulesViaApi() throws Exception {
        log.info("[本地接口] 用例：CSV 提交 -> 查询历史报告 -> 更新规则 -> 同数据新批次 FAIL");
        String csv = "id,amount,category,event_time\napi-0,500.0,a," + Instant.now() + "\n";
        mockMvc.perform(post("/api/batches?format=csv")
                        .header("X-Batch-Id", "api-1")
                        .contentType("text/csv")
                        .content(csv.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("PASS"));

        mockMvc.perform(get("/api/batches/api-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("PASS"));

        String newRules = "[{\"type\":\"RANGE\",\"enabled\":true,\"field\":\"amount\","
                + "\"min\":0.0,\"max\":100.0,\"maxOutlierRatio\":0.0}]";
        mockMvc.perform(post("/api/rules").contentType("application/json").content(newRules))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/batches?format=csv")
                        .header("X-Batch-Id", "api-2")
                        .contentType("text/csv")
                        .content(csv.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("FAIL"));

        mockMvc.perform(get("/api/batches/api-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("PASS"));
        log.info("[本地接口] 通过：历史报告在规则变更后仍为 PASS，新批次按新规则 FAIL");
    }

    @Test
    void rejectedPayloadReturns422WithReasons() throws Exception {
        log.info("[本地接口] 用例：提交畸形 JSONL，期望 422 且带可区分拒绝原因");
        mockMvc.perform(post("/api/batches?format=jsonl")
                        .header("X-Batch-Id", "api-bad")
                        .contentType("application/x-ndjson")
                        .content("{broken}\n".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.verdict").value("REJECTED"))
                .andExpect(jsonPath("$.rejections[0].reason").value("MALFORMED_RECORD"));
        log.info("[本地接口] 通过：422 + MALFORMED_RECORD");
    }
}
