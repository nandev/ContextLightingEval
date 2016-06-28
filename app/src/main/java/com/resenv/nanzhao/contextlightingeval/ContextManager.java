package com.resenv.nanzhao.contextlightingeval;

import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCPortOut;

import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;

/**
 * Created by nanzhao on 2/24/15.
 *
 * This app samples and processes activity, light and sound levels
 * A leaky weighted integrator is used to estimate activity
 * The resulting features are streamed to a OSC server
 *
 *
 *
 * apk-compass-sample was used as reference
 */


public class ContextManager {
    /**
     * The minimum distance desired between location notifications.
     */
    private static final long METERS_BETWEEN_LOCATIONS = 2;

    /**
     * The minimum elapsed time desired between location notifications.
     */
    private static final long MILLIS_BETWEEN_LOCATIONS = TimeUnit.SECONDS.toMillis(3);

    /**
     * The maximum age of a location retrieved from the passive location provider before it is
     * considered too old to use when the compass first starts up.
     */
    private static final long MAX_LOCATION_AGE_MILLIS = TimeUnit.MINUTES.toMillis(30);

    /**
     * The sensors used by the compass are mounted in the movable arm on Glass. Depending on how
     * this arm is rotated, it may produce a displacement ranging anywhere from 0 to about 12
     * degrees. Since there is no way to know exactly how far the arm is rotated, we just split the
     * difference.
     */
    private static final int ARM_DISPLACEMENT_DEGREES = 6;

    private final SensorManager mSensorManager;
    private final LocationManager mLocationManager;
    private final Set<OnChangedListener> mListeners;
    private final float[] mRotationMatrix;
    private final float[] mOrientation;
    private final float[] mAcceleration;


    private boolean mTracking;
    private float mHeading;
    private float mPitch;
    private float mActivityLevel;
    private float mLightLevel;
    private Location mLocation;
    private GeomagneticField mGeomagneticField;
    private boolean mHasInterference;

    Timer timer;
    private int updateInterval = 100;
    private OSCPortOut OSCsender;
    private String IP = "X.X.X.X";
    private int PORT = 12345;

    /**
     * Initializes a new instance of {@code OrientationManager}, using the specified context to
     * access system services.
     */
    public ContextManager(SensorManager sensorManager, LocationManager locationManager) {
        mRotationMatrix = new float[16];
        mOrientation = new float[9];
        mAcceleration = new float[3];
        mSensorManager = sensorManager;
        mLocationManager = locationManager;
        mListeners = new LinkedHashSet<OnChangedListener>();
        mTracking = false;
        mLightLevel = 0;
    }

    class RemindTask extends TimerTask {

        @Override
        public void run() {
            Object[] OSCargs = {new Float(0), new Float(0), new Float(0)};
            OSCargs[0] = mActivityLevel;
            OSCargs[1] = mLightLevel;
            OSCargs[2] = measureSound();
            OSCMessage msg = new OSCMessage("/glass", OSCargs);
            try {
                OSCsender.send(msg);
                Log.i("nan", "nan: send Activity: "+OSCargs[0]+" Light: "+OSCargs[1]+" Noise: "+OSCargs[2]);
            } catch (Exception e) {
                Log.i("nan", "nan: failed sending OSC message to Lighting Bridge");
            }

            Log.d("Nan", "nan: activity level: " + mActivityLevel);
            notifyActivityChanged();
        }

    }

    private float measureSound(){
        int sampleRate = 8000;
        AudioRecord audio = null;
        int bufferSize = 0;
        try {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            audio = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);

        } catch (Exception e) {
            android.util.Log.e("TrackingFlow", "Exception", e);
        }

        short[] buffer = new short[bufferSize];

        int bufferReadResult = 1;

