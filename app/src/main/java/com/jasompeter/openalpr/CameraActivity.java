/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.jasompeter.openalpr;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.openalpr.jni.Alpr;
import com.openalpr.jni.AlprException;
import com.openalpr.jni.AlprPlate;
import com.openalpr.jni.AlprPlateResult;
import com.openalpr.jni.AlprResults;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

// FIXME: 2/3/17 God activity, but this is only sample. :)
@SuppressWarnings("deprecation")
public class CameraActivity extends AppCompatActivity implements SurfaceHolder.Callback, OrientationManager.OrientationListener {

    private static final String TAG = "CameraActivity";

    // after successfull recognition
    // next recognition will be run after this time
    private static final long SUCCESS_RECOGNITION_PAUSE_MS = 300; // 300 ms

    private static final int CAMERA_PERMISSION_RESULT = 236;

    private boolean mCameraIsOpen;
    private boolean mSurfaceCreated;
    private Camera mCamera;
    private int mCameraId = 0;
    private AsyncTask<Void, Void, Void> mCameraOpenTask;
    private SurfaceHolder mSurfaceHolder;
    private TextView mCapturingText;
    private TextView mTouchToCaptureText;
    private OrientationManager mOrientationManager;
    private ImageButton mTorchButton;
    private boolean mTorchEnabled = false;

    private long mLastSuccessRecognition = 0;

    private RecognitionTask mRecognitionTask;

    private Alpr mAlpr;
    private boolean mEnableRecognition = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        setContentView(R.layout.activity_camera);

