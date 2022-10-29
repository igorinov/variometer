/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package info.altimeter.variometer;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import info.altimeter.variometer.common.Variometer;
import info.altimeter.variometer.common.VerticalSpeedIndicator;

import static android.media.AudioFormat.CHANNEL_OUT_MONO;
import static android.media.AudioFormat.ENCODING_PCM_16BIT;

public class MainActivity extends AppCompatActivity {
    UpdateHandler updateHandler;
    Variometer variometer;
    VerticalSpeedListener varioListener;
    VerticalSpeedIndicator vsi;
    TextView info;
    SharedPreferences pref;
    SensorManager manager;
    Sensor pressureSensor;
    double[] input = new double[2];
    double[] kB = { 1, 1, 1 };
    double[] kC = { 0, 0, 0 };
    boolean firstUpdate = true;

    /** Standard density of pressure noise, hPa */
    double sigma_p = 0.06;
    double sigma_a = 0.05;
    double sigma_vsi = 0.0625;
    double sigma_ivsi = 0.0039;
    double latitude = 45.0;
    boolean knownRotation = false;
    boolean knownAltitude = false;
    boolean flying = false;
    float vspeed = 0;
    int t = 0;

    int type = TYPE_IVSI;
    int vsiLimit = 5;
    int vsiUnitIndex = 0;
    int smoother_lag = 5;
    boolean keep_on = true;

    boolean soundEnabled = false;
    boolean soundDecay = true;
    float soundStartH = +0.3f;
    float soundStopH = +0.2f;
    float soundStopL = -0.2f;
    float soundStartL = -0.3f;
    float soundOctaveDiff = 3.0f;
    float soundBaseFreq = 500;
    int soundPartials = 4;
    float soundIHC = 0.0001f;  // Inharmonicity coefficient

    static final short REQUEST_CODE_PREFERENCES = 16384;
    static final short REQUEST_CODE_CALIBRATION = 32767;
    static final int TYPE_VSI = 0;
    static final int TYPE_IVSI = 1;

    static final float[] vsiUnits = {1, 0.51444f, 0.508f};
    String[] vsiUnitNames = null;

    AudioThread beepingThread;

    public class AudioThread extends Thread {
        AudioTrack track;
        short[] audioData;
        int sample_rate = 24000;
        double[] partPhase;
        double[] partFreq;
        double[] partAmpl;
        double v0 = 0;
        double t = 0;
        int periods = 0;
        int nPartials = 0;
        double max_sample = 0;
        double decay = Math.log(1000);  // Signal amplitude becomes 1000 times smaller in 1 s.
        boolean soundOn = false;

        private void init(int p) {
            int k;
            double b = soundIHC;

            nPartials = p;
            max_sample = 16384.0 / nPartials;
            partFreq = new double[nPartials];
            partAmpl = new double[nPartials];
            partPhase = new double[nPartials];
            for (k = 0; k < nPartials; k += 1) {
                double n = k + 1;
                double a = Math.sqrt(1 + b * n * n);
                partFreq[k] = n * soundBaseFreq * a;
                partPhase[k] = 0;
                partAmpl[k] = 1.0 / n;
            }
        }

        private void ding() {
            int k;

            for (k = 0; k < partPhase.length; k += 1)
                partPhase[k] = 0;

            t = 0;
        }

