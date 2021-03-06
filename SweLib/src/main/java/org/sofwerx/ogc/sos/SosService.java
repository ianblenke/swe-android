package org.sofwerx.ogc.sos;

import android.content.Context;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Pair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

/**
 * A service to process the outside comms in SOS format. This service can broadcast
 * data over IPC, HTTP, or both
 */
public class SosService implements SosMessageListener {
    public final static String DEFAULT_SWE_CHANNEL = "sost";
    private HandlerThread sosThread; //the MANET itself runs on this thread where possible
    private Handler handler;
    private SosMessageListener listener;
    private String serverURL;
    private String username;
    private String password;
    private Context context;
    private SosSensor sosSensor;
    private SosIpcTransceiver transceiver;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private AtomicBoolean sendSensorReadingWhenReady = new AtomicBoolean(false);
    private boolean ipcBroadcast;
    private boolean sosHttpBroadcast;
    private boolean sensorMode = true;
    private AtomicBoolean shouldPollServer = new AtomicBoolean(false);
    private long pollInterval = 1000l * 15l; //default server polling interval
    private boolean autoThrottle = false;
    private long outgoingThrottleRate = SosIpcTransceiver.DEFAULT_OUTGOING_THROTTLE_RATE;
    private long incomingThrottleRate = SosIpcTransceiver.DEFAULT_INCOMING_THROTTLE_RATE;

    /**
     * Creates a new SosService
     * @param context
     * @param sosSensor
     * @param sosServerURL
     * @param username
     * @param password
     * @param turnOn true == the service will start running immediately; false means the service will initiate but will not start sending/receiving
     * @param enableIpcBroadcast
     */
    public SosService(Context context, SosSensor sosSensor, String sosServerURL, String username, String password, final boolean turnOn, final boolean enableIpcBroadcast) {
        if (context == null)
            Log.e(SosIpcTransceiver.TAG,"SosService should not be passed a null context");
        this.context = context;
        this.sosSensor = sosSensor;
        if (sosSensor == null) {
            sensorMode = false;
            Log.d(SosIpcTransceiver.TAG,"No sensor provided for SosService constructor, so SosService assumed to be running in server (rather than) sensor mode. To switch to sensor mode, use setSensorMode()");
        }
        if (context instanceof SosMessageListener)
            listener = (SosMessageListener)context;
        this.serverURL = sosServerURL;
        setSosServerUsername(username);
        setSosServerPassword(password);
        sosThread = new HandlerThread("SosService") {
            @Override
            protected void onLooperPrepared() {
                Log.i(SosIpcTransceiver.TAG,"SosService started");
                handler = new Handler(sosThread.getLooper());
                setOn(turnOn);
            }
        };
        sosThread.start();
        this.ipcBroadcast = enableIpcBroadcast;
        this.sosHttpBroadcast = (sosServerURL != null);
        SosIpcTransceiver.setChannel(DEFAULT_SWE_CHANNEL);
    }

    public SosService(Context context, SosSensor sosSensor, String sosServerURL, final boolean turnOn, final boolean enableIpcBroadcast) {
        this(context, sosSensor, sosServerURL, null, null, turnOn, enableIpcBroadcast);
    }

    public boolean startPolling() {
        if (!shouldPollServer.get()) {
            if (handler == null) {
                Log.d(SosIpcTransceiver.TAG,"cannot setup regular server polling as handler is not yet assigned");
                return false;
            }
            shouldPollServer.set(true);
            handler.post(repeatPollServer);
            return true;
        }
        return true;
    }

    public void stopPolling() {
        shouldPollServer.set(false);
    }

    /**
     * TIme between polling of the SOS server for sensor data
     * @param interval time (in ms)
     */
    public void setPollingInterval(long interval) { pollInterval = interval; }
    public boolean isPollingServer() { return shouldPollServer.get(); }

    private Runnable repeatPollServer = new Runnable() {
        @Override
        public void run() {
            if (shouldPollServer.get()) {
                if (sosSensor != null) {
                    OperationGetResults op = new OperationGetResults(sosSensor);
                    if (op.isValid())
                        broadcast(op);
                }
                if (handler != null)
                    handler.postDelayed(repeatPollServer, pollInterval);
            }
        }
    };

