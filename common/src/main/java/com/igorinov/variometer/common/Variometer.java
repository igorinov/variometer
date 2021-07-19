package com.igorinov.variometer.common;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import androidx.annotation.RequiresApi;

public class Variometer {
    VariometerListener listener;
    boolean inertial;
    AtmosphereModel atmosphere;
    KalmanFilter filter;
    PressureListener listenerP;
    AccelerationListener listenerA;
    RotationListener listenerR;

    private HandlerThread mSensorThread;
    private Handler mSensorHandler;

    Sensor pressureSensor;
    Sensor rotationSensor;
    Sensor accelerometers;

    // Limit pressure sensor sampling rate to 25 Hz
    double minPressureSamplingPeriod = 0.04;
    double pressureSamplingPeriod;
    int pressureSamplePeriod_us;

    // Limit acceleration sensor sampling rate to 50 Hz
    double minAccelerationSamplingPeriod = 0.02;
    double accelerationSamplingPeriod;
    int accelerationSamplePeriod_us;

    // Limit rotation sensor sampling rate to 100 Hz
    double minRotationSamplingPeriod = 0.01;
    double rotationSamplingPeriod;
    int rotationSamplePeriod_us;

    // Default accelerometer noise density is 300 µg/√Hz
    double accelerometerNoiseDensity = 0.002942;
    // Pressure sensor noise in hPa
    double pressureSensorNoise = 5;
    double filterPeriod = 1e-3;

    double[] input = new double[2];
    double[] state;
    float[] dataA = new float[3];
    double[] biasA = { 0, 0, 0 };
    double[] scaleA = { 1, 1, 1 };
    double[] acc = new double[4];
    double[] q = new double[4];
    double[] q1 = new double[4];
    double[] v = new double[4];
    boolean firstUpdate = true;
    boolean realPartMayBeMissing = true;
    int smoother_lag;
    double sigma_p = 0.02;
    double sigma_h = 0.1;
    double sigma_a = 0.3;
    double sigma_vsi = 0.0625;
    double sigma_ivsi = 0.0039;
    double gravity = SensorManager.STANDARD_GRAVITY;

    /*  Values for initial state uncertainty, with
     *  high confidence in zero vertical speed on startup
     */
    static final double[] p_init = {10000.0, 0.0001, 10.0};

    boolean knownRotation = false;
    boolean knownAltitude = false;

    public Variometer(boolean ivsi, int lag) {
        inertial = ivsi;
        smoother_lag = lag;

        if (Build.VERSION.SDK_INT >= 18) {
            realPartMayBeMissing = false;
        }

        if (inertial) {
            state = new double[3];
        } else {
            state = new double[2];
        }

        atmosphere = new AtmosphereModel();

        listenerR = new RotationListener();
        listenerA = new AccelerationListener();
        listenerP = new PressureListener();

        mSensorThread = new HandlerThread("Variometer Sensors", Process.THREAD_PRIORITY_MORE_FAVORABLE);
    }

    public interface VariometerListener {
        public void onVerticalSpeedUpdate(float v);
    }

    public void setListener(VariometerListener l) {
        listener = l;
    }

    public void setProcessNoise(double sigma) {
        sigma_vsi = sigma;
        sigma_ivsi = sigma;
    }

    /**
     * Set barometric sensor noise
     * @param std_p Barometer noise (standard deviation), hPa
     */
    public void setPressureNoise(double std_p) {
        sigma_p = std_p;
    }

    /**
     * Set accelerometer noise density
     * @param s_g Accelerometer noise density, µg/√Hz
     */
    public void setAccelerometerNoiseDensity(double s_g) {
        accelerometerNoiseDensity = s_g * SensorManager.STANDARD_GRAVITY * 1e-6;
    }

    public void setAccelerometerCorrection(double[] bias, double[] scale) {
        int k;

        for (k = 0; k < 3; k += 1) {
            biasA[k] = bias[k];
            scaleA[k] = scale[k];
        }
    }

    /*
     *  Quaternion multiplication
     *  q = w + xi + yj + dk
     *  ij =  k;  jk =  i;  ki =  j
     *  ji = -k;  kj = -i;  ik = -j
     *  i² = j² = k² = ijk = -1
     */

    void HamiltonProduct(double[] dst, double[] src) {
        double w1, x1, y1, z1;
        double w2, x2, y2, z2;

        x1 = dst[0];
        y1 = dst[1];
        z1 = dst[2];
        w1 = dst[3];

        x2 = src[0];
        y2 = src[1];
        z2 = src[2];
        w2 = src[3];

        dst[0] = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2;
        dst[1] = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2;
        dst[2] = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2;
        dst[3] = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2;
    }

    /*
     *  Standard atmosphere model
     */

    public class AtmosphereModel {
        double H = 44330.77;
        double n1 = 5.25593;
        double inv_n1;
        double p0, inv_p0;

        AtmosphereModel() {
            p0 = SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
            inv_p0 = 1.0 / p0;
            inv_n1 = 1.0 / n1;
        }

        double getAltitude(double p) {
            return H * (1 - Math.pow(p * inv_p0, inv_n1));
        }

