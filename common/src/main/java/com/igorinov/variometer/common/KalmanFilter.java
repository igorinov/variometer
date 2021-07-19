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
import static org.ejml.dense.row.CommonOps_DDRM.multAddTransB;
import static org.ejml.dense.row.CommonOps_DDRM.multTransA;
import static org.ejml.dense.row.CommonOps_DDRM.multTransB;
import static org.ejml.dense.row.CommonOps_DDRM.scale;
import static org.ejml.dense.row.CommonOps_DDRM.setIdentity;
import static org.ejml.dense.row.CommonOps_DDRM.subtract;
import static org.ejml.dense.row.CommonOps_DDRM.subtractEquals;
import static org.ejml.dense.row.CommonOps_DDRM.transpose;

public class KalmanFilter {
    /** State vector dimension */
    int stateDim;

    /** Input vector dimension */
    int inputDim;

    /** Control vector dimension */
    int controlDim;

    /** Measurement input */
    Matrix z;

    /** Control input */
    Matrix u;

    /** State estimation */
    Matrix x;

    /** Predicted (prior) state estimation */
    Matrix x_prior;

    /** Residual (Innovation) */
    Matrix y;

    /** Measurement function (Observation model) */
    Matrix H, H_seq;

    /** State transition model */
    Matrix F;

    /** Control function */
    Matrix B = null;

    /** Process noise covariance */
    Matrix Q;

    /** Observation noise covariance (measurement uncertainty) */
    Matrix R;

    /** Innovation covariance, S = HPH<sup>T</sup> + R */
    Matrix S;

    /** Inverse innovation covariance, S<sup>-1</sup> */
    Matrix S_inv;

    /** Inverse system uncertainty for sequential updates */
    double s_inv;

    /** Kalman gain */
    Matrix K, K_seq;

    /** State covariance (state uncertainty) */
    Matrix P;
    Matrix P_prior;     // Prior Covariance

    // Temporary matrices are only allocated once
    /** PH<sup>T</sup> */
    Matrix PHT, PHT_seq;
    Matrix KH;

    /** (I - KH) */
    Matrix IMKH;
    Matrix tmp_11, tmp_s1;
    Matrix tmp_ss, tmp_si, tmp_is;

    private LinearSolverDense<DMatrixRMaj> solver;

    public KalmanFilter(int state, int input, int ctrls) {
        stateDim = state;
        inputDim = input;
        controlDim = ctrls;

        P = new Matrix(stateDim, stateDim);
        setIdentity(P);
        u = new Matrix(controlDim, 1);
        x = new Matrix(stateDim, 1);
        x_prior = new Matrix(stateDim, 1);
        z = new Matrix(inputDim, 1);

        PHT = new Matrix(stateDim, inputDim);
        K = new Matrix(stateDim, inputDim);
        KH = new Matrix(stateDim, stateDim);
        IMKH = new Matrix(stateDim, stateDim);

        //  In sequential processing, some matrices have different sizes
        PHT_seq = new Matrix(stateDim, 1);
        K_seq = new Matrix(stateDim, 1);

        tmp_11 = new Matrix(1, 1);
        tmp_s1 = new Matrix(stateDim, 1);
        tmp_ss = new Matrix(stateDim, stateDim);
        tmp_si = new Matrix(stateDim, inputDim);
        tmp_is = new Matrix(inputDim, stateDim);
        S_inv = new Matrix(inputDim, inputDim);
        P_prior = new Matrix(stateDim, stateDim);
        y = new Matrix(inputDim, 1);
        if (controlDim > 0) {
            B = new Matrix(controlDim, stateDim);
        }
        H = new Matrix(inputDim, stateDim);
        H.set(0, 0, 1.0);
        if (stateDim == 3 && inputDim == 2) {
            H.set(1, 2, 1.0);
        }
        H_seq = new Matrix(1, stateDim);
        R = new Matrix(inputDim, inputDim);
        S = new Matrix(inputDim, inputDim);
        F = new Matrix(stateDim, stateDim);
        Q = new Matrix(stateDim, stateDim);

        solver = LinearSolverFactory_DDRM.symmPosDef(stateDim);
    }

    public int getState(double[] dst) {
        System.arraycopy(x.data, 0, dst, 0, stateDim);

        return stateDim;
    }

    public int setState(double[] src) {
        System.arraycopy(src, 0, x.data, 0, stateDim);

        return stateDim;
    }

