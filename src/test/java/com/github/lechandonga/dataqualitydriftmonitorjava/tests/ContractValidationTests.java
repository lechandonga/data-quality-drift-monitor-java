package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.RejectionReason;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchReport;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchVerdict;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务覆盖：结构校验 —— 畸形数据、字段缺失、类型不符、多余字段、编码异常，
 * 均须给出可区分且能定位记录的拒绝原因。
 */
@SpringBootTest
class ContractValidationTests {

    private static final Logger log = LoggerFactory.getLogger(ContractValidationTests.class);

    @Autowired
    BatchService batchService;

    @Test
    void csvWithMissingRequiredFieldIsRejectedWithLocation() {
        log.info("[结构校验] 用例：CSV 缺失必填字段 amount，期望 MISSING_FIELD 且定位到 record#2");
        byte[] payload = ("id,amount,category,event_time\n"
                + "id-0,1.5,a," + Instant.now() + "\n"
                + "id-1,,a," + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);
        BatchReport report = batchService.submit("ct-missing", payload,
                com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser.Format.CSV);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.REJECTED);
        assertThat(report.rejections()).anySatisfy(r -> {
            assertThat(r.reason()).isEqualTo(RejectionReason.MISSING_FIELD);
            assertThat(r.recordIndex()).isEqualTo(2);
            assertThat(r.field()).isEqualTo("amount");
        });
        log.info("[结构校验] 通过：{}", report.rejections());
    }

    @Test
    void csvWithTypeMismatchIsRejected() {
        log.info("[结构校验] 用例：amount 列出现非数值，期望 TYPE_MISMATCH");
        byte[] payload = ("id,amount,category,event_time\n"
                + "id-0,not-a-number,a," + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);
        BatchReport report = batchService.submit("ct-type", payload,
                com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser.Format.CSV);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.REJECTED);
        assertThat(report.rejections()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(RejectionReason.TYPE_MISMATCH));
        log.info("[结构校验] 通过：{}", report.rejections());
    }

    @Test
    void csvWithUnexpectedHeaderColumnIsRejected() {
        log.info("[结构校验] 用例：CSV 表头包含契约外列，期望 UNEXPECTED_FIELD");
        byte[] payload = ("id,amount,category,event_time,hacker\n"
                + "id-0,1.0,a," + Instant.now() + ",x\n").getBytes(StandardCharsets.UTF_8);
        BatchReport report = batchService.submit("ct-extra", payload,
                com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser.Format.CSV);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.REJECTED);
        assertThat(report.rejections()).anySatisfy(r -> {
            assertThat(r.reason()).isEqualTo(RejectionReason.UNEXPECTED_FIELD);
            assertThat(r.field()).isEqualTo("hacker");
        });
        log.info("[结构校验] 通过：{}", report.rejections());
    }

    @Test
    void jsonlWithExtraFieldAndMalformedLineIsRejected() {
        log.info("[结构校验] 用例：JSONL 含多余字段与畸形行，期望 UNEXPECTED_FIELD 与 MALFORMED_RECORD");
        byte[] payload = ("{\"id\":\"a\",\"amount\":1.0,\"event_time\":\"" + Instant.now() + "\",\"zzz\":1}\n"
                + "{not json}\n").getBytes(StandardCharsets.UTF_8);
        BatchReport report = batchService.submit("ct-jsonl", payload,
                com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser.Format.JSONL);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.REJECTED);
        assertThat(report.rejections()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(RejectionReason.UNEXPECTED_FIELD));
        assertThat(report.rejections()).anySatisfy(r -> {
            assertThat(r.reason()).isEqualTo(RejectionReason.MALFORMED_RECORD);
            assertThat(r.recordIndex()).isEqualTo(2);
        });
        log.info("[结构校验] 通过：{}", report.rejections());
    }

    @Test
    void invalidUtf8IsRejectedAsEncodingError() {
        log.info("[结构校验] 用例：非 UTF-8 字节，期望 ENCODING_ERROR");
        byte[] payload = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x61};
        BatchReport report = batchService.submit("ct-encoding", payload,
                com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser.Format.CSV);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.REJECTED);
        assertThat(report.rejections()).anySatisfy(r ->
                assertThat(r.reason()).isEqualTo(RejectionReason.ENCODING_ERROR));
        log.info("[结构校验] 通过：{}", report.rejections());
    }

    @Test
    void validJsonlBatchPasses() {
        log.info("[结构校验] 用例：合法 JSONL 批次，期望 PASS");
        BatchReport report = batchService.submit("ct-valid-jsonl", TestData.jsonl(10, 100.0),
                com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser.Format.JSONL);
        assertThat(report.verdict()).isEqualTo(BatchVerdict.PASS);
        assertThat(report.acceptedRecords()).isEqualTo(10);
        log.info("[结构校验] 通过：verdict={} accepted={}", report.verdict(), report.acceptedRecords());
    }
}
