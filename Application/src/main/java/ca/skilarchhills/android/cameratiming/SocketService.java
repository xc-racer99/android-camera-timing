package ca.skilarchhills.android.cameratiming;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.LinkedList;

/**
 * Created by jon on 03/01/18.
 */

public class SocketService extends Service {
    private static final String TAG = "CameraTimingSocket";

    private final IBinder mBinder = new MyBinder();
    public static final String initialPicsTag = "ca.skilarchhills.android.cameratiming.InitialPics";
    public static final String picsTag = "ca.skilarchhills.android.cameratiming.NewPics";

    private boolean serviceClosing = false;
    private Socket socket;

    private LinkedList<String> queue = new LinkedList<>();

    @Override
    public void onCreate() {
        // Start our network socket
        Thread thread = new Thread(new Runnable() {
            public void run() {
                startNetwork();
            }
        });
        thread.start();
        Log.d(TAG, "Thread started");
    }

    @Override
    public void onDestroy() {
        serviceClosing = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String[] initialPics = intent.getStringArrayExtra(initialPicsTag);

        // Add initial pictures
        if(initialPics != null && initialPics.length > 0)
            Collections.addAll(queue, initialPics);

        // Used to add an additional picture
        String nextPic = intent.getStringExtra(picsTag);
        if(nextPic != null)
            queue.add(nextPic);

        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Used to add additional pictures
        queue.add(intent.getStringExtra(picsTag));

        return mBinder;
    }

    public class MyBinder extends Binder {
        SocketService getService() {
            return SocketService.this;
        }
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte)(l & 0xFF);
            l >>= 8;
        }
        return result;
    }

    private void startNetwork() {
        final int portNum = 54321;
        ServerSocket listener;
        InputStream nis;
        BufferedOutputStream nos;
        try {
            listener = new ServerSocket(portNum);
            Log.d(TAG, String.format("Listening on port %d", portNum));
            Log.d(TAG, "Waiting for client to connect");
            socket = listener.accept();
            if (socket.isConnected()) {
                nis = socket.getInputStream();
                nos = new BufferedOutputStream(socket.getOutputStream());
                Log.i(TAG, "doInBackground: Socket created, streams assigned");
                Log.i(TAG, "doInBackground: Waiting for initial data");

                File file;
                while (socket.isConnected() && !socket.isClosed() && !serviceClosing) {
                    // Make sure we can contact our client, if not, then break
                    if(!socket.getInetAddress().isReachable(2000))
                        break;

                    String temp = queue.poll();
                    if (temp == null) {
                        SystemClock.sleep(2000);
                        continue;
                    }

                    file = new File(temp);

                    Log.v(TAG, "doInBackground: Sending file");

                    long numBytes = file.length();
                    long timestamp = 0;
                    String[] parts = file.getName().split("\\.(?=[^\\.]+$)");
                    if (parts.length > 0) {
                        timestamp = Long.parseLong(parts[0]);
                    }

                    nos.write(longToBytes(timestamp));
                    nos.write(longToBytes(numBytes));

                    // Copy the file to a buffer that is half the size of file (because long.size/2=int.size)
                    FileInputStream fis = new FileInputStream(file);
                    int bufSize = (int) (numBytes / 2);
                    byte[] buf = new byte[bufSize];
                    for (int i = 0; i < 2; i++) {
                        if(!socket.getInetAddress().isReachable(2000)) {
                            break;
                        }

                        if(fis.read(buf, 0, bufSize) != -1)
                            nos.write(buf);
                    }
                    // If we had an odd number, then there's still one byte to go
                    if (numBytes % 2 != 0) {
                        if(!socket.getInetAddress().isReachable(2000)) {
                            break;
                        }

                        nos.write(fis.read());
                    }
                    nos.flush();
                }

                // Close socket
                if (nis != null)
                    nis.close();
                nos.close();
                socket.close();
                listener.close();
            }

            // Stop the service
            stopSelf();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "doInBackground: Caught IOException");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "doInBackground: Caught Exception");
        }
    }

    public String getClientIp() {
        if(socket == null)
            return "";
        else
            return socket.getRemoteSocketAddress().toString();
    }

    public boolean isConnected() {
        return socket.isConnected();
    }
}