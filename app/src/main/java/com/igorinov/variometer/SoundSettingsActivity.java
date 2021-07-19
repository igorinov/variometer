/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer;

import android.os.Bundle;

public class SoundSettingsActivity extends SettingsActivity {

    static public String PREF_SOUND_BASE_FREQ = "sound_base_freq";
    static public String PREF_SOUND_PARTIALS = "sound_partials";
    static public String PREF_SOUND_START_H = "sound_start_h";
    static public String PREF_SOUND_START_L = "sound_start_l";
    static public String PREF_SOUND_STOP_H = "sound_stop_h";
    static public String PREF_SOUND_STOP_L = "sound_stop_l";
    static public String PREF_SOUND_DECAY = "sound_decay";
    static public String PREF_SOUND_ENABLE = "sound_enable";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_settings);

        initEditFloat(R.id.const_alt_freq, PREF_SOUND_BASE_FREQ, 500);
        initEditInt(R.id.const_alt_freq, PREF_SOUND_PARTIALS, 4);
        initEditFloat(R.id.sound_start_h, PREF_SOUND_START_H, +0.3f);
        initEditFloat(R.id.sound_stop_h, PREF_SOUND_STOP_H, +0.2f);
        initEditFloat(R.id.sound_stop_l, PREF_SOUND_STOP_L, -0.2f);
        initEditFloat(R.id.sound_start_l, PREF_SOUND_START_L, -0.3f);
        initCompoundButton(R.id.enable_sound, PREF_SOUND_ENABLE, false);
        initCompoundButton(R.id.sound_decay, PREF_SOUND_DECAY, true);
    }
}
