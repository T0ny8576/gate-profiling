package edu.cmu.cs.owf;

import static android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
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

import java.io.ByteArrayInputStream;
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
import java.util.Locale;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
    private boolean logCompleted = false;
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
    private static final int PERMISSION_REQUEST_CODE = 440;

    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-z", Locale.US);
    private final String LOGFILE = "THUMBSUP-" + sdf.format(new Date()) + ".txt";

    private final ConcurrentLinkedDeque<String> logList = new ConcurrentLinkedDeque<>();
    private BatteryManager mBatteryManager;
    private BroadcastReceiver batteryReceiver;
    private FileWriter logFileWriter;
    private Timer timer;
    private int inputFrameCount = 0;
    private static final long TIMER_PERIOD = 1000;

    private ThumbsUpDetection thumbsUpDetector;
    private boolean readyForServer = false;
    private long currentStepStartTime = 0;

    private long syncStartTime;
    private long realStartTime = 0;
    private int curFrameIndex = 0;
    private int lastFrameIndex = -1;
    private int numFramesSkipped = 0;
    private int numFramesDelayed = 0;
    private final File recordFolder = new File("/sdcard/traces/THUMBSUP-Q-0");
    private final String recordFile = "THUMBSUP.txt";
    private ArrayList<Long> frameTimeArr = new ArrayList<>();
    private ArrayList<Integer> frameSendResultArr = new ArrayList<>();
    private ExecutorService pool;

    private final ConcurrentLinkedDeque<Long> frameThumbsUpLocalTime = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> frameDroppedLocalTime = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> frameSentLocalTime = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> frameSentRTT = new ConcurrentLinkedDeque<>();

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

            long frameTimeStamp = toClientExtras.getFrameStamp();
            if (frameTimeStamp != 0) {
                frameSentRTT.add(SystemClock.uptimeMillis() - frameTimeStamp);
            }

            // TODO: Make member variables atomic if using more than 1 Gabriel Tokens
            if (step.equals(WCA_FSM_START)) {
                realStartTime = SystemClock.uptimeMillis();
                logList.add(TAG + ": Start: " + realStartTime + "\n");
                Log.i(TAG, "Profiling started.");
                processCameraFrame();
            }
            step = toClientExtras.getStep();
            if (step.equals(WCA_FSM_END) && !logCompleted) {
                logCompleted = true;
                logList.add(TAG + ": Total Input Frames: " + inputFrameCount + "\n");
                logList.add(TAG + ": Stop: " + SystemClock.uptimeMillis() + "\n");
                logList.add(TAG + ": Number of frames skipped: " + numFramesSkipped + "\n");
                logList.add(TAG + ": Number of frames delayed: " + numFramesDelayed + "\n");
                writeLog();
                readyForServer = false;
                runOnUiThread(() -> {
                    readyView.setVisibility(View.INVISIBLE);
                    readyTextView.setVisibility(View.INVISIBLE);
                });
                Log.i(TAG, "Profiling completed.");
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
                if (!step.equals(WCA_FSM_END)) {
                    readyForServer = false;
                    currentStepStartTime = SystemClock.uptimeMillis();
                    runOnUiThread(() -> {
                        readyView.setVisibility(View.INVISIBLE);
                        readyTextView.setVisibility(View.VISIBLE);
                    });
                }

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

//        // Request ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION on Vuzix Blade 2
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            if (!Environment.isExternalStorageManager()) {
//                Intent intent = new Intent(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
//                        Uri.parse("package:" + BuildConfig.APPLICATION_ID));
//                startActivity(intent);
//            }
//        }

        // Permissions for ODG, Magicleap, and Google Glass
        String[] permissions = new String[] {Manifest.permission.CAMERA,
                                             Manifest.permission.RECORD_AUDIO,
                                             Manifest.permission.WRITE_EXTERNAL_STORAGE};
        boolean permissionGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED) {
                permissionGranted = false;
                break;
            }
        }
        if (permissionGranted) {
            initActivity();
        } else {
            requestPermissions(permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean permissionGranted = true;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) !=
                        PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = false;
                    break;
                }
            }
            if (permissionGranted) {
                initActivity();
            } else {
                finish();
            }
        }
    }

    private void initActivity() {
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

        try {
            Scanner sc = new Scanner(new File(recordFolder, recordFile));
            if (sc.hasNextLine()) {
                syncStartTime = Long.parseLong(sc.nextLine().split(": ")[2]);
                Log.i(TAG, "Syncing start time: " + syncStartTime);
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
        pool = Executors.newFixedThreadPool(2);
        timer = new Timer();
        timer.scheduleAtFixedRate(new LogTimerTask(), 0, TIMER_PERIOD);

        cameraCapture = new ML2CameraCapture(WIDTH, HEIGHT, viewFinder);

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

        thumbsUpDetector = new ThumbsUpDetection(this);
        thumbsUpDetector.hands.setResultListener(
                handsResult -> {
                    if (!handsResult.multiHandLandmarks().isEmpty()) {
                        if (handsResult.timestamp() > currentStepStartTime &&
                                ThumbsUpDetection.detectThumbsUp(handsResult)) {
                            frameThumbsUpLocalTime.add(SystemClock.uptimeMillis() - handsResult.timestamp());
                            readyForServer = true;
                            runOnUiThread(() -> {
                                readyView.setVisibility(View.VISIBLE);
                                readyTextView.setVisibility(View.VISIBLE);
                            });
                        }
                    }
                });
        thumbsUpDetector.hands.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Hands error:" + message));
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

        logList.add("\nframeThumbsUpLocalTime:\n");
        for (Long timeDiff: frameThumbsUpLocalTime) {
            logList.add(timeDiff + ",");
        }
        logList.add("\nframeDroppedLocalTime:\n");
        for (Long timeDiff: frameDroppedLocalTime) {
            logList.add(timeDiff + ",");
        }
        logList.add("\nframeSentLocalTime:\n");
        for (Long timeDiff: frameSentLocalTime) {
            logList.add(timeDiff + ",");
        }
        logList.add("\nframeSentRTT:\n");
        for (Long timeDiff: frameSentRTT) {
            logList.add(timeDiff + ",");
        }
        logList.add("\n");

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
        pool.execute(() -> {
            // Camera preview loop
            while (true) {
                Bitmap rgbaBitmap = cameraCapture.updateImagePreview();
                if (rgbaBitmap == null) {
                    continue;
                }
            }
        });
        pool.execute(() -> {
            while (curFrameIndex + 1 < frameTimeArr.size()) {
                analyzeImage();
            }
        });
    }

    private void analyzeImage() {
        boolean toWait = (prepCommand != ToServerExtras.ClientCmd.NO_CMD);
        if (step.equals(WCA_FSM_END) && !toWait) {
            return;
        }

        if (realStartTime == 0) {
            return;
        }

        // Get most concurrent frame from the recorded trace
        boolean readyToSend = readyForServer;
        long timeDiff;
        // Always try to wait and use the next frame if possible
        if (lastFrameIndex + 1 < frameTimeArr.size()) {
            curFrameIndex = lastFrameIndex + 1;
        }
        while (curFrameIndex + 1 < frameTimeArr.size()) {
            // Never skip or consume locally a frame that should be sent to the server
            if (frameSendResultArr.get(curFrameIndex) == SendSupplierResult.SUCCESS.ordinal()) {
                if (!readyToSend) {
                    curFrameIndex = Integer.max(curFrameIndex - 1, lastFrameIndex);
                }
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
                // Increase the current index and do not enter this function again
                curFrameIndex++;
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
//        Log.w(TAG, "inputCount / Cur / Last / Ready = " + inputFrameCount +
//                " / " + curFrameIndex + " / " + lastFrameIndex + " / " + readyForServer);
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

        long jpegTime = SystemClock.uptimeMillis();
        File jpegFrame = new File(recordFolder, "THUMBSUP-" + frameTimeArr.get(curFrameIndex) + ".jpg");
        try {
            byte[] jpegBytes = Files.readAllBytes(jpegFrame.toPath());

            if (readyToSend) {
                // For determinism, ensure that the same frames are sent to the server
                if (frameSendResultArr.get(curFrameIndex) == SendSupplierResult.SUCCESS.ordinal()) {
                    ToServerExtras.ClientCmd clientCmd = prepCommand;
                    prepCommand = ToServerExtras.ClientCmd.NO_CMD;
                    serverComm.sendSupplier(() -> {
                        ByteString jpegByteString = ByteString.copyFrom(jpegBytes);

                        long timeBeforeSending = SystemClock.uptimeMillis();
                        frameSentLocalTime.add(timeBeforeSending - jpegTime);
                        ToServerExtras toServerExtras = ToServerExtras.newBuilder()
                                .setStep(MainActivity.this.step)
                                .setClientCmd(clientCmd)
                                .setFrameStamp(timeBeforeSending)
                                .build();

                        return InputFrame.newBuilder()
                                .setPayloadType(PayloadType.IMAGE)
                                .addPayloads(jpegByteString)
                                .setExtras(pack(toServerExtras))
                                .build();
                    }, SOURCE, true);
                } else {
                    frameDroppedLocalTime.add(SystemClock.uptimeMillis() - jpegTime);
                }
            } else {
                // A few frames that should be consumed here might be dropped due to the
                // server's delayed response, but this level of inconsistency is tolerable
                ByteArrayInputStream bais = new ByteArrayInputStream(jpegBytes);
                Bitmap bitmapImage = BitmapFactory.decodeStream(bais);
                bais.close();
                thumbsUpDetector.hands.send(bitmapImage, jpegTime);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraCapture.shutdown();
        // TODO: Clean up the Zoom session?
    }
}