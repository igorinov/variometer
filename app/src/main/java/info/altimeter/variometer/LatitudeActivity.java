package info.altimeter.variometer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

public class LatitudeActivity extends AppCompatActivity {

    SharedPreferences pref;
    EditText editLatitude;
    Button buttonEnter;

    class EnterClickListener implements View.OnClickListener {

        @Override
        public void onClick(View v) {
            String stringLatitude;
            stringLatitude = editLatitude.getText().toString();
            try {
                float fLatitude = Float.parseFloat(stringLatitude);
                if (fLatitude + 90 < 0) {
                    return;
                }
                if (fLatitude - 90 > 0) {
                    return;
                }
                SharedPreferences.Editor edit = pref.edit();
                edit.putFloat(FilterParametersActivity.PREF_LATITUDE, fLatitude);
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
        setContentView(R.layout.activity_latitude);

        editLatitude = findViewById(R.id.edit_latitude);
        buttonEnter = findViewById(R.id.button_enter);

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        float fLatitude = pref.getFloat(FilterParametersActivity.PREF_LATITUDE, 45);
        editLatitude.setText(Float.toString(fLatitude));

        buttonEnter.setOnClickListener(new EnterClickListener());
    }
}
