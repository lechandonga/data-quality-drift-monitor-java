package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.SubmitRequest;
import com.github.lechandonga.dataqualitydriftmonitorjava.store.Store;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.AbstractApiTest;
import com.github.lechandonga.dataqualitydriftmonitorjava.tests.support.BusinessCoverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

@BusinessCoverage("畸形数据结构校验：缺失字段/类型不符/多余字段/编码异常/语法错误均可区分并定位记录")
class MalformedDataTest extends AbstractApiTest {

    @Autowired
    private Store store;

    @BeforeEach
    void reset() {
        store.resetForTesting();
    }

    private BatchVerdict submit(String format, String content) {
        return submitBase64(format, content == null ? null : content.getBytes(StandardCharsets.UTF_8));
    }

    private BatchVerdict submitBase64(String format, byte[] bytes) {
        SubmitRequest req = new SubmitRequest("orders", format, null,
                Base64.getEncoder().encodeToString(bytes), null, null,
                standardContract("orders"), standardRules());
        return new com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService(
                store, objectMapper, java.time.Clock.systemUTC()).submit(req).verdict();
    }

    @Test
    @DisplayName("CSV 记录级缺值：必填列为空时 MISSING_FIELD 定位到具体记录与字段")
    void csvMissingAndUnknownFields() {
        String csv = "id,name,age,level,event_time\n"
                + "1,alice,30,A,2026-10-06T09:00:00Z\n"
                + "2,,31,A,2026-10-06T09:00:00Z\n";
        BatchVerdict v = submit("csv", csv);
        assertThat(v.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v.recordIssues).extracting(r -> r.code())
                .contains("MISSING_FIELD");
        assertThat(v.recordIssues).anySatisfy(r -> {
            assertThat(r.recordIndex()).isEqualTo(2);
            assertThat(r.field()).isEqualTo("name");
        });
    }

    @Test
    @DisplayName("类型不符：age 非数字、datetime 非法，报 TYPE_MISMATCH 且可定位行号")
    void typeMismatch() {
        String jsonl = """
                {"id":1,"name":"a","age":"abc","event_time":"2026-10-06T09:00:00Z"}
                {"id":2,"name":"b","age":30,"event_time":"not-a-date"}
                """;
        BatchVerdict v = submit("jsonl", jsonl);
        assertThat(v.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v.recordIssues).filteredOn(r -> r.code().equals("TYPE_MISMATCH"))
                .hasSize(2)
                .extracting(r -> r.recordIndex())
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("JSON 严格类型：30.5 不能写入 integer 列")
    void jsonStrictIntegerType() {
        String jsonl = "{\"id\":1.5,\"name\":\"a\",\"age\":30.5,\"event_time\":\"2026-10-06T09:00:00Z\"}";
        BatchVerdict v = submit("jsonl", jsonl);
        assertThat(v.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v.recordIssues).anyMatch(r -> r.code().equals("TYPE_MISMATCH") && r.field().equals("id"));
    }

    @Test
    @DisplayName("枚举越界与空值约束：VALUE_NOT_ALLOWED / NULL_NOT_ALLOWED 可区分")
    void enumAndNullViolations() {
        String jsonl = """
                {"id":1,"name":null,"age":30,"level":"Z","event_time":"2026-10-06T09:00:00Z"}
                {"id":2,"name":"b","age":null,"level":"A","event_time":"2026-10-06T09:00:00Z"}
                """;
        BatchVerdict v = submit("jsonl", jsonl);
        assertThat(v.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v.recordIssues).extracting(r -> r.code())
                .contains("NULL_NOT_ALLOWED", "VALUE_NOT_ALLOWED");
        assertThat(v.recordIssues).anyMatch(r -> r.recordIndex() == 1 && "level".equals(r.field()));
    }

    @Test
    @DisplayName("编码异常：非法 UTF-8 字节产生 ENCODING_ERROR 且带行号，不静默替换")
    void encodingError() {
        byte[] withInvalidUtf8 = new byte[]{
                'i', 'd', ',', 'n', 'a', 'm', 'e', '\n',
                '1', ',', (byte) 0xFF, (byte) 0xFE, '\n'
        };
        BatchVerdict v = submitBase64("csv", withInvalidUtf8);
        assertThat(v.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v.structuralIssues).isNotEmpty();
        assertThat(v.structuralIssues.get(0).code()).isEqualTo("ENCODING_ERROR");
        assertThat(v.structuralIssues.get(0).line()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CSV 列数不一致与未闭合引号：MALFORMED_CSV 结构级拒绝")
    void malformedCsv() {
        String ragged = "id,name,event_time\n1,a\n2,b,2026-10-06T09:00:00Z,extra\n";
        BatchVerdict v = submit("csv", ragged);
        assertThat(v.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v.structuralIssues).anyMatch(i -> i.code().equals("MALFORMED_CSV"));

        String unclosedQuote = "id,name,event_time\n1,\"alice,2026-10-06T09:00:00Z\n";
        BatchVerdict v2 = submit("csv", unclosedQuote);
        assertThat(v2.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v2.structuralIssues).anyMatch(i -> i.code().equals("MALFORMED_CSV"));
    }

    @Test
    @DisplayName("JSONL 语法错误与非对象行：MALFORMED_JSON 带行号；空数据被拒绝")
    void malformedJsonAndEmpty() {
        String bad = "{\"id\":1}\nnot-json\n[1,2,3]\n";
        BatchVerdict v = submit("jsonl", bad);
        assertThat(v.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(v.structuralIssues).hasSizeGreaterThanOrEqualTo(2);
        assertThat(v.structuralIssues).allMatch(i -> i.code().equals("MALFORMED_JSON"));
        assertThat(v.structuralIssues).extracting(i -> i.line()).contains(2, 3);

        BatchVerdict empty = submit("jsonl", "");
        assertThat(empty.status).isEqualTo("REJECTED_STRUCTURAL");
        assertThat(empty.structuralIssues).anyMatch(i -> i.code().equals("EMPTY_DATA"));
    }
}
