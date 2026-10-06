package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.BatchParser;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityCheckConfig;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchReport;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchStore;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务覆盖：历史复现 —— 规则/阈值调整后，已有批次的历史结论不被改写，
 * 且重放（按 batchId 查询）仍返回当时的判定与当时的规则版本。
 */
@SpringBootTest
class RuleChangeHistoryTests {

    private static final Logger log = LoggerFactory.getLogger(RuleChangeHistoryTests.class);

    @Autowired
    BatchService batchService;

    @Autowired
    BatchStore batchStore;

    @Autowired
    RuleService ruleService;

    @AfterEach
    void restoreDefaults() {
        DefaultRules.restore(ruleService);
    }

    @Test
    void ruleChangeDoesNotRewriteHistoricalVerdicts() {
        log.info("[历史复现] 用例：阈值收紧后，历史批次结论与规则版本保持不变");
        byte[] payload = ("id,amount,category,event_time\n"
                + "id-0,500.0,a," + Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);

        ruleService.updateRules(List.of(
                QualityCheckConfig.range("amount", 0, 10000, 0.0)));
        String oldRuleVersion = ruleService.activeRuleSet().version();
        BatchReport historical = batchService.submit("hist-1", payload, BatchParser.Format.CSV);
        assertThat(historical.verdict()).isEqualTo(BatchVerdict.PASS);
        assertThat(historical.ruleVersion()).isEqualTo(oldRuleVersion);

        ruleService.updateRules(List.of(
                QualityCheckConfig.range("amount", 0, 100, 0.0)));
        String newRuleVersion = ruleService.activeRuleSet().version();
        assertThat(newRuleVersion).isNotEqualTo(oldRuleVersion);

        BatchReport replayed = batchStore.find("hist-1").orElseThrow();
        assertThat(replayed.verdict()).isEqualTo(BatchVerdict.PASS);
        assertThat(replayed.ruleVersion()).isEqualTo(oldRuleVersion);

        BatchReport fresh = batchService.submit("hist-2", payload, BatchParser.Format.CSV);
        assertThat(fresh.verdict()).isEqualTo(BatchVerdict.FAIL);
        assertThat(fresh.ruleVersion()).isEqualTo(newRuleVersion);
        log.info("[历史复现] 通过：hist-1 保持 PASS@{}，hist-2 按新规则 FAIL@{}",
                oldRuleVersion, newRuleVersion);
    }
}
