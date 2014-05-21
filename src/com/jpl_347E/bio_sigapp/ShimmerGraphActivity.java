package com.jpl_347E.bio_sigapp; 
  
import java.util.Collection; 
  
import com.jpl_347E.bio_sigservices.ShimmerService; 
import com.jpl_347E.bio_sigservices.ShimmerService.LocalShimmerBinder; 
import com.shimmerresearch.driver.FormatCluster; 
import com.shimmerresearch.driver.ObjectCluster; 
import com.shimmerresearch.driver.Shimmer; 
  
import android.app.Activity; 
import android.content.ComponentName; 
import android.content.Context; 
import android.content.Intent; 
import android.content.IntentFilter; 
import android.content.ServiceConnection; 
import android.os.Bundle; 
import android.os.Handler; 
import android.os.IBinder; 
import android.os.Message; 
import android.util.Log; 
import android.view.View; 
import android.view.ViewGroup.LayoutParams; 
import android.widget.TextView; 
  
public class ShimmerGraphActivity extends Activity { 
    //======================================================================================= 
    // Data Members 
    //======================================================================================= 
    private static String logName = "ShimmerGraphActivity"; 
      
    private int enabledSensor = 0; 
    private static String btAddress = "";                           // Bluetooth address of current device 
    private static ShimmerGraphView graphDisplay;                   // Used to display actual graph 
    private static int graphSubSampling = 0;                        // Used to only graph a small subset of the samples 
    private static String sensor = "";                              // String that stores the type of sensor 
    private boolean serviceBindShimmer = false; 
    private static ShimmerService serviceShim;                      // Stores the shimmer service  
      
    // Graph Activity data members 
    private static TextView sensorText1,                            // handles to id: label_sensor_1 
                            sensorText2,                            // handles to id: label_sensor_2 
                            sensorValues1,                          // handles to id: sensor_value1 
                            sensorValues2;                          // handles to id: sensor_value2 
      
      
    private static Handler handlerShim = new Handler() { 
        public void handleMessage(Message msg) { 
            switch (msg.what) { 
            case Shimmer.MESSAGE_READ: 
                Log.d(logName + ": Handler", "Received"); 
                if (msg.obj instanceof ObjectCluster) { 
                    ObjectCluster objCluster = (ObjectCluster)msg.obj; 
                      
                    int[] data = new int[0]; 
                    double[] calibratedData = new double[0]; 
                    String[] sensorNames = new String[0]; 
                    String units = "", 
                    calibratedUnits = ""; 
                      
                    sensor = objCluster.mMyName; 
                      
                    // Set up the sensor names 
                    if (sensor.equals("ECG")) { 
                        sensorNames = new String[2];            // Two sensors for ECG 
                        data = new int[2]; 
                        calibratedData = new double[2]; 
                        units = "u12"; 
                          
                        sensorNames[0] = "ECG RA-LL"; 
                        sensorText1.setText(sensorNames[0]); 
                        sensorNames[1] = "ECG LA-LL"; 
                        sensorText2.setText(sensorNames[1]); 
                    } 
                    else if (sensor.equals("EMG")) { 
                        sensorNames = new String[1];            // One sensor for EMG 
                        data = new int[1]; 
                        calibratedData = new double[1]; 
                        units = "u12"; 
                          
                        sensorNames[0] = "EMG"; 
                        sensorText1.setText(sensorNames[0]); 
                        sensorText2.setText("N/A"); 
                    } 
                    else if (sensor.equals("GSR")) { 
                        sensorNames = new String[1];            // One sensor for GSR 
                        data = new int[1]; 
                        calibratedData = new double[1]; 
                        units = "u16"; 
                          
                        sensorNames[0] = "GSR"; 
                        sensorText1.setText(sensorNames[0]); 
                        sensorText2.setText("N/A"); 
                    } 
                      
                    String deviceName = objCluster.mMyName; 
                      
                    // If there are sensor names, which there should be, then go in 
                    if (sensorNames.length != 0) { 
                        // If there is 1 or more sensors then set up the format clusters for sensor 1 
                        if (sensorNames.length > 0) { 
                            Log.d(logName + ": Handler", "Recieved 2"); 
                            Collection<FormatCluster> formatCollection = objCluster.mPropertyCluster.get(sensorNames[0]); 
                            FormatCluster formCluster = (FormatCluster)ObjectCluster.returnFormatCluster(formatCollection, "CAL"); 
                              
                            if (formCluster != null) { 
                                calibratedData[0] = formCluster.mData; 
                                calibratedUnits = formCluster.mUnits; 
                                  
                                data[0] = (int)((FormatCluster)ObjectCluster.returnFormatCluster(formatCollection, "RAW")).mData; 
                            } 
                        } 
                        // If there is 2 or more sensors (ECG), then set up sensor 2  
                        if (sensorNames.length > 1) { 
                            Collection<FormatCluster> formatCollection = objCluster.mPropertyCluster.get(sensorNames[1]); 
                            FormatCluster formCluster = (FormatCluster)ObjectCluster.returnFormatCluster(formatCollection, "CAL"); 
                              
                            if (formCluster != null) { 
                                calibratedData[1] = formCluster.mData; 
  
                                data[1] = (int)((FormatCluster)ObjectCluster.returnFormatCluster(formatCollection, "RAW")).mData; 
                            } 
                        } 
                          
                        // Graph the data 
                        // Reduce the amount of samples to reduce lag, and stop the program from crashing. 
                        int maxSamplesPerSecond = 50; 
                        int subSampleCount = 0; 
                        if (serviceShim.getSamplingRate(btAddress) > maxSamplesPerSecond) { 
                            subSampleCount = (int)(serviceShim.getSamplingRate(btAddress) / maxSamplesPerSecond); 
                            graphSubSampling++; 
                        } 
                        if (graphSubSampling == subSampleCount) { 
                            graphDisplay.setDataWithAdjustments(data, "Shimmer :" + deviceName, units); 
                            //Sensor 1 
                            if (calibratedData.length > 0) { 
                                sensorValues1.setText(String.format("%.4f", calibratedData[0])); 
                                sensorText1.setText(sensorNames[0] + " (" + calibratedUnits + ")"); 
                            } 
                            if (calibratedData.length > 1) { 
                                sensorValues2.setText(String.format("%.4f", calibratedData[1])); 
                                sensorText2.setText(sensorNames[1] + " (" + calibratedUnits + ")"); 
                            } 
                            graphSubSampling = 0; 
                            Log.d(logName + ":Handler", "Received: Finished"); 
                        } 
                    } 
                } 
                break;                      // Break for Shimmer.MESSAGE_READ 
            } 
        } 
    }; 
      
