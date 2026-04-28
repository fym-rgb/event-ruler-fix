package software.amazon.event.ruler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.Assume;
import org.junit.Test;

import static software.amazon.event.ruler.Benchmarks.ANYTHING_BUT_PREFIX_RULES;
import static software.amazon.event.ruler.Benchmarks.ANYTHING_BUT_SUFFIX_RULES;
import static software.amazon.event.ruler.Benchmarks.ANYTHING_BUT_WILDCARD_RULES;
import static software.amazon.event.ruler.Benchmarks.SUFFIX_EQUALS_IGNORE_CASE_RULES;
import static software.amazon.event.ruler.Benchmarks.SUFFIX_RULES;
import static software.amazon.event.ruler.Benchmarks.WILDCARD_RULES;
import static software.amazon.event.ruler.Benchmarks.readCityLots2;

/**
 * Focused perf regression check for the
 * {@code extractNextJavaCharacterFromInputCharactersForBackwardsArrays}
 * guard added to fix the InputWildcard-cast ClassCastException.
 *
 * <p>The fix touches the compile path (two {@code isByte(...)} predicate checks),
 * not the hot match path. This benchmark still measures match throughput on
 * citylots2 because that's the established comparable workload in this repo,
 * and because any secondary effect on the match path would show up there.
 *
 * <p>Unlike {@code Benchmarks#CL2Benchmark} (single-shot per rule type), this
 * benchmark:
 * <ul>
 *   <li>Runs N warmup passes per rule type before measurement (lets the JIT
 *       warm up; the first pass usually runs 10-30% below steady state).</li>
 *   <li>Runs M measured passes per rule type and reports mean + stddev.</li>
 *   <li>Focuses only on rule types whose compile path routes through the
 *       changed code: wildcard, suffix, and anything-but variants that
 *       internally use wildcard/suffix machinery.</li>
 *   <li>Is gated off by default (skipped via {@link Assume} unless
 *       {@code -Druler.perf.run=true} is passed), matching the pattern
 *       described in {@code Benchmarks.java}'s class javadoc.</li>
 * </ul>
 *
 * <p>Usage pattern for comparing parent vs fix:
 * <pre>
 *   git checkout parent
 *   mvn clean test -Druler.perf.run=true \
 *       -Dtest=ByteMachineBackwardsWalkerPerfBenchmark#runAll &gt; /tmp/parent.log
 *
 *   git checkout fix
 *   mvn clean test -Druler.perf.run=true \
 *       -Dtest=ByteMachineBackwardsWalkerPerfBenchmark#runAll &gt; /tmp/fix.log
 *
 *   diff the "MEAN" lines.
 * </pre>
 *
 * <p>Warmup and measurement counts are tuned so each full run takes
 * a few minutes against citylots2 (~213K events). Adjust via system properties
 * {@code -Druler.perf.warmup=N} {@code -Druler.perf.measure=M}.
 *
 * <p><b>Gating:</b> this test is skipped unless {@code -Druler.perf.run=true}
 * is passed. We use {@link Assume} rather than {@code @Ignore} so the test can
 * be turned on with a single system property on the CLI — no code edit needed.
 */
public class ByteMachineBackwardsWalkerPerfBenchmark {

    private static final int DEFAULT_WARMUP_PASSES = 3;
    private static final int DEFAULT_MEASURE_PASSES = 5;

    // Expected match counts. These mirror the private arrays in Benchmarks.java
    // for the same rule banks (WILDCARD_RULES, SUFFIX_RULES, etc). We can't
    // import them because they're package-private instance fields; duplicating
    // them here keeps the perf check self-contained.
    private static final int[] WILDCARD_MATCHES = { 490, 713, 43, 2540, 1 };
    private static final int[] SUFFIX_MATCHES = { 17921, 871, 13, 1963, 682 };
    private static final int[] SUFFIX_EQUALS_IGNORE_CASE_MATCHES = { 17921, 871, 13, 1963, 682 };
    private static final int[] ANYTHING_BUT_PREFIX_MATCHES = { 211158, 210118, 96667, 120, 209091 };
    private static final int[] ANYTHING_BUT_SUFFIX_MATCHES = { 211136, 210411, 94908, 0, 209055 };
    private static final int[] ANYTHING_BUT_WILDCARD_MATCHES = { 212578, 212355, 213025, 210528, 213067 };

    private final List<String> citylots2 = new ArrayList<>();

