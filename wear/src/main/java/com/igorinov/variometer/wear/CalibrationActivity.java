package com.igorinov.variometer.wear;

import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.support.wearable.activity.WearableActivity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.igorinov.variometer.common.Variometer;

import java.util.Locale;

public class CalibrationActivity extends WearableActivity {

    private SharedPreferences pref;
    private TextView textStep;
    private TextView textNext;
    VibrationEffect effect;
    SensorManager manager;
    Sensor accelerometer;
    float[] scale;
    float[] bias;
    float[] data;
    int calibrationIndex = 0;
    AccelerationListener listener;
    InternalMessageHandler handler;
    static final int MAX_POINTS = 6;
    static int string_ids[] = {
            -1,
            R.string.calibration_step1,
            R.string.calibration_step2,
            R.string.calibration_step3,
            R.string.calibration_step4,
            R.string.calibration_step5,
            R.string.calibration_step6
    };

    private void returnResult(int resultCode) {
        this.setResult(resultCode);
    }

    private class AccelerationListener implements SensorEventListener {

        public void onAccuracyChanged(Sensor arg0, int arg1) {
        }

        public void onSensorChanged(SensorEvent arg0) {
            if (calibrationIndex >= MAX_POINTS)
                return;

            System.arraycopy(arg0.values, 0, data, calibrationIndex * 3, 3);
        }
    }

    public class InternalMessageHandler extends Handler {
        @Override
        public void dispatchMessage(Message msg) {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            vibrator.vibrate(effect);
            if (calibrationIndex >= MAX_POINTS) {
                optimize();
                return;
            }
            calibrationIndex += 1;
            // textView.setText(String.format(Locale.US, "%d", calibrationIndex));
            textStep.setText(getString(string_ids[calibrationIndex]));
        }
    }

    private void optimize() {
        boolean calibrated = true;
        bias[0] = 0;
        bias[1] = 0;
        bias[2] = 0;
        Variometer.biasUpdate(bias, scale, data, calibrationIndex * 3);
        SharedPreferences.Editor editor = pref.edit();
        if (Float.isNaN(bias[0]) || Float.isNaN(bias[1]) || Float.isNaN(bias[2]))
            calibrated = false;
        editor.putFloat("bias_x", bias[0]);
        editor.putFloat("bias_y", bias[1]);
        editor.putFloat("bias_z", bias[2]);
        editor.putFloat("scale_x", scale[0]);
        editor.putFloat("scale_y", scale[1]);
        editor.putFloat("scale_z", scale[2]);
        editor.putBoolean("calibrated", calibrated);
        editor.apply();
        returnResult(RESULT_OK);
        finish();
    }

    public class DoneButtonListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
        }
    }

    private void buttonClicked() {
        manager.registerListener(listener, accelerometer, 50000);
        handler.sendEmptyMessageDelayed(73, 1000);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){
        Intent intent;

        if (event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_STEM_1:
                case KeyEvent.KEYCODE_STEM_2:
                case KeyEvent.KEYCODE_STEM_3:
                    buttonClicked();
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibration);

        effect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE);

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        listener = new AccelerationListener();
        handler = new InternalMessageHandler();

        textStep = findViewById(R.id.text_step);

        textNext = findViewById(R.id.text_next);

        // Enables Always-on
        setAmbientEnabled();

        manager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        scale = new float[3];
        bias = new float[3];
        bias[0] = 0;
        bias[1] = 0;
        bias[2] = 0;
        data = new float[MAX_POINTS * 3];
        //handler.sendEmptyMessageDelayed(73, 1000);
    }
}
