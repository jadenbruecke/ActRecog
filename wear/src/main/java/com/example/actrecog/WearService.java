package com.example.actrecog;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WearService extends Service
        implements MessageClient.OnMessageReceivedListener, SensorEventListener {

    // Communication
    private static final String CAPABILITY_NAME = "accelerometer";
    private static final String START_PATH = "/start_transfer";
    private static final String START_ACK_PATH = "/start_transfer_ack";
    private static final String STOP_PATH = "/stop_transfer";
    private static final String STOP_ACK_PATH = "/stop_transfer_ack";
    private static final String TRANSFER_PATH = "/transfer";
    private static final String HEARTBEAT_PATH = "/heartbeat";
    private static final String HEARTBEAT_ACK_PATH = "/heartbeat_ack";

    private final byte[] emptyPayload = new byte[0];

    private char type = '0';

    private boolean isTransferring = false;

    private SensorManager sensorManager;
    private Sensor sensorAccelerometer;
    private Sensor sensorLinear;

    private String nodeId = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Toast.makeText(this, "ActRecog starts", Toast.LENGTH_SHORT).show();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // remove listener for MessageClient
        Wearable.getMessageClient(this).removeListener(this);
        // remove listeners  for sensors
        sensorManager.unregisterListener(this, sensorAccelerometer);
        sensorManager.unregisterListener(this, sensorLinear);

        Toast.makeText(this, "ActRecog stops", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // register listener
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        sensorAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorLinear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);

        // add listener for MessageClient
        Wearable.getMessageClient(this).addListener(this);
        // update paired NodeId
        Log.d("onCreate","Updating NodeId!");
        showNodes();
        // add listeners  for sensors
        sensorManager.registerListener(this, sensorAccelerometer,SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(this, sensorLinear,SensorManager.SENSOR_DELAY_GAME);
    }

    private void showNodes() {
        Task<Map<String, CapabilityInfo>> capabilitiesTask =
                Wearable.getCapabilityClient(this)
                        .getAllCapabilities(CapabilityClient.FILTER_REACHABLE);
        capabilitiesTask.addOnSuccessListener(capabilityInfoMap -> {
            Set<Node> nodes = new HashSet<>();
            if (capabilityInfoMap.isEmpty()) {
                showDiscoveredNodes(nodes);
                return;
            }

            CapabilityInfo capabilityInfo = capabilityInfoMap.get(WearService.CAPABILITY_NAME);
            if (capabilityInfo != null) {
                nodes.addAll(capabilityInfo.getNodes());
            }

            showDiscoveredNodes(nodes);
        });
    }

    private void showDiscoveredNodes(Set<Node> iNodes) {
        List<String> nodesList = new ArrayList<>();
        for (Node node : iNodes) {
            nodesList.add(node.getId());
            if (node.isNearby()) {
                nodeId = node.getId();
            }
        }
        String msg;
        if (!nodesList.isEmpty()) {
            msg = "Paired with: "+ nodeId;
        } else {
            msg = "No device detected";
        }
        Log.d("showDiscoveredNodes",msg);
    }

    @WorkerThread
    private void sendMessage(String iNodeId, String iPath, byte[] iPayload) {
        if (iNodeId != null) {
            Task<Integer> sendTask =
                    Wearable.getMessageClient(this).sendMessage(
                            iNodeId, iPath, iPayload);
            sendTask.addOnFailureListener(e -> Log.d("sendMessage","Sending Failure"));
        } else {
            Log.d("sendMessage","NodeId is null!");
        }
    }

    @SuppressLint("SetTextI18n")
    private void startTransfer() {
        if(!isTransferring){
            // status
            isTransferring = true;
            // set text
            Log.d("startTransfer","Started transferring!");
        } else {
            Log.d("startTransfer","Already started transferring!");
        }
    }

    @SuppressLint("SetTextI18n")
    private void stopTransfer() {
        if (isTransferring){
            // status
            isTransferring = false;
            // set text
            Log.d("stopTransfer","Stopped transferring!");
        } else {
            Log.d("stopTransfer","Already stopped transferring!");
        }
    }

    @Override
    public void onMessageReceived(@NonNull MessageEvent p0) {
        nodeId = p0.getSourceNodeId();
        String path = p0.getPath();

        switch (path) {
            case START_PATH:
                startTransfer();
                // invoke ack start
                sendMessage(nodeId, START_ACK_PATH, emptyPayload);
                break;
            case STOP_PATH:
                stopTransfer();
                // invoke ack stop
                sendMessage(nodeId, STOP_ACK_PATH, emptyPayload);
                break;
            case HEARTBEAT_PATH:
                // invoke ack heartbeat
                sendMessage(nodeId, HEARTBEAT_ACK_PATH, emptyPayload);
                break;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isTransferring){
            // determine sensor type
            if (event.sensor == sensorAccelerometer) {
                type = 'p';
            } else if (event.sensor == sensorLinear){
                type = 'l';
            }
            // feed payload
            byte[] initPayload = convertToPayload(event.values[0], event.values[1], event.values[2], type);
            sendMessage(nodeId, TRANSFER_PATH, initPayload);
        }
    }

    private static byte [] convertToPayload(float v0, float v1, float v2, char vType)
    {
        return ByteBuffer.allocate(14).putFloat(0,v0).putFloat(4,v1).putFloat(8,v2).putChar(12,vType).array();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