    public int setPeriod(double dt) {
        setIdentity(F);

        if (stateDim == 2) {
            F.set(0, 1, dt);
        }

        if (stateDim == 3) {
            F.set(0, 1, dt);
            F.set(0, 2, dt * dt * 0.5);
            F.set(1, 2, dt);
        }

        return 0;
    }

    public int setProcessNoise(double dt, double var) {

        // Using discrete noise model

        if (stateDim == 2) {
            Q.set(0, 0, 0.25 * dt * dt * dt * dt);
            Q.set(0, 1, 0.50 * dt * dt * dt);
            Q.set(1, 0, 0.50 * dt * dt * dt);
            Q.set(1, 1, dt * dt);
        }

        if (stateDim == 3) {
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
        for (i = 0; i < stateDim; i += 1) {
            sigma = std[i];
            P.set(i, i, sigma * sigma);
        }

        return stateDim;
    }

    public int setMeasurementError(double[] std) {
        int i;
        double sigma;

        assert(std.length == inputDim);

        R.zero();
        for (i = 0; i < inputDim; i += 1) {
            sigma = std[i];
            R.set(i, i, sigma * sigma);
        }

        return inputDim;
    }

    public int filterPredict(double[] control) {
        //  Prior Mean
        //  x⁻ = Fx + Bu

        mult(F, x, x_prior);
        if (control != null) {
            assert (control.length == stateDim);
            System.arraycopy(control, 0, u.data, 0, controlDim);
            multAdd(B, u, x_prior);
        }
        x.setTo(x_prior);

        //  Prior Covariance
        //  P⁻ = FPF⸆ + Q

        mult(F, P, tmp_ss);
        multTransB(tmp_ss, F, P_prior);
        addEquals(P_prior, Q);
        P.setTo(P_prior);

        return stateDim;
    }

    public int filterUpdate(double[] input) {
        assert (input.length == inputDim);
        System.arraycopy(input, 0, z.data, 0, inputDim);

        //  Residual
        //  y = z - Hx⁻

        mult(H, x, y);
        subtract(z, y, y);

        //  System uncertainty
        //  S = HP⁻H⸆ + R

        S.setTo(R);
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
        mult(K, H, KH);
        subtractEquals(IMKH, KH);
        mult(IMKH, P, tmp_ss);
        multTransB(tmp_ss, IMKH, P);
        mult(K, R, tmp_si);
        multTransB(tmp_si, K, tmp_ss);
        addEquals(P, tmp_ss);

        return inputDim;
    }

    public int filterUpdateSequential(int i, double z_i) {
        assert (i < inputDim);
        z.set(i, 0, z_i);
        extractRow(H, i, H_seq);
        double R_ii = R.get(i, i);
        int j;

        //  Residual
        //  yᵢ = zᵢ - Hᵢx⁻

        mult(H_seq, x, tmp_11);
        double y_i = z_i - tmp_11.get(0, 0);
        y.set(i, 0, y_i);

        //  System uncertainty
        //  Sᵢ = HᵢP⁻Hᵢ⸆ + Rᵢ
        //  In sequential processing, Sᵢ is a scalar

        multTransB(P, H_seq, PHT_seq);
        mult(H_seq, PHT_seq, tmp_11);
        double S_i = tmp_11.get(0, 0) + R_ii;
        if (S_i == 0)
            return 0;

        //  Kalman gain
        //  Kᵢ = P⁻Hᵢ⸆S⁻¹

        s_inv = 1.0 / S_i;
        scale(s_inv, PHT_seq, K_seq);

        // Store the Kalman gain for input #i
        // The matrix made from Kᵢ columns will be different
        // from gain matrix K in non-sequential update.
        for (j = 0; j < stateDim; j += 1) {
            K.set(j, i, K_seq.get(j, 0));
        }

        //  State Update
        //  x = x⁻ + Kᵢyᵢ

        tmp_11.set(0, 0, y_i);
        multAdd(K_seq, tmp_11, x);

        //  Covariance Update
        //  P = (I-KᵢHᵢ)P⁻(I-KᵢHᵢ)⸆ + KᵢRᵢKᵢ⸆

        setIdentity(IMKH);
        mult(K_seq, H_seq, KH);
        subtractEquals(IMKH, KH);
        mult(IMKH, P, tmp_ss);
        multTransB(tmp_ss, IMKH, P);
        multAddTransB(R_ii, K_seq, K_seq, P);

        return inputDim;
    }
}
