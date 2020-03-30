package com.igorinov.variometer.common;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

public class Variometer {
    VariometerListener listener;
    boolean inertial = false;
    AtmosphereModel atmosphere;
    KalmanFilter filter;
    PressureListener listenerP;
    AccelerationListener listenerA;
    RotationListener listenerR;
//    VerticalSpeedIndicator vsi;

    Sensor pressureSensor;
    Sensor rotationSensor;
    Sensor accelerometers;
    double sensorPeriod = 1e-3;
    int period_us = 1000;
    double[] input = new double[2];
    double[] state = new double[3];
    float[] dataA = new float[3];
    float[] biasA = new float[3];
    float[] scaleA = new float[3];
    double[] acc = new double[4];
    double[] q = new double[4];
    double[] q1 = new double[4];
    double[] v = new double[4];
    boolean firstUpdate = true;
    boolean realPartMayBeMissing = false;
    int smoother_lag = 5;
    double sigma_h = 0.42;
    double sigma_a = 0.3;
    double sigma_vsi = 0.0625;
    double sigma_ivsi = 0.0039;

    /*  Values for state covariance initialization, with
     *  high confidence in zero vertical speed on startup
     */
    static final double[] p_init = { 1000.0, 0.01, 10.0 };

    boolean knownRotation = false;
    boolean knownAltitude = false;
    int t = 0;

    public Variometer(boolean ivsi) {
        inertial = ivsi;

        atmosphere = new AtmosphereModel();

        listenerR = new RotationListener();
        listenerA = new AccelerationListener();
        listenerP = new PressureListener();
    }

    public interface VariometerListener {
        public void onVerticalSpeedUpdate(float v);
    }

    public void setListener(VariometerListener l) {
        listener = l;
    }

    public void setAccelerometerBias(float[] b, float[] s) {
        System.arraycopy(b, 0, biasA, 0, 3);
        System.arraycopy(s, 0, scaleA, 0, 3);
    }
    /*
     *  Quaternion multiplication
     *  q = a + bi + cj + dk
     */

    void HamiltonProduct(double[] dst, double[] src) {
        double a1, b1, c1, d1;
        double a2, b2, c2, d2;

        a1 = dst[3];
        b1 = dst[0];
        c1 = dst[1];
        d1 = dst[2];

        a2 = src[3];
        b2 = src[0];
        c2 = src[1];
        d2 = src[2];

        dst[3] = a1 * a2 - b1 * b2 - c1 * c2 - d1 * d2;
        dst[0] = a1 * b2 + b1 * a2 + c1 * d2 - d1 * c2;
        dst[1] = a1 * c2 - b1 * d2 + c1 * a2 + d1 * b2;
        dst[2] = a1 * d2 + b1 * c2 - c1 * b2 + d1 * a2;
    }

    /*
     *  Standard atmosphere model
     */

    public class AtmosphereModel {
        double H = 44330.77;
        double n1 = 5.25593;
        double inv_n1;
        double inv_p0;

        AtmosphereModel() {
            inv_p0 = 1.0 / SensorManager.PRESSURE_STANDARD_ATMOSPHERE;
            inv_n1 = 1.0 / n1;
        }

        double getAltitude(double p) {
            return H * (1 - Math.pow(p * inv_p0, inv_n1));
        }
    }

    void filterUpdate() {
        float vspeed;

        if (firstUpdate) {
            state[0] = input[0];
            state[1] = 0;
            state[2] = input[1];
            filter.setState(state);
            firstUpdate = false;
        }

        filter.filterPredict(null);
        filter.filterUpdate(input);

        filter.getState(state);
        vspeed = (float) state[1];

        t += period_us;
        if (t >= 40000) {
            t -= 40000;
//            vsi.setVSpeed(vspeed);
            if (listener != null)
                listener.onVerticalSpeedUpdate(vspeed);
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

            knownAltitude = true;

            if (inertial)
                filterUpdate();
        }
    }

    private class AccelerationListener implements SensorEventListener {

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

            q1[0] = - q[0];
            q1[1] = - q[1];
            q1[2] = - q[2];
            q1[3] = q[3];

            System.arraycopy(q, 0, v, 0, 4);
            HamiltonProduct(v, acc);
            HamiltonProduct(v, q1);

            if(arg0.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                input[1] = (v[2] - SensorManager.GRAVITY_EARTH);
            }

            if(arg0.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                input[1] = v[2];
            }

            if (inertial)
                filterUpdate();
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
                // Compute real part of a unit quaternion from the other 3 components
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

