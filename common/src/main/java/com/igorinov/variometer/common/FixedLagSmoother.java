/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer.common;

import static org.ejml.dense.row.CommonOps_DDRM.mult;
import static org.ejml.dense.row.CommonOps_DDRM.multTransA;
import static org.ejml.dense.row.CommonOps_DDRM.subtract;
import static org.ejml.dense.row.CommonOps_DDRM.transpose;

public class FixedLagSmoother extends KalmanFilter {
    int lag_n;
    Matrix X;
    Matrix Ps, HTSI, F_LH;
    double[] x_k;
    int count = 0;

    public FixedLagSmoother(int state, int input, int controls, int lag) {
        super(state, input, controls);

        lag_n = lag;
        x_k = new double[state];
        X = new Matrix(lag, state);
        Ps = new Matrix(state, state);
        HTSI = new Matrix(state, input);
        F_LH = new Matrix(state, state);
    }

    @Override
    public int filterUpdate(double[] input) {
        int n = lag_n;
        int i, j;

        super.filterUpdate(input);

        //  X[0,:] = x_prior

        X.shiftDown(1);
        for (j = 0; j < state_vars; j += 1) {
            X.set(0, j, x_prior.get(j));
        }

        multTransA(H, S_inv, HTSI);

        mult(K, H, tmp_ss);
        subtract(F, tmp_ss, F_LH);

        transpose(F_LH);

        if (count < lag_n) {
            n = count++;
        }

        Ps.set(P);
        for (i = 0; i < n; i += 1) {
            mult(Ps, HTSI, K);
            mult(Ps, F_LH, tmp_ss);
            Ps.set(tmp_ss);

            mult(K, y, tmp_s1);
            for (j = 0; j < state_vars; j += 1) {
                X.add(i, j, tmp_s1.get(j));
            }

        }

        return 0;
    }

    @Override
    public int setState(double[] src) {
        int i, j;

        for (i = 0; i < lag_n; i += 1) {
            for (j = 0; j < state_vars; j += 1)
            X.set(i, j, src[j]);
        }
        count = 0;

        return super.setState(src);
    }

    @Override
    public int getState(double[] dst) {
        if (count == 0)
            return super.getState(dst);

        assert(dst.length == state_vars);

        int j;

        //        X.rowGet(count - 1, dst);
        for (j = 0; j < state_vars; j += 1) {
            dst[j] = X.get(count - 1, j);
        }

        return state_vars;
    }

}
