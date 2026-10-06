package com.github.lechandonga.dataqualitydriftmonitorjava.config;

import com.github.lechandonga.dataqualitydriftmonitorjava.contract.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.contract.DataType;
import com.github.lechandonga.dataqualitydriftmonitorjava.contract.FieldDefinition;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftEvaluator;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.CheckType;
import com.github.lechandonga.dataqualitydriftmonitorjava.quality.QualityCheckConfig;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.ProfileService;
import com.github.lechandonga.dataqualitydriftmonitorjava.service.RuleService;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(DataQualityProperties.class)
public class DataQualityConfig {

    private final DataQualityProperties properties;
    private final RuleService ruleService;
    private final ProfileService profileService;

    public DataQualityConfig(DataQualityProperties properties, RuleService ruleService,
                             ProfileService profileService) {
        this.properties = properties;
        this.ruleService = ruleService;
        this.profileService = profileService;
    }

    @Bean
    public Contract contract() {
        List<FieldDefinition> fields = properties.getContract().getFields().stream()
                .map(f -> new FieldDefinition(f.getName(), DataType.valueOf(f.getType()), f.isRequired()))
                .toList();
        return new Contract(properties.getContract().getVersion(), fields);
    }

    @PostConstruct
    public void init() {
        List<QualityCheckConfig> checks = properties.getChecks().stream()
                .map(c -> new QualityCheckConfig(
                        CheckType.valueOf(c.getType()), c.isEnabled(), c.getField(),
                        c.getMaxMissingRatio(), c.getMaxDuplicateRatio(), c.getMin(), c.getMax(),
                        c.getMaxOutlierRatio(), c.getMaxAgeSeconds()))
                .toList();
        ruleService.updateRules(checks);
        DataQualityProperties.DriftProps drift = properties.getDrift();
        profileService.configure(drift.getBaselineStrategy(), new DriftEvaluator(
                drift.getMinSamples(), drift.getNumericShiftThreshold(), drift.getPsiThreshold()));
    }
}
