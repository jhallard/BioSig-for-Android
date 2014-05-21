package com.jpl_347E.bio_sigservices; 
  
import java.util.Arrays; 
import java.util.Calendar; 
import java.util.Collection; 
import java.util.HashMap; 
import java.util.Iterator; 
  
import com.shimmerresearch.driver.FormatCluster; 
import com.shimmerresearch.driver.ObjectCluster; 
import com.shimmerresearch.driver.Shimmer; 
import com.shimmerresearch.tools.Logging; 
  
import android.app.Service; 
import android.content.Intent; 
import android.os.Binder; 
import android.os.Handler; 
import android.os.IBinder; 
import android.os.Message; 
import android.util.Log; 
import android.widget.Toast; 
  
public class ShimmerService extends Service{ 
    //======================================================================================= 
    // Data Members 
    //======================================================================================= 
    private final String logName = "BioSig-ShimmerService";         // Name of class, used for LogCat 
    private final int numDevices = 3,                               // Number of shimmer devices 
                      amtElectrodes = 2;                            // The amount of electrodes. Only ECG has 2, rest 1 
      
    private final IBinder binder = new LocalShimmerBinder();         
    private boolean calibrating = false;                            // Currently calibrating 
    private Handler graphHandler = null,                            // Handler that deals with graph 
                    allGraphHandler[] = null,                       // Used when all graphs are graphing 
                    calibrationHandler = null;                      // Handler used for calibration 
    private boolean graphing = false;                               // Currently grahping 
    private String graphBTAddress = "";                             // Address of shimmer currently graphing 
    public HashMap<String, Logging> hashMapLogging = new HashMap<String, Logging>(numDevices);  // HashMap used for logging 
    public HashMap<String, Object> hashMapShimmer = new HashMap<String, Object>(numDevices);    // HashMap that stores shimmer devices 
    private HashMap<String, Boolean> hashMapShimmerIsLogging = new HashMap<String, Boolean>(numDevices);    // Hash Map that stores the Shimmer Handlers to allow multiple logging 
    private String logFileName = "Default"; 
    //private boolean logging = false; 
    private double maxIntensity[][] = new double[numDevices][amtElectrodes]; 
    private boolean triggerResting[][] = new boolean[numDevices][amtElectrodes]; 
    private boolean triggerHit[][] = new boolean[numDevices][amtElectrodes]; 
    private String sensorNames[][] = new String[numDevices][amtElectrodes]; // Holds sensor names, ECG has two sensors so reason for 2 
    private boolean shimmerConnectionError = false;                 // Set to true if there was a Connection error 
    //public Logging shimmerLog = null; 
    private String subjectID = "0";                                     // ID of subject, default = 0 
      
    //Static Members 
    public static final int ECG_DEVICE = 0, 
                            EMG_DEVICE = 1, 
                            GSR_DEVICE = 2; 
    public static final String ECG_NAME = "ECG", 
                               EMG_NAME = "EMG", 
                               GSR_NAME = "GSR", 
                               PATH = "Bio_Sig"; 
      
    //======================================================================================= 
    // Methods 
    //======================================================================================= 
    /**Closes the log file of the specified Shimmer. 
     *  
     * @param btAddress     Bluetooth address of the Shimmer. 
     */
    private void closeLogFile(String btAddress) { 
        if (hashMapShimmerIsLogging.get(btAddress) && (hashMapLogging.get(btAddress) != null)) { 
            hashMapLogging.get(btAddress).closeFile(); 
            hashMapLogging.remove(btAddress); 
        } 
    } 
      