    /**
     * Toggles between on (active/running/transmitting and receiving) and off (paused)
     * @param on true = on/active/transmitting and receiving
     */
    public void setOn(boolean on) {
        if (on != isRunning.get()) {
            if (on) {
                if (context != null) {
                    Log.i(SosIpcTransceiver.TAG,"SosService turned ON");
                    transceiver = new SosIpcTransceiver(this);
                    IntentFilter intentFilter = new IntentFilter(SosIpcTransceiver.ACTION_SOS);
                    context.registerReceiver(transceiver, intentFilter);
                    if (sensorMode)
                        broadcastSensorReadings();
                    else
                        startPolling();
                }
            } else {
                Log.i(SosIpcTransceiver.TAG,"SosService turned OFF");
                if (transceiver != null) {
                    context.unregisterReceiver(transceiver);
                    transceiver = null;
                }
            }
            isRunning.set(on);
        }
    }

    public void broadcastSensorReadings() {
        Log.d(SosIpcTransceiver.TAG,"Trying to broadcast sensor readings");
        if (sosSensor != null) {
            if (sosSensor.isReadyToSendResults()) {
                OperationInsertResult operation = new OperationInsertResult(sosSensor);
                if (operation.isValid())
                    broadcast(operation);
                else {
                    if (listener != null)
                        listener.onSosError("Unable to send sensor readings as sensor measurements are not fully initialized");
                    Log.d(SosIpcTransceiver.TAG, "Cannot broadcast sensor readings; OperationInsertResult did not have valid data");
                }
            } else {
                registerSensor();
                sendSensorReadingWhenReady.set(true);
            }
        } else {
            if (listener != null)
                listener.onSosError("Cannot send sensor readings as no sensor has been set. Call setSosSensor first");
            Log.d(SosIpcTransceiver.TAG, "...but SosSensor is null");
        }
    }

    public void broadcast(AbstractSosOperation operation) {
        if (handler != null) {
            handler.post(() -> {
                Log.d(SosIpcTransceiver.TAG,"Broadcasting "+operation.getClass().getName());
                if (isRunning.get()) {
                    if (ipcBroadcast) {
                        SosIpcTransceiver.setEnableSqAN(true);
                        Log.d(SosIpcTransceiver.TAG,"Broadcasting SOS operation over IPC");
                        try {
                            transceiver.broadcast(context, operation);
                        } catch (SosException e) {
                            Log.e(SosIpcTransceiver.TAG,"Unable to broadcast SOS operation: "+e.getMessage());
                            e.printStackTrace();
                        }
                    }
                    if ((serverURL != null) && sosHttpBroadcast) {
                        Log.d(SosIpcTransceiver.TAG,"Broadcasting SOS operation to "+serverURL);
                        if (operation instanceof OperationGetResults) { //we need to use GET with JSON for this operation
                            SosSensor sensor = ((OperationGetResults) operation).getSensor();
                            if (!operation.isValid()) {
                                if (listener != null)
                                    listener.onSosError("Unable to GetResult without a sensor, an assignedOffering, and at least one observableProperty");
                                return;
                            }
                            try {
                                String result = HttpHelper.get(serverURL,((OperationGetResults) operation).getPairs());
                                try {
                                    sensor.parseSensors(new JSONObject(result));
                                    if (listener != null)
                                        listener.onSosOperationReceived(operation);
                                } catch (JSONException e) {
                                    try {
                                        JSONArray array = new JSONArray(result); //this is actually a list of results, so let's get the last one
                                        if ((array == null) || (array.length() < 1))
                                            return;
                                        sensor.parseSensors((JSONObject) array.get(array.length() - 1));
                                        if (listener != null)
                                            listener.onSosOperationReceived(operation);
                                    } catch (JSONException e1) {
                                        if (listener != null)
                                            listener.onSosError("Unable to parse "+result);
                                    }
                                }
                            } catch (IOException e) {
                                Log.e(SosIpcTransceiver.TAG,"Attempt to get results for "+sensor.getId()+" failed: "+e.getMessage());

                                if (listener != null)
                                    listener.onSosError("Unable to connect to SOS server: " + e.getMessage());
                            }
                        } else {
                            try {
                                String result = HttpHelper.post(serverURL, username, password, SosIpcTransceiver.toString(operation.toXML()), !sensorMode);
                                AbstractSosOperation responseOperation = AbstractSosOperation.newFromXmlString(result);
                                if (responseOperation == null) {
                                    Log.e(SosIpcTransceiver.TAG, "Unable to parse response from server: " + result);
                                    if (listener != null)
                                        listener.onSosError("Unexpected response from SOS server");
                                } else
                                    onSosOperationReceived(responseOperation);
                            } catch (IOException | TransformerException | ParserConfigurationException e) {
                                if (listener != null)
                                    listener.onSosError("Unable to connect to SOS server: " + e.getMessage());
                            }
                        }
                    }
                } else {
                    if (listener != null)
                        listener.onSosError("Cannot send SOS messages as the SosService has not be enabled (call setOn())");
                }
            });
        } else {
            Log.d(SosIpcTransceiver.TAG,"...but handler is not yet ready");
            if (listener != null)
                listener.onSosError("Unable to broadcast yet as the thread and handler SosService need are not yet ready");
        }
    }

