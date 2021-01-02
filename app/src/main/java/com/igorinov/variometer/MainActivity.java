/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.igorinov.variometer.common.FixedLagSmoother;
import com.igorinov.variometer.common.KalmanFilter;
import com.igorinov.variometer.common.VerticalSpeedIndicator;

import static android.media.AudioFormat.CHANNEL_OUT_MONO;
import static android.media.AudioFormat.ENCODING_PCM_16BIT;

public class MainActivity extends AppCompatActivity {
    AtmosphereModel atmosphere;
    KalmanFilter filter;
    PressureListener listenerP;
    AccelerationListener listenerA;
    RotationListener listenerR;
    VerticalSpeedIndicator vsi;
    TextView info;
    SharedPreferences pref;
    SensorManager manager;
    Sensor pressureSensor;
    Sensor rotationSensor;
    Sensor accelerometers;
    double sensorPeriod = 1e-3;
    int period_us = 1000;
    double[] input = new double[2];
    double[] state = new double[3];
    float[] dataA = new float[3];
    float[] biasA = new float[3];
    double[] scaleA = { 1, 1, 1 };
    double[] acc = new double[4];
    double[] q = new double[4];
    double[] q1 = new double[4];
    double[] v = new double[4];
    boolean firstUpdate = true;
    boolean realPartMayBeMissing = false;
    double sigma_h = 0.42;
    double sigma_a = 0.3;
    double sigma_vsi = 0.0625;
    double sigma_ivsi = 0.0039;
    boolean knownRotation = false;
    boolean knownAltitude = false;
    boolean flying = false;
    boolean beepEnabled = false;
    float vspeed = 0;
    int t = 0;

    int type = TYPE_IVSI;
    int vsiLimit = 5;
    int vsiUnitIndex = 0;
    int smoother_lag = 48;
    boolean keep_on = true;

    static final short REQUEST_CODE_PREFERENCES = 16384;
    static final short REQUEST_CODE_CALIBRATION = 32767;
    static final int TYPE_VSI = 0;
    static final int TYPE_IVSI = 1;

    /*  Values for state covariance initialization, with
     *  high confidence in zero vertical speed on startup
     */
    static final double[] p_init = { 10000.0, 0.01, 10.0 };

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

