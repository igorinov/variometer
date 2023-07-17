package info.altimeter.variometer;

import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;

import androidx.appcompat.app.AppCompatActivity;

public class PressureActivity extends AppCompatActivity {

    public static final double[] pressureUnits = { 1,
            1.333223684211f, 3.386388157896e1f };

    public static final double[] pressureUnitsR = { 1,
            7.500616827042e-1f, 2.952998750803e-2f };

    public static String[] pressureFormats = {
            "%.1f", "%.1f", "%.2f"
    };

    SharedPreferences pref;
    EditText editPressure;
    Button buttonEnter;
    RadioButton button0;
    RadioButton button1;
    RadioButton button2;
    RadioButtonClickListener listener = new RadioButtonClickListener();
    float referencePressure = SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
    int pressureUnitIndex = 0;

    class RadioButtonClickListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            onRadioButtonClicked(v);
        }
    }

    public void onRadioButtonClicked(View view) {
        SharedPreferences.Editor edit = pref.edit();
        boolean checked = ((RadioButton) view).isChecked();

        String stringPressure;
        stringPressure = editPressure.getText().toString();
        double fPressure = Float.parseFloat(stringPressure) * pressureUnits[pressureUnitIndex];

        // Check which radio button was clicked
        switch(view.getId()) {
            case R.id.unit_hpa:
                pressureUnitIndex = 0;
                break;
            case R.id.unit_mmhg:
                pressureUnitIndex = 1;
                break;
            case R.id.unit_inhg:
                pressureUnitIndex = 2;
                break;
        }
        editPressure.setText(String.format(pressureFormats[pressureUnitIndex], fPressure * pressureUnitsR[pressureUnitIndex]));
        edit.putInt("pressure_unit", pressureUnitIndex);
        edit.apply();
    }

    class EnterClickListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            SharedPreferences.Editor edit = pref.edit();
            String stringPressure;
            stringPressure = editPressure.getText().toString();
            try {
                float fPressure = Float.parseFloat(stringPressure);
                fPressure *= pressureUnits[pressureUnitIndex];
                edit.putFloat("baro", fPressure);
                edit.apply();
                finish();
            } catch (NumberFormatException e) {
                return;
            }

        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pressure);

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        referencePressure = pref.getFloat("baro", SensorManager.PRESSURE_STANDARD_ATMOSPHERE);
        pressureUnitIndex = pref.getInt("pressure_unit", 2);

        editPressure = findViewById(R.id.edit_pressure);
        buttonEnter = findViewById(R.id.button_enter);

        button0 = findViewById(R.id.unit_hpa);
        button1 = findViewById(R.id.unit_mmhg);
        button2 = findViewById(R.id.unit_inhg);

        button0.setOnClickListener(listener);
        button1.setOnClickListener(listener);
        button2.setOnClickListener(listener);

        switch (pressureUnitIndex) {
            case 0:
                button0.setChecked(true);
                break;
            case 1:
                button1.setChecked(true);
                break;
            case 2:
                button2.setChecked(true);
                break;
        }

        editPressure.setText(String.format(pressureFormats[pressureUnitIndex], referencePressure * pressureUnitsR[pressureUnitIndex]));
        buttonEnter.setOnClickListener(new EnterClickListener());
    }
}
