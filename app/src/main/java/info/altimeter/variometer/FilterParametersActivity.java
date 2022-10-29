/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package info.altimeter.variometer;

import android.os.Bundle;

public class FilterParametersActivity extends SettingsActivity {

    static public String PREF_SMOOTHER_LAG = "smoother_lag";
    static public String PREF_SIGMA_1 = "sigma1";
    static public String PREF_SIGMA_2 = "sigma2";
    static public String PREF_SIGMA_P = "sigma_p";
    static public String PREF_SIGMA_A = "sigma_a";
    static public String PREF_WEIGHT_X = "weight_x";
    static public String PREF_WEIGHT_Y = "weight_y";
    static public String PREF_WEIGHT_Z = "weight_z";
    static public String PREF_BIAS_X = "bias_x";
    static public String PREF_BIAS_Y = "bias_y";
    static public String PREF_BIAS_Z = "bias_z";
    static public String PREF_LATITUDE = "latitude";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_parameters);

        initEditInt(R.id.smoother_lag, PREF_SMOOTHER_LAG, 0);
        initEditFloat(R.id.sigma1, PREF_SIGMA_1, 0);
        initEditFloat(R.id.sigma2, PREF_SIGMA_2, 0);
        initEditFloat(R.id.sigma_p, PREF_SIGMA_P, 0.05f);
        initEditFloat(R.id.sigma_a, PREF_SIGMA_A, 0.05f);
        initEditFloat(R.id.latitude, PREF_LATITUDE, 45);
        initEditFloat(R.id.weight_x, PREF_WEIGHT_X, 0);
        initEditFloat(R.id.weight_y, PREF_WEIGHT_Y, 0);
        initEditFloat(R.id.weight_z, PREF_WEIGHT_Z, 0);
        initEditFloat(R.id.bias_x, PREF_BIAS_X, 0);
        initEditFloat(R.id.bias_y, PREF_BIAS_Y, 0);
        initEditFloat(R.id.bias_z, PREF_BIAS_Z, 0);
    }
}
