package com.github.lechandonga.dataqualitydriftmonitorjava.quality;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Immutable, versioned collection of quality checks. The version is a stable
 * hash of the check definitions, so any rule or threshold change yields a new
 * version and historical verdicts (recorded with their version) are never
 * rewritten.
 */
public class RuleSet {

    private final List<QualityCheckConfig> checks;
    private final String version;

    public RuleSet(List<QualityCheckConfig> checks) {
        this.checks = List.copyOf(checks);
        this.version = computeVersion(checks);
    }

    public List<QualityCheckConfig> checks() {
        return checks;
    }

    public String version() {
        return version;
    }

    private static String computeVersion(List<QualityCheckConfig> checks) {
        StringBuilder sb = new StringBuilder();
        for (QualityCheckConfig c : checks) {
            sb.append(c.type()).append('|').append(c.enabled()).append('|').append(c.field())
                    .append('|').append(c.maxMissingRatio()).append('|').append(c.maxDuplicateRatio())
                    .append('|').append(c.min()).append('|').append(c.max())
                    .append('|').append(c.maxOutlierRatio()).append('|').append(c.maxAgeSeconds())
                    .append(';');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