        private void fillBuffer(short[] data, int off, int length) {
            double v1 = vspeed;
            double v;
            double sample_period = 1.0 / sample_rate;

            // Exponent Multiplier: beep frequency doubles every X m/s
            double em = Math.log(2) / soundOctaveDiff;

            double amp, fm, dph;
            int i, k;

            double r_length = 1.0 / length;

            if (soundOn) {
                if (v1 > soundStopL && v1 < soundStopH) {
                    soundOn = false;
                }
            } else {
                if (v1 < soundStartL || v1 > soundStartH) {
                    soundOn = true;
                }
            }

            if (!soundOn) {
                for (i = 0; i < length; i += 1) {
                    data[i] = 0;
                }
                v0 = v1;
                return;
            }

            for (i = 0; i < length; i += 1) {
                v = v0 + (v1 - v0) * i * r_length;
                fm = Math.exp(v * em);
                if (soundDecay) {
                    amp = Math.exp(-decay * (t++) * fm * sample_period);
                } else {
                    amp = periods > 125 ? 0 : 1;
                }
                double sample = 0;

                for (k = 0; k < nPartials; k += 1) {
                    double f = fm * partFreq[k];
                    double fa;

                    // Low-pass filter to cut everything approaching fs/2
                    if (f * 2 >= sample_rate)
                        continue;
                    fa = 1.0 / Math.cbrt(1.0 - 2 * f * sample_period);

                    // Phase increment per sample
                    dph = 2 * Math.PI * f * sample_period;
                    sample += amp * fa * partAmpl[k] * max_sample * Math.sin(partPhase[k]);

                    partPhase[k] += dph;
                    if (partPhase[k] > Math.PI) {
                        partPhase[k] -= 2 * Math.PI;
                        // Ding every 250 periods of the first harmonic
                        if (k == 0) {
                            periods += 1;
                            if (periods >= 250) {
                                periods = 0;
                                ding();
                            }
                        }
                    }
                }

                data[off + i] = (short) Math.round(sample);
            }
            v0 = v1;
        }

        @Override
        public void run() {
            if (Build.VERSION.SDK_INT >= 21) {
                AudioAttributes.Builder attributeBuilder = new AudioAttributes.Builder();
                AudioFormat.Builder formatBuilder = new AudioFormat.Builder();

                attributeBuilder.setUsage(AudioAttributes.USAGE_MEDIA);
                attributeBuilder.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC);
                AudioAttributes attributes = attributeBuilder.build();

                formatBuilder.setChannelMask(CHANNEL_OUT_MONO);
                formatBuilder.setSampleRate(sample_rate);
                formatBuilder.setEncoding(ENCODING_PCM_16BIT);
                AudioFormat format = formatBuilder.build();

                track = new AudioTrack(attributes, format, sample_rate / 20,
                        AudioTrack.MODE_STREAM, 1);
            } else {
                track = new AudioTrack(AudioManager.STREAM_MUSIC, sample_rate,
                        CHANNEL_OUT_MONO, ENCODING_PCM_16BIT,
                        AudioTrack.MODE_STREAM, AudioTrack.MODE_STREAM);
            }

            init(soundPartials);
            track.play();

            int size = sample_rate / 20;
            audioData = new short[size];

