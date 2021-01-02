package com.igorinov.variometer;

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
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.igorinov.variometer.common.Variometer;

public class CalibrationActivity extends AppCompatActivity {

    private SharedPreferences pref;
    private Button buttonNext;
    private Button buttonSkip;
    private TextView textStep;
    private TextView textNext;
    private TextView textCounter;
    private ImageView imagePhone;
    VibrationEffect effect;
    SensorManager manager;
    Sensor accelerometer;
    double[] scale;
    double[] bias;
    double[] data;
    double[] data2;
    int positionIndex = 0;
    int pointSampleCounter;
    int delayCounter = 0;
    boolean firstClick = true;
    AccelerationListener listener;
    InternalMessageHandler handler;
    static final int MAX_POSITIONS = 6;
    static final int SAMPLES_PER_POSITION = 512;
    static final int MESSAGE_COUNTDOWN = 54321;
    static final int MESSAGE_STOP_READING = 99999;
    static final int MESSAGE_OPTIMIZATION_DONE = 100;
    static int[] string_ids = {
            R.string.calibration_display_up,
            R.string.calibration_display_down,
            R.string.calibration_upright,
            R.string.calibration_left_side_down,
            R.string.calibration_upside_down,
            R.string.calibration_right_side_down
    };
    static int[] picture_ids = {
            R.drawable.phone_displayup,
            R.drawable.phone_displaydown,
            R.drawable.phone_upright,
            R.drawable.phone_leftsidedown,
            R.drawable.phone_upsidedown,
            R.drawable.phone_rightsidedown
    };
    static String[] factoryCalibratedModels = {
            "Google", "Pixel 2",
            "Google", "Pixel 3",
            "Google", "Pixel 4",
            "Google", "Pixel 5",
    };

    private void returnResult(int resultCode) {
        this.setResult(resultCode);
    }

    // Keeping long computations out of the main thread
    private class OptimizationThread extends Thread {
        @Override
        public void run() {
            bias[0] = 0;
            bias[1] = 0;
            bias[2] = 0;
            scale[0] = 1;
            scale[1] = 1;
            scale[2] = 1;
            Variometer.biasUpdate(bias, scale, data2, positionIndex * 3);

            handler.sendEmptyMessage(MESSAGE_OPTIMIZATION_DONE);
        }
    }

    private class AccelerationListener implements SensorEventListener {

        public void onAccuracyChanged(Sensor arg0, int arg1) {
        }

        public void onSensorChanged(SensorEvent arg0) {
            if (pointSampleCounter >= SAMPLES_PER_POSITION)
                return;

            int i = pointSampleCounter;
            int k;

            for (k = 0; k < 3; k += 1) {
                data[i * 3 + k] = arg0.values[k];
            }
            pointSampleCounter += 1;

            if (pointSampleCounter == SAMPLES_PER_POSITION) {
                handler.sendEmptyMessage(MESSAGE_STOP_READING);
            }
        }
    }

    private boolean processPositionData() {
        int i, j;
        double x, y, z;
        double dx, dy, dz;
        double var_x, var_y, var_z;

        x = 0;
        y = 0;
        z = 0;

        j = 0;
        for (i = 0; i < SAMPLES_PER_POSITION; i += 1) {
            x += data[j++];
            y += data[j++];
            z += data[j++];
        }
        x /= SAMPLES_PER_POSITION;
        y /= SAMPLES_PER_POSITION;
        z /= SAMPLES_PER_POSITION;

        var_x = 0;
        var_y = 0;
        var_z = 0;

        j = 0;
        for (i = 0; i < SAMPLES_PER_POSITION; i += 1) {
            dx = data[j++] - x;
            dy = data[j++] - y;
            dz = data[j++] - z;
            var_x += dx * dx;
            var_y += dy * dy;
            var_z += dz * dz;
        }
        var_x /= SAMPLES_PER_POSITION;
        var_y /= SAMPLES_PER_POSITION;
        var_z /= SAMPLES_PER_POSITION;

        double eps = 0.01;
        if (var_x > eps || var_y > eps || var_z > eps) {
            return false;
        }

        j = positionIndex * 3;
        data2[j++] = x;
        data2[j++] = y;
        data2[j++] = z;

        return true;
    }

