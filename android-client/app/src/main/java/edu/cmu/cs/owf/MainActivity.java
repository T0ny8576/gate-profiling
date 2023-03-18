package edu.cmu.cs.owf;

import static android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
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
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Consumer;

import edu.cmu.cs.gabriel.camera.CameraCapture;
import edu.cmu.cs.gabriel.camera.ImageViewUpdater;
import edu.cmu.cs.gabriel.client.comm.ServerComm;
import edu.cmu.cs.gabriel.client.results.ErrorType;
import edu.cmu.cs.gabriel.client.results.SendSupplierResult;
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

    private static final int REQUEST_CODE = 999;
    private static final String CALL_EXPERT = "CALL EXPERT";
    private static final String REPORT = "REPORT";
    private static final String WCA_FSM_START = "WCA_FSM_START";
    private static final String WCA_FSM_END = "WCA_FSM_END";
    private ToServerExtras.ClientCmd reqCommand = ToServerExtras.ClientCmd.NO_CMD;
    private ToServerExtras.ClientCmd prepCommand = ToServerExtras.ClientCmd.NO_CMD;
    private boolean logCompleted = false;
    private String step = WCA_FSM_START;

    private ServerComm serverComm;
    private CameraCapture cameraCapture;

    private TextToSpeech textToSpeech;
    private ImageViewUpdater instructionViewUpdater;
    private ImageView instructionImage;
    private ImageView readyView;
    private TextView readyTextView;
    private VideoView instructionVideo;
    private File videoFile;

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-z", Locale.US);
    private final String LOGFILE = "NO-GATING-" + sdf.format(new Date()) + ".txt";

    private final ConcurrentLinkedDeque<String> logList = new ConcurrentLinkedDeque<>();
    private BatteryManager mBatteryManager;
    private BroadcastReceiver batteryReceiver;
    private FileWriter logFileWriter;
    private Timer timer;
    private int inputFrameCount = 0;
    private static final long TIMER_PERIOD = 1000;

    private long syncStartTime;
    private long realStartTime = 0;
    private int curFrameIndex = 0;
    private int lastFrameIndex = -1;
    private int numFramesSkipped = 0;
    private int numFramesDelayed = 0;
    private final File recordFolder = new File("/sdcard/traces/2023-02-27-11-58-14-EST");
    private final String recordFile = "NO-GATING-2023-02-27-11-58-14-EST.txt";
    private ArrayList<Long> frameTimeArr = new ArrayList<>();
    private ArrayList<Integer> frameSendResultArr = new ArrayList<>();

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

            // TODO: Make member variables atomic if using more than 1 Gabriel Tokens
            if (step.equals(WCA_FSM_START)) {
                realStartTime = SystemClock.uptimeMillis();
                logList.add(TAG + ": Start: " + realStartTime + "\n");
                Log.i(TAG, "Profiling started.");
            }
            step = toClientExtras.getStep();
            if (step.equals(WCA_FSM_END) && !logCompleted) {
                logCompleted = true;
                logList.add(TAG + ": Total Input Frames: " + inputFrameCount + "\n");
                logList.add(TAG + ": Stop: " + SystemClock.uptimeMillis() + "\n");
                logList.add(TAG + ": Number of frames skipped: " + numFramesSkipped + "\n");
                logList.add(TAG + ": Number of frames delayed: " + numFramesDelayed + "\n");
                writeLog();
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

        // Request ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION on Vuzix Blade 2
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + BuildConfig.APPLICATION_ID));
                startActivity(intent);
            }
        }

