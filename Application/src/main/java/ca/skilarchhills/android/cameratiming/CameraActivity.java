/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.skilarchhills.android.cameratiming;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.ImageReader;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v13.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends Activity implements View.OnClickListener, AdapterView.OnItemSelectedListener {

    OrientationEventListener listener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_camera2_basic);

        // Keep the screen from timing out
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Listen for orientation changes
        listener = new OrientationEventListener (getApplicationContext(),
                SensorManager.SENSOR_DELAY_NORMAL) {
            public void onOrientationChanged (int orientation) {
                if(orientation > 315 && orientation < 45)
                    mOrientation = ExifInterface.ORIENTATION_NORMAL;
                else if(orientation >= 45 && orientation <= 135)
                    mOrientation = ExifInterface.ORIENTATION_ROTATE_90;
                else if(orientation > 135 && orientation <= 225)
                    mOrientation = ExifInterface.ORIENTATION_ROTATE_180;
                else if(orientation > 225 && orientation <= 315)
                    mOrientation = ExifInterface.ORIENTATION_ROTATE_270;
            }
        };

        mServerButton = (Button) findViewById(R.id.picture);
        mServerButton.setOnClickListener(this);
        // If the server is already started, set the correct text
        if(networkTask != null) {
            mServerButton.setText(R.string.server_running);
            mServerButton.setClickable(false);
        }

        findViewById(R.id.info).setOnClickListener(this);
        mTextureView = (AutoFitTextureView) findViewById(R.id.texture);

        // Initialize the zoom spinner
        zoomSpinner = (Spinner) findViewById(R.id.zoom_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                getApplicationContext(), R.array.zoom_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        zoomSpinner.setAdapter(adapter);
        zoomSpinner.setOnItemSelectedListener(this);
        mZoomRect = new Rect();
    }

    @Override
    protected void onStart() {
        super.onStart();

        listener.enable();
    }

    @Override
    protected void onStop() {
        super.onStop();

        listener.disable();
    }

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "CameraTimingForAndroid";

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            mTextureWidth = width;
            mTextureHeight = height;
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }

    };

    /**
     * Button used to start the server
     */
    protected Button mServerButton;

    /**
     * Asynchronous network server
     */
    protected static NetworkTask networkTask;

    /**
     * Rect that holds the zoom of the image
     */
    private Rect mZoomRect;

    /**
     * Spinner that holds the zoom level desired
     */
    private Spinner zoomSpinner;

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String mCameraId;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    private AutoFitTextureView mTextureView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            finish();
        }

    };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    /**
     * An {@link ImageReader} that handles still image capture.
     */
    private ImageReader mImageReader;

    /**
     * This is the output file for our picture.
     */
    private File mFile;

    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
     */
    private CaptureRequest mPreviewRequest;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;

    /**
     * Size of the preview texture
     */
    private int mTextureWidth;
    private int mTextureHeight;

    /**
     * Physical orientation of the device
     */
    private int mOrientation = 0;


    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            // Do nothing, we would initiate capture here if we we're saving the surface
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            process(result);
        }

    };

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Close our sockets
        if (networkTask != null) {
            networkTask.programClosing = true;
            networkTask.closeSocket();
            try {
                networkTask.listener.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            networkTask.onPostExecute(false);
            networkTask = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            mTextureWidth = mTextureView.getWidth();
            mTextureHeight = mTextureView.getHeight();
            openCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "This app requires the Camera permission.  Exiting.", Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void changeZoomFactor(double factor) {
        if(mCameraId != null && mCaptureSession != null && mPreviewRequestBuilder != null)
            try {
                CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(mCameraId);

                Rect maxSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                int maxWidth = maxSize.width();
                int maxHeight = maxSize.height();

                int left = (maxWidth - (int)((float)maxWidth/factor))/2;
                int top = (maxHeight - (int)((float)maxHeight/factor))/2;
                int right = maxWidth - left;
                int bottom = maxHeight - top;

                mZoomRect.set(left, top, right, bottom);

                // Set the preview zoom, the taking picture zoom is in takeStillPicture()
                mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, mZoomRect);
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback,
                        null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics
                        = manager.getCameraCharacteristics(cameraId);

                // We don't use a front facing camera in this sample.
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // Pick our size of image
                Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
                Size largest = Collections.max(
                        Arrays.asList(sizes),
                        new CompareSizesByArea());

                // This is never used, but we need it here so we don't crash
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/6);

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
                //noinspection ConstantConditions
                int mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(TAG, "Display rotation is invalid: " + displayRotation);
                }

                // Adjust the zoom factor
                spinnerPositionToZoomFactor(zoomSpinner.getSelectedItemPosition());

                Point displaySize = new Point();
                getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = largest;

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    mTextureView.setAspectRatio(
                            mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            Toast.makeText(this, "Camera2 API not supported on this device!  This app will not work", Toast.LENGTH_LONG);
            finish();
        }
    }

    /**
     * Opens the camera specified by {@link #mCameraId}.
     */
    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA},
                    REQUEST_CAMERA_PERMISSION);
            return;
        }
        setUpCameraOutputs(mTextureWidth, mTextureHeight);
        configureTransform(mTextureWidth, mTextureHeight);
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == mCameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            mCaptureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                setAutoFlash(mPreviewRequestBuilder);

                                // Set to sports mode
                                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE,
                                        CaptureRequest.CONTROL_SCENE_MODE_SPORTS);

                                // Finally, we start displaying the camera preview.
                                mPreviewRequest = mPreviewRequestBuilder.build();
                                mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                        mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            Toast.makeText(getApplicationContext(), "Failed to configure camera!", Toast.LENGTH_SHORT).show();
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {

            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    /**
     * Initiate a still image capture.
     */
    public void takePicture() {
        if (networkTask == null) {
            Toast.makeText(this, "Server not started, won't be able to sync file", Toast.LENGTH_SHORT).show();
        }

        long time = System.currentTimeMillis();

        Bitmap pic = mTextureView.getBitmap();

        mFile = new File(getExternalFilesDir(null), time + ".jpg");

        FileOutputStream output = null;
        try {
            output = new FileOutputStream(mFile);
            pic.compress(Bitmap.CompressFormat.JPEG, 95, output);

            // Fix orientation
            ExifInterface exif = new ExifInterface(mFile.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(mOrientation));
            exif.saveAttributes();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (null != output) {
                try {
                    output.close();
                    long sizeBytes = mFile.length();

                    // Now send the actual file
                    if(networkTask != null)
                        networkTask.sendDataToNetwork(sizeBytes, mFile);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        Toast.makeText(this, String.format(Locale.US, "Time: %d", System.currentTimeMillis() - time), Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                networkTask = new NetworkTask();
                networkTask.execute();
                break;
            }
            case R.id.info: {
                    // Get the IP of this device
                    WifiManager wifiMan = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wifiInfo = wifiMan.getConnectionInfo();

                    // Check if there's a client connected
                    String clientIp = "";
                    if (networkTask != null && networkTask.socket != null && networkTask.socket.isConnected()) {
                        clientIp = networkTask.socket.getRemoteSocketAddress().toString();
                    }
                    int ipAddress = wifiInfo.getIpAddress();
                    new AlertDialog.Builder(this)
                            .setMessage("IP: " + String.format(Locale.US, "%d.%d.%d.%d",
                                    (ipAddress & 0xff),(ipAddress >> 8 & 0xff),
                                    (ipAddress >> 16 & 0xff),(ipAddress >> 24 & 0xff)) +
                                    "\nClient: " + clientIp)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                break;
            }
        }
    }

    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        spinnerPositionToZoomFactor(pos);
    }

    public void spinnerPositionToZoomFactor(int pos) {
        switch(pos) {
            case 0:
                // Zoom factor of 1
                changeZoomFactor(1.0);
                break;
            case 1:
                // Zoom factor of 1.25
                changeZoomFactor(1.25);
                break;
            case 2:
                // Zoom factor of 1.5
                changeZoomFactor(1.5);
                break;
            case 3:
                // Zoom factor of 1.75
                changeZoomFactor(1.75);
                break;
            case 4:
                // Zoom factor to 2
                changeZoomFactor(2.0);
                break;
            default:
                // Should never reach here, but set zoom factor to 1
                changeZoomFactor(1.0);
        }
    }

    public void onNothingSelected(AdapterView<?> parent) {

    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Helper function to convert a long to a byte[]
     */
    public static byte[] longToBytes(long l) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte)(l & 0xFF);
            l >>= 8;
        }
        return result;
    }

    private class NetworkTask extends AsyncTask<Void, byte[], Boolean> {
        private final int portNum = 54321;
        private boolean programClosing = false;
        private ServerSocket listener;
        private Socket socket;
        private InputStream nis;
        private BufferedOutputStream nos;

        @Override
        protected void onPreExecute(){
            Log.i(TAG, "onPreExecute of NetworkTask");
            mServerButton.setText(R.string.server_running);
            mServerButton.setClickable(false);
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                listener = new ServerSocket(portNum);
                Log.d(TAG, String.format("Listening on port %d", portNum));
                while(true) {
                    Log.d(TAG, "Waiting for client to connect");
                    socket = listener.accept();
                    if (socket.isConnected()) {
                        nis = socket.getInputStream();
                        nos = new BufferedOutputStream(socket.getOutputStream());
                        Log.i(TAG, "doInBackground: Socket created, streams assigned");
                        Log.i(TAG, "doInBackground: Waiting for initial data");

                        byte[] buffer = new byte[4096];
                        int read = nis.read(buffer, 0, 4096); //This is blocking
                        while(read != -1) {
                            byte[] tempdata = new byte[read];
                            System.arraycopy(buffer, 0, tempdata, 0, read);
                            publishProgress(tempdata);
                            Log.i(TAG, "doInBackground: Got some data");
                            read = nis.read(buffer, 0, 4096); //This is blocking
                        }
                        closeSocket();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "doInBackground: Caught IOException");
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, "doInBackground: Caught Exception");
            } finally {
                closeSocket();
            }
            return true;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            Log.i(TAG, "onPostExecute of NetworkTask");
            mServerButton.setText(R.string.start_server);
            mServerButton.setClickable(true);
            if(!programClosing)
                mServerButton.callOnClick();
        }

        private void closeSocket() {
            try {
                if (nis != null)
                    nis.close();
                if (nos != null)
                    nos.close();
                if (socket != null)
                    socket.close();
                if(listener != null)
                    listener.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void sendDataToNetwork(long numBytes, File file) {
            try {
                long timestamp = 0;
                String[] parts = file.getName().split("\\.(?=[^\\.]+$)");
                if(parts.length > 0) {
                    timestamp = Long.parseLong(parts[0]);
                }

                if(null != socket && socket.isConnected()) {
                    nos.write(longToBytes(timestamp));
                    nos.write(longToBytes(numBytes));

                    // Copy the file to a buffer that is half the size of file (because long.size/2=int.size)
                    FileInputStream fis = new FileInputStream(file);
                    int bufSize = (int)(numBytes / 2);
                    byte[] buf = new byte[bufSize];
                    for(int i = 0; i < 2; i++) {
                        fis.read(buf, 0, bufSize);
                        nos.write(buf);
                    }
                    // If we had an odd number, then there's still one byte to go
                    if(numBytes % 2 != 0) {
                        nos.write(fis.read());
                    }
                    nos.flush();
                }
            } catch (IOException e) {
                Log.e(TAG, "sendDataToNetwork: IOException");
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
        // If this is a multiple press, discard it
        if(keyEvent.getRepeatCount() > 0)
            return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_HEADSETHOOK:
            case KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
            case KeyEvent.KEYCODE_CAMERA:
                takePicture();
                return true;

            default:
                return super.onKeyDown(keyCode, keyEvent);
        }
    }
}
