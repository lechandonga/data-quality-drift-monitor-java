package com.github.lechandonga.dataqualitydriftmonitorjava.tests;

import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityCheckConfig;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService;

import java.util.List;

/** Restores the default rule set so test classes sharing a Spring context do not interfere. */
final class DefaultRules {

    private DefaultRules() {
    }

    static void restore(RuleService ruleService) {
        ruleService.updateRules(List.of(
                new QualityCheckConfig(com.github.lechandonga.dataqualitydriftmonitorjava.quality.CheckType.COMPLETENESS,
                        true, null, 0.0, null, null, null, null, null),
                QualityCheckConfig.uniqueness("id", 0.0),
                QualityCheckConfig.range("amount", 0, 10000, 0.0),
                QualityCheckConfig.freshness("event_time", 86400)));
    }
}
