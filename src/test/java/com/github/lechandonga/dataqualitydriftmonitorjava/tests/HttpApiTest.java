package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.AbstractApiTest;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.BusinessCoverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@BusinessCoverage("本地 HTTP 接口：提交（CSV/JSONL）、重复提交响应、历史结论查询、管理接口")
class HttpApiTest extends AbstractApiTest {

    @Autowired
    private Store store;

    @BeforeEach
    void reset() {
        store.resetForTesting();
    }

    @Test
    @DisplayName("注册契约后提交 JSONL 批次，再通过历史接口取回同样结论；重复提交 duplicate=true")
    void submitAndQueryViaHttp() throws Exception {
        String contractJson = objectMapper.writeValueAsString(standardContract("events"));
        mockMvc.perform(post("/api/admin/contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(contractJson))
                .andExpect(status().isOk());

        String rulesJson = objectMapper.writeValueAsString(standardRules());
        mockMvc.perform(post("/api/admin/datasets/events/rules")
                        .contentType(MediaType.APPLICATION_JSON).content(rulesJson))
                .andExpect(status().isOk());

        String body = """
                {"dataset":"events","format":"jsonl",
                 "content":"{\\"id\\":1,\\"name\\":\\"a\\",\\"age\\":30,\\"level\\":\\"A\\",\\"event_time\\":\\"2026-10-06T09:30:00Z\\"}"}
                """;
        mockMvc.perform(post("/api/batches/events/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.drift.decision").value("UNDECIDABLE"))
                .andExpect(jsonPath("$.rulesSnapshot.version").value("v1"));

        mockMvc.perform(post("/api/batches/events/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        mockMvc.perform(get("/api/batches/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/admin/datasets/events/baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updateStrategy").value("EXPANDING"))
                .andExpect(jsonPath("$.batchIds.length()").value(1));
    }

    @Test
    @DisplayName("CSV 提交结构拒绝时返回 200 且带可定位的记录问题；查询不存在批次返回 404")
    void csvRejectAndNotFound() throws Exception {
        String contractJson = objectMapper.writeValueAsString(standardContract("events2"));
        mockMvc.perform(post("/api/admin/contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(contractJson))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/datasets/events2/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(standardRules())))
                .andExpect(status().isOk());

        String csv = "id,name,age,level,event_time\n"
                + "1,,30,A,2026-10-06T09:00:00Z\n";
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "format", "csv", "content", csv));
        mockMvc.perform(post("/api/batches/events2/submit")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED_STRUCTURAL"))
                .andExpect(jsonPath("$.recordIssues[0].code").value("MISSING_FIELD"))
                .andExpect(jsonPath("$.recordIssues[0].recordIndex").value(1))
                .andExpect(jsonPath("$.recordIssues[0].field").value("name"));

        mockMvc.perform(get("/api/batches/events2/not-exist"))
                .andExpect(status().isNotFound());
    }
}
