package com.kamilkrzywda.astrolapse;

import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Camera;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.ma1co.pmcademo.app.BaseActivity;

import com.sony.scalar.hardware.CameraEx;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ShootActivity extends BaseActivity implements SurfaceHolder.Callback
{
    private Settings settings;

    private int shotCount;

    private TextView tvCount, tvBattery, tvRemaining;
    private LinearLayout llEnd;

    private SurfaceView reviewSurfaceView;
    private SurfaceHolder cameraSurfaceHolder;
    private CameraEx cameraEx;
    private Camera camera;
    private CameraEx.AutoPictureReviewControl autoReviewControl;
    private int pictureReviewTime;

    private boolean burstShooting;
    private boolean stopPicturePreview;
    private boolean takingPicture;

    private long shootTime;
    private long shootStartTime;

    private Display display;

    static private final boolean SHOW_END_SCREEN = false;

    private Handler shootRunnableHandler = new Handler();
    private final Runnable shootRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            if(stopPicturePreview) {
                stopPicturePreview = false;
                camera.stopPreview();
                reviewSurfaceView.setVisibility(View.GONE);
                if(settings.displayOff)
                    display.off();
            }

            if (takingPicture) {
                cameraEx.cancelTakePicture();
                cameraEx.getNormalCamera().stopPreview();
                camera.stopPreview();
                stopPicturePreview = true;
                takingPicture = false;
                shootRunnableHandler.postDelayed(shootRunnable, 700);
            } else if(shotCount < settings.shotCount) {
                shoot();
                display.on();
            } else {
                display.on();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if(SHOW_END_SCREEN) {
                            tvCount.setText("Thanks for using this app!");
                            tvBattery.setVisibility(View.INVISIBLE);
                            tvRemaining.setVisibility(View.INVISIBLE);
                            llEnd.setVisibility(View.VISIBLE);
                        }
                        else {
                            onBackPressed();
                        }
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        log("onCreate");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shoot);

        Intent intent = getIntent();
        settings = Settings.getFromIntent(intent);

        shotCount = 0;
        takingPicture = false;
        burstShooting = false;

        tvCount = (TextView) findViewById(R.id.tvCount);
        tvBattery = (TextView) findViewById(R.id.tvBattery);
        tvRemaining = (TextView) findViewById(R.id.tvRemaining);
        llEnd = (LinearLayout) findViewById(R.id.llEnd);

        reviewSurfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        reviewSurfaceView.setZOrderOnTop(false);
        cameraSurfaceHolder = reviewSurfaceView.getHolder();
        cameraSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }


    @Override
    protected void onResume() {
        log("onResume");

        super.onResume();
        cameraEx = CameraEx.open(0, null);
        cameraSurfaceHolder.addCallback(this);
        //autoReviewControl = new CameraEx.AutoPictureReviewControl();
        //autoReviewControl.setPictureReviewInfoHist(true);
        //cameraEx.setAutoPictureReviewControl(autoReviewControl);

        final Camera.Parameters params = cameraEx.getNormalCamera().getParameters();

        try {
            if(settings.mf)
                params.setFocusMode(CameraEx.ParametersModifier.FOCUS_MODE_MANUAL);
            else
                params.setFocusMode("auto");
        }
        catch(Exception ignored)
        {}


        final CameraEx.ParametersModifier modifier = cameraEx.createParametersModifier(params);

        modifier.setDriveMode(CameraEx.ParametersModifier.DRIVE_MODE_SINGLE);

        // Disable long exposure noise reduction (LENR)
        try {
            modifier.setLongExposureNR(false);
        }
        catch(NoSuchMethodError ignored)
        {}

        // setSilentShutterMode doesn't exist on all cameras
        try {
            modifier.setSilentShutterMode(settings.silentShutter);
        }
        catch(NoSuchMethodError ignored)
        {}

        try{
            //add also AEL if set
            if(settings.ael) {
                modifier.setAutoExposureLock(CameraEx.ParametersModifier.AE_LOCK_ON);
            }
        }
        catch (Exception e){
            //do nothing
        }

        cameraEx.getNormalCamera().setParameters(params);

        pictureReviewTime = 2; //autoReviewControl.getPictureReviewTime();
        //log(Integer.toString(pictureReviewTime));


        shotCount = 0;
        shootRunnableHandler.postDelayed(shootRunnable, (long) settings.delay * 1000);

        display = new Display(getDisplayManager());

        if(settings.displayOff) {
            display.turnAutoOff(5000);
        }

        setAutoPowerOffMode(false);

        tvCount.setText(Integer.toString(shotCount)+"/"+Integer.toString(settings.shotCount));
        tvRemaining.setText(getRemainingTime());
        tvBattery.setText(getBatteryPercentage());
    }

    @Override
    protected boolean onMenuKeyUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onPause() {
        display.on();
        display.turnAutoOff(Display.NO_AUTO_OFF);

        super.onPause();

        log("on pause");

        cameraEx.cancelTakePicture();
        shootRunnableHandler.removeCallbacks(shootRunnable);

        if(cameraSurfaceHolder == null)
            log("cameraSurfaceHolder == null");
        else {
            cameraSurfaceHolder.removeCallback(this);
        }

        autoReviewControl = null;

        if(camera == null)
            log("camera == null");
        else {
            camera.stopPreview();
            camera = null;
        }

        if(cameraEx == null)
            log("cameraEx == null");
        else {
            cameraEx.setAutoPictureReviewControl(null);
            cameraEx.release();
            cameraEx = null;
        }

        setAutoPowerOffMode(true);
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = cameraEx.getNormalCamera();
            camera.setPreviewDisplay(holder);
        }
        catch (IOException e) {}
    }

    private void shoot() {
        shootTime = System.currentTimeMillis();

        cameraEx.getNormalCamera().takePicture(null, null, null);

        shotCount++;
        takingPicture = true;

        shootRunnableHandler.postDelayed(shootRunnable, (long) settings.interval * 1000);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvCount.setText(Integer.toString(shotCount)+"/"+Integer.toString(settings.shotCount));
                tvRemaining.setText(getRemainingTime());
                tvBattery.setText(getBatteryPercentage());
            }
        });
    }

    private String getBatteryPercentage()
    {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                chargePlug == BatteryManager.BATTERY_PLUGGED_USB ||
                chargePlug == BatteryManager.BATTERY_PLUGGED_AC;

        String s = "";
        if(isCharging)
            s = "c ";

        return s + (int)(level / (float)scale * 100) + "%";
    }

    private String getRemainingTime() {
        return "" + Math.round((settings.shotCount - shotCount) * settings.interval / 60) + "min";
    }

    @Override
    protected void onAnyKeyDown() {
        display.on();
    }


    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {}

    @Override
    protected void setColorDepth(boolean highQuality)
    {
        super.setColorDepth(false);
    }


    private void log(String s) {
        Logger.info(s);
    }

    private void dumpList(List list, String name) {
        log(name);
        log(": ");
        if (list != null)
        {
            for (Object o : list)
            {
                log(o.toString());
                log(" ");
            }
        }
        else
            log("null");
        log("\n");
    }
}
