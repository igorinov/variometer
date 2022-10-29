/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package info.altimeter.variometer;

import android.os.Bundle;

public class SoundSettingsActivity extends SettingsActivity {

    static public String PREF_BASE_FREQ = "sound_base_freq";
    static public String PREF_OCTAVE_DIFF = "sound_octave_diff";
    static public String PREF_PARTIALS = "sound_partials";
    static public String PREF_INHARMONICITY = "sound_inharmonicity";
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

        initEditFloat(R.id.const_alt_freq, PREF_BASE_FREQ, 500);
        initEditFloat(R.id.octave_diff, PREF_OCTAVE_DIFF, 3);
        initEditInt(R.id.sound_partials, PREF_PARTIALS, 4);
        initEditFloat(R.id.sound_inharmonic, PREF_INHARMONICITY, 0);
        initEditFloat(R.id.sound_start_h, PREF_SOUND_START_H, +0.25f);
        initEditFloat(R.id.sound_stop_h, PREF_SOUND_STOP_H, +0.1875f);
        initEditFloat(R.id.sound_stop_l, PREF_SOUND_STOP_L, -0.1875f);
        initEditFloat(R.id.sound_start_l, PREF_SOUND_START_L, -0.25f);
        initCompoundButton(R.id.enable_sound, PREF_SOUND_ENABLE, false);
        initCompoundButton(R.id.sound_decay, PREF_SOUND_DECAY, true);
    }
}
