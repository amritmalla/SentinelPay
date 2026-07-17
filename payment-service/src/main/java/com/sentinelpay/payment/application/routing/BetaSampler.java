package com.sentinelpay.payment.application.routing;

import java.util.Random;

/** Samples from Beta(α, β) using the Gamma-ratio method. */
final class BetaSampler {

    private BetaSampler() {
    }

    static double sample(double alpha, double beta, Random random) {
        double x = sampleGamma(alpha, random);
        double y = sampleGamma(beta, random);
        if (x + y == 0.0) {
            return 0.5;
        }
        return x / (x + y);
    }

    /** Marsaglia and Tsang's method for Gamma(shape, 1). */
    private static double sampleGamma(double shape, Random random) {
        if (shape < 1.0) {
            return sampleGamma(1.0 + shape, random) * Math.pow(random.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x;
            double v;
            do {
                x = random.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0.0);
            v = v * v * v;
            double u = random.nextDouble();
            if (u < 1.0 - 0.0331 * (x * x) * (x * x)) {
                return d * v;
            }
            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }
}
