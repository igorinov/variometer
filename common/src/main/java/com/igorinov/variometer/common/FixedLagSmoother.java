/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer.common;

import static org.ejml.dense.row.CommonOps_DDRM.add;
import static org.ejml.dense.row.CommonOps_DDRM.extractColumn;
import static org.ejml.dense.row.CommonOps_DDRM.insert;
import static org.ejml.dense.row.CommonOps_DDRM.mult;
import static org.ejml.dense.row.CommonOps_DDRM.multAdd;
import static org.ejml.dense.row.CommonOps_DDRM.multTransA;
import static org.ejml.dense.row.CommonOps_DDRM.scale;
import static org.ejml.dense.row.CommonOps_DDRM.subtract;
import static org.ejml.dense.row.CommonOps_DDRM.transpose;

public class FixedLagSmoother extends KalmanFilter {
    int N;
    int smoothInputIndex;

    /** [ x<sub>k-N+1|k</sub>, ..., x<sub>k-1|k</sub>, x<sub>k|k</sub> ] */
    Matrix X;

    /** x<sub>k-i | k</sub> */
    Matrix x_i;
    Matrix P_i;

    /** F<sub>s</sub> = (F - KH)<sup>T</sup> */
    Matrix Fs;

    /** H<sup>T</sup>S<sup>-1</sup> */
    Matrix HTSI, HTSI_seq;

    /** Number of data points available */
    int count;
    int k;

    public FixedLagSmoother(int state, int input, int controls, int lag) {
        super(state, input, controls);

        N = lag;
        count = 0;
        k = 0;
        X = new Matrix(stateDim, lag);
        x_i = new Matrix(stateDim, 1);
        P_i = new Matrix(stateDim, stateDim);
        Fs = new Matrix(stateDim, stateDim);
        HTSI = new Matrix(stateDim, inputDim);
        HTSI_seq = new Matrix(stateDim, 1);

        smoothInputIndex = inputDim - 1;
    }

    @Override
    public int filterUpdate(double[] input) {
        int i, t;

        super.filterUpdate(input);
        insert(x, X, 0, k);

        if (count < N) {
            count += 1;
        }

        if (count < 2) {
            return 0;
        }

        //  Fₛ = (F - KH)⸆
        subtract(F, KH, Fs);
        transpose(Fs);

        //  H⸆ S⁻¹
        multTransA(H, S_inv, HTSI);

        //  P₀ = P⁻
        P_i.setTo(P_prior);

        for (i = 1; i < count; i += 1) {
            t = k - i;
            if (t < 0) {
                t += N;
            }

            //  Kᵢ₊₁ = Pᵢ H⸆ S⁻¹
            mult(P_i, HTSI, K);

            extractColumn(X, t, x_i);
            multAdd(K, y, x_i);
            insert(x_i, X, 0, t);

            if (i + 1 < count) {
                //  Pᵢ = P⁻(Fₛ)ⁱ
                mult(P_i, Fs, tmp_ss);
                P_i.setTo(tmp_ss);
            }
        }

        if (++k >= N) {
            k -= N;
        }

        return 0;
    }

    @Override
    public int filterUpdateSequential(int index, double z_i) {
        int i, t;

        super.filterUpdateSequential(index, z_i);

        // Run smoother only on one input
        if (index != smoothInputIndex) {
            return 0;
        }

        insert(x, X, 0, k);

        if (count < N) {
            count += 1 ;
        }

        if (count < 2) {
            return 0;
        }

        //  Fₛ = (F - KH)⸆
        subtract(F, KH, Fs);
        transpose(Fs);

        double y_i = y.get(index, 0);
        transpose(H_seq, HTSI_seq);
        scale(s_inv, HTSI_seq);

        //  P₀ = P⁻
        P_i.setTo(P_prior);

        for (i = 1; i < count; i += 1) {
            t = k - i;
            if (t < 0) {
                t += N;
            }
            //  Kᵢ₊₁ = Pᵢ H⸆ S⁻¹
            mult(P_i, HTSI_seq, K_seq);

            extractColumn(X, t, x_i);
            add(x_i, y_i, K_seq, x_i);
            insert(x_i, X, 0, t);

            if (i + 1 < count) {
                //  Pᵢ = P⁻(Fₛ)ⁱ
                mult(P_i, Fs, tmp_ss);
                P_i.setTo(tmp_ss);
            }
        }

        if (++k >= N) {
            k -= N;
        }

        return 0;
    }

    @Override
    public int setState(double[] src) {
        int i, j;

        for (i = 0; i < N; i += 1) {
            for (j = 0; j < stateDim; j += 1)
            X.set(i, j, src[j]);
        }
        count = 0;

        return super.setState(src);
    }

    @Override
    public int getState(double[] dst) {
        if (count == 0)
            return super.getState(dst);

        assert(dst.length == stateDim);

        int i = count - 1;
        int t = k - i;
        if (t < 0) {
            t += N;
        }
        int j;

        for (j = 0; j < stateDim; j += 1) {
            dst[j] = X.get(j, t);
        }

        return stateDim;
    }

    public int setSmoothingInput(int index) {
        if (index < inputDim) {
            smoothInputIndex = index;
        }

        return smoothInputIndex;
    }
}