    public void shutdown() {
        Log.i(SosIpcTransceiver.TAG,"Shutting down SosServer");
        stopPolling();
        setOn(false);
        if (sosThread != null) {
            if (handler != null)
                handler.removeCallbacksAndMessages(null);
            sosThread.quitSafely();
            sosThread = null;
            handler = null;
        }
        if (context != null)
            context = null;
    }

    /**
     * Registers the sensor with the SOS server if not already done
     */
    public void registerSensor() {
        Log.d(SosIpcTransceiver.TAG,"Trying to register sensor");
        if (handler != null) {
            handler.post(() -> {
                if (sosSensor.getAssignedProcedure() == null) {
                    if (sosSensor.isReadyToRegisterSensor()) {
                        if (autoThrottle)
                            SosIpcTransceiver.clearThrottle();
                        Log.d(SosIpcTransceiver.TAG,"Sensor has all required info to register; contacting server...");
                        OperationInsertSensor operation = new OperationInsertSensor(sosSensor);
                        broadcast(operation);
                    } else
                        Log.w(SosIpcTransceiver.TAG, "sosSensor does not yet have enough information to register with the SOS server");
                } else if (sosSensor.getAssignedTemplate() == null) {
                    if (sosSensor.isReadyToRegisterResultTemplate()) {
                        if (autoThrottle)
                            SosIpcTransceiver.clearThrottle();
                        OperationInsertResultTemplate operation = new OperationInsertResultTemplate(sosSensor);
                        broadcast(operation);
                    } else
                        Log.w(SosIpcTransceiver.TAG, "sosSensor does not yet have enough information to register a result template with the SOS server");
                } else
                    Log.i(SosIpcTransceiver.TAG, "registerSensor ignored as sosSensor already appears to be registered with the SOS server");
            });
        } else
            Log.d(SosIpcTransceiver.TAG,"... but handler not ready yet");
    }

    public SosMessageListener getListener() { return listener; }
    public void setListener(SosMessageListener listener) { this.listener = listener; }
    public SosSensor getSosSensor() { return sosSensor; }
    public void setSensor(SosSensor sensor) { this.sosSensor = sensor; }
    public void setSosServerUrl(String serverUrl) { this.serverURL = serverUrl; }
    public String getSosServerUrl() { return serverURL; }
    public void setSosServerUsername(String username) {
        if ((username != null) && (username.length() == 0))
            this.username = null;
        else
            this.username = username;
    }
    public void setSosServerPassword(String password) {
        if ((password != null) && (password.length() == 0))
            this.password = null;
        else
            this.password = password;
    }

    /**
     * Sets the current sosSensor; if the sosSensor already has enough information
     * to register with the SOS server, start that process. The process will keep going
     * if the sosSensor already has enough information to register a result template
     * @param sosSensor
     */
    public void setSosSensor(SosSensor sosSensor) {
        this.sosSensor = sosSensor;
        if (sosSensor != null)
            registerSensor();
    }

