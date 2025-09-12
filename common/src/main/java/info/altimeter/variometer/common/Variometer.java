package info.altimeter.variometer.common;

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

    // For target API 31 (Android 12), sensor rate is limited to 200 Hz
    // https://developer.android.com/guide/topics/sensors/sensors_overview#sensors-rate-limiting

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
    double[] correctionWeight = { 1, 1, 1 };
    double[] correctionBias = { 0, 0, 0 };
    double[] acc = new double[4];
    double[] q = new double[4];
    double[] q1 = new double[4];
    double[] v = new double[4];
    boolean realPartMayBeMissing = true;
    int smoother_lag;
    double sigma_p = 0.06;
    double sigma_h = 1.0;
    double sigma_a = 0.05;
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
        public void onStateUpdate(float h, float v);
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
     * @param std_a Accelerometer noise (standard deviation), m/s²
     */
    public void setAccelerometerNoise(double std_a) {
        accelerometerNoiseDensity = std_a;
    }

    public void setAccelerometerCorrection(double[] weights, double[] biases) {
        int k;

        for (k = 0; k < 3; k += 1) {
            correctionWeight[k] = weights[k];
            correctionBias[k] = biases[k];
        }
    }

    /*
     *  Quaternion multiplication
     *  q = w + x·i + y·j + z·k
     *  i·j =  k;  j·k =  i;  k·i =  j
     *  j·i = -k;  k·j = -i;  i·k = -j
     *  i² = j² = k² = i·j·k = -1
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

        void setReferencePressure(double value) {
            p0 = value;
            inv_p0 = 1.0 / p0;
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
            float altitude = (float) state[0];
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
                listener.onStateUpdate(altitude, vspeed);
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

            double a_x, a_y, a_z;

            /*
             *  Transform the acceleration vector to the reference coordinate system
             *  of the rotation sensor, where Z axis is vertical and points up
             */

            a_x = arg0.values[0];
            a_y = arg0.values[1];
            a_z = arg0.values[2];

            acc[0] = correctionWeight[0] * a_x + correctionBias[0];
            acc[1] = correctionWeight[1] * a_y + correctionBias[1];
            acc[2] = correctionWeight[2] * a_z + correctionBias[2];
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
            rotationSamplingPeriod = rotationSensor.getMinDelay() * 1e-6;
            if (rotationSamplingPeriod < minRotationSamplingPeriod) {
                rotationSamplingPeriod = minRotationSamplingPeriod;
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

    public float getVerticalSpeed() {
        return (float) filter.x.get(1);
    }

    public float getAltitude() {
        return (float) filter.x.get(0);
    }

    public void setReferencePressure(float p0) {
        double h = filter.x.get(0);
        double p = atmosphere.getPressure(h);
        atmosphere.setReferencePressure(p0);
        h = atmosphere.getAltitude(p);
        filter.x.set(0, h);
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
    public static void biasUpdate(double[] b, double[] c, double[] data, int length, double g) {
        /*
         *  Squared length of corrected vector [x, y, z]
         *  lₖ² = (b_x·xₖ + c₀)² + (b_y·yₖ + c₁)² + (b_z·zₖ + c₂)²
         *
         *  Squared error (difference from local gravity)
         *  fₖ = (lₖ - g)²
         *
         *  Minimize ∑fₖ by gradient descent:
         *
         *     ∂f / ∂b_x = 2·x·(l - g) (b_x·x + c0) / l
         *     ∂f / ∂b_y = 2·y·(l - g) (b_y·y + c1) / l
         *     ∂f / ∂b_z = 2·z·(l - g) (b_z·z + c2) / l
         *
         *     ∂f / ∂c_x = 2·(l - g) (b_x·x + c0) / l
         *     ∂f / ∂c_y = 2·(l - g) (b_y·y + c1) / l
         *     ∂f / ∂c_z = 2·(l - g) (b_z·z + c2) / l
         */

        int N = 4096;
        int i, k;
        double x, y, z;
        double b_x, b_y, b_z;
        double c_x, c_y, c_z;
        double[] ac = new double[3];
        double gbx, gby, gbz;
        double gcx, gcy, gcz;
        double ll, l;
        double r_l = 1.0f / length;

        // Learning rate for B
        double lr_b = 1e-3;

        // Learning rate for C
        double lr_c = 3e-3;

        b_x = 1;
        b_y = 1;
        b_z = 1;
        c_x = 0;
        c_y = 0;
        c_z = 0;

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
                ac[0] = b_x * x + c_x;
                ac[1] = b_y * y + c_y;
                ac[2] = b_z * z + c_z;

                // Squared length of the corrected acceleration vector
                ll = powerSum(ac);
                l = Math.sqrt(ll);

                // Update the weight gradient vector
                gbx += 2 * x * (l - g) * ac[0] / l;
                gby += 2 * y * (l - g) * ac[1] / l;
                gbz += 2 * z * (l - g) * ac[2] / l;

                // Update the bias gradient vector
                gcx += 2 * (l - g) * ac[0] / l;
                gcy += 2 * (l - g) * ac[1] / l;
                gcz += 2 * (l - g) * ac[2] / l;
            }

            gbx *= r_l;
            gby *= r_l;
            gbz *= r_l;

            // Update vector B
            b_x -= gbx * lr_b;
            b_y -= gby * lr_b;
            b_z -= gbz * lr_b;

            gcx *= r_l;
            gcy *= r_l;
            gcz *= r_l;

            // Update vector C
            c_x -= gcx * lr_c;
            c_y -= gcy * lr_c;
            c_z -= gcz * lr_c;
        }

        b[0] = b_x;
        b[1] = b_y;
        b[2] = b_z;
        c[0] = c_x;
        c[1] = c_y;
        c[2] = c_z;
    }
}
