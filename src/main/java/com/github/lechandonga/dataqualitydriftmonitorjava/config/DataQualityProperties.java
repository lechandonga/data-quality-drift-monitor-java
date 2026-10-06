package com.github.lechandonga.dataqualitydriftmonitorjava.config;

import com.github.lechandonga.dataqualitydriftmonitorjava.drift.BaselineStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Externalized configuration. Example:
 *
 * <pre>
 * dq.contract.version=v1
 * dq.contract.fields[0].name=id
 * dq.contract.fields[0].type=STRING
 * dq.contract.fields[0].required=true
 * dq.checks[0].type=RANGE
 * dq.checks[0].enabled=true
 * dq.checks[0].field=amount
 * dq.checks[0].min=0
 * dq.checks[0].max=10000
 * dq.checks[0].max-outlier-ratio=0.0
 * dq.drift.min-samples=20
 * dq.drift.baseline-strategy=ON_PASS
 * </pre>
 */
@ConfigurationProperties(prefix = "dq")
public class DataQualityProperties {

    private final ContractProps contract = new ContractProps();
    private final DriftProps drift = new DriftProps();
    private List<CheckProps> checks = new ArrayList<>();

    public ContractProps getContract() {
        return contract;
    }

    public DriftProps getDrift() {
        return drift;
    }

    public List<CheckProps> getChecks() {
        return checks;
    }

    public void setChecks(List<CheckProps> checks) {
        this.checks = checks;
    }

    public static class ContractProps {
        private String version = "v1";
        private List<FieldProps> fields = new ArrayList<>();

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public List<FieldProps> getFields() {
            return fields;
        }

        public void setFields(List<FieldProps> fields) {
            this.fields = fields;
        }
    }

    public static class FieldProps {
        private String name;
        private String type;
        private boolean required = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    public static class CheckProps {
        private String type;
        private boolean enabled = true;
        private String field;
        private Double maxMissingRatio;
        private Double maxDuplicateRatio;
        private Double min;
        private Double max;
        private Double maxOutlierRatio;
        private Long maxAgeSeconds;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public Double getMaxMissingRatio() {
            return maxMissingRatio;
        }

        public void setMaxMissingRatio(Double maxMissingRatio) {
            this.maxMissingRatio = maxMissingRatio;
        }

        public Double getMaxDuplicateRatio() {
            return maxDuplicateRatio;
        }

        public void setMaxDuplicateRatio(Double maxDuplicateRatio) {
            this.maxDuplicateRatio = maxDuplicateRatio;
        }

        public Double getMin() {
            return min;
        }

        public void setMin(Double min) {
            this.min = min;
        }

        public Double getMax() {
            return max;
        }

        public void setMax(Double max) {
            this.max = max;
        }

        public Double getMaxOutlierRatio() {
            return maxOutlierRatio;
        }

        public void setMaxOutlierRatio(Double maxOutlierRatio) {
            this.maxOutlierRatio = maxOutlierRatio;
        }

        public Long getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(Long maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }

    public static class DriftProps {
        /** Baseline samples below this => drift is INDETERMINATE. */
        private long minSamples = 20;
        /** Numeric mean-shift threshold, in baseline standard deviations. */
        private double numericShiftThreshold = 3.0;
        /** Categorical PSI threshold. */
        private double psiThreshold = 0.25;
        private BaselineStrategy baselineStrategy = BaselineStrategy.ON_PASS;

        public long getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(long minSamples) {
            this.minSamples = minSamples;
        }

        public double getNumericShiftThreshold() {
            return numericShiftThreshold;
        }

        public void setNumericShiftThreshold(double numericShiftThreshold) {
            this.numericShiftThreshold = numericShiftThreshold;
        }

        public double getPsiThreshold() {
            return psiThreshold;
        }

        public void setPsiThreshold(double psiThreshold) {
            this.psiThreshold = psiThreshold;
        }

        public BaselineStrategy getBaselineStrategy() {
            return baselineStrategy;
        }

        public void setBaselineStrategy(BaselineStrategy baselineStrategy) {
            this.baselineStrategy = baselineStrategy;
        }
    }
}
