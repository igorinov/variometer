package info.altimeter.variometer;

import static android.media.AudioFormat.CHANNEL_OUT_MONO;
import static android.media.AudioFormat.ENCODING_PCM_16BIT;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import info.altimeter.variometer.common.Variometer;

public class VariometerService extends Service {

    private final IBinder mBinder = new VariometerServiceBinder();
    private static final String TAG = "VariometerService";
    private static final String CHANNEL_ID = "VSI Channel ID";
    NotificationManager notificationManager;
    NotificationCompat.Builder mBuilder;
    Notification notification = null;
    boolean foregroundState = false;
    int notifyID = 375;
    SharedPreferences pref;
    VariometerServiceListener myListener = new VariometerServiceListener();
    VarioPreferenceListener preferenceListener = new VarioPreferenceListener();
    boolean started = false;
    boolean soundEnabled = false;
    VarioCallback callback = null;

    static final int TYPE_VSI = 0;
    static final int TYPE_IVSI = 1;

    double[] input = new double[2];
    double[] kB = { 1, 1, 1 };
    double[] kC = { 0, 0, 0 };

    /** Standard density of pressure noise, hPa */
    double sigma_p = 0.06;
    double sigma_a = 0.05;
    double sigma_vsi = 0.0625;
    double sigma_ivsi = 0.0039;
    double latitude = 45.0;

    int type = TYPE_IVSI;
    int vsiLimit = 5;
    int vsiUnitIndex = 0;
    int smoother_lag = 5;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    Variometer variometer;
    AudioThread beepingThread;

    public class VariometerServiceBinder extends Binder {
        VariometerService getService() {
            return VariometerService.this;
        }
    }

    public static interface VarioCallback {
        void OnUpdate(float alt, float vspeed);
    }

    public class AudioThread extends Thread {
        AudioTrack track;
        short[] audioData;
        int sample_rate = 24000;
        double[] partPhase;
        double[] partFreq;
        double[] partAmpl;
        double v0 = 0;
        double vspeed = 0;
        double t = 0;
        int periods = 0;
        boolean stopRequested = false;

        float soundStartH = +0.3f;
        float soundStopH = +0.2f;
        float soundStopL = -0.2f;
        float soundStartL = -0.3f;
        float soundOctaveDiff = 3.0f;
        float soundBaseFreq = 500;
        int soundPartials = 4;
        boolean soundOddPartialsOnly = false;
        float soundIHC = 0.0001f;  // Inharmonicity coefficient

        double max_sample = 0;
        double decay = Math.log(1000);  // Signal amplitude becomes 1000 times smaller in 1 s.
        boolean soundDecay = false;
        boolean soundOn = false;

        public void loadSettings() {
            soundEnabled = pref.getBoolean(SoundSettingsActivity.PREF_SOUND_ENABLE, soundEnabled);
            soundDecay = pref.getBoolean(SoundSettingsActivity.PREF_SOUND_DECAY, soundDecay);
            soundBaseFreq = pref.getFloat(SoundSettingsActivity.PREF_BASE_FREQ, soundBaseFreq);
            soundOctaveDiff = pref.getFloat(SoundSettingsActivity.PREF_OCTAVE_DIFF, soundOctaveDiff);
            soundPartials = pref.getInt(SoundSettingsActivity.PREF_PARTIALS, soundPartials);
            soundOddPartialsOnly = pref.getBoolean(SoundSettingsActivity.PREF_ODD_PARTIALS, soundOddPartialsOnly);
            soundIHC = pref.getFloat(SoundSettingsActivity.PREF_INHARMONICITY, soundIHC);
            soundStartH = pref.getFloat(SoundSettingsActivity.PREF_SOUND_START_H, soundStartH);
            soundStopH = pref.getFloat(SoundSettingsActivity.PREF_SOUND_STOP_H, soundStopH);
            soundStopL = pref.getFloat(SoundSettingsActivity.PREF_SOUND_STOP_L, soundStopL);
            soundStartL = pref.getFloat(SoundSettingsActivity.PREF_SOUND_START_L, soundStartL);
        }

        public void setVerticalSpeed(float fSpeed) {
            vspeed = fSpeed;
        }

        public void requestStop() {
            stopRequested = true;
        }

        public void safelyStop() {
            requestStop();

            try {
                beepingThread.join();
            } catch (InterruptedException e) {
                // Do something
            }
        }