        private void init(int p) {
            int k;
            double B = 0.0001;  // Inharmonicity coefficient

            nPartials = p;
            max_sample = 16384.0 / nPartials;
            partFreq = new double[nPartials];
            partAmpl = new double[nPartials];
            partPhase = new double[nPartials];
            for (k = 0; k < nPartials; k += 1) {
                double n = k + 1;
                double a = Math.sqrt(1 + B * n * n);
                partFreq[k] = n * 500 * a;
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

            // Exponent Multiplier: beep frequency doubles every +3 m/s
            double em = Math.log(2) / 3;

            double amp, fm, dph;
            int i, k;

            double r_length = 1.0 / length;

            for (i = 0; i < length; i += 1) {
                v = v0 + (v1 - v0) * i * r_length;
                fm = Math.exp(v * em);
                amp = Math.exp(-decay * (t++) * fm * sample_period);
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

            init(4);
            track.play();

            int size = sample_rate / 20;
            audioData = new short[size];

            while (flying) {
                // To be implemented
                fillBuffer(audioData, 0, size);
                track.write(audioData, 0, size);
            }
            track.stop();
            track.release();
        }
    }

    /*
     *  Quaternion multiplication
     *  q = a + bi + cj + dk
     */

    void HamiltonProduct(double[] dst, double[] src) {
        double a1, b1, c1, d1;
        double a2, b2, c2, d2;

        a1 = dst[3];
        b1 = dst[0];
        c1 = dst[1];
        d1 = dst[2];

        a2 = src[3];
        b2 = src[0];
        c2 = src[1];
        d2 = src[2];

        dst[3] = a1 * a2 - b1 * b2 - c1 * c2 - d1 * d2;
        dst[0] = a1 * b2 + b1 * a2 + c1 * d2 - d1 * c2;
        dst[1] = a1 * c2 - b1 * d2 + c1 * a2 + d1 * b2;
        dst[2] = a1 * d2 + b1 * c2 - c1 * b2 + d1 * a2;
    }

    /*
     *  Standard atmosphere model
     */

    public class AtmosphereModel {
        double H = 44330.77;
        double n1 = 5.25593;
        double inv_n1;
        double inv_p0;

        AtmosphereModel() {
            inv_p0 = 1.0 / SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
            inv_n1 = 1.0 / n1;
        }

        double getAltitude(double p) {
            return H * (1 - Math.pow(p * inv_p0, inv_n1));
        }
    }

    void filterUpdate() {
        if (firstUpdate) {
            state[0] = input[0];
            state[1] = 0;
            state[2] = input[1];
            filter.setState(state);
            firstUpdate = false;
        }

        filter.filterPredict(null);
        filter.filterUpdate(input);

        filter.getState(state);
        vspeed = (float) state[1];

        t += period_us;
        if (t >= 40000) {
            t -= 40000;
            vsi.setVSpeed(vspeed);
        }
    }

    private class PressureListener implements SensorEventListener {

        public void onAccuracyChanged(Sensor arg0, int arg1) {
        }

        public void onSensorChanged(SensorEvent arg0) {
            float p = arg0.values[0];
            double alt;

            if (p == 0)
                return;

            alt = atmosphere.getAltitude(p);
            input[0] = alt;

            knownAltitude = true;

            if (type == TYPE_VSI)
                filterUpdate();
        }
    }

    private class AccelerationListener implements SensorEventListener {

        public void onAccuracyChanged(Sensor arg0, int arg1) {
        }

        public void onSensorChanged(SensorEvent arg0) {
            if (!knownRotation)
                return;

            if (!knownAltitude)
                return;

            /*
             *  Transform the acceleration vector to the reference coordinate system
             *  of the rotation sensor, where Z axis is vertical and points up
             */

            System.arraycopy(arg0.values, 0, dataA, 0, 3);

            acc[0] = (dataA[0] - biasA[0]) * scaleA[0];
            acc[1] = (dataA[1] - biasA[1]) * scaleA[1];
            acc[2] = (dataA[2] - biasA[2]) * scaleA[2];
            acc[3] = 0;

            q1[0] = - q[0];
            q1[1] = - q[1];
            q1[2] = - q[2];
            q1[3] = q[3];

            System.arraycopy(q, 0, v, 0, 4);
            HamiltonProduct(v, acc);
            HamiltonProduct(v, q1);

            if(arg0.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                input[1] = (v[2] - SensorManager.GRAVITY_EARTH);
            }

            if(arg0.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                input[1] = v[2];
            }

            if (type == TYPE_IVSI)
                filterUpdate();
        }
    }

    private class RotationListener implements SensorEventListener {

        public void onAccuracyChanged(Sensor arg0, int arg1) {
        }

        public void onSensorChanged(SensorEvent event) {
            double x, y, z;
            double ll;

            q[0] = event.values[0];
            q[1] = event.values[1];
            q[2] = event.values[2];
            q[3] = event.values[3];
            if (realPartMayBeMissing) {
                // Compute real part of a unit quaternion from the other 3 components
                x = q[0];
                y = q[1];
                z = q[2];
                ll = x * x + y * y + z * z;
                q[3] = Math.sqrt(1 - ll);
            }

            knownRotation = true;
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

            case R.id.enable_sound:
                editor = pref.edit();
                beepEnabled = !item.isChecked();
                item.setChecked(beepEnabled);
                beepEnabled = item.isChecked();
                editor.putBoolean(IndicatorSettingsActivity.PREF_SOUND_ENABLE, beepEnabled);
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
        if (item != null)
            item.setChecked(beepEnabled);
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

        atmosphere = new AtmosphereModel();

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor editor = pref.edit();
        boolean pref_valid = pref.contains(IndicatorSettingsActivity.PREF_TYPE);
        try {
            beepEnabled = pref.getBoolean(IndicatorSettingsActivity.PREF_SOUND_ENABLE, beepEnabled);
            type = pref.getInt(IndicatorSettingsActivity.PREF_TYPE, type);
            vsiLimit = pref.getInt(IndicatorSettingsActivity.PREF_SCALE_LIMIT, vsiLimit);
            vsiUnitIndex = pref.getInt(IndicatorSettingsActivity.PREF_UNIT_INDEX, vsiUnitIndex);
            keep_on = pref.getBoolean(IndicatorSettingsActivity.PREF_KEEP_SCREEN, keep_on);

            smoother_lag = pref.getInt(FilterParametersActivity.PREF_SMOOTHER_LAG, smoother_lag);
            sigma_vsi = pref.getFloat(FilterParametersActivity.PREF_SIGMA_1, (float) sigma_vsi);
            sigma_ivsi = pref.getFloat(FilterParametersActivity.PREF_SIGMA_2, (float) sigma_ivsi);
            sigma_a = pref.getFloat(FilterParametersActivity.PREF_SIGMA_A, (float) sigma_a);
            sigma_h = pref.getFloat(FilterParametersActivity.PREF_SIGMA_H, (float) sigma_h);
            biasA[0] = pref.getFloat(FilterParametersActivity.PREF_BIAS_X, biasA[0]);
            biasA[1] = pref.getFloat(FilterParametersActivity.PREF_BIAS_Y, biasA[1]);
            biasA[2] = pref.getFloat(FilterParametersActivity.PREF_BIAS_Z, biasA[2]);
            scaleA[0] = pref.getFloat(FilterParametersActivity.PREF_SCALE1_X, 0) + 1.0;
            scaleA[1] = pref.getFloat(FilterParametersActivity.PREF_SCALE1_Y, 0) + 1.0;
            scaleA[2] = pref.getFloat(FilterParametersActivity.PREF_SCALE1_Z, 0) + 1.0;
        } catch (ClassCastException exception) {
            pref_valid = false;
        }

        if (!pref_valid) {
            editor.putBoolean(IndicatorSettingsActivity.PREF_SOUND_ENABLE, beepEnabled);
            editor.putInt(IndicatorSettingsActivity.PREF_TYPE, type);
            editor.putInt(IndicatorSettingsActivity.PREF_SCALE_LIMIT, vsiLimit);
            editor.putInt(IndicatorSettingsActivity.PREF_UNIT_INDEX, vsiUnitIndex);
            editor.putBoolean(IndicatorSettingsActivity.PREF_KEEP_SCREEN, keep_on);

            editor.putInt(FilterParametersActivity.PREF_SMOOTHER_LAG, smoother_lag);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_1, (float) sigma_vsi);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_2, (float) sigma_ivsi);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_A, (float) sigma_a);
            editor.putFloat(FilterParametersActivity.PREF_SIGMA_H, (float) sigma_h);
            editor.putFloat(FilterParametersActivity.PREF_BIAS_X, biasA[0]);
            editor.putFloat(FilterParametersActivity.PREF_BIAS_Y, biasA[1]);
            editor.putFloat(FilterParametersActivity.PREF_BIAS_Z, biasA[2]);
            editor.putFloat(FilterParametersActivity.PREF_SCALE1_X, (float) (scaleA[0] - 1.0));
            editor.putFloat(FilterParametersActivity.PREF_SCALE1_Y, (float) (scaleA[1] - 1.0));
            editor.putFloat(FilterParametersActivity.PREF_SCALE1_Z, (float) (scaleA[2] - 1.0));
            editor.apply();
        }

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

