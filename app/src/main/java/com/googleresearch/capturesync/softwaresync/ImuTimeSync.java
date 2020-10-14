package com.googleresearch.capturesync.softwaresync;

import android.content.Context;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.Toast;

import com.googleresearch.capturesync.GlobalClass;
import com.googleresearch.capturesync.RawSensorInfo;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ImuTimeSync extends TimeSyncProtocol {
    private static final String TAG = "ImuTimeSync";
    private final ExecutorService mTimeSyncExecutor = Executors.newSingleThreadExecutor();
    private Context mContext;

    @Override
    protected ExecutorService getTimeSyncExecutor() {
        return mTimeSyncExecutor;
    }

    public ImuTimeSync(
            Ticker localClock, DatagramSocket timeSyncSocket, int timeSyncPort, SoftwareSyncLeader leader, Context context) {
        super(localClock, timeSyncSocket, timeSyncPort, leader);
        mContext = context;
    }

    @Override
    void submitNewSyncRequest(InetAddress clientAddress) {
        super.submitNewSyncRequest(clientAddress);
    }

    /** Is executed on leader
     *  smartphone, performs gyroSync
     *  algorithm and returns calculated offset
     * @param clientAddress
     * @return
     */
    @Override
    protected TimeSyncOffsetResponse doTimeSync(InetAddress clientAddress) {
        byte[] bufferStart = ByteBuffer.allocate(SyncConstants.RPC_BUFFER_SIZE).putInt(
                SyncConstants.METHOD_MSG_START_RECORDING
        ).array();
        byte[] bufferStop = ByteBuffer.allocate(SyncConstants.RPC_BUFFER_SIZE).putInt(
                SyncConstants.METHOD_MSG_STOP_RECORDING
        ).array();

        DatagramPacket packetStart = new DatagramPacket(bufferStart, bufferStart.length, clientAddress, mTimeSyncPort);
        DatagramPacket packetStop = new DatagramPacket(bufferStop, bufferStop.length, clientAddress, mTimeSyncPort);
        try (ServerSocket recServerSocket = new ServerSocket(mTimeSyncPort)) {
            mTimeSyncSocket.send(packetStart);
            Log.d(TAG, "Sent packet start recording to client, recording...");
            RawSensorInfo recorder = new RawSensorInfo(mContext);
            recorder.enableSensors(0, 0);
            String timeStamp = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new Date());
            recorder.startRecording(mContext, timeStamp);
            // Recording process
            Log.d(TAG, "Started recording");
            Thread.sleep(SyncConstants.SENSOR_REC_PERIOD_MILLIS);
            recorder.stopRecording();
            recorder.disableSensors();
            Log.d(TAG, "Stopped recording");
            mTimeSyncSocket.send(packetStop);
            Log.d(TAG, "Sent stop recording packet to client");

            // Accept file
            Log.d(TAG, "Connecting to Client...");
            Socket receiveSocket = recServerSocket.accept();
            Log.d(TAG, "Connected to Client...");
            // Getting file details

            Log.d(TAG, "Getting details from Client...");
            ObjectInputStream getDetails = new ObjectInputStream(receiveSocket.getInputStream());
            FileDetails details = (FileDetails) getDetails.readObject();
            Log.d(TAG, "Now receiving file...");
            // Storing file name and sizes

            String fileName = details.getName() + ".csv";
            Log.d(TAG, "File Name : " + fileName);
            byte data[] = new byte[2048];
            FileOutputStream fileOut = new FileOutputStream(
                    new File(mContext.getExternalFilesDir(null), fileName)
            );
            InputStream fileIn = receiveSocket.getInputStream();
            BufferedOutputStream fileBuffer = new BufferedOutputStream(fileOut);
            int count;
            int sum = 0;
            while ((count = fileIn.read(data)) > 0) {
                sum += count;
                fileBuffer.write(data, 0, count);
                Log.d(TAG, "Data received : " + sum);
                fileBuffer.flush();
            }
            Log.d(TAG, "File Received...");
            fileBuffer.close();
            fileIn.close();

            return TimeSyncOffsetResponse.create(42, 42, true);


        } catch (IOException | ClassNotFoundException | InterruptedException e) {
            e.printStackTrace();
            return TimeSyncOffsetResponse.create(0, 0, false);
        } // TODO: autogenerated

    }
}