    /**Connect to a Shimmer with a specified sensor type: ECG_DEVICE, EMG_DEVICE, GSR_DEVICE. 
     *  
     * @param btAddress     Bluetooth address of the Shimmer device. 
     * @param type          Type of sensor: ECG_DEVICE, EMG_DEVICE, GSR_DEVICE 
     * @return              True if the connection is successful, false otherwise. 
     */
    public boolean connectShimmer(String btAddress, int type) { 
        Log.d(logName, "ConnectShimmer"); 
        shimmerConnectionError = false;                     // Reset in case of a connection error 
          
        String name = ""; 
        if (type == ECG_DEVICE) 
            name = ECG_NAME; 
        else if (type == EMG_DEVICE) 
            name = EMG_NAME; 
        else if (type == GSR_DEVICE) 
            name = GSR_NAME; 
        else
            return false; 
          
        ShimmerHandler temp = new ShimmerHandler(); 
          
        Shimmer newShimmer = new Shimmer(this, temp, name, false); 
            newShimmer.connect(btAddress, "default"); 
          
        // Add the shimmer to the hash map 
        hashMapShimmer.remove(btAddress);                   // Remove the shimmer if it exists 
        if (hashMapShimmer.get(btAddress) == null) 
            hashMapShimmer.put(btAddress, newShimmer); 
          
        Long waitTime = System.currentTimeMillis() + 10000;     // Set up to wait for connection for 10 seconds. 
        while (newShimmer.getShimmerState() != Shimmer.STATE_CONNECTED) { 
            //Wait for the Shimmer to connect or else the following statements will 
            // cause the program to crash. 
            if (System.currentTimeMillis() > waitTime) 
                return false; 
            // If there was an error in connecting the device 
            if (shimmerConnectionError) 
                return false; 
        } 
          
        //newShimmer.writeEnabledSensors(0x00000); 
        if (type == ECG_DEVICE) 
            setECGOptions(newShimmer);          // Setup Shimmer in ECG mode 
        else if (type == EMG_DEVICE) 
            setEMGOptions(newShimmer);          // Setup Shimmer in EMG mode 
        else if (type == GSR_DEVICE) 
            setGSROptions(newShimmer);          // Setup Shimmer in GSR mode 
        else { 
            hashMapShimmer.remove(btAddress); 
            return false; 
        } 
          
        return true;                // Connection Successful 
    } 
      
    /**Disable calibration 
     *  
     */
    public void disableCalibrating() { 
        calibrating = false; 
    } 
      
    /**Disconnect all of the Shimmer devices currently connected. They are then 
     * cleared from the hash maps. 
     */
    public void disconnectAllShimmers() { 
        // Stop the shimmers 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
            temp.stop(); 
        } 
          