        listenerR = new RotationListener();
        listenerA = new AccelerationListener();
        listenerP = new PressureListener();

        manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (manager == null)
            return;

        pressureSensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE);
    }

    @Override
    public void onStop() {
        manager.unregisterListener(listenerP);
        manager.unregisterListener(listenerA);
        manager.unregisterListener(listenerR);
        super.onStop();
    }

    @Override
    public void onPause() {
        flying = false;
        manager.unregisterListener(listenerP);
        manager.unregisterListener(listenerA);
        manager.unregisterListener(listenerR);
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

        try {
            beepEnabled = pref.getBoolean(IndicatorSettingsActivity.PREF_SOUND_ENABLE, beepEnabled);
            type = pref.getInt(IndicatorSettingsActivity.PREF_TYPE, type);
            vsiLimit = pref.getInt(IndicatorSettingsActivity.PREF_SCALE_LIMIT, vsiLimit);
            vsiUnitIndex = pref.getInt(IndicatorSettingsActivity.PREF_UNIT_INDEX, vsiUnitIndex);
            keep_on = pref.getBoolean(IndicatorSettingsActivity.PREF_KEEP_SCREEN, keep_on);

            smoother_lag = pref.getInt(FilterParametersActivity.PREF_SMOOTHER_LAG, smoother_lag);
            sigma_vsi = pref.getFloat(FilterParametersActivity.PREF_SIGMA_1, (float) sigma_vsi);
            sigma_ivsi = pref.getFloat(FilterParametersActivity.PREF_SIGMA_2, (float) sigma_ivsi);
            sigma_a = pref.getFloat(FilterParametersActivity.PREF_SIGMA_A, (float) sigma_a);
            sigma_h = pref.getFloat(FilterParametersActivity.PREF_SIGMA_H, (float) sigma_h);
            biasA[0] = pref.getFloat(FilterParametersActivity.PREF_BIAS_X, biasA[0]);
            biasA[1] = pref.getFloat(FilterParametersActivity.PREF_BIAS_Y, biasA[1]);
            biasA[2] = pref.getFloat(FilterParametersActivity.PREF_BIAS_Z, biasA[2]);
            scaleA[0] = pref.getFloat(FilterParametersActivity.PREF_SCALE1_X, 0) + 1.0;
            scaleA[1] = pref.getFloat(FilterParametersActivity.PREF_SCALE1_Y, 0) + 1.0;
            scaleA[2] = pref.getFloat(FilterParametersActivity.PREF_SCALE1_Z, 0) + 1.0;
        } catch (ClassCastException exception) {
            // ignore this exception
        }

        if (type == TYPE_IVSI) {
            vsi.setTypeName("IVSI");
        } else {
            vsi.setTypeName("");
            type = TYPE_VSI;
        }

        vsi.setUnit(vsiLimit, vsiUnits[vsiUnitIndex], vsiUnitNames[vsiUnitIndex]);

        if (type == TYPE_VSI) {
            if (smoother_lag > 0)
                filter = new FixedLagSmoother(2, 1, 0, smoother_lag);
            else
                filter = new KalmanFilter(2, 1, 0);

            filter.initCovariance(p_init);
        }

        if (type == TYPE_IVSI) {
            if (smoother_lag > 0)
                filter = new FixedLagSmoother(3, 2, 0, smoother_lag);
            else
                filter = new KalmanFilter(3, 2, 0);

            accelerometers = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotationSensor == null) {
                rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                realPartMayBeMissing = true;
            }

            if (period_us < accelerometers.getMinDelay()) {
                period_us = accelerometers.getMinDelay();
                sensorPeriod = period_us * 1e-6;
            }
            filter.setPeriod(sensorPeriod);
            filter.setProcessNoise(sensorPeriod, sigma_ivsi * sigma_ivsi);

            double[] r = { sigma_h, sigma_a };
            filter.setMeasurementError(r);
            filter.initCovariance(p_init);
        }

        if (pressureSensor == null) {
            Toast.makeText(this, "No pressure sensor", Toast.LENGTH_LONG)
                    .show();
        }

        if (type == TYPE_VSI && pressureSensor != null) {
            if (period_us < pressureSensor.getMinDelay()) {
                period_us = pressureSensor.getMinDelay();
                sensorPeriod = period_us * 1e-6;
            }
            filter.setPeriod(sensorPeriod);
            filter.setProcessNoise(sensorPeriod, sigma_vsi * sigma_vsi);
            double[] r = { sigma_h };
            filter.setMeasurementError(r);
        }

        if (rotationSensor != null) {
            manager.registerListener(listenerR, rotationSensor, period_us);
        }

        if (accelerometers != null) {
            manager.registerListener(listenerA, accelerometers, period_us);
        }

        if (pressureSensor != null) {
            manager.registerListener(listenerP, pressureSensor, period_us);
        }

        if (beepEnabled) {
            beepingThread = new AudioThread();
            beepingThread.start();
        }
    }
}
