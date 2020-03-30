package com.igorinov.variometer.wear;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.wearable.activity.WearableActivity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.TextView;

import com.igorinov.variometer.common.Variometer;
import com.igorinov.variometer.common.VerticalSpeedIndicator;

public class MainActivity extends WearableActivity {

    private SharedPreferences pref;
    private Variometer variometer;
    private VerticalSpeedIndicator vsiView;
    static final int REQUEST_CODE_ABOUT = 1;
    static final int REQUEST_CODE_CALIBRATION = 2;
    float[] scaleA = { 1f, 1f, 1f };
    float[] biasA = new float[3];
    boolean calibrated = false;


    class SpeedListener implements Variometer.VariometerListener {

        @Override
        public void onVerticalSpeedUpdate(float v) {
            vsiView.setVSpeed(v);
        }
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;

        switch (item.getItemId()) {
/*
            case R.id.defaults:
                SharedPreferences.Editor editor = pref.edit();
                editor.clear();
                editor.apply();
                recreate();
                break;
*/
            case R.id.calibration:
                intent = new Intent(this, CalibrationActivity.class);
                startActivityForResult(intent, REQUEST_CODE_CALIBRATION);
                break;
/*
            case R.id.about:
                Dialog aDialog = new Dialog(this, R.style.Dialog);
                aDialog.setContentView(R.layout.about);
                aDialog.setTitle(getString(R.string.about));
                aDialog.setCancelable(true);
                aDialog.show();
                break;
*/
            default:
                return super.onOptionsItemSelected(item);
        }

        return true;
    }

    private void startCalibration() {
        Intent intent;
        intent = new Intent(this, CalibrationActivity.class);
        startActivityForResult(intent, REQUEST_CODE_CALIBRATION);

    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){

        if (event.getRepeatCount() == 0) {
            if (keyCode == KeyEvent.KEYCODE_STEM_1) {
                startCalibration();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_STEM_2) {
                // Do stuff
                openOptionsMenu();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_STEM_3) {
                // Do stuff
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        variometer = new Variometer(true);
        vsiView = findViewById(R.id.vsi);

        // Enables Always-on
        setAmbientEnabled();

        variometer.setListener(new SpeedListener());
    }

    @Override
    protected void onResume() {
        super.onResume();
        calibrated = pref.getBoolean("calibrated", false);
        if (calibrated) {
            biasA[0] = pref.getFloat("bias_x", 0);
            biasA[1] = pref.getFloat("bias_y", 0);
            biasA[2] = pref.getFloat("bias_z", 0);
            scaleA[0] = pref.getFloat("scale_x", 1);
            scaleA[1] = pref.getFloat("scale_y", 1);
            scaleA[2] = pref.getFloat("scale_z", 1);
        } else {
            startCalibration();
        }
        variometer.setAccelerometerBias(biasA, scaleA);
        variometer.start(this);
    }

    @Override
    protected void onPause() {
        variometer.stop(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        variometer.stop(this);
        super.onDestroy();
    }
}
