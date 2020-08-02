/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer;

import android.os.Bundle;

public class IndicatorSettingsActivity extends SettingsActivity {

    static public String PREF_SCALE_LIMIT = "scale_limit";
    static public String PREF_TYPE = "filter_type";
    static public String PREF_UNIT_INDEX = "unit_index";
    static public String PREF_KEEP_SCREEN = "keep_screen";
    static public String PREF_SOUND_ENABLE = "sound_enable";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_indicator_settings);

        initSpinner(R.id.filter_type, R.array.pref_sensor_sets, PREF_TYPE, 1);
        initEditInt(R.id.scale_limit, PREF_SCALE_LIMIT, 1);
        initSpinner(R.id.vsi_unit, R.array.pref_unit_list_titles, PREF_UNIT_INDEX, 0);
        initCompoundButton(R.id.keep_screen, PREF_KEEP_SCREEN, true);
    }
}
