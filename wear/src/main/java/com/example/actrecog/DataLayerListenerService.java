/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.actrecog;

import android.content.Intent;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

/** Listens to DataItems and Messages from the local node. */
public class DataLayerListenerService extends WearableListenerService {
    private static final String START_SERVICE_PATH = "/start_actrecog";
    private static final String START_SERVICE_ACK_PATH = "/start_actrecog_ack";
    private static final String STOP_SERVICE_PATH = "/stop_actrecog";
    private static final String STOP_SERVICE_ACK_PATH = "/stop_actrecog_ack";
    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        Log.d("DataLayerListenerService", "onMessageReceived: " + messageEvent);
        // Check to see if the message is to start an activity
        if (messageEvent.getPath().equals(START_SERVICE_PATH)) {
            Intent startIntent = new Intent(this, WearService.class);
            startService(startIntent);
            Wearable.getMessageClient(this).sendMessage(messageEvent.getSourceNodeId(), START_SERVICE_ACK_PATH, new byte[0]);
        } else if (messageEvent.getPath().equals(STOP_SERVICE_PATH)) {
            Wearable.getMessageClient(this).sendMessage(messageEvent.getSourceNodeId(), STOP_SERVICE_ACK_PATH, new byte[0]);
            Intent stopIntent = new Intent(this, WearService.class);
            stopService(stopIntent);
        }
    }
}
