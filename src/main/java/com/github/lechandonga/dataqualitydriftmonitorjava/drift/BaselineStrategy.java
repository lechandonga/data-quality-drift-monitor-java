package com.github.lechandonga.dataqualitydriftmonitorjava.drift;

/** Controls how the drift baseline is updated after a batch is processed. */
public enum BaselineStrategy {
    /** Baseline never updates automatically; fully manual control. */
    NONE,
    /** Baseline absorbs batches whose quality verdict is PASS. */
    ON_PASS,
    /** Every structurally valid batch is absorbed into the baseline. */
    ALWAYS
}
