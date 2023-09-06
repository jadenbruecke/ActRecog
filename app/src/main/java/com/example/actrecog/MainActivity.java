package com.example.actrecog;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.actrecog.ml.AccPositionLstm;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity
        implements MessageClient.OnMessageReceivedListener {

    private static final int N_SAMPLES = 200;

    // cycle counts
    private int cycleCountPosition;
    private int cycleCountMotion;

    // ByteBuffers for TensorFlow inference
    private ByteBuffer inputAccPosition;
    private float[] inputLinMotionX;
    private float[] inputLinMotionY;
    private float[] inputLinMotionZ;
    private boolean loadingReleaseForInputBuffer = true;

    // references for TensorFlow model
    AccPositionLstm modelPosition;

    // communication
    private static final String CAPABILITY_NAME = "accelerometer";
    private static final String START_SERVICE_PATH = "/start_actrecog";
    private static final String START_SERVICE_ACK_PATH = "/start_actrecog_ack";
    private static final String STOP_SERVICE_PATH = "/stop_actrecog";
    private static final String STOP_SERVICE_ACK_PATH = "/stop_actrecog_ack";
    private static final String START_PATH = "/start_transfer";
    private static final String START_ACK_PATH = "/start_transfer_ack";
    private static final String STOP_PATH = "/stop_transfer";
    private static final String STOP_ACK_PATH = "/stop_transfer_ack";
    private static final String TRANSFER_PATH = "/transfer";
    private static final String HEARTBEAT_PATH = "/heartbeat";
    private static final String HEARTBEAT_ACK_PATH = "/heartbeat_ack";
    private final byte[] emptyPayload = new byte[0];

    private boolean isTransferring = false;
    private boolean wearableServiceStarted = false;
    private boolean connectedWithWebSocket = false;

    private String nodeId = null;

    // set up views
    private TextView textInfo;
    private ImageView imageStatus;
    private ScrollView infoBox;
    private ImageView imageToggle;

    // create WebSocket
    private WebSocket ws;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // find view setups
        textInfo = findViewById(R.id.text_info);
        imageStatus = findViewById(R.id.image_status);
        infoBox = findViewById(R.id.info_box);
        imageToggle = findViewById(R.id.image_toggle);

        // init cycleCount, ByteBuffer in and out
        cycleCountPosition = 0;
        cycleCountMotion = 0;
        inputAccPosition = ByteBuffer.allocateDirect(200 * 3 * Float.SIZE / Byte.SIZE).order(ByteOrder.nativeOrder());
        inputLinMotionX = new float[200];
        inputLinMotionY = new float[200];
        inputLinMotionZ = new float[200];

        try {
            // instantiate TensorFlow model
            modelPosition = AccPositionLstm.newInstance(MainActivity.this);
        } catch (IOException e) {
            // TODO Handle the exception
            Log.d("IOException", e.toString());
        }

        Log.d("onCreate","Created ByteBuffer ML inference.");
        updateTextInfo("Created ByteBuffer ML inference.");

        initiateConnectionToServer();

    }

    public void initiateConnectionToServer(){
        try {
            // set web socket host ip address and port number
            ws = new WebSocketFactory().createSocket("ws://192.168.1.57:5000");
            Log.d("webSocket", "Creating web socket...");
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Connect to the server asynchronously.
        ws.connectAsynchronously();
        Log.d("webSocket", "Trying to connect...");
        updateTextInfo("Trying to connect...");

        ws.addListener(new WebSocketAdapter(){
            @Override
            public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
                super.onConnected(websocket, headers);
                connectedWithWebSocket = true;
                Log.d("onConnected", "Connected to web socket server!");
                updateTextInfo("Connected to web socket server!");
                // Start Service
                onStartServiceClick(imageToggle);
            }
        });

        ws.addListener(new WebSocketAdapter(){
            @Override
            public void onConnectError(WebSocket websocket, WebSocketException exception) throws Exception {
                super.onConnectError(websocket, exception);
                connectedWithWebSocket = false;
                Log.d("onConnectError", exception.toString());
                updateTextInfo(exception.toString());
            }
        });

        ws.addListener(new WebSocketAdapter(){
            @Override
            public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
                super.onError(websocket, cause);
                connectedWithWebSocket = false;
                Log.d("onError", cause.getError().toString());
                updateTextInfo(cause.getError().toString());
            }
        });

        ws.addListener(new WebSocketAdapter(){
            @Override
            public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                super.onDisconnected(websocket, serverCloseFrame, clientCloseFrame, closedByServer);
                connectedWithWebSocket = false;
                Log.d("onDisconnected", "Disconnected from web socket server!");
                updateTextInfo("Disconnected from web socket server!");
                // Stop Transfer
                onStopTransferClick(imageToggle);
            }
        });
    }

    public void onImageToggleClick(View view) {
        if (!connectedWithWebSocket){
            onConnectWebSocketClick(imageToggle);
        } else if (!wearableServiceStarted){
            onStartServiceClick(imageToggle);
        } else if (!isTransferring){
            onStartTransferClick(imageToggle);
        } else {
            onStopTransferClick(imageToggle);
        }
    }

    public void onConnectWebSocketClick(View view) {
        initiateConnectionToServer();
    }

    public void onStartServiceClick(View view) {
        if (isTransferring){
            Toast.makeText(this,"Stop transferring first!",Toast.LENGTH_SHORT).show();
        } else {
            // find paired node
            showNodes();
            Log.d("onStartServiceClick", "Generating RPC to START wearable service");
            sendMessage(nodeId, START_SERVICE_PATH, emptyPayload);
            checkHeartbeat(2000);
        }
    }

    public void onStopServiceClick(View view) {
        if (isTransferring){
            Toast.makeText(this,"Stop transferring first!",Toast.LENGTH_SHORT).show();
        } else {
            Log.d("onStopServiceClick", "Generating RPC to STOP wearable service");
            sendMessage(nodeId, STOP_SERVICE_PATH, emptyPayload);
        }
    }

    public void onStartTransferClick(View view) {
        if (connectedWithWebSocket){
            // Start Recognition
            sendMessage(nodeId, START_PATH, emptyPayload);
        } else {
            updateTextInfo("Please reconnect web socket.");
        }
    }

    public void onStopTransferClick(View view) {
        // Stop Recognition
        sendMessage(nodeId, STOP_PATH, emptyPayload);
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

            CapabilityInfo capabilityInfo = capabilityInfoMap.get(MainActivity.CAPABILITY_NAME);
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
            setImage(imageStatus, R.drawable.outline_bluetooth_connected_black_48);
        } else {
            msg = "No device detected";
            setImage(imageStatus, R.drawable.outline_report_problem_black_48);
        }
        updateTextInfo(msg);
        updateTextInfo("Please start service!");
    }

    @WorkerThread
    private void sendMessage(String iNodeId, String iPath, byte[] iPayload) {
        if (iNodeId != null) {
            Task<Integer> sendTask =
                    Wearable.getMessageClient(this).sendMessage(
                            iNodeId, iPath, iPayload);
            // You can add success and/or failure listeners,
            // Or you can call Tasks.await() and catch ExecutionException
            sendTask.addOnFailureListener(e -> {
                updateTextInfo("Sending Failure");
                setImage(imageStatus, R.drawable.outline_error_outline_black_48);
            });
        } else {
            // Unable to retrieve node with capability
            updateTextInfo("NodeId is null!");
            Toast.makeText(this,"NodeId is null!",Toast.LENGTH_SHORT).show();
            setImage(imageStatus, R.drawable.outline_error_outline_black_48);
        }
    }

    @SuppressLint("SetTextI18n")
    private void startWatchService() {
        Toast.makeText(this, "Wearable service started!", Toast.LENGTH_SHORT).show();
        updateTextInfo("Wearable service started!");
        setImage(imageStatus, R.drawable.outline_sync_black_48);
        // Start Transfer
        onStartTransferClick(imageToggle);
    }

    @SuppressLint("SetTextI18n")
    private void stopWatchService() {
        Toast.makeText(this, "Wearable service stopped!", Toast.LENGTH_SHORT).show();
        setImage(imageToggle, R.drawable.ic_play_circle_fill0_wght400_grad0_opsz48);
        updateTextInfo("Wearable service stopped!");
        setImage(imageStatus, R.drawable.outline_block_black_48);
    }

    @SuppressLint("SetTextI18n")
    private void startTransfer() {
        if (!isTransferring){
            // status
            isTransferring = true;
            ws.sendText("start");
            // set button and text
            setImage(imageToggle, R.drawable.ic_stop_circle_fill0_wght400_grad0_opsz48);
            updateTextInfo("Started transferring!");
        } else {
            updateTextInfo("Already started transferring!");
        }
        setImage(imageStatus, R.drawable.outline_sync_black_48);
    }

    @SuppressLint("SetTextI18n")
    private void stopTransfer() {
        if (isTransferring){
            // status
            isTransferring = false;
            ws.sendText("stop");
            // set button and text
            setImage(imageToggle, R.drawable.ic_play_circle_fill0_wght400_grad0_opsz48);
            updateTextInfo("Stopped transferring!");
        } else {
            updateTextInfo("Already stopped transferring!");
        }
        setImage(imageStatus, R.drawable.outline_block_black_48);
        // Stop Service
        onStopServiceClick(imageToggle);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onMessageReceived(@NonNull MessageEvent p0) {
        nodeId = p0.getSourceNodeId();
        String path = p0.getPath();

        switch (path) {
            case START_SERVICE_ACK_PATH:
                wearableServiceStarted = true;
                startWatchService();
                break;
            case STOP_SERVICE_ACK_PATH:
                wearableServiceStarted = false;
                stopWatchService();
                break;
            case START_ACK_PATH:
                startTransfer();
                break;
            case STOP_ACK_PATH:
                stopTransfer();
                break;
            case TRANSFER_PATH:
                if (loadingReleaseForInputBuffer){
                    if (cycleCountPosition < N_SAMPLES | cycleCountMotion < N_SAMPLES) {
                        // extract data
                        byte[] initPayload = p0.getData();
                        byte[] payloadX = Arrays.copyOfRange(initPayload, 0, 4);
                        byte[] payloadY = Arrays.copyOfRange(initPayload, 4, 8);
                        byte[] payloadZ = Arrays.copyOfRange(initPayload, 8, 12);
                        byte[] payloadType = Arrays.copyOfRange(initPayload, 12, 14);
                        float valueX = ByteBuffer.wrap(payloadX).getFloat();
                        float valueY = ByteBuffer.wrap(payloadY).getFloat();
                        float valueZ = ByteBuffer.wrap(payloadZ).getFloat();
                        char valueType = ByteBuffer.wrap(payloadType).getChar();
                        if ((cycleCountPosition < N_SAMPLES) && (valueType == 'p')) {
                            inputAccPosition.putFloat(valueX).putFloat(valueY).putFloat(valueZ);
                            cycleCountPosition++;
                        } else if ((cycleCountMotion < N_SAMPLES) && (valueType == 'l')){
                            inputLinMotionX[cycleCountMotion] = valueX;
                            inputLinMotionY[cycleCountMotion] = valueY;
                            inputLinMotionZ[cycleCountMotion] = valueZ;
                            cycleCountMotion++;
                        }
                    } else {
                        while (cycleCountMotion < cycleCountPosition){
                            inputLinMotionX[cycleCountMotion] = 0;
                            inputLinMotionY[cycleCountMotion] = 0;
                            inputLinMotionZ[cycleCountMotion] = 0;
                            cycleCountMotion++;
                        }
                        Log.d("Full", "Each ByteBuffer has reached 300!");
                        // preventing more data entering buffer
                        loadingReleaseForInputBuffer = false;
                        // keep wearable alive
                        checkHeartbeat(0);
                        // invoke running inference
                        runInference();
                    }
                }
                break;
            case HEARTBEAT_ACK_PATH:
                Log.d(HEARTBEAT_ACK_PATH,"Wearable Alive!");
                updateTextInfo("Wearable Alive!");
                break;
        }
    }

    @SuppressLint("DefaultLocale")
    @WorkerThread
    private void runInference() {
        Log.d("Inference", "Starting running inference!");
        try{
            // create inputs for reference
            TensorBuffer inputFeaturePosition = TensorBuffer.createFixedSize(new int[]{1, 200, 3}, DataType.FLOAT32);
            inputFeaturePosition.loadBuffer(inputAccPosition);
            // clear ByteBuffer and reset cycleCount
            inputAccPosition.clear();
            float[] copy_X = Arrays.copyOf(inputLinMotionX, inputLinMotionX.length);
            float[] copy_Y = Arrays.copyOf(inputLinMotionY, inputLinMotionY.length);
            float[] copy_Z = Arrays.copyOf(inputLinMotionZ, inputLinMotionZ.length);
            inputLinMotionX = new float[200];
            inputLinMotionY = new float[200];
            inputLinMotionZ = new float[200];
            // reset counter
            cycleCountPosition = 0;
            cycleCountMotion = 0;
            Log.d("Counter", "Successfully cleared!");
            // allow sensor data into buffer again
            loadingReleaseForInputBuffer = true;
            // run model inference and get result
            AccPositionLstm.Outputs outputsPosition = modelPosition.process(inputFeaturePosition);
            // 3 values for position
            float[] outputPosition = outputsPosition.getOutputFeature0AsTensorBuffer().getFloatArray();
            // send position result
            ws.sendText(encodeJsonString("position", outputPosition));
            double sd_X = calculateSD(copy_X);
            double sd_Y = calculateSD(copy_Y);
            double sd_Z = calculateSD(copy_Z);
            // fused_sd represents the amplitude of the motion
            float fused_sd = (float) (Math.sqrt(sd_X*sd_X + sd_Y*sd_Y + sd_Z*sd_Z));
            // send motion result
            float[] outputMotion = {fused_sd, 0, 0};
            ws.sendText(encodeJsonString("motion", outputMotion));
            Log.d("runInference","sent text with probs.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static double calculateSD(float[] numArray)
    {
        float sum = 0, standardDeviation = 0;
        int length = numArray.length;
        for(float num : numArray) {
            sum += num;
        }
        float mean = sum/length;
        for(float num: numArray) {
            standardDeviation += Math.pow(num - mean, 2);
        }
        return Math.sqrt(standardDeviation/length);
    }

    private String encodeJsonString(String type, float[] probs) throws JSONException {
        JSONObject obj = new JSONObject();

        obj.put("type", type);
        obj.put("prob_0", probs[0]);
        obj.put("prob_1", probs[1]);
        obj.put("prob_2", probs[2]);
        obj.put("prob_3", probs[3]);
        return obj.toString();
    }
    
    public void checkHeartbeat(int delay){
        // initiate heartbeat
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            sendMessage(nodeId, HEARTBEAT_PATH, emptyPayload);
        });
        thread.start();
    }

    public void updateTextInfo(String text){
        runOnUiThread(() -> {
            StringBuilder sbTemp = new StringBuilder();
            sbTemp.append(textInfo.getText()).append(text).append("\n");
            textInfo.setText(sbTemp);
            infoBox.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

//    public void enableButton(Button button, boolean yourWish){
//        runOnUiThread(() -> {
//            button.setEnabled(yourWish);
//        });
//    }

    public void setImage(ImageView image, int resId){
        runOnUiThread(() -> {
            image.setImageResource(resId);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // add listener for MessageClient
        Wearable.getMessageClient(this).addListener(this);
        // update paired NodeId
        Toast.makeText(this,"Updating NodeId!",Toast.LENGTH_SHORT).show();
        showNodes();
    }

    @Override
    public void onPause() {
        super.onPause();
        // remove listener for MessageClient
        Wearable.getMessageClient(this).removeListener(this);
    }

}
