package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.github.lechandonga.dataqualitydriftmonitorjava.contract.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.contract.DataType;
import com.github.lechandonga.dataqualitydriftmonitorjava.contract.FieldDefinition;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.BaselineStrategy;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.ColumnProfile;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftEvaluator;
import com.github.lechandonga.dataqualitydriftmonitorjava.drift.DriftResult;
import com.github.lechandonga.dataqualitydriftmonitorjava.ingest.ParsedRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains per-column baseline profiles and evaluates drift for incoming
 * batches. Baseline updates are serialized through a lock and controlled by
 * a {@link BaselineStrategy}, so concurrent submissions can neither lose
 * statistics nor race the baseline update.
 */
@Service
public class ProfileService {

    private final Map<String, ColumnProfile> baselines = new ConcurrentHashMap<>();
    private final Object baselineLock = new Object();
    private volatile BaselineStrategy strategy;
    private volatile DriftEvaluator evaluator;

    public void configure(BaselineStrategy strategy, DriftEvaluator evaluator) {
        this.strategy = strategy;
        this.evaluator = evaluator;
    }

    public BaselineStrategy strategy() {
        return strategy;
    }

    /** Build the profile of a single batch for a column. */
    public ColumnProfile profileOf(String column, List<ParsedRecord> records, DataType type) {
        ColumnProfile profile = new ColumnProfile();
        for (ParsedRecord record : records) {
            Object value = record.values().get(column);
            if (value == null) {
                continue;
            }
            if (type == DataType.INTEGER || type == DataType.DOUBLE) {
                profile.addNumeric(((Number) value).doubleValue());
            } else {
                profile.addCategorical(String.valueOf(value));
            }
        }
        return profile;
    }

    /**
     * Evaluate drift of the batch against a consistent snapshot of the
     * baseline, then apply the configured baseline update strategy. The whole
     * evaluate+update cycle is atomic with respect to other batches.
     */
    public List<DriftResult> evaluateAndMaybeUpdate(Contract contract, List<ParsedRecord> records,
                                                    BatchVerdict verdict) {
        List<DriftResult> results = new ArrayList<>();
        synchronized (baselineLock) {
            List<ColumnProfile> batchProfiles = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            for (FieldDefinition field : contract.fields()) {
                if (field.type() == DataType.TIMESTAMP) {
                    continue;
                }
                ColumnProfile batchProfile = profileOf(field.name(), records, field.type());
                batchProfiles.add(batchProfile);
                columns.add(field.name());
                ColumnProfile baseline = baselines.get(field.name());
                results.add(evaluator.evaluate(field.name(),
                        baseline == null ? null : baseline.snapshot(), batchProfile));
            }
            boolean absorb = switch (strategy) {
                case NONE -> false;
                case ON_PASS -> verdict == BatchVerdict.PASS;
                case ALWAYS -> true;
            };
            if (absorb) {
                for (int i = 0; i < columns.size(); i++) {
                    baselines.computeIfAbsent(columns.get(i), k -> new ColumnProfile())
                            .merge(batchProfiles.get(i));
                }
            }
        }
        return results;
    }

    public long baselineSamples(String column) {
        ColumnProfile profile = baselines.get(column);
        return profile == null ? 0 : profile.count();
    }

    public void reset() {
        synchronized (baselineLock) {
            baselines.clear();
        }
    }
}
