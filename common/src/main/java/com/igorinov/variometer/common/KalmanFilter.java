/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer.common;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM;
import org.ejml.interfaces.linsol.LinearSolverDense;

import static org.ejml.dense.row.CommonOps_DDRM.add;
import static org.ejml.dense.row.CommonOps_DDRM.addEquals;
import static org.ejml.dense.row.CommonOps_DDRM.mult;
import static org.ejml.dense.row.CommonOps_DDRM.multTransA;
import static org.ejml.dense.row.CommonOps_DDRM.multTransB;
import static org.ejml.dense.row.CommonOps_DDRM.scale;
import static org.ejml.dense.row.CommonOps_DDRM.setIdentity;
import static org.ejml.dense.row.CommonOps_DDRM.subtract;
import static org.ejml.dense.row.CommonOps_DDRM.subtractEquals;
import static org.ejml.dense.row.CommonOps_DDRM.transpose;

public class KalmanFilter {
    int state_vars;
    int input_vars;
    int control_vars;

    Matrix z;           // Input
    Matrix x;           // State estimation
    Matrix x_prior;     // Prior state estimation
    Matrix y;           // Residual (Innovation)
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
    Matrix IMKH;
    Matrix tmp_s;
    Matrix tmp_ss, tmp_ss2, tmp_si, tmp_is;

    private LinearSolverDense<DMatrixRMaj> solver;

    public KalmanFilter(int state, int input, int ctrls) {
        state_vars = state;
        input_vars = input;
        control_vars = ctrls;

        z = new Matrix(input_vars, 1);
        x = new Matrix(state_vars, 1);
        x_prior = new Matrix(state_vars, 1);
        P = new Matrix(state_vars, state_vars);
        setIdentity(P);
        K = new Matrix(state_vars, input_vars);
        K_T = new Matrix(input_vars, state_vars);
        IMKH = new Matrix(state_vars, state_vars);
        tmp_s = new Matrix(state_vars, 1);
        tmp_ss = new Matrix(state_vars, state_vars);
        tmp_ss2 = new Matrix(state_vars, state_vars);
        tmp_si = new Matrix(state_vars, input_vars);
        tmp_is = new Matrix(input_vars, state_vars);
        S_inv = new Matrix(input_vars, input_vars);
        P_prior = new Matrix(state_vars, state_vars);
        y = new Matrix(input_vars, 1);
        if (control_vars > 0)
            B = new Matrix(control_vars, state_vars);
        H = new Matrix(input_vars, state_vars);
        H.set(0, 0, 1.0);
        if (state_vars == 3 && input_vars == 2)
            H.set(1, 2, 1.0);
        H_T = new Matrix(state_vars, input_vars);
        transpose(H, H_T);
        R = new Matrix(input_vars, input_vars);
        S = new Matrix(input_vars, input_vars);
        F = new Matrix(state_vars, state_vars);
        F_T = new Matrix(state_vars, state_vars);
        Q = new Matrix(state_vars, state_vars);

        solver = LinearSolverFactory_DDRM.symmPosDef(state_vars);
    }

    public int getState(double[] dst) {
        System.arraycopy(x.data, 0, dst, 0, state_vars);

        return state_vars;
    }

    public int setState(double[] src) {
        System.arraycopy(src, 0, x.data, 0, state_vars);

        return state_vars;
    }

    public int setPeriod(double dt) {
        setIdentity(F);

        if (state_vars == 2) {
            F.set(0, 1, dt);
        }

        if (state_vars == 3) {
            F.set(0, 1, dt);
            F.set(0, 2, dt * dt * 0.5);
            F.set(1, 2, dt);
        }

        transpose(F, F_T);

        return 0;
    }

    public int setProcessNoise(double dt, double var) {

        // Using discrete noise model

        if (state_vars == 2) {
            Q.set(0, 0, 0.25 * dt * dt * dt * dt);
            Q.set(0, 1, 0.50 * dt * dt * dt);
            Q.set(1, 0, 0.50 * dt * dt * dt);
            Q.set(1, 1, dt * dt);
        }

        if (state_vars == 3) {
            Q.set(0, 0, 0.25 * dt * dt * dt * dt);
            Q.set(0, 1, 0.50 * dt * dt * dt);
            Q.set(0, 2, 0.50 * dt * dt);
            Q.set(1, 0, 0.50 * dt * dt * dt);
            Q.set(1, 1, dt * dt);
            Q.set(1, 2, dt);
            Q.set(2, 0, 0.50 * dt * dt);
            Q.set(2, 1, dt);
            Q.set(2, 2, 1);
        }

        scale(var, Q);

        return 0;
    }

    public int initCovariance(double[] std) {
        double sigma;
        int i;

        setIdentity(P);
        for (i = 0; i < state_vars; i += 1) {
            sigma = std[i];
            P.set(i, i, sigma * sigma);
        }

        return state_vars;
    }

    public int setMeasurementError(double[] std) {
        int i;
        double sigma;

        assert(std.length == input_vars);

        R.zero();
        for (i = 0; i < input_vars; i += 1) {
            sigma = std[i];
            R.set(i, i, sigma * sigma);
        }

        return input_vars;
    }

    public int filterPredict(double[] u) {
        //  Prior Mean
        //  x⁻ = Fx + Bu

        mult(F, x, x_prior);

        //  Prior Covariance
        //  P⁻ = FPF⸆ + Q

        mult(F, P, tmp_ss);
        multTransB(tmp_ss, F, P_prior);
        addEquals(P_prior, Q);

        return state_vars;
    }

    public int filterUpdate(double[] input) {
        assert (input.length == input_vars);
        System.arraycopy(input, 0, z.data, 0, input_vars);

        //  Residual
        //  y = z - Hx⁻

        mult(H, x_prior, y);
        subtract(z, y, y);

        //  System uncertainty
        //  S = HP⁻H⸆ + R

        mult(H, P_prior, tmp_si);
        multTransB(tmp_si, H, S);
        addEquals(S, R);

        //  Kalman gain
        //  K = P⁻H⸆S⁻¹

        if (!solver.setA(S)) return 0;
        solver.invert(S_inv);
        multTransA(H, S_inv, tmp_si);
        mult(P_prior, tmp_si, K);

        //  State Update
        //  x = x⁻ + Ky

        mult(K, y, tmp_s);
        add(x_prior, tmp_s, x);

        //  Covariance Update
        //  P = (I-KH)P⁻(I-KH)⸆ + KRK⸆

        setIdentity(IMKH);
        mult(K, H, tmp_ss);
        subtractEquals(IMKH, tmp_ss);

        mult(IMKH, P_prior, tmp_ss);
        multTransB(tmp_ss, IMKH, tmp_ss2);

        mult(K, R, tmp_si);
        multTransB(tmp_si, K, tmp_ss);

        add(tmp_ss, tmp_ss2, P);

        return input_vars;
    }
}
