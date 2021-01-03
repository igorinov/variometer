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
import static org.ejml.dense.row.CommonOps_DDRM.extractRow;
import static org.ejml.dense.row.CommonOps_DDRM.mult;
import static org.ejml.dense.row.CommonOps_DDRM.multAdd;
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

    Matrix z;           // Measurement input
    Matrix u;           // Control input
    Matrix x;           // State estimation
    Matrix x_prior;     // Prior state estimation
    Matrix y;           // Residual (Innovation)
    Matrix H, H_i;      // Measurement function (Observation model)
    Matrix F;           // State transition model
    Matrix B = null;    // Control function
    Matrix Q;           // Process noise covariance
    Matrix R;           // Measurement Uncertainty
    Matrix S, S_inv;    // System Uncertainty
    Matrix K, K_i;      // Kalman gain
    Matrix P;           // Covariance
    Matrix P_prior;     // Prior Covariance

    // Temporary matrices are only allocated once
    Matrix PHT, PHT1;
    Matrix IMKH;
    Matrix tmp_11, tmp_s1;
    Matrix tmp_ss, tmp_si, tmp_is;

    private LinearSolverDense<DMatrixRMaj> solver;

    public KalmanFilter(int state, int input, int ctrls) {
        state_vars = state;
        input_vars = input;
        control_vars = ctrls;

        z = new Matrix(input_vars, 1);
        u = new Matrix(state_vars, 1);
        x = new Matrix(state_vars, 1);
        x_prior = new Matrix(state_vars, 1);
        P = new Matrix(state_vars, state_vars);
        setIdentity(P);
        K = new Matrix(state_vars, input_vars);
        K_i = new Matrix(state_vars, 1);
        PHT = new Matrix(state_vars, input_vars);
        PHT1 = new Matrix(state_vars, 1);
        IMKH = new Matrix(state_vars, state_vars);
        tmp_11 = new Matrix(1, 1);
        tmp_s1 = new Matrix(state_vars, 1);
        tmp_ss = new Matrix(state_vars, state_vars);
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
        H_i = new Matrix(1, state_vars);
        R = new Matrix(input_vars, input_vars);
        S = new Matrix(input_vars, input_vars);
        F = new Matrix(state_vars, state_vars);
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

    public int filterPredict(double[] control) {
        //  Prior Mean
        //  x⁻ = Fx + Bu

        mult(F, x, x_prior);
        if (control != null) {
            assert (control.length == state_vars);
            System.arraycopy(control, 0, u.data, 0, control_vars);
            multAdd(B, u, x_prior);
        }
        x.set(x_prior);

        //  Prior Covariance
        //  P⁻ = FPF⸆ + Q

        mult(F, P, tmp_ss);
        multTransB(tmp_ss, F, P_prior);
        addEquals(P_prior, Q);
        P.set(P_prior);

        return state_vars;
    }

    public int filterUpdate(double[] input) {
        assert (input.length == input_vars);
        System.arraycopy(input, 0, z.data, 0, input_vars);

        //  Residual
        //  y = z - Hx⁻

        mult(H, x, y);
        subtract(z, y, y);

        //  System uncertainty
        //  S = HP⁻H⸆ + R

        S.set(R);
        multTransB(P, H, PHT);
        multAdd(H, PHT, S);

        //  Kalman gain
        //  K = P⁻H⸆S⁻¹

        if (!solver.setA(S)) return 0;
        solver.invert(S_inv);
        mult(PHT, S_inv, K);

        //  State Update
        //  x = x⁻ + Ky

        multAdd(K, y, x);

        //  Covariance Update
        //  P = (I-KH)P⁻(I-KH)⸆ + KRK⸆

        setIdentity(IMKH);
        mult(K, H, tmp_ss);
        subtractEquals(IMKH, tmp_ss);
        mult(IMKH, P, tmp_ss);
        multTransB(tmp_ss, IMKH, P);
        mult(K, R, tmp_si);
        multTransB(tmp_si, K, tmp_ss);
        addEquals(P, tmp_ss);

        return input_vars;
    }

    public int filterUpdateSequential(int i, double z_i) {
        assert (i < input_vars);
        z.set(i, 0, z_i);
        extractRow(H, i, H_i);
        double R_ii = R.get(i, i);
        int j;

        //  Residual
        //  yᵢ = zᵢ - Hᵢx⁻

        mult(H_i, x, tmp_11);
        double y_i = z_i - tmp_11.get(0, 0);

        //  System uncertainty
        //  Sᵢ = HᵢP⁻Hᵢ⸆ + Rᵢ
        //  In sequential processing, Sᵢ is a scalar

        multTransB(P, H_i, PHT1);
        mult(H_i, PHT1, tmp_11);
        double S_i = tmp_11.get(0, 0) + R_ii;
        if (S_i == 0)
            return 0;

        //  Kalman gain
        //  Kᵢ = P⁻Hᵢ⸆S⁻¹

        scale(1.0 / S_i, PHT1, K_i);

        // Store the Kalman gain component
        for (j = 0; j < state_vars; j += 1) {
            K.set(j, i, K_i.get(j, 0));
        }

        //  State Update
        //  x = x⁻ + Kᵢyᵢ

        tmp_11.set(0, 0, y_i);
        multAdd(K_i, tmp_11, x);

        //  Covariance Update
        //  P = (I-KᵢHᵢ)P⁻(I-KᵢHᵢ)⸆ + KᵢRᵢKᵢ⸆

        setIdentity(IMKH);
        mult(K_i, H_i, tmp_ss);
        subtractEquals(IMKH, tmp_ss);
        mult(IMKH, P, tmp_ss);
        multTransB(tmp_ss, IMKH, P);
        multTransB(R_ii, K_i, K_i, tmp_ss);
        addEquals(P, tmp_ss);

        return input_vars;
    }
}