        double getPressure(double h) {
            return Math.pow(1 - h / H, n1) * p0;
        }

        /**
         * Get altitude measurement noise from pressure sensor noise
         * at the specified altitude
         * @param h Estimated altitude (m)
         * @param std_p Standard deviation of pressure sensor noise (hPa)
         * @return Standard deviation of altitude measurement
         */
        double getStdH(double h, double std_p) {
            double p = getPressure(h);
            return (getAltitude(p - std_p) - getAltitude(p + std_p)) / 2.0;
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

            if (!knownAltitude) {
                filter.x.set(0, 0, alt);
                knownAltitude = true;
                return;
            }

            if (!inertial) {
                double[] z = { alt };
                filter.filterPredict(null);
                filter.filterUpdate(z);
            } else {
                filter.filterUpdateSequential(0, alt);
            }
            filter.getState(state);
            float vspeed = (float) state[1];

            sigma_h = atmosphere.getStdH(state[0], sigma_p);
            if (inertial) {
                double[] r = { sigma_h, sigma_a };
                filter.setMeasurementError(r);
            } else {
                double[] r = { sigma_h };
                filter.setMeasurementError(r);
            }

            if (listener != null) {
                listener.onVerticalSpeedUpdate(vspeed);
            }
        }
    }

    private class AccelerationListener implements SensorEventListener {

        @RequiresApi(api = Build.VERSION_CODES.N)
        public void onSensorAdditionalInfo (SensorAdditionalInfo info) {
            if (info.type == SensorAdditionalInfo.TYPE_SAMPLING) {

            }
        }

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

            q1[0] = -q[0];
            q1[1] = -q[1];
            q1[2] = -q[2];
            q1[3] = q[3];

            System.arraycopy(q, 0, v, 0, 4);
            HamiltonProduct(v, acc);
            HamiltonProduct(v, q1);

            if (arg0.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                input[1] = (v[2] - gravity);
            }

            if (arg0.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                input[1] = v[2];
            }

            if (inertial) {
                filter.filterPredict(null);
                filter.filterUpdateSequential(1, input[1]);
            }
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
                // Compute the real part of a unit quaternion from the 3 imaginary parts
                x = q[0];
                y = q[1];
                z = q[2];
                ll = x * x + y * y + z * z;
                q[3] = Math.sqrt(1 - ll);
            }