        if (audio != null) {
            audio.startRecording();
            // Sense the voice...
            bufferReadResult = audio.read(buffer, 0, bufferSize);

            /*//calculating everage
            double sumLevel = 0;
            for (int i = 0; i < bufferReadResult; i++) {
                sumLevel += buffer[i];
            }
            double lastLevel = Math.abs((sumLevel / bufferReadResult));
            */

            //calculating max
            float lastLevel = 0;
            for (int i = 0; i < bufferReadResult; i++) {
                if(lastLevel<buffer[i]){
                    lastLevel = buffer[i];
                }
            }
            audio.stop();
            audio.release();
            audio = null;
            return lastLevel;
        } else {
            return 0;
        }

    }
    /**
     * Classes should implement this interface if they want to be notified of changes in the user's
     * location, orientation, or the accuracy.
     */
    public interface OnChangedListener {
        /**
         * Called when the user's activity level changes.
         *
         * @param activityManager the activity manager that detected the change
         */
        void onActivityChanged(ContextManager activityManager);

        /**
         * Called when the user's orientation changes.
         *
         * @param orientationManager the orientation manager that detected the change
         */
        void onOrientationChanged(ContextManager orientationManager);

        /**
         * Called when the user's location changes.
         *
         * @param orientationManager the orientation manager that detected the change
         */
        void onLocationChanged(ContextManager orientationManager);

        /**
         * Called when the accuracy of the compass changes.
         *
         * @param orientationManager the orientation manager that detected the change
         */
        void onAccuracyChanged(ContextManager orientationManager);
    }

    /**
     * The sensor listener used by the orientation manager.
     */
    private SensorEventListener mSensorListener = new SensorEventListener() {

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                mHasInterference = (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_HIGH);
                notifyAccuracyChanged();
            }
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                float lastOrientation[] = new float[mOrientation.length];
                for(int i= 0; i<mOrientation.length; i++){
                    lastOrientation[i] = mOrientation[i];
                }

                // Get the current heading from the sensor, then notify the listeners of the
                // change.
                SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values);
                SensorManager.remapCoordinateSystem(mRotationMatrix, SensorManager.AXIS_X,
                        SensorManager.AXIS_Z, mRotationMatrix);
                SensorManager.getOrientation(mRotationMatrix, mOrientation);

                mPitch = (float) Math.toDegrees(mOrientation[1]);

                // Convert the heading (which is relative to magnetic north) to one that is
                // relative to true north, using the user's current location to compute this.
                float magneticHeading = (float) Math.toDegrees(mOrientation[0]);
                mHeading = MathUtils.mod(computeTrueNorth(magneticHeading), 360.0f)
                        - ARM_DISPLACEMENT_DEGREES;

                notifyOrientationChanged();

            }
            if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                for(int i= 0; i<mAcceleration.length; i++){
                    mAcceleration[i] = event.values[0];
                    //Log.d("Nan", "nan: acc: " + mAcceleration[0] + " " + mAcceleration[1] +" "+ mAcceleration[2]);

                    float leakrate;
                    //float scale = .005f;
                    float scale = .01f;
                    if(mPitch<0.5) {leakrate = 0.995f;}
                    else {leakrate = 0.99f;}
                    mActivityLevel = (mActivityLevel*leakrate + scale*(Math.abs(mAcceleration[0]) +
                            Math.abs(mAcceleration[1]) + Math.abs(mAcceleration[2])));
                    if(mActivityLevel>1) mActivityLevel=1;
                }
            }
            if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
                mLightLevel = event.values[0];
                Log.d("nan", "nan: light value: " + mLightLevel);
            }
        }
    };

    /**
     * The location listener used by the orientation manager.
     */
    private LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            mLocation = location;
            updateGeomagneticField();
            notifyLocationChanged();
        }

        @Override
        public void onProviderDisabled(String provider) {
            // Don't need to do anything here.
        }

        @Override
        public void onProviderEnabled(String provider) {
            // Don't need to do anything here.
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Don't need to do anything here.
        }
    };



    /**
     * Adds a listener that will be notified when the user's location or orientation changes.
     */
    public void addOnChangedListener(OnChangedListener listener) {
        mListeners.add(listener);
    }

    /**
     * Removes a listener from the list of those that will be notified when the user's location or
     * orientation changes.
     */
    public void removeOnChangedListener(OnChangedListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Starts tracking the user's location and orientation. After calling this method, any
     * {@link OnChangedListener}s added to this object will be notified of these events.
     */
    public void start() {
        if (!mTracking) {
            mSensorManager.registerListener(mSensorListener,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                    SensorManager.SENSOR_DELAY_UI);

            // The rotation vector sensor doesn't give us accuracy updates, so we observe the
            // magnetic field sensor solely for those.
            mSensorManager.registerListener(mSensorListener,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                    SensorManager.SENSOR_DELAY_UI);

            mSensorManager.registerListener(mSensorListener,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION),
                    SensorManager.SENSOR_DELAY_UI);

            mSensorManager.registerListener(mSensorListener,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT),
                    SensorManager.SENSOR_DELAY_UI);

            /* TODO for some reason this causes a runtime exception
            Location lastLocation = mLocationManager
                    .getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            if (lastLocation != null) {
                long locationAge = lastLocation.getTime() - System.currentTimeMillis();
                if (locationAge < MAX_LOCATION_AGE_MILLIS) {
                    mLocation = lastLocation;
                    updateGeomagneticField();
                }
            }*/

            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setBearingRequired(false);
            criteria.setSpeedRequired(false);

            List<String> providers =
                    mLocationManager.getProviders(criteria, true /* enabledOnly */);
            for (String provider : providers) {
                mLocationManager.requestLocationUpdates(provider,
                        MILLIS_BETWEEN_LOCATIONS, METERS_BETWEEN_LOCATIONS, mLocationListener,
                        Looper.getMainLooper());
            }

            timer = new Timer();
            timer.schedule(new RemindTask(), 0, updateInterval); //delay in milliseconds
            mActivityLevel = 1;


            //start OSC sender
            try {
                InetAddress myAddress = InetAddress.getByName(IP);
                try {

                    OSCsender = new OSCPortOut(myAddress, PORT);

                    try {
                        OSCsender.send(new OSCMessage("set_active", null));
                        Log.i("nan", "nan: OSC now active");
                    } catch (Exception e) {
                        Log.i("nan", "nan: failed sending first OSC message");
                    }

                } catch (Exception e) {
                    Log.d("nan", "nan: failed opening osc socket");
                }
            } catch (Exception e) {
                Log.d("nan", "nan: failed to generate InetAddress");
            }

            mTracking = true;
        }
    }

    /**
     * Stops tracking the user's location and orientation. Listeners will no longer be notified of
     * these events.
     */
    public void stop() {
        if (mTracking) {
            mSensorManager.unregisterListener(mSensorListener);
            mLocationManager.removeUpdates(mLocationListener);

            //close OSC connection
            OSCsender.close();

            //stop task
            timer.cancel();
            mTracking = false;
        }
    }

    /**
     * Gets a value indicating whether there is too much magnetic field interference for the
     * compass to be reliable.
     *
     * @return true if there is magnetic interference, otherwise false
     */
    public boolean hasInterference() {
        return mHasInterference;
    }

    /**
     * Gets a value indicating whether the orientation manager knows the user's current location.
     *
     * @return true if the user's location is known, otherwise false
     */
    public boolean hasLocation() {
        return mLocation != null;
    }

    /**
     * Gets the user's current heading, in degrees. The result is guaranteed to be between 0 and
     * 360.
     *
     * @return the user's current heading, in degrees
     */
    public float getHeading() {
        return mHeading;
    }

    /**
     * Gets the user's current activity level 0 to 1
     *
     * @return the user's current activity level
     */
    public float getActivityLevel() {
        return mActivityLevel;
    }

    /**
     * Gets the user's current pitch (head tilt angle), in degrees. The result is guaranteed to be
     * between -90 and 90.
     *
     * @return the user's current pitch angle, in degrees
     */
    public float getPitch() {
        return mPitch;
    }

    /**
     * Gets the user's current location.
     *
     * @return the user's current location
     */
    public Location getLocation() {
        return mLocation;
    }

    /**
     * Notifies all listeners that the user's activity has changed.
     */
    private void notifyActivityChanged() {
        for (OnChangedListener listener : mListeners) {
            listener.onActivityChanged(this);
        }
    }

    /**
     * Notifies all listeners that the user's orientation has changed.
     */
    private void notifyOrientationChanged() {
        for (OnChangedListener listener : mListeners) {
            listener.onOrientationChanged(this);
        }
    }

    /**
     * Notifies all listeners that the user's location has changed.
     */
    private void notifyLocationChanged() {
        for (OnChangedListener listener : mListeners) {
            listener.onLocationChanged(this);
        }
    }

    /**
     * Notifies all listeners that the compass's accuracy has changed.
     */
    private void notifyAccuracyChanged() {
        for (OnChangedListener listener : mListeners) {
            listener.onAccuracyChanged(this);
        }
    }

    /**
     * Updates the cached instance of the geomagnetic field after a location change.
     */
    private void updateGeomagneticField() {
        mGeomagneticField = new GeomagneticField((float) mLocation.getLatitude(),
                (float) mLocation.getLongitude(), (float) mLocation.getAltitude(),
                mLocation.getTime());
    }

    /**
     * Use the magnetic field to compute true (geographic) north from the specified heading
     * relative to magnetic north.
     *
     * @param heading the heading (in degrees) relative to magnetic north
     * @return the heading (in degrees) relative to true north
     */
    private float computeTrueNorth(float heading) {
        if (mGeomagneticField != null) {
            return heading + mGeomagneticField.getDeclination();
        } else {
            return heading;
        }
    }
}
