package edu.cmu.cs.owf;

import android.media.AudioRecord;
import android.util.Log;

import edu.cmu.pocketsphinx.MLRecorder;

public class ML2AudioCapture implements MLRecorder {
    private static final String TAG = "ML2AudioCapture";
    private int mState;

    public ML2AudioCapture() {
        mState = -1;  // Uninitialized
    }

    public void initialize(int channelCount, int sampleRate) {
        createAudioInput(channelCount, sampleRate);
        if (getBitDepth() != 16) {
            Log.e(TAG, "Input audio data not PCM_16BIT format!");
        }
        mState = 0;
    }

    @Override
    public int getRecordingState() {
        if (mState != 0) {
            return -1;
        }
        return getAudioInputState();
    }

    @Override
    public int read(short[] audioData, int offsetInShorts, int sizeInShorts) {
        int readSize;
        if (mState != 0) {
            readSize = AudioRecord.ERROR_INVALID_OPERATION;
        } else if ((audioData == null) || (offsetInShorts < 0) || (sizeInShorts < 0)
                || (offsetInShorts + sizeInShorts > audioData.length)) {
            readSize = AudioRecord.ERROR_BAD_VALUE;
        } else {
            readSize = readAudioBuffer(audioData, offsetInShorts, sizeInShorts);
        }

        if (readSize < 0) {
            Log.e(TAG, "Reading audio buffer returns error code: " + readSize);
            return -1;
        }
        return readSize;
    }

    @Override
    public void startRecording() {
        if (mState != 0) {
            return;
        }
        startAudioInput();
    }

    @Override
    public void stop() {
        if (mState != 0) {
            return;
        }
        stopAudioInput();
    }

    @Override
    public void release() {
        if (mState != 0) {
            return;
        }
        destroyAudioInput();
    }

    private native void createAudioInput(int channelCount, int sampleRate);
    private native int getBitDepth();
    private native int getAudioInputState();
    private native int readAudioBuffer(short[] audioData, int offsetInShorts, int sizeInShorts);
    private native void startAudioInput();
    private native void stopAudioInput();
    private native void destroyAudioInput();

    static {
        System.loadLibrary("ml2_api_lib");
    }
}