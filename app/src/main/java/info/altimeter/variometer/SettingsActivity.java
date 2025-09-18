/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package info.altimeter.variometer;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.appcompat.app.AppCompatActivity;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

import static android.view.View.NO_ID;

@SuppressLint("Registered")
public class SettingsActivity extends AppCompatActivity {

    SharedPreferences pref = null;
    SharedPreferences.Editor editor = null;
    SparseArray<View> labelMap = new SparseArray<View>();

    // Find the target view's label

    String labelText(View target) {
        if (target == null)
            return null;

        View view = labelMap.get(target.getId());

        if (view instanceof TextView) {
            if (view.getLabelFor() == target.getId()) {
                TextView textView = (TextView) view;
                return textView.getText().toString();
            }
        }

        return null;
    }

    // Map labels of child Views

    int mapLabels(View layout) {
        ViewGroup group;
        View view;
        int c = 0;
        int n, i;

        if (!(layout instanceof ViewGroup))
            return 0;

        group = (ViewGroup) layout;

        n = group.getChildCount();
        for (i = 0; i < n; i += 1) {
            view = group.getChildAt(i);
            if (view == null)
                continue;

            if (view instanceof ViewGroup) {
                c += mapLabels(view);
            }

            int id = view.getLabelFor();
            if (id != NO_ID) {
                labelMap.put(id, view);
                c += 1;
            }
        }

        return c;
    }

    public class IntNumberDialog extends Dialog {
        Context myContext;
        EditText editNumber = null;
        TextView viewDescription = null;
        TextView target = null;
        Button buttonSet = null;
        String key = null;
        String format = "%d";
        int number = 0;

        public IntNumberDialog(Context context) {
            super(context);
            myContext = context;
        }

        IntNumberDialog(Context context, int theme) {
            super(context, theme);
            myContext = context;
        }

        public IntNumberDialog(Context context, boolean cancelable,
                               OnCancelListener cancelListener) {
            super(context, cancelable, cancelListener);
            myContext = context;
        }

        void setKey(String s) {
            key = s;
        }

        void setTarget(TextView textView) {
            target = textView;

            int id = target.getId();
            if (id != NO_ID) {
                View label = labelMap.get(id);
                if (label instanceof TextView) {
                    textView = (TextView) label;
                    String title = textView.getText().toString();
                    setTitle(title);
                }
            }

            if (target != null) {
                try {
                    number = Integer.parseInt(target.getText().toString(), 10);
                } catch (NumberFormatException e) {
                    // ?
                }
                try {
                    float a = Float.parseFloat(target.getText().toString());
                    number = Math.round(a);
                } catch (NumberFormatException e) {
                    // ?
                }
            }
        }

        void setNumber(int x) {
            number = x;
            if (editNumber != null) {
                editNumber.setText(Integer.toString(x));
            }
        }

        private class ButtonSetListener implements View.OnClickListener {

            @Override
            public void onClick(View v) {
                updateSetting();
            }
        }

        void updateSetting() {
            boolean valid = false;

            try {
                number = Integer.parseInt(editNumber.getText().toString(), 10);
                valid = true;
            } catch (NumberFormatException e) {
                // ?
            }

            if (!valid) {
                float a;
                try {
                    a = Float.parseFloat(editNumber.getText().toString());
                    number = Math.round(a);
                    valid = true;
                } catch (NumberFormatException e) {
                    //
                }
            }

            if (target != null) {
                target.setText(Integer.toString(number));
            }
            if (key != null) {
                SharedPreferences.Editor editor = pref.edit();
                editor.putInt(key, number);
                editor.apply();
            }
            dismiss();
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            setContentView(R.layout.dialog_number);

            editNumber = findViewById(R.id.number);
            if (editNumber != null) {
                // specify the locale to make sure decimal point is not replaced by comma
                editNumber.setText(String.format(Locale.US, format, number));
                editNumber.setSelectAllOnFocus(true);
            }
            buttonSet = findViewById(R.id.set);
            if (buttonSet != null) {
                buttonSet.setOnClickListener(new ButtonSetListener());
            }
            setCancelable(true);
        }
    }

    public class FloatNumberDialog extends Dialog {
        Context myContext;
        EditText editNumber = null;
        TextView viewDescription = null;
        TextView target = null;
        Button buttonSet = null;
        String key = null;
        String format = "%g";
        float number = 0;

        public FloatNumberDialog(Context context) {
            super(context);
            myContext = context;
        }

        FloatNumberDialog(Context context, int theme) {
            super(context, theme);
            myContext = context;
        }

        public FloatNumberDialog(Context context, boolean cancelable,
                                 OnCancelListener cancelListener) {
            super(context, cancelable, cancelListener);
            myContext = context;
        }

        void setKey(String s) {
            key = s;
        }

        void setTarget(TextView textView) {
            target = textView;

            int id = target.getId();
            if (id != NO_ID) {
                View label = labelMap.get(id);
                if (label instanceof TextView) {
                    textView = (TextView) label;
                    String title = textView.getText().toString();
                    setTitle(title);
                }
            }
/*
            if (target != null) {
                number = Float.parseFloat(target.getText().toString());
            }
 */
        }

