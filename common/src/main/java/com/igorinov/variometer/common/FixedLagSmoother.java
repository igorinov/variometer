/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer.common;

public class FixedLagSmoother extends KalmanFilter {
    int lag_n;
    Matrix X;
    Matrix Ps, HTSI, F_LH;
    double x_k[];
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

    public int filterUpdate(double[] z) {
        int n = lag_n;
        int i;

        super.filterUpdate(z);

        X.shiftDown(1);
        X.rowSet(0, x_prior);

        HTSI.dotProduct(H_T, S_inv);

        tmp_ss.copy(F);
        tmp_ss.subProduct(K, H);
        F_LH.transpose(tmp_ss);

        if (count < lag_n) {
            n = count;
        }

        Ps.copy(P);
        for (i = 0; i < n; i += 1) {
            K.dotProduct(Ps, HTSI);

            tmp_ss.dotProduct(Ps, F_LH);
            Ps.copy(tmp_ss);

            X.rowGet(i, x_k);
            K.vmuladd(x_k, y);
            X.rowSet(i, x_k);
        }

        return 0;
    }

    public int setState(double[] src) {
        int i;

        for (i = 0; i < lag_n; i += 1) {
            X.rowSet(i, src);
        }
        count = 0;

        return super.setState(src);
    }

    public int getState(double[] dst) {
        if (count < lag_n)
            System.arraycopy(x, 0, dst, 0, state_vars);
        else
            X.rowGet(lag_n - 1, dst);

        return state_vars;
    }

}