//        // Permissions for ODG, Magicleap, and Google Glass
//        String[] permissions = new String[] {
//                Manifest.permission.INTERNET, Manifest.permission.READ_EXTERNAL_STORAGE,
//                Manifest.permission.WRITE_EXTERNAL_STORAGE};
//        for (String permission : permissions) {
//            if (ContextCompat.checkSelfPermission(this, permission) !=
//                    PackageManager.PERMISSION_GRANTED) {
//                requestPermissions(permissions, 0);
//                break;
//            }
//        }

        videoFile = new File(this.getCacheDir(), VIDEO_NAME);
        PreviewView viewFinder = findViewById(R.id.viewFinder);

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

        try {
            Scanner sc = new Scanner(new File(recordFolder, recordFile));
            if (sc.hasNextLine()) {
                syncStartTime = Long.parseLong(sc.nextLine().split(": ")[2]);
                Log.w(TAG, "Syncing start time: " + syncStartTime);
            }
            while (sc.hasNextLine()) {
                String[] frameRec = sc.nextLine().split(",");
                if (frameRec.length == 4) {
                    frameTimeArr.add(Long.parseLong(frameRec[0]));
                    frameSendResultArr.add(Integer.parseInt(frameRec[3]));
                }
            }
        } catch (FileNotFoundException e) {
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

        TextToSpeech.OnInitListener onInitListener = status -> {
            if (status == TextToSpeech.ERROR) {
                Log.e(TAG, "TextToSpeech initialization failed with status " + status);
            }

            ToServerExtras toServerExtras = ToServerExtras.newBuilder().setStep(step).build();
            InputFrame inputFrame = InputFrame.newBuilder()
                    .setExtras(pack(toServerExtras))
                    .build();

            // We need to wait for textToSpeech to be initialized before asking for the first
            // instruction.
            serverComm.send(inputFrame, SOURCE, /* wait */ true);
        };
        this.textToSpeech = new TextToSpeech(getApplicationContext(), onInitListener);

        cameraCapture = new CameraCapture(this, analyzer, WIDTH, HEIGHT, viewFinder, CameraSelector.DEFAULT_BACK_CAMERA, false);
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

    final private ImageAnalysis.Analyzer analyzer = new ImageAnalysis.Analyzer() {
        @Override
        public void analyze(@NonNull ImageProxy image) {
            image.close();

            boolean toWait = (prepCommand != ToServerExtras.ClientCmd.NO_CMD);
            if (step.equals(WCA_FSM_END) && !toWait) {
                return;
            }

            if (realStartTime == 0) {
                return;
            }

            // Get most concurrent frame from the recorded trace
            // Always try to wait and use the next frame if possible
            long timeDiff;
            if (lastFrameIndex + 1 < frameTimeArr.size()) {
                curFrameIndex = lastFrameIndex + 1;
            }
            while (curFrameIndex + 1 < frameTimeArr.size()) {
                // Never skip a frame that should be sent to the server
                // Note that the last frame in the recorded trace will never be skipped and might be reused
                if (frameSendResultArr.get(curFrameIndex) == SendSupplierResult.SUCCESS.ordinal()) {
                    break;
                }

                timeDiff = syncStartTime + SystemClock.uptimeMillis() - realStartTime - frameTimeArr.get(curFrameIndex + 1);
                // If it runs slower than the recorded trace, skip frames when necessary
                if (timeDiff > 0) {
                    curFrameIndex++;
                } else {
                    break;
                }
            }

            // Do not reuse frames even if it runs faster than the recorded trace
            if (curFrameIndex == lastFrameIndex) {
                if (curFrameIndex + 1 == frameTimeArr.size()) {
                    return;
                }
                // Pause for the next frame
                timeDiff = syncStartTime + SystemClock.uptimeMillis() - realStartTime - frameTimeArr.get(curFrameIndex + 1);
                if (timeDiff < 0) {
                    try {
                        Thread.sleep(-timeDiff);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                return;
            }

            inputFrameCount++;
//            Log.w(TAG, "inputCount / Cur / Last / NumSkipped / ShouldSend? = " + inputFrameCount +
//                    " / " + curFrameIndex + " / " + lastFrameIndex + " / " + numFramesSkipped +
//                    " / " + frameSendResultArr.get(curFrameIndex));
            numFramesSkipped += Integer.max(curFrameIndex - lastFrameIndex - 1, 0);
            lastFrameIndex = curFrameIndex;

            // If it runs faster, pause until the timestamp of the chosen frame matches that from the recorded trace
            timeDiff = syncStartTime + SystemClock.uptimeMillis() - realStartTime - frameTimeArr.get(curFrameIndex);
            if (timeDiff < 0) {
                numFramesDelayed++;
                try {
                    Thread.sleep(-timeDiff);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            File jpegFrame = new File(recordFolder, "NO-GATING-" + frameTimeArr.get(curFrameIndex) + ".jpg");
            try {
                byte[] jpegBytes = Files.readAllBytes(jpegFrame.toPath());

                // For determinism, ensure that the same frames are sent to the server
                if (frameSendResultArr.get(curFrameIndex) == SendSupplierResult.SUCCESS.ordinal()) {
                    ToServerExtras.ClientCmd clientCmd = prepCommand;
                    prepCommand = ToServerExtras.ClientCmd.NO_CMD;
                    serverComm.sendSupplier(() -> {
                        ByteString jpegByteString = ByteString.copyFrom(jpegBytes);

                        ToServerExtras toServerExtras = ToServerExtras.newBuilder()
                                .setStep(MainActivity.this.step)
                                .setClientCmd(clientCmd)
                                .build();

                        return InputFrame.newBuilder()
                                .setPayloadType(PayloadType.IMAGE)
                                .addPayloads(jpegByteString)
                                .setExtras(pack(toServerExtras))
                                .build();
                    }, SOURCE, true);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraCapture.shutdown();
        // TODO: Clean up the Zoom session?
    }

    public void startVoiceRecognition(View view) {
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        startActivityForResult(intent, REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            final List<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            Log.d(TAG, "ASR results: " + results.toString());
            if (results.size() > 0 && !results.get(0).isEmpty()) {
                String spokenText = results.get(0);
                // TODO: Use more keywords for starting Zoom or sending error report
                if (spokenText.toUpperCase().contains(CALL_EXPERT)) {
                    this.textToSpeech.speak("Calling expert now.",
                            TextToSpeech.QUEUE_FLUSH, null, null);
                    this.reqCommand = ToServerExtras.ClientCmd.ZOOM_START;
                } else if (spokenText.toUpperCase().contains(REPORT)) {
                    this.reqCommand = ToServerExtras.ClientCmd.REPORT;
                    // TODO: Send error report
                    //  Let the server return this feedback message audio
                    final String feedback = "An error log has been recorded. We appreciate your feedback.";
                    this.textToSpeech.speak(feedback, TextToSpeech.QUEUE_FLUSH, null, null);
                }
            }
        } else {
            Log.d(TAG, "ASR Result not OK");
        }
    }
}