        if (inertial) {
            if (smoother_lag > 0)
                filter = new FixedLagSmoother(3, 2, 0, smoother_lag);
            else
                filter = new KalmanFilter(3, 2, 0);

            accelerometers = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (rotationSensor == null) {
                rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                realPartMayBeMissing = true;
            }

            if (period_us < accelerometers.getMinDelay()) {
                period_us = accelerometers.getMinDelay();
                sensorPeriod = period_us * 1e-6;
            }
            filter.setPeriod(sensorPeriod);
            filter.setProcessNoise(sensorPeriod, sigma_ivsi * sigma_ivsi);

            double[] r = { sigma_h, sigma_a };
            filter.setMeasurementError(r);
            filter.initCovariance(p_init);
        } else {
            if (smoother_lag > 0)
                filter = new FixedLagSmoother(2, 1, 0, smoother_lag);
            else
                filter = new KalmanFilter(3, 1, 0);

            filter.initCovariance(p_init);
        }


        if (!inertial && pressureSensor != null) {
            if (period_us < pressureSensor.getMinDelay()) {
                period_us = pressureSensor.getMinDelay();
                sensorPeriod = period_us * 1e-6;
            }
            filter.setPeriod(sensorPeriod);
            filter.setProcessNoise(sensorPeriod, sigma_vsi * sigma_vsi);
            double[] r = { sigma_h };
            filter.setMeasurementError(r);
        }

        if (rotationSensor != null) {
            manager.registerListener(listenerR, rotationSensor, period_us);
        }

        if (accelerometers != null) {
            manager.registerListener(listenerA, accelerometers, period_us);
        }

        if (pressureSensor != null) {
            manager.registerListener(listenerP, pressureSensor, period_us);
        }

    }

    public void stop(Context context) {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        manager.unregisterListener(listenerP);
        manager.unregisterListener(listenerA);
        manager.unregisterListener(listenerR);
    }

    /*
     *  Accelerometer bias estimation using gradient descent
     */
    public static void biasUpdate(float[] bias, float[] scale, float[] data, int length) {
        /*
         *  lₖ² = c_x² (axₖ - b_x)² + c_y² (ayₖ - b_y)² + c_z² (azₖ - b_z)²
         *  rₖ = lₖ² - g²
         *
         *  Minimize ∑r²ₖ by gradient descent:
         *  ∂r²ₖ / ∂ b_x = 2 * (lₖ² - g²) * c_x² (2 * b_x - 2 * axₖ)
         *  ∂r²ₖ / ∂ c_x = 2 * (lₖ² - g²) * 2 * (ax_k - b_x)²
         */

        int i, k;

        float ax, ay, az;
        float cx, cy, cz;
        float x, y, z;
        float gx, gy, gz;
        float gcx, gcy, gcz;
        float aa, g;
        float r_l = 1.0f / length;

        // Learning rate for bias
        float lr = 1 / 8192.0f;

        // Learning rate for scale
        float lr_s = 1 / 65536.0f;

        g = SensorManager.GRAVITY_EARTH;
        cx = 1;
        cy = 1;
        cz = 1;

        for (i = 0; i < 1024; i += 1) {
            gx = 0;
            gy = 0;
            gz = 0;
            gcx = 0;
            gcy = 0;
            gcz = 0;

            for (k = 0; k < length; k += 3) {
                ax = data[k];
                ay = data[k + 1];
                az = data[k + 2];

                // Corrected acceleration vector
                x = (ax - bias[0]) * (cx * cx);
                y = (ay - bias[1]) * (cy * cy);
                z = (az - bias[2]) * (cz * cz);

                // Squared length of the corrected acceleration vector
                aa = x * x + y * y + z * z;

                // Update the bias gradient vector
                gx += 4 * (g * g - aa) * x;
                gy += 4 * (g * g - aa) * y;
                gz += 4 * (g * g - aa) * z;

                // Bias-corrected acceleration vector
                x = ax - bias[0];
                y = ay - bias[1];
                z = az - bias[2];

                // Update the scale gradient vector
                gcx += 4 * (aa - g * g) * x * x;
                gcy += 4 * (aa - g * g) * y * y;
                gcz += 4 * (aa - g * g) * z * z;
            }
            gx *= r_l;
            gy *= r_l;
            gz *= r_l;

            // Update bias vector
            bias[0] -= gx * lr;
            bias[1] -= gy * lr;
            bias[2] -= gz * lr;

            gcx *= r_l;
            gcy *= r_l;
            gcz *= r_l;

            // Update scale vector
            cx -= gcx * lr_s;
            cy -= gcy * lr_s;
            cz -= gcz * lr_s;
        }
        scale[0] = cx;
        scale[1] = cy;
        scale[2] = cz;
    }
}
