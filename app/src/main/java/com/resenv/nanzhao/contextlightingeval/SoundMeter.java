package com.resenv.nanzhao.contextlightingeval;

import android.app.Activity;
import android.widget.LinearLayout;
import android.os.Bundle;
import android.os.Environment;
import android.view.ViewGroup;
import android.widget.Button;
import android.view.View;
import android.view.View.OnClickListener;
import android.content.Context;
import android.util.Log;
import android.media.MediaRecorder;
import android.media.MediaPlayer;

import java.io.IOException;
/**
 * tired this to measure sound but did not work, I think because sound was not buffered correctly
 * switche to audioRecord
 * Created by nanzhao on 2/24/15.
 */
public class SoundMeter {

    private MediaRecorder mRecorder = null;

    public void start() {
        if (mRecorder == null) {
            mRecorder = new MediaRecorder();
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mRecorder.setOutputFile("/dev/null");
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

            try {
                mRecorder.prepare();
            } catch (IOException e) {
                Log.e("nan", "nan: recorder prepare() failed");
            }

            mRecorder.start();
            getAmplitude();
        }
    }

    public void stop() {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder.release();
            mRecorder = null;
        }
    }

    public double getAmplitude() {
        if (mRecorder==null){
            return 0;
        } else {
            int result = mRecorder.getMaxAmplitude();
            return result;
        }
    }
}
