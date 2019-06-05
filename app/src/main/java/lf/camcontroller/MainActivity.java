package lf.camcontroller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    // USB communication variables
    private static final String ACTION_USB_PERMISSION = "finalusb.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbSerialDevice usbSerialDevice;
    private UsbDeviceConnection usbDeviceConnection;
    private boolean usbBtnState = false;
    private boolean isUsbPortConnected = false;

    // OpenCV variables
    private static final Scalar LOWER_LIMIT = new Scalar(29, 86, 6);
    private static final Scalar UPPER_LIMIT = new Scalar(70, 255, 255);
    private static final Point TEXT_POS = new Point(300,600);
    private static final int SENSITIVITY = 100;
    private boolean isFrontCam = false;
    private Mat mRgba;
    private CameraBridgeViewBase opencvCamView;
    private BaseLoaderCallback baseLoaderCallback;
    private int prevCmd = 4;

    // GUI and other variables
    private static final String TAG = "MainActivity";
    private Handler mainHandler;
    private TextView logTv;
    private Toolbar toolBar;
    private Button toggleUsb;

    // Initializations
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler();

        toggleUsb = findViewById(R.id.toggleUsb);
        toggleUsb.setEnabled(false);
        toggleUsb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(usbBtnState){
                    usbBtnState = false;
                    disconnectUsb();
                    Log("Stopped USB communication");
                }
                else{
                    usbBtnState = true;
                    try{
                        PendingIntent permissionIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, new Intent(ACTION_USB_PERMISSION), 0);
                        usbManager.requestPermission(usbDevice, permissionIntent);
                    }
                    catch (Exception ex){
                        Log(ex.toString());
                    }
                    Log("Started USB communication");
                }
            }
        });
        toolBar = findViewById(R.id.toolBar);
        logTv = findViewById(R.id.logView);
        logTv.setMovementMethod(new ScrollingMovementMethod());
        setSupportActionBar(toolBar);
        opencvCamView = findViewById(R.id.camView);
        opencvCamView.setVisibility(SurfaceView.VISIBLE);
        opencvCamView.setCvCameraViewListener(this);

        baseLoaderCallback = new BaseLoaderCallback(this) {
            @Override
            public void onManagerConnected(int status) {
                switch (status){
                    case BaseLoaderCallback.SUCCESS:
                        Log.wtf(TAG,"OpenCV loaded successfully");
                        opencvCamView.enableView();
                        break;
                    default:
                        super.onManagerConnected(status);
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_items, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.toggleCamera:
                if(isFrontCam){
                    swapCamera();
                    item.setTitle("Front Camera");
                    isFrontCam = false;
                    Log.wtf(TAG,"Back Camera");
                }
                else{
                    swapCamera();
                    item.setTitle("Back Camera");
                    isFrontCam = true;
                    Log.wtf(TAG,"Front Camera");
                }
                return true;
            case R.id.echo:
                sendCommand(3);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void Log(String text) {
        final String ftext = text+"\n";
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                logTv.append(ftext);
            }
        });
    }

    // Camera and image processing >>>>>
    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height,width, CvType.CV_8UC4);
    }

    @Override
    public void onCameraViewStopped() {
        mRgba.release();
        Log.wtf(TAG,"CameraView Stopped");
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        Mat inter = new Mat(mRgba.size(),CvType.CV_8UC4);
        List<MatOfPoint> contours = new ArrayList<>();
        Mat interHi = new Mat();
        float[] controllerRadius = new float[1];
        Point controllerCenter = new Point();

        mRgba = inputFrame.rgba();
        Point frameCenter = new Point(mRgba.cols() /2, mRgba.rows() /2);
        Imgproc.circle(mRgba,frameCenter,15,new Scalar(0x00, 0xFF, 0x1A),7);
        Imgproc.GaussianBlur(mRgba,inter,new Size(9,9),0);
        Imgproc.cvtColor(inter,inter,Imgproc.COLOR_BGR2HSV);
        Core.inRange(inter,LOWER_LIMIT,UPPER_LIMIT,inter);
        Imgproc.findContours(inter,contours,interHi,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE);
        if(contours.size()>0){
            double maxVal = 0;
            int maxValIdx = 0;
            for (int contourIdx = 0; contourIdx < contours.size(); contourIdx++){
                double contourArea = Imgproc.contourArea(contours.get(contourIdx));
                if (maxVal < contourArea){
                    maxVal = contourArea;
                    maxValIdx = contourIdx;
                }
            }
            Imgproc.minEnclosingCircle(new MatOfPoint2f(contours.get(maxValIdx).toArray()), controllerCenter, controllerRadius);
            if(controllerRadius[0]>10){
                Imgproc.circle(mRgba,controllerCenter,7,new Scalar(255,0, 0),-1);
                int diffX = (int) (mRgba.cols() /2 - controllerCenter.x);
                if (diffX > 0){
                    if (diffX > SENSITIVITY){
                        Imgproc.putText(mRgba,"RIGHT",TEXT_POS,Core.FONT_HERSHEY_COMPLEX,1,new Scalar(0,0,0),1);
                        sendCommand(2);
                    }
                    else{
                        Imgproc.putText(mRgba,"CENTER",TEXT_POS,Core.FONT_HERSHEY_COMPLEX,1,new Scalar(0,0,0),1);
                        sendCommand(0);
                    }
                }
                else if(diffX < 0){
                    if(-1*diffX > SENSITIVITY){
                        Imgproc.putText(mRgba,"LEFT",TEXT_POS,Core.FONT_HERSHEY_COMPLEX,1,new Scalar(0,0,0),1);
                        sendCommand(1);
                    }
                    else{
                        Imgproc.putText(mRgba,"CENTER",TEXT_POS,Core.FONT_HERSHEY_COMPLEX,1,new Scalar(0,0,0),1);
                        sendCommand(0);
                    }
                }
                else{
                    Imgproc.putText(mRgba,"CENTER",TEXT_POS,Core.FONT_HERSHEY_COMPLEX,1,new Scalar(0,0,0),1);
                    sendCommand(0);
                }
            }
        }
        inter.release();
        interHi.release();
        return mRgba;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(opencvCamView !=null){
            opencvCamView.disableView();
            Log.wtf(TAG,"Camera Paused");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(!OpenCVLoader.initDebug()){
            Log.wtf(TAG,"OpenCVLoader.initdebug() returned false");
            Toast.makeText(getApplicationContext(),"Prob 00",Toast.LENGTH_SHORT).show();
        }
        else{
            baseLoaderCallback.onManagerConnected(BaseLoaderCallback.SUCCESS);
            Log.wtf(TAG,"Camera Resumed");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(opencvCamView !=null){
            opencvCamView.disableView();
            Log.w(TAG,"Camera Turned OFF");
        }
        disconnectUsb();
    }

    private void swapCamera() {
        opencvCamView.disableView();
        opencvCamView.setCameraIndex(isFrontCam ? 0 : 1);
        opencvCamView.enableView();
    }

    // USB communication >>>>

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log("usbDevice attached");
                synchronized (this) {
                    usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            toggleUsb.setEnabled(true);
                        }
                    });
                }
            }
            else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
                if(isUsbPortConnected){
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            usbSerialDevice.close();
                            isUsbPortConnected = false;
                        }
                    });
                }
                Log("Device removed.");
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        toggleUsb.performClick();
                        toggleUsb.setEnabled(false);

                    }
                });
            }
            else if(ACTION_USB_PERMISSION.equals(action)){
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)){
                    try{
                        if(usbDevice != null){
                            if(usbDevice.getVendorId() == 1027){
                                usbDeviceConnection = usbManager.openDevice(usbDevice);
                                    usbSerialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, usbDeviceConnection);
                                    if (usbSerialDevice != null) {
                                        if (usbSerialDevice.open()) {                                       //Set Serial Connection Parameters.
                                            usbSerialDevice.setBaudRate(9600);
                                            usbSerialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
                                            usbSerialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
                                            usbSerialDevice.setParity(UsbSerialInterface.PARITY_NONE);
                                            usbSerialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                                            usbSerialDevice.read(mCallback);
                                            isUsbPortConnected = true;
                                            Log("Connected to Port");
                                        }
                                        else {
                                            Log("Could not open port.");
                                        }
                                    } else {
                                        Log("Port is null.");
                                    }
                            }
                            else Log("Device not recognized as spduino.");
                        }
                        else{
                            Log("Device is null.");
                        }
                        if(!isUsbPortConnected){
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    usbBtnState = false;
                                    toggleUsb.setText("Start");
                                }
                            });
                        }
                    }
                    catch (Exception e){
                        final Exception ex = e;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(),ex.toString(),Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
                else Log("USYUDUY");
            }
        }
    };

    //Defining a Callback which triggers whenever data is read.
    private final UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] arg0) {
            final byte[] arg = arg0;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String data = null;
                    data = new String(arg, StandardCharsets.UTF_8);
                    Log("Recv: "+ data);
                }
            }).start();
        }
    };


    private void disconnectUsb(){
        if(isUsbPortConnected){
            usbSerialDevice.close();
            isUsbPortConnected = false;
        }
    }

    private class SendingThread implements Runnable{
        byte[] msg;
        SendingThread(String cmd){
            this.msg = cmd.getBytes();
        }
        @Override
        public void run() {
            usbSerialDevice.write(msg);
        }
    }

    private void sendCommand(int i){
        if(!(prevCmd == i)&&isUsbPortConnected){
            SendingThread s = new SendingThread(""+i);
            new Thread(s).start();
            prevCmd = i;
        }
    }

}
