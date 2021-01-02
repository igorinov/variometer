/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer;

import android.os.Bundle;

public class FilterParametersActivity extends SettingsActivity {

    static public String PREF_SMOOTHER_LAG = "smoother_lag";
    static public String PREF_SIGMA_1 = "sigma1";
    static public String PREF_SIGMA_2 = "sigma2";
    static public String PREF_SIGMA_H = "sigma_h";
    static public String PREF_SIGMA_A = "sigma_a";
    static public String PREF_BIAS_X = "bias_x";
    static public String PREF_BIAS_Y = "bias_y";
    static public String PREF_BIAS_Z = "bias_z";
    static public String PREF_SCALE1_X = "scale1p_x";
    static public String PREF_SCALE1_Y = "scale1p_y";
    static public String PREF_SCALE1_Z = "scale1p_z";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_parameters);

        initEditInt(R.id.smoother_lag, PREF_SMOOTHER_LAG, 0);
        initEditFloat(R.id.sigma1, PREF_SIGMA_1, 0);
        initEditFloat(R.id.sigma2, PREF_SIGMA_2, 0);
        initEditFloat(R.id.sigma_h, PREF_SIGMA_H, 0);
        initEditFloat(R.id.sigma_a, PREF_SIGMA_A, 0);
        initEditFloat(R.id.bias_a_x, PREF_BIAS_X, 0);
        initEditFloat(R.id.bias_a_y, PREF_BIAS_Y, 0);
        initEditFloat(R.id.bias_a_z, PREF_BIAS_Z, 0);
        initEditFloat(R.id.scale_a_x, PREF_SCALE1_X, 0);
        initEditFloat(R.id.scale_a_y, PREF_SCALE1_Y, 0);
        initEditFloat(R.id.scale_a_z, PREF_SCALE1_Z, 0);
    }
}