        void setNumber(float x) {
            number = x;
            if (editNumber != null) {
                editNumber.setText(String.format(Locale.US, format, number));
            }
        }

        private class ButtonSetListener implements View.OnClickListener {

            @Override
            public void onClick(View v) {
                updateSetting();
            }
        }

        void updateSetting() {
            try {
                number = Float.parseFloat(editNumber.getText().toString());
            } catch (NumberFormatException e) {
                return;
            }

            if (target != null) {
                target.setText(Float.toString( number));
            }
            if (key != null) {
                SharedPreferences.Editor editor = pref.edit();
                editor.putFloat(key, number);
                editor.apply();
            }
            dismiss();
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            setContentView(R.layout.dialog_number);

            viewDescription = findViewById(R.id.description);

            editNumber = findViewById(R.id.number);
            if (editNumber != null) {
                // specify the locale to make sure decimal point is not replaced by comma
                editNumber.setText(String.format(Locale.US, format, number));
                editNumber.setSelectAllOnFocus(true);
            }
            buttonSet = findViewById(R.id.set);
            if (buttonSet != null) {
                buttonSet.setOnClickListener(new ButtonSetListener());
            }
            setCancelable(true);
        }
    }

    class PrefCompoundListener implements CompoundButton.OnCheckedChangeListener {
        String key;

        PrefCompoundListener(String keyName) {
            key = keyName;
        }

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            editor = pref.edit();
            editor.putBoolean(key, isChecked);
            editor.apply();
        }
    }

    class PrefSelectedListener implements Spinner.OnItemSelectedListener {
        String key;

        PrefSelectedListener(String keyName) {
            key = keyName;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            editor = pref.edit();
            editor.putInt(key, position);
            editor.apply();
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

    private class IntNumberClickListener implements TextView.OnClickListener {
        String key;

        IntNumberClickListener(String s) {
            key = s;
        }

        @Override
        public void onClick(View v) {
            TextView textView = (TextView) v;
            int value = 0;

            if (textView != null) {
                try {
                    value = Integer.parseInt(textView.getText().toString(), 10);
                } catch (NumberFormatException e) {
                    value = 0;
                }
            }

            IntNumberDialog dialog = new IntNumberDialog(SettingsActivity.this, R.style.Dialog);
            dialog.setKey(key);
            dialog.setNumber(value);
            dialog.setTarget(textView);
            dialog.show();
        }
    }

    private class FloatNumberClickListener implements TextView.OnClickListener {
        String key;

        FloatNumberClickListener(String s) {
            key = s;
        }

        @Override
        public void onClick(View v) {
            TextView textView = (TextView) v;
            float value = 0;

            if (textView != null) {
                try {
                    value = Float.parseFloat(textView.getText().toString());
                } catch (NumberFormatException e) {
                    value = 0;
                }
            }
            FloatNumberDialog dialog;
            dialog = new FloatNumberDialog(SettingsActivity.this, R.style.Dialog);
            dialog.setKey(key);
            dialog.setNumber(value);
            dialog.setTarget(textView);
            dialog.show();
        }
    }

    public void initSpinner(int spinner_id, int array_id, String key, int position) {
        Spinner spinner = findViewById(spinner_id);

        if (spinner == null)
            return;

        try {
            position = pref.getInt(key, position);
        } catch (ClassCastException e) {
            // ...
        }

        ArrayAdapter<CharSequence> typeAdapter = ArrayAdapter.createFromResource(this,
                array_id, android.R.layout.simple_spinner_item);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(typeAdapter);
        spinner.setSelection(position);
        spinner.setOnItemSelectedListener(new PrefSelectedListener(key));
    }

    public void initEditInt(int edit_text_id, String key, int value) {
        TextView textView = findViewById(edit_text_id);

        if (textView == null)
            return;

        try {
            value = pref.getInt(key, value);
        } catch (ClassCastException e) {
            // ...
        }

        textView.setText(Integer.toString(value));
        textView.setOnClickListener(new IntNumberClickListener(key));
    }

    public void initEditFloat(int edit_text_id, String key, float defaultValue) {
        TextView textView = findViewById(edit_text_id);
        float value;

        if (textView == null)
            return;

        try {
            value = pref.getFloat(key, defaultValue);
        } catch (ClassCastException e) {
            value = 0;
        }

        textView.setText(String.format("%.9f", value));
        textView.setOnClickListener(new FloatNumberClickListener(key));
    }

    public void initCompoundButton(int view_id, String key, boolean value) {
        CompoundButton button = findViewById(view_id);

        if (button == null)
            return;

        try {
            value = pref.getBoolean(key, value);
        } catch (ClassCastException e) {
            // ...
        }

        button.setChecked(value);
        button.setOnCheckedChangeListener(new PrefCompoundListener(key));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Content view is set by derivative classes

        pref = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    public void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        mapLabels(findViewById(android.R.id.content));
        setResult(RESULT_OK);
    }

    @Override
    public void onDestroy() {
        labelMap.clear();

        super.onDestroy();
    }
}