    /** Run every affected rule type end-to-end. */
    @Test
    public void runAll() throws Exception {
        Assume.assumeTrue(
                "Skipped: set -Druler.perf.run=true to run the perf benchmark.",
                Boolean.getBoolean("ruler.perf.run"));

        readCityLots2(citylots2);
        int warmup = getIntProp("ruler.perf.warmup", DEFAULT_WARMUP_PASSES);
        int measure = getIntProp("ruler.perf.measure", DEFAULT_MEASURE_PASSES);

        System.out.println("===== ByteMachineBackwardsWalkerPerfBenchmark =====");
        System.out.println("warmup passes: " + warmup);
        System.out.println("measure passes: " + measure);
        System.out.println("events: " + citylots2.size());
        System.out.println();

        // Rule types that route through the fixed code path on compile.
        runRuleType("WILDCARD", WILDCARD_RULES, WILDCARD_MATCHES, warmup, measure);
        runRuleType("SUFFIX", SUFFIX_RULES, SUFFIX_MATCHES, warmup, measure);
        runRuleType("SUFFIX_EIC",
                SUFFIX_EQUALS_IGNORE_CASE_RULES, SUFFIX_EQUALS_IGNORE_CASE_MATCHES,
                warmup, measure);
        runRuleType("ANYTHING_BUT_SUFFIX",
                ANYTHING_BUT_SUFFIX_RULES, ANYTHING_BUT_SUFFIX_MATCHES,
                warmup, measure);
        runRuleType("ANYTHING_BUT_WILDCARD",
                ANYTHING_BUT_WILDCARD_RULES, ANYTHING_BUT_WILDCARD_MATCHES,
                warmup, measure);
        runRuleType("ANYTHING_BUT_PREFIX",
                ANYTHING_BUT_PREFIX_RULES, ANYTHING_BUT_PREFIX_MATCHES,
                warmup, measure);
    }

    // --- Helpers -------------------------------------------------------

    private void runRuleType(String label, String[] rules, int[] expectedMatches,
                             int warmupPasses, int measurePasses) throws Exception {
        List<Double> samples = new ArrayList<>(measurePasses);

        for (int i = 0; i < warmupPasses; i++) {
            double eps = timeOnePass(rules, expectedMatches);
            System.out.printf(Locale.ROOT, "  [%s] warmup %d/%d: %.1f events/sec%n",
                    label, i + 1, warmupPasses, eps);
        }

        for (int i = 0; i < measurePasses; i++) {
            double eps = timeOnePass(rules, expectedMatches);
            samples.add(eps);
            System.out.printf(Locale.ROOT, "  [%s] measure %d/%d: %.1f events/sec%n",
                    label, i + 1, measurePasses, eps);
        }

        double mean = mean(samples);
        double stddev = stddev(samples, mean);
        double relStddev = 100.0 * stddev / mean;
        double min = Collections.min(samples);
        double max = Collections.max(samples);

        System.out.printf(Locale.ROOT,
                "  [%s] MEAN=%.1f  STDDEV=%.1f  (%.1f%%)  MIN=%.1f  MAX=%.1f  events/sec%n%n",
                label, mean, stddev, relStddev, min, max);
    }

    /**
     * Build a fresh machine for this rule set and time a single scan of
     * citylots2. Each pass compiles the rules from scratch, so compile cost
     * is included. Returns events/sec for the scan.
     */
    private double timeOnePass(String[] rules, int[] expectedMatches) throws Exception {
        Machine machine = new Machine();
        int[] gotCounts = new int[rules.length];
        for (int i = 0; i < rules.length; i++) {
            machine.addRule("r" + i, rules[i]);
        }

        long before = System.nanoTime();
        for (String event : citylots2) {
            List<String> matches = machine.rulesForJSONEvent(event);
            for (String match : matches) {
                int idx = Integer.parseInt(match.substring(1));
                gotCounts[idx]++;
            }
        }
        long afterNs = System.nanoTime() - before;

        // Sanity: reject the result if counts don't match. A perf number for
        // a match function that doesn't return correct results is useless.
        for (int i = 0; i < rules.length; i++) {
            if (gotCounts[i] != expectedMatches[i]) {
                throw new AssertionError("match count mismatch for rule " + i
                        + ": expected=" + expectedMatches[i] + " got=" + gotCounts[i]);
            }
        }

        return (1_000_000_000.0 * citylots2.size()) / afterNs;
    }

    private static double mean(List<Double> xs) {
        double sum = 0;
        for (double x : xs) {
            sum += x;
        }
        return sum / xs.size();
    }

    private static double stddev(List<Double> xs, double mean) {
        if (xs.size() < 2) {
            return 0;
        }
        double sum = 0;
        for (double x : xs) {
            double d = x - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (xs.size() - 1));
    }

    private static int getIntProp(String name, int defaultValue) {
        String v = System.getProperty(name);
        if (v == null) {
            return defaultValue;
        }
        return Integer.parseInt(v);
    }
}
