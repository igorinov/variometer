/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package info.altimeter.variometer;

import static info.altimeter.variometer.PressureActivity.pressureFormats;
import static info.altimeter.variometer.PressureActivity.pressureUnits;
import static info.altimeter.variometer.PressureActivity.pressureUnitsR;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import info.altimeter.variometer.common.VerticalSpeedIndicator;

public class MainActivity extends AppCompatActivity {
    static final String TAG = "MainActivity";
    static final int UPDATE_INDICATORS_NOW = 42;

    MainPreferenceListener prefListener = new MainPreferenceListener();
    UpdateHandler updateHandler;
    VariometerService varioService;
    VariometerServiceConnection serviceConnection;
    VariometerService.VariometerServiceBinder serviceBinder;
    MainVarioCallback varioCallback = new MainVarioCallback();
    boolean boundToService = false;
    VerticalSpeedIndicator vsi;
    TextView viewAltitude;
    TextView viewAltitudeUnit;
    TextView viewPressureUnit;
    Button buttonStart;
    Button buttonStop;
    TextView viewPressure;
    SharedPreferences pref;
    double[] kB = { 1, 1, 1 };
    double[] kC = { 0, 0, 0 };
    boolean firstUpdate = true;

    /** Standard density of pressure noise, hPa */
    double sigma_p = 0.06;
    double sigma_a = 0.05;
    double sigma_vsi = 0.0625;
    double sigma_ivsi = 0.0039;
    double latitude = 45.0;
    float altitude = 0;
    float vspeed = 0;
    int t = 0;

    int type = TYPE_IVSI;
    int vsiLimit = 5;
    int vsiUnitIndex = 0;
    int pressureUnitIndex = 0;
    int altUnitIndex = 1;
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
    static final short REQUEST_CODE_PRESSURE = 9999;
    static final int TYPE_VSI = 0;
    static final int TYPE_IVSI = 1;

    float referencePressure = SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
    static final float[] vsiUnits = { 1, 0.51444f, 0.508f };
    String[] vsiUnitNames = null;
    String[] pressureUnitNames = null;

    private class MainPreferenceListener implements SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key == null) {
                // Yes, it can be null when apply() is called on a SharedPreferences.Editor
                return;
            }

            if (key.equals("pressure_unit")) {
                pressureUnitIndex = sharedPreferences.getInt(key, pressureUnitIndex);
                if (viewPressureUnit != null) {
                    viewPressureUnit.setText(pressureUnitNames[pressureUnitIndex]);
                }
            }

            if (key.equals("baro")) {
                referencePressure = sharedPreferences.getFloat(key, referencePressure);
                if (viewPressure != null) {
                    viewPressure.setText(String.format(pressureFormats[pressureUnitIndex], referencePressure * pressureUnitsR[pressureUnitIndex]));
                }
            }
        }
    }

    private class StartClick implements View.OnClickListener {

        @Override
        public void onClick(View view) {
            Intent intent = new Intent(MainActivity.this, VariometerService.class);
            startForegroundService(intent);
            if (buttonStart != null) {
                buttonStart.setEnabled(false);
            }
            if (buttonStop != null) {
                buttonStop.setEnabled(true);
            }
        }
    }

    private class StopClick implements View.OnClickListener {

        @Override
        public void onClick(View view) {
            if (varioService != null) {
                varioService.stopEverything();
                if (buttonStart != null) {
                    buttonStart.setEnabled(true);
                }
                if (buttonStop != null) {
                    buttonStop.setEnabled(false);
                }
            }
            vspeed = Float.NaN;
            altitude = Float.NaN;
//            vsi.setVSpeed(vspeed);
//            vsi.invalidate();
//            alt.setText("");
            updateHandler.sendEmptyMessage(UPDATE_INDICATORS_NOW);
        }
    }

    private class PressureClickListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            Intent intent = new Intent(MainActivity.this, PressureActivity.class);
            startActivityForResult(intent, REQUEST_CODE_PRESSURE);
        }
    }

    private class MainVarioCallback implements VariometerService.VarioCallback {

        @Override
        public void OnUpdate(float alt, float vspeed) {
            if (updateHandler == null) {
                return;
            }
            vsi.setVSpeed(vspeed);
            if (updateHandler.hasMessages(UPDATE_INDICATORS_NOW)) {
                return;
            }
            updateHandler.sendEmptyMessage(UPDATE_INDICATORS_NOW);
        }
    }
    private class VariometerServiceConnection implements ServiceConnection {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            VariometerService.VariometerServiceBinder binder;

            binder = (VariometerService.VariometerServiceBinder) service;
            varioService = binder.getService();
            varioService.setVarioCallback(varioCallback);
            boundToService = true;
            boolean hasStarted = varioService.hasStarted();
            if (buttonStart != null) {
                buttonStart.setEnabled(!hasStarted);
            }
            if (buttonStop != null) {
                buttonStop.setEnabled(hasStarted);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            varioService.setVarioCallback(null);
            varioService = null;
            boundToService = false;
            if (buttonStart != null) {
                buttonStart.setEnabled(false);
            }
            if (buttonStop != null) {
                buttonStop.setEnabled(false);
            }
        }
    }

    class UpdateHandler extends Handler {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);

            if (msg.what == UPDATE_INDICATORS_NOW) {
                if (boundToService) {
                    vspeed = varioService.getVerticalSpeed();
                    altitude = varioService.getAltitude();
                } else {
                    vspeed = Float.NaN;
                    altitude = Float.NaN;
                }

                vsi.setVSpeed(vspeed);
                vsi.invalidate();
            }

            if (viewAltitude != null) {
                if (Float.isNaN(altitude)) {
                    viewAltitude.setText("");
                } else {
                    viewAltitude.setText(Integer.toString(Math.round(altitude * 3.28084f)));
                }
            }
        }
    }
