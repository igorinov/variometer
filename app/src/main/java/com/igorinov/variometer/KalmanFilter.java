/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer;

public class KalmanFilter {
    int state_vars;
    int input_vars;
    int control_vars;

    double[] x;         // State estimation
    double[] x_prior;   // Prior state estimation
    double[] y;         // Residual (Innovation)
    Matrix H, H_T;      // Measurement function (Observation model)
    Matrix F, F_T;      // State transition model
    Matrix B = null;    // Control function
    Matrix Q;           // Process noise covariance
    Matrix R;           // Measurement Uncertainty
    Matrix S, S_inv;    // System Uncertainty
    Matrix K, K_T;      // Kalman gain
    Matrix P;           // Covariance
    Matrix P_prior;     // Prior Covariance

    // Temporary matrices are only allocated once
    Matrix I_KH, I_KH_T;
    Matrix tmp_ss, tmp_si, tmp_is;

    KalmanFilter(int state, int input, int ctrls) {
        state_vars = state;
        input_vars = input;
        control_vars = ctrls;

        x = new double[state_vars];
        x_prior = new double[state_vars];
        P = new Matrix(state_vars, state_vars);
        P.eye();
        K = new Matrix(state_vars, input_vars);
        K_T = new Matrix(input_vars, state_vars);
        I_KH = new Matrix(state_vars, state_vars);
        I_KH_T = new Matrix(state_vars, state_vars);
        tmp_ss = new Matrix(state_vars, state_vars);
        tmp_si = new Matrix(state_vars, input_vars);
        tmp_is = new Matrix(input_vars, state_vars);
        S_inv = new Matrix(input_vars, input_vars);
        P_prior = new Matrix(state_vars, state_vars);
        y = new double[input_vars];
        if (control_vars > 0)
            B = new Matrix(control_vars, state_vars);
        H = new Matrix(input_vars, state_vars);
        H.itemSet(0, 0, 1.0);
        if (state_vars == 3 && input_vars == 2)
            H.itemSet(1, 2, 1.0);
        H_T = new Matrix(state_vars, input_vars);
        H_T.transpose(H);
        R = new Matrix(input_vars, input_vars);
        S = new Matrix(input_vars, input_vars);
        F = new Matrix(state_vars, state_vars);
        F_T = new Matrix(state_vars, state_vars);
        Q = new Matrix(state_vars, state_vars);
    }

    int getState(double[] dst) {
        System.arraycopy(x, 0, dst, 0, state_vars);

        return state_vars;
    }

    int setState(double[] src) {
        System.arraycopy(src, 0, x, 0, state_vars);

        return state_vars;
    }

    int setPeriod(double dt) {
        F.eye();

        if (state_vars == 2) {
            F.itemSet(0, 1, dt);
        }

        if (state_vars == 3) {
            F.itemSet(0, 1, dt);
            F.itemSet(0, 2, dt * dt * 0.5);
            F.itemSet(1, 2, dt);
        }

        F_T.transpose(F);

        return 0;
    }

    int setProcessNoise(double dt, double var) {

        // Using discrete noise model

        if (state_vars == 2) {
            Q.itemSet(0, 0, 0.25 * dt * dt * dt * dt);
            Q.itemSet(0, 1, 0.50 * dt * dt * dt);
            Q.itemSet(1, 0, 0.50 * dt * dt * dt);
            Q.itemSet(1, 1, dt * dt);
        }

        if (state_vars == 3) {
            Q.itemSet(0, 0, 0.25 * dt * dt * dt * dt);
            Q.itemSet(0, 1, 0.50 * dt * dt * dt);
            Q.itemSet(0, 2, 0.50 * dt * dt);
            Q.itemSet(1, 0, 0.50 * dt * dt * dt);
            Q.itemSet(1, 1, dt * dt);
            Q.itemSet(1, 2, dt);
            Q.itemSet(2, 0, 0.50 * dt * dt);
            Q.itemSet(2, 1, dt);
            Q.itemSet(2, 2, 1);
        }

        Q.scale(var);

        return 0;
    }

    int initCovariance(double[] std) {
        double sigma;
        int i;

        P.eye();
        for (i = 0; i < state_vars; i += 1) {
            sigma = std[i];
            P.itemSet(i, i, sigma * sigma);
        }

        return state_vars;
    }

    int setMeasurementError(double[] std) {
        int i;
        double sigma;

        assert(std.length == input_vars);

        R.zero();
        for (i = 0; i < input_vars; i += 1) {
            sigma = std[i];
            R.itemSet(i, i, sigma * sigma);
        }

        return input_vars;
    }

    int filterPredict(double[] u) {
        //  Prior Mean
        //  x⁻ = Fx + Bu

        F.vmul(x_prior, x);
        if (u != null) {
            assert(u.length == control_vars);
            B.vmuladd(x_prior, u);
        }

        //  Prior Covariance
        //  P⁻ = FPF⸆ + Q

        tmp_ss.dotProduct(P, F_T);
        P_prior.copy(Q);
        P_prior.addProduct(F, tmp_ss);

        return state_vars;
    }

    int filterUpdate(double[] z) {
        int i;

        assert (z.length == input_vars);

        //  Residual
        //  y = z - Hx⁻

        H.vmul(y, x_prior);
        for (i = 0; i < input_vars; i += 1) {
            y[i] = z[i] - y[i];
        }

        //  System uncertainty
        //  S = HPH⸆ + R

        S.copy(R);
        tmp_si.dotProduct(P_prior, H_T);
        S.addProduct(H, tmp_si);

        //  Kalman gain
        //  K = P⁻H⸆S⁻¹

        S_inv.inv(S);
        tmp_si.dotProduct(H_T, S_inv);
        K.dotProduct(P_prior, tmp_si);

        //  State Update
        //  x = x⁻ + Ky

        System.arraycopy(x_prior, 0, x, 0, state_vars);
        K.vmuladd(x, y);

        //  Covariance Update
        //  P = (I-KH)P⁻(I-KH)⸆ + KRK⸆

        I_KH.eye();
        I_KH.subProduct(K, H);
        I_KH_T.transpose(I_KH);
        tmp_ss.dotProduct(P_prior, I_KH_T);
        P.dotProduct(I_KH, tmp_ss);

        K_T.transpose(K);
        tmp_is.dotProduct(R, K_T);
        P.addProduct(K, tmp_is);

        return input_vars;
    }
}
