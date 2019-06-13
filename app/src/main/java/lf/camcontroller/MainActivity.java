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
import android.view.WindowManager;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements CameraBridgeViewBase.CvCameraViewListener2 {

    // USB communication variables
    private static final String ACTION_USB_PERMISSION = "finalusb.USB_PERMISSION";
    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbSerialDevice usbSerialDevice;
    private boolean usbBtnState = false;
    private boolean isUsbPortConnected = false;
    //Defining a Callback which triggers whenever data is read.
    private final UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] arg0) {
            final byte[] arg = arg0;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String data;
                    data = new String(arg, StandardCharsets.UTF_8);
                    Log("Recv: " + data);
                }
            }).start();
        }
    };
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log("usbDevice attached");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                try{
                    if (isUsbPortConnected) {
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
                            usbBtnState = false;
                            disconnectUsb();
                            menu.findItem(R.id.usbBtn).setTitle("USB Start");
                            Log("Stopped USB communication");
                        }
                    });
                }catch(Exception ex){
                    Toast.makeText(getApplicationContext(),ex.toString(),Toast.LENGTH_LONG).show();
                }
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    try {
                        if (usbDevice != null) {
                            if (usbDevice.getVendorId() == 1027) {
                                UsbDeviceConnection usbDeviceConnection = usbManager.openDevice(usbDevice);
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
                                    } else {
                                        Log("Could not open port.");
                                    }
                                } else {
                                    Log("Port is null.");
                                }
                            } else Log("Device not recognized as SP-duino.");
                        } else {
                            Log("Device is null.");
                        }
                        if (!isUsbPortConnected) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    usbBtnState = false;
                                    menu.findItem(R.id.usbBtn).setTitle("USB Start");
                                }
                            });
                        }
                    } catch (Exception e) {
                        final Exception ex = e;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), ex.toString(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                } else Log("Permission not granted.");
            }
        }
    };

    // OpenCV variables
    private static final Scalar LOWER_LIMIT = new Scalar(29, 86, 6);
    private static final Scalar UPPER_LIMIT = new Scalar(70, 255, 255);
    private static final int SENSITIVITY = 250;
    private Mat mRgba;
    private CameraBridgeViewBase opencvCamView;
    private BaseLoaderCallback baseLoaderCallback;

    // GUI and other variables
    private static final String TAG = "MainActivity";
    private Handler mainHandler;
    private TextView logTv;
    private Menu menu;
    private boolean isFrontCam = true;
    private boolean isRoboOn = false;
    private char prevCmd = '\0';

    // Initializations and UI
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler();

        Toolbar toolBar = findViewById(R.id.toolBar);
        logTv = findViewById(R.id.logView);
        logTv.setMovementMethod(new ScrollingMovementMethod());
        setSupportActionBar(toolBar);
        opencvCamView = findViewById(R.id.camView);
        opencvCamView.setCameraIndex(1);
        opencvCamView.setVisibility(SurfaceView.VISIBLE);
        opencvCamView.setCvCameraViewListener(this);

        baseLoaderCallback = new BaseLoaderCallback(this) {
            @Override
            public void onManagerConnected(int status) {
                if (status == BaseLoaderCallback.SUCCESS) {
                    Log.wtf(TAG, "OpenCV loaded successfully");
                    opencvCamView.enableView();
                } else {
                    super.onManagerConnected(status);
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
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.toggleCamera:
                if (isFrontCam) {
                    swapCamera();
                    item.setTitle("Front Camera");
                    isFrontCam = false;
                    Log("Back Camera");
                } else {
                    swapCamera();
                    item.setTitle("Back Camera");
                    isFrontCam = true;
                    Log("Front Camera");
                }
                return true;
            case R.id.echo:
                sendCommand('M');
                return true;
            case R.id.robotBtn:
                if (isRoboOn) {
                    item.setTitle("Robo Start");
                    isRoboOn = false;
                    Log("Robot stopped.");
                } else {
                    item.setTitle("Robo Stop");
                    isRoboOn = true;
                    Log("Robot started.");
                }
                return true;
            case R.id.usbBtn:
                if(usbBtnState){
                    usbBtnState = false;
                    disconnectUsb();
                    item.setTitle("USB Start");
                    Log("Stopped USB communication");
                }
                else{
                    usbBtnState = true;
                    try{
                        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
                        if (!usbDevices.isEmpty()) {
                            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                                usbDevice = entry.getValue();
                                Log("Device:" + usbDevice.getProductName());
                                PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
                                usbManager.requestPermission(usbDevice, pi);
                                Log("Requested USB communication");
                            }
                        } else {
                            Log("USB Devices list is empty.");
                            usbBtnState = false;
                        }
                    } catch (Exception ex){
                        Log(ex.toString());
                        usbBtnState = false;
                    }
                    finally {
                        if(usbBtnState) item.setTitle("USB Stop");
                    }
                }
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

    // Camera and image processing
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
        mRgba = inputFrame.rgba();
        Point frameCenter = new Point(640, 360);
        if (isRoboOn) {
            Mat inter = new Mat(mRgba.size(), CvType.CV_8UC4);
            List<MatOfPoint> contours = new ArrayList<>();
            Mat interHi = new Mat();
            float[] controllerRadius = new float[1];
            Point controllerCenter = new Point();

            Imgproc.GaussianBlur(mRgba, inter, new Size(9, 9), 0);
            Imgproc.cvtColor(inter, inter, Imgproc.COLOR_BGR2HSV);
            Core.inRange(inter, LOWER_LIMIT, UPPER_LIMIT, inter);
            Imgproc.findContours(inter, contours, interHi, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            if (contours.size() > 0) {
                if(prevCmd=='?') Log("Found controller. Alignment procedure initiated.");
                double maxVal = 0;
                int maxValIdx = 0;
                for (int contourIdx = 0; contourIdx < contours.size(); contourIdx++) {
                    double contourArea = Imgproc.contourArea(contours.get(contourIdx));
                    if (maxVal < contourArea) {
                        maxVal = contourArea;
                        maxValIdx = contourIdx;
                    }
                }
                Imgproc.minEnclosingCircle(new MatOfPoint2f(contours.get(maxValIdx).toArray()), controllerCenter, controllerRadius);
                if (controllerRadius[0] > 8) {
                    Imgproc.circle(mRgba, controllerCenter, 7, new Scalar(255, 0, 0), -1);
                    int diffX = (int) (frameCenter.x - controllerCenter.x);
                    Log("Radius: "+controllerRadius[0]);
                    if (diffX > 0) {
                        if (diffX > SENSITIVITY) {
                            if (isFrontCam) {
                                sendCommand('a');
                                Log("Turn left");
                            } else {
                                sendCommand('d');
                                Log("Turn right");
                            }
                        } else {
                            if(controllerRadius[0]>420){
                                sendCommand('x');
                                Log("Reached!!!");
                            }
                            else{
                                sendCommand('w');
                                Log("In range. Moving ahead.");
                            }
                        }
                    } else if (diffX < 0) {
                        if (-1 * diffX > SENSITIVITY) {
                            if (isFrontCam) {
                                sendCommand('d');
                                Log("Turn right");
                            } else {
                                sendCommand('a');
                                Log("Turn left");
                            }
                        } else {
                            if(controllerRadius[0]>420){
                                sendCommand('x');
                                Log("Reached!!!");
                            }
                            else{
                                sendCommand('w');
                                Log("In range. Moving ahead.");
                            }
                        }
                    } else {
                        if(controllerRadius[0]>420){
                            sendCommand('x');
                            Log("Reached!!!");
                        }
                        else{
                            sendCommand('w');
                            Log("In range. Moving ahead.");
                        }
                    }
                }
            } else {
                if (prevCmd != '?') {
                    Log("Controller not found. Searching controller.");
                    sendCommand('a');
                    prevCmd = '?';
                }
            }
            inter.release();
            interHi.release();
        }
        Imgproc.line(mRgba, new Point(frameCenter.x - SENSITIVITY, 0), new Point(frameCenter.x - SENSITIVITY, 720), new Scalar(0, 0, 0), 7);
        Imgproc.line(mRgba, new Point(frameCenter.x + SENSITIVITY, 0), new Point(frameCenter.x + SENSITIVITY, 720), new Scalar(0, 0, 0), 7);
        return mRgba;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (opencvCamView != null) {
            opencvCamView.disableView();
            Log.wtf(TAG, "Camera Paused");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!OpenCVLoader.initDebug()) {
            Log.wtf(TAG, "OpenCVLoader.initdebug() returned false");
            Toast.makeText(getApplicationContext(), "Prob 00", Toast.LENGTH_SHORT).show();
        } else {
            baseLoaderCallback.onManagerConnected(BaseLoaderCallback.SUCCESS);
            Log.wtf(TAG, "Camera Resumed");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (opencvCamView != null) {
            opencvCamView.disableView();
            Log.w(TAG, "Camera Turned OFF");
        }
        disconnectUsb();
    }

    private void swapCamera() {
        opencvCamView.disableView();
        opencvCamView.setCameraIndex(isFrontCam ? 0 : 1);
        opencvCamView.enableView();
    }

    // USB communication

    private void disconnectUsb(){
        if(isUsbPortConnected){
            usbSerialDevice.close();
            isUsbPortConnected = false;
        }
    }

    private void sendCommand(char command) {
        if (prevCmd != command && isUsbPortConnected) {
            SendingThread s = new SendingThread(command);
            new Thread(s).start();
            prevCmd = command;
        }
    }

    private class SendingThread implements Runnable{
        byte[] msg = new byte[1];

        SendingThread(char cmd) {
            this.msg[0] = (byte) cmd;
        }
        @Override
        public void run() {
            usbSerialDevice.write(msg);
        }
    }

}