            knownRotation = true;
        }
    }

    public void start(Context context) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);

        pressureSensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        if (pressureSensor == null) {
            return;
        }
        pressureSamplingPeriod = pressureSensor.getMinDelay() * 1e-6;
        if (pressureSamplingPeriod < minPressureSamplingPeriod) {
            pressureSamplingPeriod = minPressureSamplingPeriod;
        }
        pressureSamplePeriod_us = (int) Math.round(pressureSamplingPeriod * 1e6);

        accelerometers = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelerationSamplingPeriod = accelerometers.getMinDelay() * 1e-6;
        if (accelerationSamplingPeriod < minAccelerationSamplingPeriod) {
            accelerationSamplingPeriod = minAccelerationSamplingPeriod;
        }
        accelerationSamplePeriod_us = (int) Math.round(accelerationSamplingPeriod * 1e6);
        rotationSamplePeriod_us = accelerationSamplePeriod_us;

        if (inertial) {
            if (smoother_lag > 0) {
                FixedLagSmoother fls = new FixedLagSmoother(3, 2, 0, smoother_lag);
                fls.setPeriod(accelerationSamplingPeriod);
                fls.setSmoothingInput(1);
                filter = fls;
            } else {
                filter = new KalmanFilter(3, 2, 0);
                filter.setPeriod(accelerationSamplingPeriod);
            }

            rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotationSensor == null) {
                rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            }

            filterPeriod = accelerationSamplingPeriod;
            filter.setPeriod(filterPeriod);
            filter.setProcessNoise(filterPeriod, sigma_ivsi * sigma_ivsi);

            sigma_a = accelerometerNoiseDensity / Math.sqrt(accelerationSamplingPeriod * 2);
            sigma_h = atmosphere.getStdH(0, sigma_p);
            double[] r = { sigma_h, sigma_a };
            filter.setMeasurementError(r);
            filter.initCovariance(p_init);
        } else {
            if (smoother_lag > 0) {
                FixedLagSmoother fls = new FixedLagSmoother(2, 1, 0, smoother_lag);
                fls.setPeriod(pressureSamplingPeriod);
                filter = fls;
            } else {
                filter = new KalmanFilter(2, 1, 0);
                filter.setPeriod(pressureSamplingPeriod);
            }

            filterPeriod = pressureSamplingPeriod;
            filter.setProcessNoise(filterPeriod, sigma_vsi * sigma_vsi);

            sigma_h = atmosphere.getStdH(0, sigma_p);
            double[] r = { sigma_h };
            filter.setMeasurementError(r);
            filter.initCovariance(p_init);
        }

        mSensorThread.start();
        mSensorHandler = new Handler(mSensorThread.getLooper());

        if (rotationSensor != null) {
            manager.registerListener(listenerR, rotationSensor, rotationSamplePeriod_us, mSensorHandler);
        }

        if (accelerometers != null) {
            manager.registerListener(listenerA, accelerometers, accelerationSamplePeriod_us, mSensorHandler);
        }

        if (pressureSensor != null) {
            manager.registerListener(listenerP, pressureSensor, pressureSamplePeriod_us, mSensorHandler);
        }

    }

    public void stop(Context context) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        manager.unregisterListener(listenerP);
        manager.unregisterListener(listenerA);
        manager.unregisterListener(listenerR);
        mSensorThread.quitSafely();
    }

    public void setLatitude(double phi) {
        gravity = localGravity(phi);
    }

    public static double localGravity(double latitude) {
        // WGS80
        double gamma_a = 9.7803253359;
        double gamma_b = 9.8321863685;
        double a = 6378137;
        double b = 6356752.3141;
        double e_sq = (a * a - b * b) / (a * a);
        double p = (b * gamma_b - a * gamma_a) / (a * gamma_a);
        double phi = Math.PI * latitude / 180.0;
        double sin_phi = Math.sin(phi);
        double sin_sq = sin_phi * sin_phi;
        double gamma;

        gamma = gamma_a * (1 + p * sin_sq) / Math.sqrt(1 - e_sq * sin_sq);

        return gamma;
    }

    static double powerSum(double[] v) {
        double sum = 0;

        for (double a: v) {
            sum += a * a;
        }

        return sum;
    }

    /*
     *  Accelerometer bias estimation using gradient descent
     */
    public static void biasUpdate(double[] bias, double[] scale, double[] data, int length, double g) {
        /*
         *  Length of corrected vector [x, y, z]
         *  lₖ² = (c₀ (xₖ - b₀))² + (c₁ (yₖ - b₁))² + (c₂ (zₖ - b₂))²
         *
         *  Error (difference from local gravity)
         *  rₖ = lₖ - g
         *
         *  Minimize ∑r²ₖ by gradient descent:
         *
         *  ∂r²ₖ / ∂b₀ = c₀² * (2 * b₀ - 2 * x) * (lₖ - g) / lₖ
         *  ∂r²ₖ / ∂b₁ = c₁² * (2 * b₁ - 2 * y) * (lₖ - g) / lₖ
         *  ∂r²ₖ / ∂b₂ = c₂² * (2 * b₂ - 2 * z) * (lₖ - g) / lₖ
         *
         *  ∂r²ₖ / ∂c₀ = 2 * c₀ * (x - b₀)² * (lₖ - g) / lₖ
         *  ∂r²ₖ / ∂c₁ = 2 * c₁ * (y - b₁)² * (lₖ - g) / lₖ
         *  ∂r²ₖ / ∂c₂ = 2 * c₂ * (z - b₂)² * (lₖ - g) / lₖ
         */

        int N = 4096;
        int i, k;
        double x, y, z;
        double c0, c1, c2;
        double _x, _y, _z;
        double[] ac = new double[3];
        double gbx, gby, gbz;
        double gcx, gcy, gcz;
        double ll, l;
        double r_l = 1.0f / length;

        // Learning rate for bias
        double lr = 3e-3;

        // Learning rate for scale
        double lr_s = 1e-3;

        c0 = 1;
        c1 = 1;
        c2 = 1;

        for (i = 0; i < N; i += 1) {
            gbx = 0;
            gby = 0;
            gbz = 0;
            gcx = 0;
            gcy = 0;
            gcz = 0;

            for (k = 0; k < length; k += 3) {
                x = data[k];
                y = data[k + 1];
                z = data[k + 2];

                // Corrected acceleration vector
                ac[0] = (x - bias[0]) * c0;
                ac[1] = (y - bias[1]) * c1;
                ac[2] = (z - bias[2]) * c2;

                // Squared length of the corrected acceleration vector
                ll = powerSum(ac);
                l = Math.sqrt(ll);

                // Update the bias gradient vector
                gbx += c0 * c0 * 2 * (bias[0] - x) * (l - g) / l;
                gby += c1 * c1 * 2 * (bias[1] - y) * (l - g) / l;
                gbz += c2 * c2 * 2 * (bias[2] - z) * (l - g) / l;

                // Update the scale gradient vector
                gcx += 2 * c0 * (x - bias[0]) * (x - bias[0]) * (l - g) / l;
                gcy += 2 * c1 * (y - bias[1]) * (y - bias[1]) * (l - g) / l;
                gcz += 2 * c2 * (z - bias[2]) * (z - bias[2]) * (l - g) / l;
            }
            gbx *= r_l;
            gby *= r_l;
            gbz *= r_l;

            // Update bias vector
            bias[0] -= gbx * lr;
            bias[1] -= gby * lr;
            bias[2] -= gbz * lr;

            gcx *= r_l;
            gcy *= r_l;
            gcz *= r_l;

            // Update scale vector
            c0 -= gcx * lr_s;
            c1 -= gcy * lr_s;
            c2 -= gcz * lr_s;
        }
        scale[0] = c0;
        scale[1] = c1;
        scale[2] = c2;
    }
}
