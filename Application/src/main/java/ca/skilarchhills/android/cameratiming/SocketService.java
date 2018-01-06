package ca.skilarchhills.android.cameratiming;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Created by jon on 03/01/18.
 * Service class for all TCP communications with the PC
 */

public class SocketService extends Service {
    private static final String TAG = "CameraTimingSocket";

    // Error messages
    private static final int NO_DATA = 1;
    private static final int NOT_IMPLEMENTED = 2;

    // Commands that the phone can send
    private static final int PHONE_IMAGE = 1001;
    private static final int PHONE_WITH_SECOND_IMAGE = 1002;

    // Commands that the PC can send
    private static final int PC_ACK = 2001;
    private static final int PC_REQUEST_NEXT = 2002;
    private static final int PC_REQUEST_SPECIFIC = 2003;
    private static final int PC_REQUEST_ALL = 2004;

    // For setup of service
    private final IBinder mBinder = new MyBinder();
    public static final String initialPicsTag = "ca.skilarchhills.android.cameratiming.InitialPics";
    public static final String picsTag = "ca.skilarchhills.android.cameratiming.NewPics";

    // For the network server itself
    private boolean serviceClosing = false;
    final int portNum = 54321;
    ServerSocket listener;
    private Socket socket;
    BufferedInputStream nis;
    BufferedOutputStream nos;

    // Contains all of the images we know of
    private ArrayList<String> queue = new ArrayList<>();
    private int nextIndex;

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
        // Used to add an additional picture
        String nextPic = intent.getStringExtra(picsTag);
        if(nextPic != null)
            queue.add(nextPic);

        Log.d(TAG, "onStartCommand run");

        return Service.START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String[] initialPics = intent.getStringArrayExtra(initialPicsTag);

        // Add initial pictures
        if(initialPics != null && initialPics.length > 0)
            Collections.addAll(queue, initialPics);

        if(queue.size() == 0)
            nextIndex = 0;
        else
            nextIndex = queue.size() - 1;

        Log.d(TAG, "onBind run");