    private ServiceConnection shimmerServiceConnection = new ServiceConnection() { 
        public void onServiceConnected(ComponentName name, IBinder service) { 
            // TODO Auto-generated method stub 
            // Get and connect to the Shimmer Service 
            LocalShimmerBinder binder = (LocalShimmerBinder) service; 
            serviceShim = binder.getService(); 
            Log.d(logName + ": ServiceConnection", "Graph Connected for" + btAddress); 
            serviceBindShimmer = true; 
              
            // Enable the graphing 
            serviceShim.setGraphHandler(handlerShim, btAddress); 
            serviceShim.setGraphingHandlerStatus(true); 
            enabledSensor = serviceShim.getEnabledSensor(btAddress);         
        } 
  
        public void onServiceDisconnected(ComponentName name) { 
            serviceBindShimmer = false; 
              
        } 
          
    }; 
      
      
    //======================================================================================= 
    // Methods 
    //======================================================================================= 
    @Override
    protected void onCreate(Bundle savedInstanceState) { 
        // TODO Auto-generated method stub 
        super.onCreate(savedInstanceState); 
          
        // Get the view items 
        setContentView(R.layout.shimmer_graph_view); 
        graphDisplay = (ShimmerGraphView)findViewById(R.id.shimmerGraph); 
        sensorText1 = (TextView)findViewById(R.id.labelSensor1); 
        sensorText2 = (TextView)findViewById(R.id.labelSensor2); 
        sensorValues1 = (TextView)findViewById(R.id.sensor_value1); 
        sensorValues2 = (TextView)findViewById(R.id.sensor_value2); 
          
        Bundle extras = getIntent().getExtras(); 
        btAddress = extras.getString("Bluetooth Address"); 
        Intent intent = new Intent(ShimmerGraphActivity.this, ShimmerService.class); 
        bindService(intent, shimmerServiceConnection, Context.BIND_AUTO_CREATE); 
        //getWindow().setLayout(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);  //TODO: Don't know if this is needed 
    } 
      
    protected void onDestroy() { 
        super.onDestroy(); 
        unbindService(shimmerServiceConnection); 
    } 
      
    protected void onPause() { 
        super.onPause(); 
        Log.d(logName, "Pausing Graph"); 
        if (serviceBindShimmer) { 
            serviceShim.setGraphingHandlerStatus(false); 
            getApplicationContext().unbindService(shimmerServiceConnection); 
        } 
    } 
      
    protected void onResume() { 
        super.onResume(); 
        Log.d(logName, "Resuming Graph"); 
          
        Intent intent = new Intent(this, ShimmerService.class); 
        getApplicationContext().bindService(intent, shimmerServiceConnection, Context.BIND_AUTO_CREATE); 
    } 
      
} 