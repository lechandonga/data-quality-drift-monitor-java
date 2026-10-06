package com.github.lechandonga.dataqualitydriftmonitorjava.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.FieldContract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.RangeRule;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitRequest;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

@SpringBootTest(properties = "dq.store.dir=target/dq-test-store")
@AutoConfigureMockMvc
@ExtendWith(BusinessTestWatcher.class)
public abstract class AbstractApiTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected Contract standardContract(String name) {
        return new Contract(name, "v1", List.of(
                new FieldContract("id", "integer", true, false, null),
                new FieldContract("name", "string", true, false, null),
                new FieldContract("age", "integer", false, false, null),
                new FieldContract("level", "string", false, true, List.of("A", "B", "C")),
                new FieldContract("event_time", "datetime", true, false, null)
        ));
    }

    protected QualityRules standardRules() {
        return new QualityRules("v1", true, 0.0,
                List.of("id"), 0.0,
                List.of(new RangeRule("age", 0.0, 120.0, 0.0)),
                0.0,
                new com.github.lechandonga.dataqualitydriftmonitorjava.model.FreshnessRule(
                        "event_time", "PT2H", 0.0));
    }

    protected SubmitRequest inline(String dataset, String format, String content) {
        return new SubmitRequest(dataset, format, null, null, content,
                null, standardContract(dataset), standardRules());
    }
}