        // Empty the Hash Maps 
        hashMapShimmer.clear(); 
        hashMapLogging.clear(); 
    } 
      
    /**Disconnects a single Shimmer device. 
     *  
     * @param btAddress     Bluetooth address of Shimmer device to disconnect. 
     */
    public void disconnectShimmer(String btAddress) { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getBluetoothAddress().equals(btAddress) && temp.getShimmerState() == Shimmer.STATE_CONNECTED) { 
                temp.stop(); 
            } 
        } 
          
        hashMapShimmer.remove(btAddress);               // remove the shimmer from hash map 
    } 
      
    /** Enable calibration 
     *  
     * @param hand          Handler that handles the messages for calibration. 
     */
    public void enableCalibration(Handler hand) { 
        graphing = false; 
        // Disable all streaming since it can interfere. 
        stopStreamingAllDevices(); 
        calibrationHandler = hand; 
        calibrating = true; 
    } 
      
    /**Get whether there was an error when connecting with a Shimmer device 
     *  
     * @return True if there was an error, false otherwise. 
     */
    public boolean getShimmerConnectionError() { 
        return shimmerConnectionError; 
    } 
      
    /**Get the range currently being used by the GSR. It's set at auto range so 4. 
     *  
     * @param btAddress                 Bluetooth address of the Shimmer device 
     * @return                          Returns the integer value of the range. 
     */
    public int getGSRRange(String btAddress) { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getBluetoothAddress().equals(btAddress) && temp.getShimmerState() == Shimmer.STATE_CONNECTED) { 
                return temp.getGSRRange();              // Return the state of the shimmer 
            } 
        } 
          
        return -1;      // Shimmer not found 
    } 
      
    /** Returns the logging status of a Shimmer. 
     *  
     * @param btAddress             Bluetooth address of the Shimmer device 
     * @return                      The logging status of the Shimmer. True if it is currently logging. 
     */
    public boolean getLogging(String btAddress) { 
        if (hashMapShimmerIsLogging.get(btAddress) != null) 
            return hashMapShimmerIsLogging.get(btAddress); 
          
        return false; 
    } 
      
    /** Get the sampling rate of a Shimmer device. 
     *  
     * @param btAddress             Bluetooth address of the Shimmer device. 
     * @return                      The sampling rate. 
     */
    public double getSamplingRate(String btAddress) { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getBluetoothAddress().equals(btAddress)) 
                return temp.getSamplingRate(); 
        } 
          
        return 0; 
    } 
      
    /** Get the current enabled sensor on a Shimmer. 
     *   
     * @param btAddress             Bluetooth address of the Shimmer device. 
     * @return                      Returns the sensor value in accordance with the Shimmer class (e.g. SENSOR_EMG, SENSOR_ECG ...) 
     */
    public int getEnabledSensor(String btAddress) { 
        return ((Shimmer)hashMapShimmer.get(btAddress)).getEnabledSensors(); 
    } 
      
    /** Returns the current connection state of a Shimmer device. 
     *  
     * @param btAddress             Bluetooth address of the Shimmer device. 
     * @return                      STATE_NONE, STATE_CONNECTED, STATE_CONNECTING 
     */
    public int getShimmerState(String btAddress) { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getBluetoothAddress().equals(btAddress)) { 
                Log.d(logName + ": ShimmerState", Integer.toString(temp.getShimmerState())); 
                return temp.getShimmerState();              // Return the state of the shimmer 
            } 
        } 
          
        return -1;      // Shimmer not found 
    } 
      
    /** Get the subject ID currently being used. 
     *  
     * @return      Returns the subject Id. 
     */
    public String getSubjectID() { 
        return subjectID; 
    } 
      
    /** Determines if a device is currently connected. 
     *  
     * @param btAddress             Bluetooth address of the Shimmer device 
     * @return                      True if the device is connected. False otherwise. 
     */
    public boolean isDeviceConnected(String btAddress) { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getBluetoothAddress().equals(btAddress) && temp.getShimmerState() == Shimmer.STATE_CONNECTED) { 
                return true; 
            } 
        } 
          
        return false; 
    } 
      
    /** Returns the streaming status of a Shimmer device. 
     *  
     * @param btAddress             Bluetooth address of the Shimmer device 
     * @return                      True if the Shimmer is streaming. 
     */
    public boolean isDeviceStreaming(String btAddress) { 
        Shimmer temp = (Shimmer)hashMapShimmer.get(btAddress); 
        if (temp.getStreamingStatus()) 
            return true; 
        return false; 
    } 
      
    /** Returns the binder of the service. 
     *  
     * @return                      Returns binder 
     */
    public IBinder onBind(Intent arg0) { 
        return binder; 
    } 
  
    /** Initializes the service when it is first created. It shows a toast message, and then 
     * fills the trigger and maxItensity arrays. Finally it sets sensor names. 
     */
    public void onCreate() { 
        Toast.makeText(this, "Shimmer Service Created", Toast.LENGTH_LONG).show(); 
        Log.d(logName, "OnCreate"); 
          
        for (int i = 0; i < numDevices; i++) { 
            Arrays.fill(maxIntensity[i], 0); 
            Arrays.fill(triggerResting[i], true); 
            Arrays.fill(triggerHit[i], true);    
        } 
          
        setSensorNames(); 
    } 
      
    /** Called when the service is destroyed. It shows a toast message, and stops all Shimmers. 
     *  
     */
    public void onDestroy() { 
        Toast.makeText(this, "Shimmer Service Stopped", Toast.LENGTH_LONG).show(); 
        Log.d(logName, "OnDestroy"); 
          
        // Stop the shimmers 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
            temp.stop(); 
        } 
    } 
  
    /** When the service is started, this ensures that the service must be terminated 
     * explicitly. 
     */
    public int onStartCommand(Intent intent, int flags, int startId) { 
        Log.d(logName, "OnStartCommand-Received start id " + startId + ": " + intent); 
        return START_STICKY;                // Service must be explicitly stopped 
    } 
      
    /** Performs the same function as onDestroy() 
     *  
     */
    public void onStop() { 
        onDestroy();                        // Same thing as onDestroy so just call it 
    } 
      
          
    /** Configures the Shimmer device to stream ECG signals. It writes to the Shimmer 
     * the code for the ECG sensor and the sampling rate. 
     *  
     * @param shim                  The Shimmer to configure. 
     */
    private void setECGOptions(Shimmer shim) { 
        shim.writeEnabledSensors(Shimmer.SENSOR_ECG); 
        shim.writeSamplingRate(256.0);                      // Sampling rate of 256 Hz 
    } 
      
    /** Configures the Shimmer device to stream EMG signals. It writes to the Shimmer 
     * the code for the EMG sensor and the sampling rate. 
     *  
     * @param shim                  The Shimmer to configure. 
     */
    private void setEMGOptions(Shimmer shim) { 
        shim.writeEnabledSensors(Shimmer.SENSOR_EMG); 
        shim.writeSamplingRate(512.0);                      // Sampling rate of 512 Hz 
    } 
      
    /** Sets the handler that will deal with graphing. Since only one Shimmer graph can be seen 
     * at one time, this is for all Shimmer devices. 
     *  
     * @param hand                  The graph handler 
     * @param btAdd                 Bluetooth address of the Shimmer device that is graphing. 
     */
    public void setGraphHandler(Handler hand, String btAdd) { 
        graphHandler = hand; 
        graphBTAddress = btAdd; 
    } 
      
    /** Sets the graphing status. 
     *  
     * @param status            True to enable graphing. False to disable. 
     */
    public void setGraphingHandlerStatus(boolean status) { 
        graphing = status; 
    } 
      
    /** Configures the Shimmer device to stream GSR signals. It writes to the Shimmer 
     * the code for the EMG sensor, the sampling rate, and sets the range of skin resistance to auto. 
     *  
     * @param shim                  The Shimmer to configure. 
     */
    private void setGSROptions(Shimmer shim) { 
        shim.writeEnabledSensors(Shimmer.SENSOR_GSR);   // Enable the GSR sensor 
        shim.writeGSRRange(3);              // Uses auto-range for skin resistance 
        shim.writeSamplingRate(5.0);        // Sets a sampling rate of 5 Hz 
    } 
      
    /** sets the log file name to use. 
     *  
     * @param fileName              File name. 
     */
    public void setLogFileName(String fileName) { 
        logFileName = fileName; 
    } 
      
    /** Sets the logging status for a Shimmer device. 
     *  
     * @param log                   The logging status. 
     * @param btAddress             Bluetooth address of the Shimmer device. 
     */
    public void setLogging(boolean log, String btAddress) { 
        hashMapShimmerIsLogging.remove(btAddress); 
        hashMapShimmerIsLogging.put(btAddress, log); 
    } 
      
    /** Fills the sensorNames array with the appropriate names. 
     *  
     */
    private void setSensorNames() { 
        sensorNames[ECG_DEVICE][0] = "ECG RA-LL"; 
        sensorNames[ECG_DEVICE][1] = "ECG LA-LL"; 
        sensorNames[EMG_DEVICE][0] = "EMG"; 
        sensorNames[EMG_DEVICE][1] = ""; 
        sensorNames[GSR_DEVICE][0] = "GSR"; 
        sensorNames[GSR_DEVICE][1] = ""; 
    } 
      
    /** Sets the subject Id to use. 
     *  
     * @param id                    Subject ID 
     */
    public void setSubjectId(String id) { 
        subjectID = id; 
    } 
      
    /** Start streaming from a Shimmer device. 
     *  
     * @param btAddress             Bluetooth address of Shimmer device. 
     */
    public void startStreaming(String btAddress) { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getBluetoothAddress().equals(btAddress) && temp.getShimmerState() == Shimmer.STATE_CONNECTED) { 
                temp.startStreaming(); 
            } 
        } 
    } 
      
    /** Starts streaming all connected Shimmer devices. 
     *  
     */
    public void startStreamingAllDevices() { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getShimmerState() == Shimmer.STATE_CONNECTED) 
                temp.startStreaming(); 
        } 
    } 
      
    /** Stops the streaming of a Shimmer device. 
     *  
     * @param btAddress             Bluetooth address of the Shimmer device. 
     */
    public void stopStreaming(String btAddress) { 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getBluetoothAddress().equals(btAddress) && temp.getShimmerState() == Shimmer.STATE_CONNECTED) { 
                temp.stopStreaming(); 
            } 
        } 
    } 
      
    /** Stops the streaming of all connected devices. 
     *  
     */
    public void stopStreamingAllDevices() { 
        // Stop the streaming 
        Collection<Object> shimmers = hashMapShimmer.values(); 
        Iterator<Object> iterator = shimmers.iterator(); 
        while(iterator.hasNext()) { 
            Shimmer temp = (Shimmer)iterator.next(); 
              
            if (temp.getShimmerState() == Shimmer.STATE_CONNECTED) 
                temp.stopStreaming(); 
        } 
    } 
  
    //======================================================================================= 
    // Nested Classes 
    //======================================================================================= 
    /** A binder that allows access to the service from other classes.   * 
     */
    public class LocalShimmerBinder extends Binder { 
        /** Returns a reference to the Shimmer service which allows its use. 
         *  
         * @return              Returns this service. 
         */
        public ShimmerService getService() { 
            return ShimmerService.this; 
        } 
    } 
      
    /** The handler that handles the messages between the service and the UI. 
     * Each connected device has its own handler which allows for simultaneous streaming 
     * and logging. 
     */
    private class ShimmerHandler extends Handler { 
        Logging shimmerLog;                     // Holds the Logging object 
          
        /** Handles the messages that are meant for this handler. If logging is enabled, it sends the data 
         * to the Logging object. If graphing is enabled, the data is sent to the graph handler. 
         * It monitors triggers and the maximum intensity. Finally, it broadcasts messages when the  
         * Shimmer state changes. 
         */
        public void handleMessage(Message msg) { 
            //Switch to handle the different messages 
            switch(msg.what) { 
            case Shimmer.MESSAGE_READ: 
                // The message is shimmer data which is held in ObjectCluster 
                if (msg.obj instanceof ObjectCluster) { 
                    ObjectCluster shimData = (ObjectCluster)msg.obj; 
                      
                    //Get Device type 
                    int type = 0; 
                    String sensor; 
                    if (shimData.mMyName.equals(ECG_NAME)) { 
                        type = ECG_DEVICE; 
                        sensor = "c"; 
                    } 
                    else if (shimData.mMyName.equals(EMG_NAME)) { 
                        type = EMG_DEVICE; 
                        sensor = "m"; 
                    } 
                    else if (shimData.mMyName.equals(GSR_NAME)) { 
                        type = GSR_DEVICE; 
                        sensor = "g"; 
                    } 
                    else
                        break; 
                      
                    //Calibrating 
                    if (calibrating) { 
                        Log.d(logName, "Sending to Calibration"); 
                        calibrationHandler.obtainMessage(Shimmer.MESSAGE_READ, shimData).sendToTarget(); 
                    } 
                      
                    // Data is being logged 
                    boolean logging = false; 
                    if (hashMapShimmerIsLogging.get(shimData.mBluetoothAddress) != null) 
                        logging = hashMapShimmerIsLogging.get(shimData.mBluetoothAddress); 
                    if (logging) { 
                          
                        // If the log exists, then log data, else create a new log 
                        if (shimmerLog != null) 
                            shimmerLog.logData(shimData); 
                        else { 
                            Logging newShimLog; 
                              
                            // Create the file 
                            //TODO: Change file name to Adrian suggestion- Just need subject ID 
                            if (logFileName.equals("Default")) 
                                newShimLog = new Logging(sensor + "_" + subjectID + "_" + Long.toString(System.currentTimeMillis()), "\t",PATH + "/" + subjectID); 
                            else
                                newShimLog = new Logging(sensor + "_" + Long.toString(System.currentTimeMillis()) + logFileName, "\t", "Bio_Sig"); 
                                  
                            //hashMapLogging.remove(shimData.mBluetoothAddress); 
                            if (hashMapLogging.get(shimData.mBluetoothAddress) == null) { 
                                hashMapLogging.put(shimData.mBluetoothAddress, newShimLog); 
                                shimmerLog = hashMapLogging.get(shimData.mBluetoothAddress); 
                            } 
                              
                        } 
                    } 
                      
                    //Graph if graphing is enabled, graph handler has been initialized, 
                    // and the bluetooth address of the data is the same as the handler's 
                    // address 
                    if (graphing && shimData.mBluetoothAddress.equals(graphBTAddress)) { 
                        Log.d(logName, "Sending for Graph"); 
                        graphHandler.obtainMessage(Shimmer.MESSAGE_READ, shimData).sendToTarget(); 
                    } 
                      
                    Collection<FormatCluster> shimDataFormats; 
                    FormatCluster formCluster; 
                    int maxData = 600, 
                        dataLimit = 200; 
                    for (int i = 0; i < amtElectrodes; i++) { 
                        shimDataFormats = shimData.mPropertyCluster.get(sensorNames[type][i]); 
                        if (shimDataFormats != null) { 
                            formCluster = (FormatCluster)ObjectCluster.returnFormatCluster(shimDataFormats, "CAL"); 
                            if (formCluster != null) { 
                                // Not enough data to trigger a hit, so resting 
                                if (formCluster.mData < dataLimit) 
                                    triggerResting[type][i] = true; 
                                  
                                // Enough data detected and it's resting, so it's a hit 
                                if ((formCluster.mData > dataLimit) && triggerResting[type][0]) 
                                    triggerHit[type][i] = true; 
                                  
                                // There is a hit so monitor its intensity 
                                if (triggerHit[type][0]) { 
                                    // Obtain the maximum intensity 
                                    if (maxIntensity[type][i] < formCluster.mData) 
                                        maxIntensity[type][i] = formCluster.mData; 
                                      
                                    // If the difference of the max intensity and current intesity is greater than 0, or the max intensity is greater than the 
                                    // max data limit, and the trigger is resting, then  
                                    if (((maxIntensity[type][i] - formCluster.mData > 0) || maxIntensity[type][i] > maxData) && triggerResting[type][i]) { 
                                        // There is no hit and no resting 
                                        triggerHit[type][i] = triggerResting[type][i] = false; 
                                          
                                        // Log the max intensity to the LogCat 
                                        Log.d(logName + ": MaxData", sensorNames[type][i] + ": " + Double.toString(maxIntensity[type][i])); 
                                          
                                        // clear the max intensity 
                                        maxIntensity[type][i] = 0; 
                                    } 
                                } 
                            } 
                        } 
                    } 
                } 
                break;              // Break for case MESSAGE_READ 
                  
            case Shimmer.MESSAGE_TOAST: 
                Log.d(logName + "Case Toast", msg.getData().getString(Shimmer.TOAST)); 
                Toast.makeText(getApplicationContext(), msg.getData().getString(Shimmer.TOAST), Toast.LENGTH_SHORT).show(); 
                if (msg.getData().getString(Shimmer.TOAST).equals("Device connection was lost")){ 
                    //TODO: Do something so it shows that the device is not connected anymore 
                    shimmerConnectionError = true; 
                } 
                break;              // Break for Shimmer.MESSAGE_TOAST 
                  
            case Shimmer.MESSAGE_STATE_CHANGE: 
                Intent intent = new Intent("com.jpl_347E.bio_sigservices.ShimmerService"); 
                Log.d(logName + ": ShimmerGraph", "Sending"); 
                  
                // Send message to the graph handler 
                if (graphing) 
                    graphHandler.obtainMessage(Shimmer.MESSAGE_STATE_CHANGE, msg.arg1, -1, msg.obj).sendToTarget(); 
                  
                // switch for msg.arg1 
                switch(msg.arg1) { 
                case Shimmer.STATE_CONNECTED: 
                    Log.d(logName + ":Shimmer",((ObjectCluster) msg.obj).mBluetoothAddress + "  " + ((ObjectCluster) msg.obj).mMyName); 
                    intent.putExtra("ShimmerBluetoothAddress", ((ObjectCluster) msg.obj).mBluetoothAddress); 
                    intent.putExtra("ShimmerDeviceName", ((ObjectCluster) msg.obj).mMyName); 
                    intent.putExtra("ShimmerState", Shimmer.STATE_CONNECTED); 
                    sendBroadcast(intent); 
                    break;          // Break for case Shimmer.STATE_CONNECTED 
                      
                case Shimmer.STATE_CONNECTING: 
                    intent.putExtra("ShimmerBluetoothAddress", ((ObjectCluster) msg.obj).mBluetoothAddress); 
                    intent.putExtra("ShimmerDeviceName", ((ObjectCluster) msg.obj).mMyName); 
                    intent.putExtra("ShimmerState", Shimmer.STATE_CONNECTING); 
                    break;          // Break for case Shimmer.STATE_CONNECTED 
                      
                case Shimmer.STATE_NONE: 
                    intent.putExtra("ShimmerBluetoothAddress", ((ObjectCluster) msg.obj).mBluetoothAddress); 
                    intent.putExtra("ShimmerDeviceName", ((ObjectCluster) msg.obj).mMyName); 
                    intent.putExtra("ShimmerState", Shimmer.STATE_NONE); 
                    sendBroadcast(intent); 
                    break;          // Break for case Shimmer.STATE_NONE 
                } 
                break;              // Break for case Shimmer.MESSAGE_STATE_CHANGED 
                  
            case Shimmer.MESSAGE_STOP_STREAMING_COMPLETE: 
                String btAddress = msg.getData().getString("Bluetooth Address"); 
                if (shimmerLog != null && msg.getData().getBoolean("Stop Streaming")) 
                    closeLogFile(btAddress); 
                break;              // Break for Shimmer.MESSAGE_STOP_STREAMING_COMPLETE 
            } 
        } 
    } 
} 