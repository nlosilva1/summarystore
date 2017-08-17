package com.samsung.sra.experiments;

import com.moandjiezana.toml.Toml;

import java.util.SplittableRandom;

/** Zipf distribution over [1, N] with shape parameter k */
public class ZipfDistribution implements Distribution<Long> {
    private final org.apache.commons.math3.distribution.ZipfDistribution apacheZD;

    public ZipfDistribution(Toml conf) {
        int N = conf.getLong("N").intValue(); // range = [1, N]
        double k = conf.getDouble("k");
        apacheZD = new org.apache.commons.math3.distribution.ZipfDistribution(N, k);
    }

    /** WARNING: ignores argument and uses private RNG */
    @Override
    public Long next(SplittableRandom random) {
        return (long) apacheZD.sample();
    }
}