        return mBinder;
    }

    @Override
    public boolean onUnbind (Intent intent) {
        serviceClosing = true;
        return false;
    }

    public class MyBinder extends Binder {
        SocketService getService() {
            return SocketService.this;
        }
    }

    /**
     * Converts a long to a byte array for sending over the network, endian independent
     */
    private static byte[] longToBytes(long l) {
        byte[] result = new byte[8];
        for (int i = 7; i >= 0; i--) {
            result[i] = (byte)(l & 0xFF);
            l >>= 8;
        }
        return result;
    }

    /**
     * Converts a byte array to a long, endian independent
     */
    public long bytesToLong(byte[] b) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result <<= 8;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    /**
     * Convenience method to make sure wifi is enabled and connected
     */
    private boolean isWifiConnected() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork;
        if(connectivityManager != null) {
            activeNetwork = connectivityManager.getActiveNetworkInfo();
            if(activeNetwork != null) {
                if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI)
                    return true;
            }
        }
        return false;
    }

    /**
     * Reads a long from socket
     * @return next 8 bytes as long from socket, or -1 on error
     */
    private long readLong() {

        //FIXME Have some sort of timeout for if we don't receive 8 bytes

        Log.e(TAG, "Reading long");

        int bytesRead = 0;
        int temp;
        byte[] buffer = new byte[8];

        try {
            if (!socket.getInetAddress().isReachable(2000)) {
                Log.e(TAG, "Not reachable");
                return -1;
            }

            /* Try reading */
            /*
            while(nis.available() < 8) {
                Log.e(TAG, "Bytes available is " + nis.available());
                SystemClock.sleep(50);
            }
            */

            while (bytesRead < 8) {
                temp = nis.read();
                if(temp == -1) {
                    Log.e(TAG, "Read -1 reading long");
                    return -1;
                }
                buffer[bytesRead++] = (byte)temp;
            }

            Log.e(TAG, "Read long");

            return bytesToLong(buffer);
        } catch (IOException e) {
            e.printStackTrace();

            Log.e(TAG, "Read long - caught exception");

            return -1;
        }
    }

    /**
     * Waits for the PC to acknowledge receipt of the packet
     * Returns true if ACK received, false if times out
     */
    private boolean waitForAck() {
        int ret = (int)readLong();
        Log.e(TAG, "Read " + ret);
        return ret == PC_ACK;
    }

    /**
     * Sends a file across the socket
     * @param file File to send
     * @param fileIndex File number
     * @return Success
     */
    private boolean sendFile(File file, int fileIndex) {
        try {
            Log.v(TAG, "Sending file");

            int bufsize = 4096;
            byte[] buffer = new byte[bufsize];
            long numBytes = file.length();
            long timestamp = 0;
            String[] parts = file.getName().split("\\.(?=[^\\.]+$)");
            if (parts.length > 0) {
                timestamp = Long.parseLong(parts[0]);
            }

            nos.write(longToBytes(PHONE_IMAGE));
            nos.write(longToBytes(fileIndex));
            nos.write(longToBytes(timestamp));
            nos.write(longToBytes(numBytes));

            // Copy the file to a buffer that is half the size of file (because long.size/2=int.size)
            FileInputStream fis = new FileInputStream(file);

            for(int i = 0; i < numBytes / bufsize; i++) {
                if(!socket.getInetAddress().isReachable(2000))
                    return false;
                if(fis.read(buffer, 0, bufsize) == -1)
                    return false;
                else
                    nos.write(buffer);
            }

            // Write remaining bytes
            int remain = (int)(numBytes % bufsize);
            if(fis.read(buffer, 0, remain) == -1)
                return false;
            else
                nos.write(buffer, 0, remain);

            fis.close();
            nos.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Starts a network server if wifi is enabled that the PC can connect to
     */
    private void startNetwork() {
        // Wait until wifi is connected
        while(!isWifiConnected())
            SystemClock.sleep(5000);

        try {
            listener = new ServerSocket(portNum);
            Log.d(TAG, String.format("Listening on port %d", portNum));
            Log.d(TAG, "Waiting for client to connect");
            socket = listener.accept();
            if (socket.isConnected()) {
                nis = new BufferedInputStream(socket.getInputStream());
                nos = new BufferedOutputStream(socket.getOutputStream());
                Log.i(TAG, "doInBackground: Socket created, streams assigned");
                Log.i(TAG, "doInBackground: Waiting for initial data");

                int fileIndex = -1;

                while (socket.isConnected() && !socket.isClosed() && !serviceClosing) {
                    // Read next command from PC
                    long cmd = readLong();
                    Log.d(TAG, "Read " + cmd);
                    if(cmd == -1) {
                        // Error reading data
                        Log.e(TAG, "Failed to read command from computer");
                        break;
                    }

                    File file;
                    switch((int)cmd) {
                        // FIXME check return code of waitForAck and actually break while loop if necessary
                        case PC_REQUEST_NEXT:
                            Log.e(TAG, "Got here 0");
                            if(nextIndex >= queue.size() || queue.size() == 0) {
                                Log.e(TAG, "Got here 0.1");
                                nos.write(longToBytes(NO_DATA));
                                nos.flush();
                                waitForAck();
                                continue;
                            }
                            fileIndex = nextIndex++;
                            file = new File(queue.get(fileIndex));
                            break;
                        case PC_REQUEST_SPECIFIC:
                            int index = (int)readLong();
                            if(queue.size() - 1 > index) {
                                nos.write(longToBytes(NO_DATA));
                                waitForAck();
                            }
                            fileIndex = index;
                            file = new File(queue.get(index));
                            break;
                        case PC_REQUEST_ALL:
                        default:
                            // Not implemented
                            nos.write(longToBytes(NOT_IMPLEMENTED));
                            waitForAck();
                            continue;
                    }

                    if(!socket.getInetAddress().isReachable(2000))
                        break;

                    if(!sendFile(file, fileIndex))
                        break;

                    if(!waitForAck())
                        break;
                }

                closeSocket();
            }

            if(serviceClosing) {
                Log.d(TAG, "Service closing");
                stopSelf();
            } else {
                startNetwork();
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "doInBackground: Caught IOException");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "doInBackground: Caught Exception");
        } finally {
            closeSocket();
            startNetwork();
        }
    }

    /* Closes the socket and associated streams */
    private void closeSocket() {
        try {
            if (nis != null)
                nis.close();
            nos.close();
            socket.close();
            listener.close();
        } catch(IOException e){
            e.printStackTrace();
        }
    }

    public String getClientIp() {
        if(socket == null)
            return "";
        else
            return socket.getRemoteSocketAddress().toString();
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }
}