/*
    class VerticalSpeedListener implements Variometer.VariometerListener {
        @Override
        public void onStateUpdate(float h, float v) {
            altitude = h;
            vspeed = v;

            if (!updateHandler.hasMessages(WHAT)) {
                updateHandler.sendEmptyMessage(WHAT);
            }
        }
    }
*/

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
        referencePressure = pref.getFloat("baro", SensorManager.PRESSURE_STANDARD_ATMOSPHERE);

//        checkSensors();
        loadSettings();

        pref.registerOnSharedPreferenceChangeListener(prefListener);

        if(type != TYPE_IVSI)
            type = TYPE_VSI;

        if (keep_on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        vsiUnitNames = getResources().getStringArray(R.array.pref_unit_list_titles);
        vsi = findViewById(R.id.vsi);

        pressureUnitNames = getResources().getStringArray(R.array.p_units);
        viewPressure = findViewById(R.id.pressure);
        viewPressureUnit = findViewById(R.id.pressure_unit);
        if (viewPressureUnit != null) {
            viewPressureUnit.setText(pressureUnitNames[pressureUnitIndex]);
        }

        viewAltitude = findViewById(R.id.altitude);
        viewAltitudeUnit = findViewById(R.id.alt_unit);
        if (viewAltitudeUnit != null) {
            viewAltitudeUnit.setText(getResources().getStringArray(R.array.alt_units)[1]);
        }

        viewPressure.setOnClickListener(new PressureClickListener());

        buttonStart = findViewById(R.id.start);
        buttonStop = findViewById(R.id.stop);
        if (buttonStart != null) {
            buttonStart.setOnClickListener(new StartClick());
        }
        if (buttonStop != null) {
            buttonStop.setOnClickListener(new StopClick());
        }

        updateHandler = new UpdateHandler();

        serviceConnection = new VariometerServiceConnection();
/*
        if (varioService == null) {
            // Creating the service but not starting it yet
            Intent intent = new Intent(MainActivity.this, VariometerService.class);
            bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        }
*/
    }

    @Override
    public void onStop() {
        if (boundToService) {
            varioService.setVarioCallback(null);
            unbindService(serviceConnection);
            boundToService = false;
        }
        updateHandler.sendEmptyMessage(UPDATE_INDICATORS_NOW);

        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();

        if (!boundToService) {
            // Creating the service but not starting it yet
            Intent intent = new Intent(MainActivity.this, VariometerService.class);
            bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        }

        updateHandler.sendEmptyMessage(UPDATE_INDICATORS_NOW);
    }

    @Override
    public void onPause() {
//        vspeed = Float.NaN;
//        vsi.setVSpeed(vspeed);
//        vsi.invalidate();

        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();

        loadSettings();

        if (type == TYPE_IVSI) {
            vsi.setTypeName("IVSI");
        } else {
            vsi.setTypeName("");
            type = TYPE_VSI;
        }

        vsi.setUnit(vsiLimit, vsiUnits[vsiUnitIndex], vsiUnitNames[vsiUnitIndex]);
        viewAltitudeUnit.setText(getResources().getStringArray(R.array.alt_units)[altUnitIndex]);

        viewPressure.setText(String.format(pressureFormats[pressureUnitIndex], referencePressure * pressureUnitsR[pressureUnitIndex]));
    }

    protected void loadSettings() {
        SharedPreferences.Editor editor = pref.edit();
        boolean pref_valid = pref.contains(IndicatorSettingsActivity.PREF_TYPE);
        try {
            type = pref.getInt(IndicatorSettingsActivity.PREF_TYPE, type);
            vsiLimit = pref.getInt(IndicatorSettingsActivity.PREF_SCALE_LIMIT, vsiLimit);
            vsiUnitIndex = pref.getInt(IndicatorSettingsActivity.PREF_UNIT_INDEX, vsiUnitIndex);
            pressureUnitIndex = pref.getInt("pressure_unit", pressureUnitIndex);
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
        SensorManager manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) {
            return;
        }

        Sensor rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            type = TYPE_VSI;
        }
    }
}
