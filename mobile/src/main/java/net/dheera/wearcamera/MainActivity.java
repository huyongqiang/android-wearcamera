package net.dheera.wearcamera;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.ImageView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.ErrorDialogFragment;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "WearCamera";
    private static final boolean D = true;
    private int displayFrameLag = 0;

    SurfaceHolder mSurfaceHolder;
    SurfaceView mSurfaceView;
    ImageView mImageView;
    public Camera mCamera;
    public boolean mPreviewRunning=false;
    GoogleApiClient mGoogleApiClient;
    private Node mWearableNode = null;
    private String mWearableAddress = null;
    public boolean readyToProcessImage = true;

    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final String DEVICE_NAME = "device_name";
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    void findWearableNode() {
        PendingResult<NodeApi.GetConnectedNodesResult> nodes = Wearable.NodeApi.getConnectedNodes(mGoogleApiClient);
        nodes.setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
            @Override
            public void onResult(NodeApi.GetConnectedNodesResult result) {
                if(result.getNodes().size()>0) {
                    mWearableNode = result.getNodes().get(0);
                    Log.d(TAG, "Found wearable: name=" + mWearableNode.getDisplayName() + ", id=" + mWearableNode.getId());
                    sendToWearable("/start", null, null);
                } else {
                    mWearableNode = null;
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSurfaceView = (SurfaceView) findViewById(R.id.surfaceView);
        mSurfaceHolder = mSurfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mImageView = (ImageView) findViewById(R.id.imageView);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(Bundle connectionHint) {
                        Log.d(TAG, "onConnected: " + connectionHint);
                        findWearableNode();
                        Wearable.MessageApi.addListener(mGoogleApiClient, new MessageApi.MessageListener() {
                            @Override
                            public void onMessageReceived (MessageEvent m){
                                if(m.getPath().equals("/snap")) {
                                    doSnap();
                                } else if(m.getPath().equals("/received")) {
                                    displayFrameLag--;
                                } else if(m.getPath().equals("/stop")) {
                                    moveTaskToBack(true);
                                }
                            }
                        });
                    }
                    @Override
                    public void onConnectionSuspended(int cause) {
                        Log.d(TAG, "onConnectionSuspended: " + cause);
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(ConnectionResult result) {
                        Log.d(TAG, "onConnectionFailed: " + result);
                    }
                })
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();

        // slowly subtract from the lag; in case the lag
        // is occurring due to transmission errors
        // this will un-stick the application
        // from a stuck state in which displayFrameLag>6
        // and nothing gets transmitted (therefore nothing
        // else pulls down displayFrameLag to allow transmission
        // again)
        Timer mTimer = new Timer();
        mTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if(displayFrameLag>1) { displayFrameLag--; }
            }
        }, 0, 2000);
    }

    public void setCameraDisplayOrientation() {
            Camera.CameraInfo info = new Camera.CameraInfo();
            mCamera.getCameraInfo(0, info);
            int rotation = this.getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }
            int result = (info.orientation - degrees + 360) % 360;
            mCamera.setDisplayOrientation(result);
    }

     public void doSnap(){
        Log.d(TAG, "doSnap()");
        Camera.Parameters params = mCamera.getParameters();
        List<Camera.Size> sizes = params.getSupportedPictureSizes();
        Camera.Size size = sizes.get(0);
        for (int i = 0; i < sizes.size(); i++) {
            if (sizes.get(i).width > size.width)
                size = sizes.get(i);
        }
        params.setPictureSize(size.width, size.height);
        mCamera.setParameters(params);
        Camera.PictureCallback jpegCallback = new Camera.PictureCallback() {
            public void onPictureTaken(byte[] data, Camera camera) {
                FileOutputStream outStream = null;
                try {
                    outStream = new FileOutputStream(String.format("/sdcard/DCIM/Camera/IMG_%d.jpg", System.currentTimeMillis()));
                    outStream.write(data);
                    outStream.close();
                    Log.d(TAG, "wrote bytes: " + data.length);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                    mImageView.setImageBitmap(bmp);
                    mImageView.setX(0);
                    mImageView.setRotation(0);
                    mImageView.setVisibility(View.VISIBLE);
                    mImageView.animate().setDuration(500).translationX(mImageView.getWidth()).rotation(40).withEndAction(new Runnable() {
                        public void run() {
                            mImageView.setVisibility(View.GONE);
                        }
                    });
                    mCamera.startPreview();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        mCamera.takePicture(null, null, jpegCallback);
    }

    public void surfaceView_onClick(View view) {
        doSnap();
    }

    private void sendToWearable(String path, byte[] data, final ResultCallback<MessageApi.SendMessageResult> callback) {
        if (mWearableNode != null) {
            PendingResult<MessageApi.SendMessageResult> pending = Wearable.MessageApi.sendMessage(mGoogleApiClient, mWearableNode.getId(), path, data);
            pending.setResultCallback(new ResultCallback<MessageApi.SendMessageResult>() {
                @Override
                public void onResult(MessageApi.SendMessageResult result) {
                    if (callback != null) {
                        callback.onResult(result);
                    }
                    if (!result.getStatus().isSuccess()) {
                        Log.d(TAG, "ERROR: failed to send Message: " + result.getStatus());
                    }
                }
            });
        } else {
            Log.d(TAG, "ERROR: tried to send message before device was found");
        }
    }

    Bitmap bmp;
    Bitmap bmpSmall;

    @Override
    public void surfaceChanged(SurfaceHolder arg0, int arg1, int arg2, int arg3) {
        if (mPreviewRunning) {
            mCamera.stopPreview();
        }
        if (mSurfaceHolder.getSurface() == null){
            return;
        }
        Camera.Parameters p = mCamera.getParameters();
        mCamera.setParameters(p);
        try {
            if (mCamera != null) {
                mCamera.setPreviewDisplay(arg0);
                setCameraDisplayOrientation();
                mCamera.setPreviewCallback(new Camera.PreviewCallback() {
                    public void onPreviewFrame(byte[] data, Camera arg1) {
                        if (mWearableNode != null && readyToProcessImage && mPreviewRunning && displayFrameLag<6) {
                            readyToProcessImage = false;
                            if(D) Log.d(TAG, "Lag: " + String.valueOf(displayFrameLag));
                            Camera.Size previewSize = mCamera.getParameters().getPreviewSize();

                            int[] rgb = decodeYUV420SP(data, previewSize.width, previewSize.height);
                            bmp = Bitmap.createBitmap(rgb, previewSize.width, previewSize.height, Bitmap.Config.ARGB_8888);
                            int smallWidth, smallHeight;
                            int dimension = 280;
                            if(displayFrameLag > 3) {
                                // stream is lagging, cut resolution and catch up
                                dimension = 140;
                            }
                            if(previewSize.width > previewSize.height) {
                                smallWidth = dimension;
                                smallHeight = dimension*previewSize.height/previewSize.width;
                            } else {
                                smallHeight = dimension;
                                smallWidth = dimension*previewSize.width/previewSize.height;
                            }
                            bmpSmall = Bitmap.createScaledBitmap(bmp, smallWidth, smallHeight, false);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bmpSmall.compress(Bitmap.CompressFormat.WEBP, 30, baos);
                            sendToWearable("/show", baos.toByteArray(), null);
                            displayFrameLag++;
                            bmp.recycle();
                            bmpSmall.recycle();
                            readyToProcessImage = true;
                        }
                    }
                });
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();
        mPreviewRunning = true;
        if(mWearableNode != null) {
            sendToWearable("/start", null, null);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mCamera = Camera.open();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mPreviewRunning = false;
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
            mCamera.release();
        }
    }

    Camera.PictureCallback mPictureCallback = new Camera.PictureCallback() {
        public void onPictureTaken(byte[] imageData, Camera c) { }
 	};

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public int[] decodeYUV420SP( byte[] yuv420sp, int width, int height) {
        final int frameSize = width * height;
        int rgb[]=new int[width*height];
        for (int j = 0, yp = 0; j < height; j++) {
            int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
            for (int i = 0; i < width; i++, yp++) {
                int y = (0xff & ((int) yuv420sp[yp])) - 16;
                if (y < 0) y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }
                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);
                if (r < 0) r = 0; else if (r > 262143) r = 262143;
                if (g < 0) g = 0; else if (g > 262143) g = 262143;
                if (b < 0) b = 0; else if (b > 262143) b = 262143;
                rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000)
                        | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
            }
        }
        return rgb;   }

}
