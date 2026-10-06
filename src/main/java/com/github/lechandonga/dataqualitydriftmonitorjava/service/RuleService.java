package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityCheckConfig;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.RuleSet;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Holds the currently active {@link RuleSet}. Replacing the rule set creates
 * a new version; reports already stored keep the version they were computed
 * with and are never re-evaluated.
 */
@Service
public class RuleService {

    private volatile RuleSet activeRuleSet;

    public synchronized void updateRules(List<QualityCheckConfig> checks) {
        this.activeRuleSet = new RuleSet(checks);
    }

    public RuleSet activeRuleSet() {
        return activeRuleSet;
    }
}