    public class InternalMessageHandler extends Handler {
        @Override
        public void dispatchMessage(Message msg) {

            if (msg.what == MESSAGE_COUNTDOWN) {
                delayCounter -= 1;

                if (delayCounter > 0) {
                    textCounter.setText(Integer.toString(delayCounter));
                    handler.sendEmptyMessageDelayed(MESSAGE_COUNTDOWN, 1000);
                    return;
                }
                textCounter.setText(R.string.reading_sensor_data);

                pointSampleCounter = 0;
                manager.registerListener(listener, accelerometer, accelerometer.getMinDelay());
                return;
            }

            if (msg.what == MESSAGE_STOP_READING) {
                manager.unregisterListener(listener);

                textCounter.setText("");

                Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(effect);
                } else {
                    vibrator.vibrate(100);
                }

                if (processPositionData()) {
                    positionIndex += 1;
                } else {
                    Toast toast = Toast.makeText(CalibrationActivity.this, R.string.too_much_noise, Toast.LENGTH_LONG);
                    toast.show();
                }

                if (positionIndex >= MAX_POSITIONS) {
                    textCounter.setText(R.string.processing_sensor_data);
                    OptimizationThread thread = new OptimizationThread();
                    thread.start();
                    return;
                }
                textStep.setText(getString(string_ids[positionIndex]));
                imagePhone.setImageResource(picture_ids[positionIndex]);
                buttonNext.setEnabled(true);
            }

            if (msg.what == MESSAGE_OPTIMIZATION_DONE) {
                saveResults();
            }
        }
    }

    private void saveResults() {
        int k;
        boolean calibrated = true;
        SharedPreferences.Editor editor = pref.edit();
        for (k = 0; k < 3; k += 1) {
            if (Double.isNaN(bias[k]) || Math.abs(bias[k]) > 0.25) {
                calibrated = false;
                break;
            }
            if (Double.isNaN(scale[k]) || Math.abs(scale[k] - 1.0) > 0.125) {
                calibrated = false;
                break;
            }
        }
        if (calibrated) {
            editor.putFloat("bias_x", (float) bias[0]);
            editor.putFloat("bias_y", (float) bias[1]);
            editor.putFloat("bias_z", (float) bias[2]);

            /*
             * Subtracting 1 from scale before converting to float to improve precision
             */
            editor.putFloat(FilterParametersActivity.PREF_SCALE1_X, (float) (scale[0] - 1.0));
            editor.putFloat(FilterParametersActivity.PREF_SCALE1_Y, (float) (scale[1] - 1.0));
            editor.putFloat(FilterParametersActivity.PREF_SCALE1_Z, (float) (scale[2] - 1.0));
            editor.putBoolean("calibrated", calibrated);
            editor.apply();
            returnResult(RESULT_OK);
        } else {
            returnResult(RESULT_CANCELED);
        }
        finish();
    }

    public class ButtonNextListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            buttonClicked();
        }
    }

    public class ButtonSkipListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            returnResult(RESULT_CANCELED);
            finish();
        }
    }

    private void buttonClicked() {
        if (firstClick) {
            positionIndex = 0;
            textNext.setText(getString(R.string.calibration_next));
            textStep.setText(getString(string_ids[positionIndex]));
            imagePhone.setImageResource(picture_ids[positionIndex]);
            firstClick = false;
        } else {
            buttonNext.setEnabled(false);
            delayCounter = 5;
            textCounter.setText(Integer.toString(delayCounter));
            handler.sendEmptyMessageDelayed(MESSAGE_COUNTDOWN, 1000);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){
        Intent intent;

        if (event.getRepeatCount() == 0) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_SPACE:
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

        if (Build.VERSION.SDK_INT >= 26) {
            effect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE);
        }

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        listener = new AccelerationListener();
        handler = new InternalMessageHandler();

        buttonNext = findViewById(R.id.button_next);
        buttonSkip = findViewById(R.id.button_skip);
        textStep = findViewById(R.id.text_step);
        textNext = findViewById(R.id.text_next);
        textCounter = findViewById(R.id.text_counter);
        imagePhone = findViewById(R.id.image_phone);

        textNext.setText(getString(R.string.calibration_step0));

        manager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        scale = new double[3];
        bias = new double[3];
        bias[0] = 0;
        bias[1] = 0;
        bias[2] = 0;
        scale[0] = 1;
        scale[1] = 1;
        scale[2] = 1;
        data = new double[SAMPLES_PER_POSITION * 3];
        data2 = new double[MAX_POSITIONS * 3];

        buttonNext.setOnClickListener(new ButtonNextListener());
        buttonSkip.setOnClickListener(new ButtonSkipListener());
    }
}