            while (flying) {
                fillBuffer(audioData, 0, size);
                track.write(audioData, 0, size);
            }
            track.stop();
            track.release();
        }
    }

    class UpdateHandler extends Handler {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == VerticalSpeedListener.WHAT)
            vsi.setVSpeed(vspeed);
            vsi.invalidate();
        }
    }

    class VerticalSpeedListener implements Variometer.VariometerListener {
        static final int WHAT = 1;
        @Override
        public void onVerticalSpeedUpdate(float v) {
            vspeed = v;

            if (!updateHandler.hasMessages(WHAT)) {
                updateHandler.sendEmptyMessage(WHAT);
            }
        }
    }

    /*
     * Main menu
     */

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        SharedPreferences.Editor editor;

        switch (item.getItemId()) {
            case R.id.defaults:
                editor = pref.edit();
                editor.clear();
                editor.apply();
                recreate();
                break;

            case R.id.accelerometer_calibration:
                intent = new Intent(this, CalibrationActivity.class);
                startActivityForResult(intent, REQUEST_CODE_CALIBRATION);
                break;

            case R.id.indicator_settings:
                intent = new Intent(this, IndicatorSettingsActivity.class);
                startActivityForResult(intent, REQUEST_CODE_PREFERENCES);
                break;

            case R.id.sound_settings:
                intent = new Intent(this, SoundSettingsActivity.class);
                startActivityForResult(intent, REQUEST_CODE_PREFERENCES);
                break;

            case R.id.filter_parameters:
                intent = new Intent(this, FilterParametersActivity.class);
                startActivityForResult(intent, REQUEST_CODE_PREFERENCES);
                break;

            case R.id.about:
                Dialog aDialog = new Dialog(this, R.style.Dialog);
                aDialog.setContentView(R.layout.about);
                aDialog.setTitle(getString(R.string.about));
                aDialog.setCancelable(true);
                aDialog.show();
                break;

            default:
                return super.onOptionsItemSelected(item);
        }

        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        MenuItem item = menu.findItem(R.id.enable_sound);
        if (item != null) {
            item.setChecked(soundEnabled);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);

        if (request == REQUEST_CODE_PREFERENCES) {
            recreate();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

//        checkSensors();
        loadSettings();

        if(type != TYPE_IVSI)
            type = TYPE_VSI;

        if (keep_on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        vsiUnitNames = getResources().getStringArray(R.array.pref_unit_list_titles);
        vsi = findViewById(R.id.vsi);
        info = findViewById(R.id.info);

        manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (manager == null)
            return;

        pressureSensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE);

        updateHandler = new UpdateHandler();
    }

    @Override
    public void onStop() {
        variometer.stop(this);
        super.onStop();
    }

    @Override
    public void onPause() {
        flying = false;
        variometer.stop(this);
        knownAltitude = false;
        knownRotation = false;
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        knownRotation = false;
        knownAltitude = false;
        firstUpdate = true;
        flying = true;
        input[1] = 0;

        loadSettings();

        if (type == TYPE_IVSI) {
            vsi.setTypeName("IVSI");
        } else {
            vsi.setTypeName("");
            type = TYPE_VSI;
        }

        vsi.setUnit(vsiLimit, vsiUnits[vsiUnitIndex], vsiUnitNames[vsiUnitIndex]);

        if (type == TYPE_VSI) {
            variometer = new Variometer(false, smoother_lag);
        }

        if (type == TYPE_IVSI) {
            variometer = new Variometer(true, smoother_lag);
            variometer.setProcessNoise(sigma_ivsi);
        }

        if (pressureSensor == null) {
            Toast.makeText(this, "No pressure sensor", Toast.LENGTH_LONG)
                    .show();
        }

        if (type == TYPE_VSI && pressureSensor != null) {
            variometer.setProcessNoise(sigma_vsi);
        }

        varioListener = new VerticalSpeedListener();
        variometer.setLatitude(latitude);
        variometer.setAccelerometerCorrection(kB, kC);
        variometer.setAccelerometerNoise(sigma_a);
        variometer.setPressureNoise(sigma_p);
        variometer.setListener(varioListener);
        variometer.start(this);

        if (soundEnabled) {
            beepingThread = new AudioThread();
            beepingThread.start();
        }
    }

    protected void loadSettings() {
        SharedPreferences.Editor editor = pref.edit();
        boolean pref_valid = pref.contains(IndicatorSettingsActivity.PREF_TYPE);
        try {
            soundEnabled = pref.getBoolean(SoundSettingsActivity.PREF_SOUND_ENABLE, soundEnabled);
            soundDecay = pref.getBoolean(SoundSettingsActivity.PREF_SOUND_DECAY, soundDecay);
            soundBaseFreq = pref.getFloat(SoundSettingsActivity.PREF_BASE_FREQ, soundBaseFreq);
            soundOctaveDiff = pref.getFloat(SoundSettingsActivity.PREF_OCTAVE_DIFF, soundOctaveDiff);
            soundPartials = pref.getInt(SoundSettingsActivity.PREF_PARTIALS, soundPartials);
            soundIHC = pref.getFloat(SoundSettingsActivity.PREF_INHARMONICITY, soundIHC);
            soundStartH = pref.getFloat(SoundSettingsActivity.PREF_SOUND_START_H, soundStartH);
            soundStopH = pref.getFloat(SoundSettingsActivity.PREF_SOUND_STOP_H, soundStopH);
            soundStopL = pref.getFloat(SoundSettingsActivity.PREF_SOUND_STOP_L, soundStopL);
            soundStartL = pref.getFloat(SoundSettingsActivity.PREF_SOUND_START_L, soundStartL);

            type = pref.getInt(IndicatorSettingsActivity.PREF_TYPE, type);
            vsiLimit = pref.getInt(IndicatorSettingsActivity.PREF_SCALE_LIMIT, vsiLimit);
            vsiUnitIndex = pref.getInt(IndicatorSettingsActivity.PREF_UNIT_INDEX, vsiUnitIndex);
            keep_on = pref.getBoolean(IndicatorSettingsActivity.PREF_KEEP_SCREEN, keep_on);

            smoother_lag = pref.getInt(FilterParametersActivity.PREF_SMOOTHER_LAG, smoother_lag);
            sigma_vsi = pref.getFloat(FilterParametersActivity.PREF_SIGMA_1, (float) sigma_vsi);
            sigma_ivsi = pref.getFloat(FilterParametersActivity.PREF_SIGMA_2, (float) sigma_ivsi);
            sigma_a = pref.getFloat(FilterParametersActivity.PREF_SIGMA_A, (float) sigma_a);
            sigma_p = pref.getFloat(FilterParametersActivity.PREF_SIGMA_P, (float) sigma_p);
            latitude = pref.getFloat(FilterParametersActivity.PREF_LATITUDE, (float) latitude);
            kB[0] = pref.getFloat(FilterParametersActivity.PREF_WEIGHT_X, 0) + 1.0;
            kB[1] = pref.getFloat(FilterParametersActivity.PREF_WEIGHT_Y, 0) + 1.0;
            kB[2] = pref.getFloat(FilterParametersActivity.PREF_WEIGHT_Z, 0) + 1.0;
            kC[0] = pref.getFloat(FilterParametersActivity.PREF_BIAS_X, 0);
            kC[1] = pref.getFloat(FilterParametersActivity.PREF_BIAS_Y, 0);
            kC[2] = pref.getFloat(FilterParametersActivity.PREF_BIAS_Z, 0);
        } catch (ClassCastException exception) {
            pref_valid = false;
        }

        if (!pref_valid) {
            checkSensors();
            editor.putBoolean(SoundSettingsActivity.PREF_SOUND_ENABLE, soundEnabled);
            editor.putBoolean(SoundSettingsActivity.PREF_SOUND_DECAY, soundDecay);
            editor.putFloat(SoundSettingsActivity.PREF_BASE_FREQ, soundBaseFreq);
            editor.putFloat(SoundSettingsActivity.PREF_OCTAVE_DIFF, soundOctaveDiff);
            editor.putInt(SoundSettingsActivity.PREF_PARTIALS, soundPartials);
            editor.putFloat(SoundSettingsActivity.PREF_INHARMONICITY, soundIHC);
            editor.putFloat(SoundSettingsActivity.PREF_SOUND_START_H, soundStartH);
            editor.putFloat(SoundSettingsActivity.PREF_SOUND_STOP_H, soundStopH);
            editor.putFloat(SoundSettingsActivity.PREF_SOUND_STOP_L, soundStopL);
            editor.putFloat(SoundSettingsActivity.PREF_SOUND_START_L, soundStartL);

            editor.putInt(IndicatorSettingsActivity.PREF_TYPE, type);
            editor.putInt(IndicatorSettingsActivity.PREF_SCALE_LIMIT, vsiLimit);
            editor.putInt(IndicatorSettingsActivity.PREF_UNIT_INDEX, vsiUnitIndex);
            editor.putBoolean(IndicatorSettingsActivity.PREF_KEEP_SCREEN, keep_on);

            editor.putInt(FilterParametersActivity.PREF_SMOOTHER_LAG, smoother_lag);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_1, (float) sigma_vsi);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_2, (float) sigma_ivsi);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_A, (float) sigma_a);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_P, (float) sigma_p);
            editor.putFloat(FilterParametersActivity.PREF_LATITUDE, (float) latitude);
            editor.putFloat(FilterParametersActivity.PREF_WEIGHT_X, (float) (kB[0] - 1.0));
            editor.putFloat(FilterParametersActivity.PREF_WEIGHT_Y, (float) (kB[1] - 1.0));
            editor.putFloat(FilterParametersActivity.PREF_WEIGHT_Z, (float) (kB[2] - 1.0));
            editor.putFloat(FilterParametersActivity.PREF_BIAS_X, (float) kC[0]);
            editor.putFloat(FilterParametersActivity.PREF_BIAS_Y, (float) kC[1]);
            editor.putFloat(FilterParametersActivity.PREF_BIAS_Z, (float) kC[2]);
            editor.apply();

            Intent intent = new Intent(this, LatitudeActivity.class);
            startActivity(intent);
        }
    }

    public void checkSensors() {
        manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) {
            return;
        }

        Sensor rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            type = TYPE_VSI;
        }
    }
}