    @Override
    public void onSosOperationReceived(AbstractSosOperation operation) {
        if (listener != null)
            listener.onSosOperationReceived(operation);
        if (operation instanceof OperationInsertSensorResponse) {
            if (sosSensor != null) {
                OperationInsertSensorResponse response = (OperationInsertSensorResponse)operation;
                if ((sosSensor.getUniqueId() != null) && (sosSensor.getUniqueId().equalsIgnoreCase(response.getAssignedProcedure()))) {
                    sosSensor.setAssignedProcedure(response.getAssignedProcedure());
                    sosSensor.setAssignedOffering(response.getAssignedOffering());
                    if (sendSensorReadingWhenReady.get())
                        registerSensor();
                } else
                    Log.i(SosIpcTransceiver.TAG,"InsertSensorResponse received, but it was for sensor "+response.getAssignedProcedure());
            }
        } else if (operation instanceof OperationInsertResultTemplateResponse) {
            if (autoThrottle)
                SosIpcTransceiver.setThrottleRate(outgoingThrottleRate);
            if (sosSensor != null) {
                OperationInsertResultTemplateResponse response = (OperationInsertResultTemplateResponse) operation;
                if ((response.getAcceptedTemplate() != null) && (sosSensor.getAssignedProcedure() != null)
                        && response.getAcceptedTemplate().startsWith(sosSensor.getAssignedProcedure())) {
                    sosSensor.setAssignedTemplate(response.getAcceptedTemplate());
                    if (sendSensorReadingWhenReady.get())
                        broadcastSensorReadings();
                    if (listener != null)
                        listener.onSosConfigurationSuccess();
                } else
                    Log.i(SosIpcTransceiver.TAG, "InsertResultTemplateResponse received, but it was for template " + response.getAcceptedTemplate());
            }
        } else if (operation instanceof OperationInsertResult) {
            if (autoThrottle)
                SosIpcTransceiver.setThrottleRate(incomingThrottleRate);
        } else if (operation instanceof OperationInsertResultResponse) {
            if (autoThrottle)
                SosIpcTransceiver.setThrottleRate(outgoingThrottleRate);
        }
        if (listener != null)
            listener.onSosOperationReceived(operation);
    }

    @Override
    public void onSosError(String message) {
        Log.e(SosIpcTransceiver.TAG,message);
        if (listener != null)
            listener.onSosError(message);
    }

    @Override
    public void onSosConfigurationSuccess() {
        Log.i(SosIpcTransceiver.TAG,"Successfully connected to SOS Server");
        if (listener != null)
            listener.onSosConfigurationSuccess();
    }

    /**
     * Should this service broadcast SOS data over IPC
     * @return
     */
    public boolean isIpcBroadcast() { return ipcBroadcast; }

    /**
     * Sets if this service should broadcast SOS data over IPC
     * @param ipcBroadcast
     */
    public void setIpcBroadcast(boolean ipcBroadcast) { this.ipcBroadcast = ipcBroadcast; }

    /**
     * Sets if this service should broadcast SOS data over HTTP
     * @param enable
     */
    public void setHttpBroadcast(boolean enable) { this.sosHttpBroadcast = enable; }

    /**
     * Is this service working in sensor mode (as opposed to server mode)
     * @return true == sensor mode; false == server mode
     */
    public boolean isSensorMode() {
        return sensorMode;
    }

    /**
     * Sets this service mode
     * @param sensorMode true == sensor mode; false == server mode
     */
    public void setSensorMode(boolean sensorMode) { this.sensorMode = sensorMode; }
    public void setSensorMode() { setSensorMode(true); }

    /**
     * Is the service set to automatically throttle incoming messages to offset
     * cost on flooding XML unmarshalling.
     * @return true == will automatically throttle
     */
    public boolean isAutoThrottle() { return autoThrottle; }

    /**
     * Sets the service to automatically throttle incoming messages (usually done as
     * a way to prevent flooding the processor with a bunch of costly XML unmarshalling)
     * @param autoThrottle true == the service will automatically throttle
     */
    public void setAutoThrottle(boolean autoThrottle) { this.autoThrottle = autoThrottle; }

    /**
     * Gets the set throttle rate on outgoing messages
     * @return interval between messages in ms
     */
    public long getOutgoingThrottleRate() { return outgoingThrottleRate; }

    /**
     * Sets the set throttle rate on outgoing messages
     * @param outgoingThrottleRate interval between messages in ms
     */
    public void setOutgoingThrottleRate(long outgoingThrottleRate) {
        if (outgoingThrottleRate > 0l)
            this.outgoingThrottleRate = outgoingThrottleRate;
    }

    /**
     * Gets the set throttle rate on incoming messages
     * @return interval between messages in ms
     */
    public long getIncomingThrottleRate() { return incomingThrottleRate; }

    /**
     * Sets the set throttle rate on incoming messages
     * @param incomingThrottleRate interval between messages in ms
     */
    public void setIncomingThrottleRate(long incomingThrottleRate) {
        if (incomingThrottleRate > 0l)
            this.incomingThrottleRate = incomingThrottleRate;
    }
}
