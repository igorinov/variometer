package com.igorinov.variometer.common;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;

public class KalmanFilterTest {

    /*  Values for state covariance initialization, with
     *  high confidence in zero speed on startup
     */

    static final double[] p_init = { 100.0, 0.0001, 100.0 };

    @Test
    public void FilterTest() {
        KalmanFilter filter = new KalmanFilter(3, 2, 0);
        Random random = new Random();

        // sampling period
        double dt = 0.02;
        int n = (int) Math.round(20 / dt);

        double[] a_sim = new double[n];
        double[] v_sim = new double[n];
        double[] x_sim = new double[n];

        double[] a_est = new double[n];
        double[] v_est = new double[n];
        double[] x_est = new double[n];

        double[] input = new double[2];
        double[] state = new double[3];
        double sigma_a = 0.25;
        double sigma_x = 0.5;
        double a, v, x;
        int i;

        a = 0;
        v = 0;
        x = 0;

        filter.setPeriod(dt);
        filter.setProcessNoise(dt, 0.01);
        double[] r = { sigma_x, sigma_a };
        filter.setMeasurementError(r);
        filter.initCovariance(p_init);

        for (i = 0; i < n; i += 1) {
            double t = i * dt;

            v += a * dt;
            x += v * dt + a * dt * dt / 2;

            a_sim[i] = a;
            v_sim[i] = v;
            x_sim[i] = x;

            // input data with measurement noise
            input[0] = x + random.nextGaussian() * sigma_x;
            input[1] = a + random.nextGaussian() * sigma_a;

            filter.filterPredict(null);
            filter.filterUpdate(input);
            filter.getState(state);

            a_est[i] = state[2];
            v_est[i] = state[1];
            x_est[i] = state[0];

            a = 0;
            if (t >= 2 && t < 4) {
                a = +1.5;
            }
            if (t >= 6 && t < 8) {
                a = -1.5;
            }
            if (t >= 12 && t < 14) {
                a = -1.5;
            }
            if (t >= 16 && t < 18) {
                a = +1.5;
            }
        }

        // Check if the hidden variable (speed) is tracked correctly
        double delta = 0.2;
        assertArrayEquals(v_sim, v_est, delta);
    }

    @Test
    public void SequentialUpdateTest() {
        KalmanFilter kf1 = new KalmanFilter(3, 2, 0);
        KalmanFilter kf2 = new KalmanFilter(3, 2, 0);
        Random random = new Random();

        // sampling period
        double dt = 0.02;
        int n = (int) Math.round(20 / dt);

        double[] input = new double[2];
        double[] state = new double[3];
        double sigma_a = 0.25;
        double sigma_x = 0.5;
        double a, v, x;
        int i;

        a = 0;
        v = 0;
        x = 0;

        kf1.setPeriod(dt);
        kf2.setPeriod(dt);
        kf1.setProcessNoise(dt, 0.01);
        kf2.setProcessNoise(dt, 0.01);
        double[] r = { sigma_x, sigma_a };
        kf1.setMeasurementError(r);
        kf2.setMeasurementError(r);
        kf1.initCovariance(p_init);
        kf2.initCovariance(p_init);

        for (i = 0; i < n; i += 1) {
            double t = i * dt;

            v += a * dt;
            x += v * dt + a * dt * dt / 2;

            // input data with measurement noise
            input[0] = x + random.nextGaussian() * sigma_x;
            input[1] = a + random.nextGaussian() * sigma_a;

            kf1.filterPredict(null);
            kf1.filterUpdate(input);

            kf2.filterPredict(null);
            kf2.filterUpdateSequential(0, input[0]);
            kf2.filterUpdateSequential(1, input[1]);

            double delta = 1e-12;
            assertArrayEquals(kf1.x.data, kf2.x.data, delta);
            assertArrayEquals(kf1.P.data, kf2.P.data, delta);

            a = 0;
            if (t >= 2 && t < 4) {
                a = +1.5;
            }
            if (t >= 6 && t < 8) {
                a = -1.5;
            }
            if (t >= 12 && t < 14) {
                a = -1.5;
            }
            if (t >= 16 && t < 18) {
                a = +1.5;
            }
        }
    }
}