        private void init() {
            int k;
            double b = soundIHC;

            int nPartials = soundPartials;
            max_sample = 16384.0 / nPartials;
            partFreq = new double[nPartials];
            partAmpl = new double[nPartials];
            partPhase = new double[nPartials];
            for (k = 0; k < nPartials; k += 1) {
                double n = k;
                if (soundOddPartialsOnly) {
                    n *= 2;
                }
                n += 1;

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

                for (k = 0; k < partFreq.length; k += 1) {
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

            init();
            track.play();

            int size = sample_rate / 20;
            audioData = new short[size];

            while (!stopRequested) {
                fillBuffer(audioData, 0, size);
                track.write(audioData, 0, size);
            }

            track.stop();
            track.release();
            track = null;
        }
    }

    private class VariometerServiceListener implements Variometer.VariometerListener {

        @Override
        public void onStateUpdate(float h, float v) {
            if (beepingThread != null) {
                beepingThread.setVerticalSpeed(v);
            }

            if (callback != null) {
                callback.OnUpdate(h, v);
            }
            // modify notification
            // notification.? = Float.toString(h);
        }
    }

    private class VarioPreferenceListener implements SharedPreferences.OnSharedPreferenceChangeListener {

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key.equals("baro")) {
                if (variometer != null) {
                    variometer.setReferencePressure(sharedPreferences.getFloat(key, Float.NaN));
                }
                return;
            }

            if (key.equals(SoundSettingsActivity.PREF_SOUND_ENABLE)) {
                soundEnabled = sharedPreferences.getBoolean(key, soundEnabled);
                return;
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.channel_name);
            String description = getString(R.string.channel_description);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setImportance(NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
        }

        pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        pref.registerOnSharedPreferenceChangeListener(preferenceListener);
        soundEnabled = pref.getBoolean(SoundSettingsActivity.PREF_SOUND_ENABLE, soundEnabled);
    }

    @Override
    public int onStartCommand(Intent intent, int flag, int startId) {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent notifyPendingIntent = PendingIntent.getActivity(
                this, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        mBuilder = new NotificationCompat.Builder(this, CHANNEL_ID);
        mBuilder.setContentTitle("Vertical Speed Indicator");
        mBuilder.setContentText("Running");
        mBuilder.setSmallIcon(R.drawable.ic_vsi_24dp);
        mBuilder.setContentIntent(notifyPendingIntent);
        mBuilder.setAutoCancel(false);
        mBuilder.setCategory(Notification.CATEGORY_SERVICE);
        mBuilder.setOngoing(true);

        try {
            notification = mBuilder.build();
            startForeground(notifyID, notification);
            foregroundState = true;
            started = true;
        } catch (Exception e) {
            // notificationManager.notify(notifyID, mBuilder.build());
            foregroundState = false;
        }

        if (type == TYPE_VSI) {
            variometer = new Variometer(false, smoother_lag);
        }

        if (type == TYPE_IVSI) {
            variometer = new Variometer(true, smoother_lag);
            variometer.setProcessNoise(sigma_ivsi);
        }

        variometer.setListener(myListener);

/*
        if (pressureSensor == null) {
            Toast.makeText(this, "No pressure sensor", Toast.LENGTH_LONG)
                    .show();
        }
*/

        if (type == TYPE_VSI) {
            variometer.setProcessNoise(sigma_vsi);
        }

        variometer.setLatitude(latitude);
        variometer.setAccelerometerCorrection(kB, kC);
        variometer.setAccelerometerNoise(sigma_a);
        variometer.setPressureNoise(sigma_p);
//        variometer.setListener(varioListener);
        variometer.start(this);

        if (soundEnabled) {
            beepingThread = new AudioThread();
            beepingThread.loadSettings();
            beepingThread.start();
        }

        return Service.START_STICKY;
    }

    public void stopEverything()  {
        if (beepingThread != null) {
            beepingThread.safelyStop();
            beepingThread = null;
        }

        if (variometer != null) {
            variometer.stop(this);
            variometer = null;
        }

        if (foregroundState) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundState = false;
        }
        started = false;
        stopSelf();
    }

    public void setVarioCallback(VarioCallback cb) {
        callback = cb;
    }

    public float getVerticalSpeed() {
        if (variometer == null) {
            return Float.NaN;
        }

        return variometer.getVerticalSpeed();
    }

    public float getAltitude() {
        if (variometer == null) {
            return Float.NaN;
        }

        return variometer.getAltitude();
    }

    public boolean hasStarted() {
        return started;
    }
}
