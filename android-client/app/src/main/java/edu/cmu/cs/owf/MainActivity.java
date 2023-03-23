package edu.cmu.cs.owf;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import edu.cmu.cs.gabriel.camera.ImageViewUpdater;
import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.results.ErrorType;
import edu.cmu.cs.gabriel.protocol.Protos.InputFrame;
import edu.cmu.cs.gabriel.protocol.Protos.ResultWrapper;
import edu.cmu.cs.gabriel.protocol.Protos.PayloadType;
import edu.cmu.cs.owf.Protos.ToClientExtras;
import edu.cmu.cs.owf.Protos.ToServerExtras;
import edu.cmu.cs.owf.Protos.ZoomInfo;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String VIDEO_NAME = "video";
    private static final String SOURCE = "owf_client";
    private static final int PORT = 9099;

    // Attempt to get the largest images possible. ImageAnalysis is limited to something below 1080p
    // according to this:
    // https://developer.android.com/reference/androidx/camera/core/ImageAnalysis.Builder#setTargetResolution(android.util.Size)
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    public static final String EXTRA_APP_KEY = "edu.cmu.cs.owf.APP_KEY";
    public static final String EXTRA_APP_SECRET = "edu.cmu.cs.owf.APP_SECRET";
    public static final String EXTRA_MEETING_NUMBER = "edu.cmu.cs.owf.MEETING_NUMBER";
    public static final String EXTRA_MEETING_PASSWORD = "edu.cmu.cs.owf.MEETING_PASSWORD";
    private static final String WCA_FSM_START = "WCA_FSM_START";
    private static final String WCA_FSM_END = "WCA_FSM_END";
    private ToServerExtras.ClientCmd reqCommand = ToServerExtras.ClientCmd.NO_CMD;
    private ToServerExtras.ClientCmd prepCommand = ToServerExtras.ClientCmd.NO_CMD;
    private boolean readyToCount = false;
    private String step = WCA_FSM_START;

    private ServerComm serverComm;
    private ML2CameraCapture cameraCapture;

    private TextToSpeech textToSpeech;
    private ImageViewUpdater instructionViewUpdater;
    private ImageView instructionImage;
    private ImageView readyView;
    private TextView readyTextView;
    private VideoView instructionVideo;
    private File videoFile;

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-z", Locale.US);
    private final String LOGFILE = "IDLE-" + sdf.format(new Date()) + ".txt";

    private final ConcurrentLinkedDeque<String> logList = new ConcurrentLinkedDeque<>();
    private BatteryManager mBatteryManager;
    private BroadcastReceiver batteryReceiver;
    private FileWriter logFileWriter;
    private Timer timer;
    private int inputFrameCount = 0;
    private static final long TIMER_PERIOD = 1000;
    private ExecutorService pool;

    private final ActivityResultLauncher<Intent> activityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    ToServerExtras toServerExtras = ToServerExtras.newBuilder()
                            .setClientCmd(ToServerExtras.ClientCmd.ZOOM_STOP)
                            .build();

                    serverComm.send(
                            InputFrame.newBuilder().setExtras(pack(toServerExtras)).build(),
                            SOURCE,
                            /* wait */ true);
                }
            });

    private final Consumer<ResultWrapper> consumer = resultWrapper -> {
        try {
            ToClientExtras toClientExtras = ToClientExtras.parseFrom(
                    resultWrapper.getExtras().getValue());
            if (toClientExtras.getZoomResult() == ToClientExtras.ZoomResult.CALL_START) {
                ZoomInfo zoomInfo = toClientExtras.getZoomInfo();

                Intent intent = new Intent(this, ZoomActivity.class);
                intent.putExtra(EXTRA_APP_KEY, zoomInfo.getAppKey());
                intent.putExtra(EXTRA_APP_SECRET, zoomInfo.getAppSecret());
                intent.putExtra(EXTRA_MEETING_NUMBER, zoomInfo.getMeetingNumber());
                intent.putExtra(EXTRA_MEETING_PASSWORD, zoomInfo.getMeetingPassword());

                activityResultLauncher.launch(intent);
                return;
            } else if (toClientExtras.getZoomResult() == ToClientExtras.ZoomResult.EXPERT_BUSY) {
                runOnUiThread(() -> {
                    AlertDialog alertDialog = new AlertDialog.Builder(this)
                            .setTitle("Expert Busy")
                            .setMessage("The expert is currently helping someone else.")
                            .create();
                    alertDialog.show();
                });
            }

            if (step.equals(WCA_FSM_START)) {
                Log.i(TAG, "Profiling started.");
            }
            step = toClientExtras.getStep();
            if (step.equals(WCA_FSM_END)) {
                Log.i(TAG, "Profiling completed.");
            }

            // Display or hide the thumbs-up icon
            if (toClientExtras.getUserReady() == ToClientExtras.UserReady.SET) {
                runOnUiThread(() -> {
                    readyView.setVisibility(View.VISIBLE);
                    readyTextView.setVisibility(View.VISIBLE);
                });
            } else if (toClientExtras.getUserReady() == ToClientExtras.UserReady.CLEAR) {
                runOnUiThread(() -> {
                    readyView.setVisibility(View.INVISIBLE);
                    readyTextView.setVisibility(View.VISIBLE);
                });
            } else if (toClientExtras.getUserReady() == ToClientExtras.UserReady.DISABLE) {
                runOnUiThread(() -> {
                    readyView.setVisibility(View.INVISIBLE);
                    readyTextView.setVisibility(View.INVISIBLE);
                });
            }

        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Protobuf parse error", e);
        }

        // Prepare the command parsed from ASR to be sent to the server with the next frame
        if (reqCommand != ToServerExtras.ClientCmd.NO_CMD) {
            prepCommand = reqCommand;
            reqCommand = ToServerExtras.ClientCmd.NO_CMD;
        }

        if (resultWrapper.getResultsCount() == 0) {
            return;
        }

        boolean hasVideo = false;
        for (ResultWrapper.Result result : resultWrapper.getResultsList()) {
            if (result.getPayloadType() == PayloadType.VIDEO) {
                hasVideo = true;
                break;
            }
        }

        // Load the user guidance (audio, image/video) from the result wrapper
        for (ResultWrapper.Result result : resultWrapper.getResultsList()) {
            if (result.getPayloadType() == PayloadType.TEXT) {
                ByteString dataString = result.getPayload();
                String speech = dataString.toStringUtf8();
                this.textToSpeech.speak(speech, TextToSpeech.QUEUE_FLUSH, null, null);
                Log.i(TAG, "Saying: " + speech);
            } else if ((result.getPayloadType() == PayloadType.IMAGE) && !hasVideo) {
                ByteString image = result.getPayload();
                instructionViewUpdater.accept(image);

                runOnUiThread(() -> {
                    instructionImage.setVisibility(View.VISIBLE);
                    instructionVideo.setVisibility(View.INVISIBLE);
                    instructionVideo.stopPlayback();
                });
            } else if (result.getPayloadType() == PayloadType.VIDEO) {
                try {
                    videoFile.delete();
                    videoFile.createNewFile();
                    FileOutputStream fos = new FileOutputStream(videoFile);
                    result.getPayload().writeTo(fos);
                    fos.close();

                    runOnUiThread(() -> {
                        instructionVideo.setVideoPath(videoFile.getPath());
                        instructionVideo.start();

                        instructionImage.setVisibility(View.INVISIBLE);
                        instructionVideo.setVisibility(View.VISIBLE);
                    });
                } catch (IOException e) {
                    Log.e(TAG, "video file failed", e);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        videoFile = new File(this.getCacheDir(), VIDEO_NAME);
        ImageView viewFinder = findViewById(R.id.viewFinder);

        readyView = findViewById(R.id.readyView);
        readyTextView = findViewById(R.id.readyTextView);
        AssetManager assetManager = getAssets();
        try
        {
            InputStream ins = assetManager.open("thumbs_up.png");
            Drawable drawable = Drawable.createFromStream(ins, null);
            readyView.setImageDrawable(drawable);
            ins.close();
        }
        catch(IOException e) {
            e.printStackTrace();
        }

        instructionImage = findViewById(R.id.instructionImage);
        instructionViewUpdater = new ImageViewUpdater(instructionImage);

        instructionVideo = findViewById(R.id.instructionVideo);

        // from https://stackoverflow.com/a/8431374/859277
        instructionVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.setLooping(true);
            }
        });

        File logFile = new File(getExternalFilesDir(null), LOGFILE);
        logFile.delete();
        logFile = new File(getExternalFilesDir(null), LOGFILE);
        try {
            logFileWriter = new FileWriter(logFile, true);
        } catch (IOException e) {
            e.printStackTrace();
        }

        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        batteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
                int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, Integer.MIN_VALUE);
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, Integer.MIN_VALUE);
                String voltageMsg = TAG + ": Time: " + SystemClock.uptimeMillis() +
                        "\tBattery voltage = " + voltage +
                        " Level = " + level + "/" + scale + "\n";
                logList.add(voltageMsg);
            }
        };
        registerReceiver(batteryReceiver, intentFilter);

        mBatteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);

        timer = new Timer();
        timer.scheduleAtFixedRate(new LogTimerTask(), 0, TIMER_PERIOD);

        Consumer<ErrorType> onDisconnect = errorType -> {
            Log.e(TAG, "Disconnect Error: " + errorType.name());
            finish();
        };
        serverComm = ServerComm.createServerComm(
                consumer, BuildConfig.GABRIEL_HOST, PORT, getApplication(), onDisconnect);

        TextToSpeech.OnInitListener onInitListener = i -> {
            readyToCount = true;
        };
        this.textToSpeech = new TextToSpeech(this, onInitListener);

        cameraCapture = new ML2CameraCapture(WIDTH, HEIGHT, viewFinder);
        pool = Executors.newFixedThreadPool(1);
        pool.execute(this::processCameraFrame);
    }

    class LogTimerTask extends TimerTask {
        @Override
        public void run() {
            int current = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            String testMag = TAG + ": Time: " + SystemClock.uptimeMillis() +
                    "\tCurrent: " + current + "\n";
            logList.add(testMag);
        }
    }

    private void writeLog() {
        timer.cancel();
        timer.purge();
        unregisterReceiver(batteryReceiver);
        try {
            for (String logString: logList) {
                logFileWriter.write(logString);
            }
            logFileWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Based on
    // https://github.com/protocolbuffers/protobuf/blob/master/src/google/protobuf/compiler/java/java_message.cc#L1387
    public static Any pack(ToServerExtras toServerExtras) {
        return Any.newBuilder()
                .setTypeUrl("type.googleapis.com/owf.ToServerExtras")
                .setValue(toServerExtras.toByteString())
                .build();
    }


    private void processCameraFrame() {
        while (true) {
            Bitmap rgbaBitmap = cameraCapture.updateImagePreview();
            if (rgbaBitmap == null) {
                continue;
            }

            boolean toWait = (prepCommand != ToServerExtras.ClientCmd.NO_CMD);
            if ((!readyToCount || step.equals(WCA_FSM_END)) && !toWait) {
                continue;
            }
            inputFrameCount++;
            if (inputFrameCount == 1) {
                logList.add(TAG + ": Start: " + SystemClock.uptimeMillis() + "\n");
                Log.i(TAG, "Profiling started.");
            }
            if (inputFrameCount == 2500) {
                logList.add(TAG + ": Total Input Frames: " + inputFrameCount + "\n");
                logList.add(TAG + ": Stop: " + SystemClock.uptimeMillis() + "\n");
                writeLog();
                Log.i(TAG, "Profiling completed.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraCapture.shutdown();
        // TODO: Clean up the Zoom session?
    }
}