        SurfaceView surfaceView = (SurfaceView) findViewById(R.id.surface_view);
        mTorchButton = (ImageButton) findViewById(R.id.torch_button);
        mCapturingText = (TextView) findViewById(R.id.capturing_text);
        mTouchToCaptureText = (TextView) findViewById(R.id.touch_to_capture);
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // we should not open camera on UI thread
        mCameraOpenTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void[] params) {

                if (!mCameraIsOpen) {
                    releaseCameraAndPreview();
                    try {

                        int cameraCount = Camera.getNumberOfCameras();
                        Log.d(TAG, "We have " + cameraCount + " cameras.");
                        for (int i = 0; i < cameraCount; i++) {
                            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
                            Camera.getCameraInfo(i, cameraInfo);
                            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                                mCameraId = i;
                                break;
                            }
                        }

                        mCamera = Camera.open(mCameraId);
                        mCameraIsOpen = true;
                    } catch (Exception e) {
                        mCameraIsOpen = false;
                        Log.d(TAG, "Failed to open camera. Camera is probably in use.");
                    }
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void o) {
                onCameraOpened(mCamera != null);
            }
        };

        // orientation manager handles orientation changes
        // because our activity is set to force landscape mode
        // and we may want rotate action buttons or do something with view
        mOrientationManager = new OrientationManager(this, SensorManager.SENSOR_DELAY_NORMAL, this);

        invalidateTorchButton();
        mTorchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTorch();
            }
        });

        initializeAlpr();

        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mEnableRecognition = true;
                        mCapturingText.setVisibility(View.VISIBLE);
                        mTouchToCaptureText.setVisibility(View.GONE);
                        return true; // if you want to handle the touch event
                    case MotionEvent.ACTION_UP:
                        mEnableRecognition = false;
                        mCapturingText.setVisibility(View.GONE);
                        mTouchToCaptureText.setVisibility(View.VISIBLE);
                        return true; // if you want to handle the touch event
                }
                return false;
            }
        });

    }

    public void initializeAlpr() {
        String androidDataDir = this.getApplicationInfo().dataDir;
        String openAlprConfFile = androidDataDir + File.separatorChar + "runtime_data" + File.separatorChar + "openalpr.conf";
        String runtimeDir = androidDataDir + File.separatorChar + "runtime_data";

        mAlpr = new Alpr(this, androidDataDir, "eu", openAlprConfFile, runtimeDir);

        Log.d(TAG, "Android data dir: " + androidDataDir);
        Log.d(TAG, "openalpr.conf file: " + openAlprConfFile);
        Log.d(TAG, "Runtime dir: " + runtimeDir);
        Log.d(TAG, "Alpr version: " + mAlpr.getVersion());
        Log.d(TAG, "Alpr is loaded: " + mAlpr.isLoaded());

        if (!mAlpr.isLoaded()) {
            return;
        }

        mAlpr.setTopN(5);
        mAlpr.setDefaultRegion("sk");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mAlpr.unload(); // make sure to unload, to release memory
    }

    @Override
    protected void onStart() {
        super.onStart();

        if (hasCameraPermissions()) {
            openCamera();
        } else {
            requestCameraPermissons();
        }

        registerRotationListener();
    }

    @Override
    protected void onStop() {
        super.onStop();

        unregisterRotationListener();

        if (mCameraOpenTask.getStatus().equals(AsyncTask.Status.RUNNING)
                || mCameraOpenTask.getStatus().equals(AsyncTask.Status.PENDING)) {
            mCameraOpenTask.cancel(true);
        }

        releaseCameraAndPreview();
    }

    public void openCamera() {
        mCameraOpenTask.execute();
    }

    public void releaseCamera() {
        if (mCameraIsOpen && mCamera != null) {
            mCamera.release();
            mCamera = null;
            mCameraIsOpen = false;
        }
    }

    public void releaseCameraAndPreview() {
        if (mCameraIsOpen) {
            mCamera.stopPreview();
        }

        releaseCamera();
    }

    public void onCameraOpened(boolean success) {
        if (!success) {
            Log.d(TAG, "Cannot open camera.");
            return;
        }

        if (mSurfaceCreated) {
            startPreview();
        }
    }

    public void setCorrectOrientation(Camera camera) {
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();

        int degrees = 0;
        switch (displayRotation) {
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

        Camera.CameraInfo cameraInfo = getCurrentCameraInfo();
        int resultDegrees;
        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            resultDegrees = (cameraInfo.orientation + degrees) % 360;
            resultDegrees = (360 - resultDegrees) % 360;
        } else {
            resultDegrees = (cameraInfo.orientation - degrees + 360) % 360;
        }

        camera.setDisplayOrientation(resultDegrees);

        Camera.Parameters parameters = camera.getParameters();
        parameters.setRotation(resultDegrees);
        camera.setParameters(parameters);
    }

    public void setCorrectSize(Camera camera, int width, int height) {
        Camera.Parameters parameters = camera.getParameters();
        Camera.Size size = getBestPreviewSize(width, height, parameters);

        if (size != null) {
            parameters.setPreviewSize(size.width, size.height);
            mCamera.setParameters(parameters);
        }
    }

    public Camera.Size getBestPreviewSize(int width, int height, Camera.Parameters parameters){
        Camera.Size bestSize = null;
        List<Camera.Size> sizeList = parameters.getSupportedPreviewSizes();

        bestSize = sizeList.get(0);

        for(int i = 1; i < sizeList.size(); i++){
            if((sizeList.get(i).width * sizeList.get(i).height) >
                    (bestSize.width * bestSize.height)){
                bestSize = sizeList.get(i);
            }
        }

        return bestSize;
    }

    public Camera.CameraInfo getCurrentCameraInfo() {
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, cameraInfo);
        return cameraInfo;
    }

    public void startPreview() {
        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);
        } catch (IOException e) {
            e.printStackTrace();
            Log.d(TAG, "Cannot set preview display.");
        }

        setCorrectOrientation(mCamera);
        setCorrectSize(
                mCamera,
                mSurfaceHolder.getSurfaceFrame().width(),
                mSurfaceHolder.getSurfaceFrame().height()
        );

        mCamera.startPreview();

        mCamera.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera camera) {

                if (!mEnableRecognition) {
                    return;
                }

                if (camera.getParameters().getPreviewFormat() == ImageFormat.NV21) {
                    Camera.Size previewSize = camera.getParameters()
                            .getPreviewSize();
                    YuvImage yuvimage = new YuvImage(data,
                            ImageFormat.NV21, previewSize.width,
                            previewSize.height, null);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    yuvimage.compressToJpeg(new Rect(0, 0,
                                    previewSize.width, previewSize.height), 50,
                            baos);
                    recognize(baos.toByteArray());
                }

            }
        });
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceCreated = true;
        if (mCameraIsOpen) {
            startPreview();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mCameraIsOpen && mSurfaceCreated) {
            setCorrectSize(mCamera, width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mCamera != null) {
            mCamera.stopPreview();
        }
    }

    public void registerRotationListener() {
        mOrientationManager.enable();
    }

    public void unregisterRotationListener() {
        mOrientationManager.disable();
    }

    @Override
    public void onOrientationChange(OrientationManager.ScreenOrientation screenOrientation) {
        invalidateViewRotation(screenOrientation);
    }

    public void invalidateTorchButton() {
        if (mTorchEnabled) {
            mTorchButton.setAlpha(1.0f);
        } else {
            mTorchButton.setAlpha(0.25f);
        }
    }

    public void toggleTorch() {
        mTorchEnabled = !mTorchEnabled;
        invalidateTorchButton();

        if (!mCameraIsOpen || !mSurfaceCreated) {
            return;
        }

        Camera.Parameters parameters = mCamera.getParameters();

        if (mTorchEnabled) {
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        } else {
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
        }

        mCamera.setParameters(parameters);
    }

    public void invalidateViewRotation(OrientationManager.ScreenOrientation currentOrientation) {
        // here you can rotate buttons and other stuff to simulate device rotation
        // because we have fixed orientation as landscape
    }

    // image frame representation
    class Frame {

        byte[] mBytes;

        Frame(byte[] bytes) {
            mBytes = bytes;
        }

        byte[] getBytes() {
            return mBytes;
        }

    }

    class RecognitionTask extends AsyncTask<Frame, Void, AlprResults> {

        @Override
        protected AlprResults doInBackground(Frame... frames) {

            // make sure alpr instance is initialized successfully
            if (!mAlpr.isLoaded()) {
                return null;
            }

            if (frames.length > 0) {
                Frame frame = frames[0]; // just work with one frame
                try {
                    return mAlpr.recognize(frame.getBytes());
                } catch (AlprException e) {
                    Log.d(TAG, "Error while recognition: " + e.toString());
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(AlprResults alprResults) {
            if (alprResults == null) {
                Log.d(TAG, "Nothing recognised.");
                return;
            }

            mLastSuccessRecognition = System.currentTimeMillis();
            Log.d(TAG, "Total processing time: " + alprResults.getTotalProcessingTimeMs());

            if (alprResults.getPlates().isEmpty()) {
                Log.d(TAG, "Not found.");
                return;
            }

            Log.d(TAG, "Got " + alprResults.getPlates().size() + " results.");

            for (AlprPlateResult alprPlateResult : alprResults.getPlates()) {
                String bestPlate = alprPlateResult.getBestPlate().getCharacters();

                // best plate may not be the right plate we are looking for
                if (alprPlateResult.getBestPlate().isMatchesTemplate()) {
                    onPlateRecognised(bestPlate);
                    return;
                }

                String matchingPlate = bestPlate;
                // check if even one of top N plates match the pattern
                for (AlprPlate alprPlate : alprPlateResult.getTopNPlates()) {
                    if (alprPlate.isMatchesTemplate()) {
                        matchingPlate = alprPlate.getCharacters();
                        break;
                    }
                }

                onPlateRecognised(matchingPlate);
            }
        }
    }

    // we wont allow simultanous frame recognition
    public boolean isRecognitionRunning() {
        return mRecognitionTask != null &&
                (mRecognitionTask.getStatus().equals(AsyncTask.Status.PENDING)
                        || mRecognitionTask.getStatus().equals(AsyncTask.Status.RUNNING));
    }

    // run recognition task
    public void recognize(byte[] bytes) {
        if (!isRecognitionRunning()) {

            // prevent overlapping same plate recognition if last attempt was successfull
            if (System.currentTimeMillis() - mLastSuccessRecognition < SUCCESS_RECOGNITION_PAUSE_MS) {
                return;
            }

            Frame frame = new Frame(bytes);
            mRecognitionTask = new RecognitionTask();
            mRecognitionTask.execute(frame);
        }
    }

    public void onPlateRecognised(String plate) {
        Toast.makeText(this, "Plate: " + plate, Toast.LENGTH_SHORT).show();
    }

    public boolean hasCameraPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void requestCameraPermissons() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_RESULT
        );
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_RESULT) {
            if (grantResults.length > 0) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    onCameraPermissionGranted();
                } else {
                    onCameraPermissionDenied();
                }
            }
        }
    }

    public void onCameraPermissionGranted() {

        if (!mCameraIsOpen) {
            openCamera();
        }

    }

    public void onCameraPermissionDenied() {
        Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_SHORT).show();
        finish();
    }

}
