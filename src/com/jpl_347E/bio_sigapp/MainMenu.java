package com.jpl_347E.bio_sigapp;
 
 
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
 
import com.jpl_347E.bio_sigservices.ShimmerService;
import com.jpl_347E.bio_sigservices.ShimmerService.LocalShimmerBinder;
import com.shimmerresearch.driver.Shimmer;
 
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
 
 
public class MainMenu extends Activity {
    //=======================================================================================
    // Data Members
    //=======================================================================================
    private final String logName = "BioSig-MainMenu";
     
    private String[] menuOptions1 = {"EEG Device", "ECG Device", "EMG Device", "GSR Device"},
                     menuOptions2 = {"Monitor State"},
                     menuOptions3 = {"Change Subject ID"};
    private int menu1SelectedSlot = -1,                                                             // Menu1 option that was chosen
                menu2SelectedSlot = -1;                                                             // Same as menu1 variable
    private ShimmerService serviceShim;
    //private EEGService     serviceEEG;
    public static final int REQUEST_BT_ENABLE = 1,
                            REQUEST_SHIMMER_DEVICES_MENU = 2,
                            REQUEST_SUBJECT_ID_MENU = 3,
                            REQUEST_GRAPHING = 7;
    public boolean serviceBindShimmer = false,
                   serviceBindEEG = false,
                   serviceFirstTimeEEG = true,
                   serviceFirstTimeShimmer = true;
    private TextView subID;
    private String subjectID;
     
    //=======================================================================================
    // Methods
    //=======================================================================================
    /**Create a directory to store the log files.
     * 
     * @param path      The directory to create.
     * @return          True if the directory was created, false otherwise.
     */
    private boolean createDirectory(String path) {
        File file = new File(Environment.getExternalStorageDirectory(), path);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                Log.d(logName + ": createDirectory", "Problem creating directory");
                return false;
            }
            Log.d(logName + ": createDirectory", "Directory Created");
        }
        return true;
    }
     
    /**Finds out if the Shimmer Service is running.
     * 
     * @return      True if the service is running, false otherwise.
     */
    private boolean isShimmerServiceRunning() {
        ActivityManager manager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.service.getClassName().equals("com.jpl_347E.bio_sigapp.ShimmerService"))
                return true;
        }
        return false;
    }
     
    /**Performs certain operations dependent on the request code.
     * @param requestCode       The request code that the child activity was given on initialization.
     * @param resultCode        The result code that the child activity terminated with.
     * @param data              The intent from the child activity.
     */
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch(requestCode) {
        case REQUEST_SUBJECT_ID_MENU:
            switch(resultCode) {
            case Activity.RESULT_OK:
                subjectID = data.getStringExtra("Subject ID");
                 
                // Stop streaming since the subject has been changed
                serviceShim.stopStreamingAllDevices();
                 
                serviceShim.setSubjectId(subjectID);
                subID.setText("Subject ID: " + subjectID);
                //break;                        // End of REQUEST_SUBJECT_ID_MENU
            }
        }
    }
 
    /**Initializes the main menu. It activates the main menu window, and begins the Shimmer Service.
     * If the Bio_Sig directory does not exist, it will call the function to create the directory. If
     * an error occurs while creating this directory, the program will quit. The receiver that captures messages
     * from the Shimmer Service is registered. Then the menu options lists are populated. Finally,
     * the listeners for the menu options are initialized.
     */
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_menu);
        Log.d(logName,"OnCreate");
         
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!isShimmerServiceRunning()) {
            Log.d(logName + ": OnCreate", "Start Shimmer Service");
            Intent intent = new Intent(this, ShimmerService.class);
            startService(intent);
            if (serviceFirstTimeShimmer) {
                Log.d(logName + ": OnCreate", "Bind Shimmer Service");
                getApplicationContext().bindService(intent, shimmerServiceConnection, Context.BIND_AUTO_CREATE);
                serviceFirstTimeShimmer = false;
            }
        }
         
        // Create the directory for logging if needed
        if (!createDirectory(ShimmerService.PATH)) {
            Toast.makeText(this, "Error creating directory.\nExiting...", Toast.LENGTH_LONG);
            finish();
        }
         
        registerReceiver(shimmerReceiver, new IntentFilter("com.jpl_347E.bio_sigapp.ShimmerService"));
 
        // Set up the main menu 1 (Individual Devices)
        Log.d(logName, "OnCreate-MenuSetup");
        final ListView menu1 = (ListView)findViewById(R.id.deviceView1);
        ArrayList<String> menuOpts = new ArrayList<String>();
        menuOpts.addAll(Arrays.asList(menuOptions1));
        ArrayAdapter<String> mO = new ArrayAdapter<String>(this, R.layout.menu_options, menuOpts);
        menu1.setAdapter(mO);
         
        // Set up main menu 2 (monitor State)
        final ListView menu2 = (ListView)findViewById(R.id.deviceView2);
        menuOpts = new ArrayList<String>();
        menuOpts.addAll(Arrays.asList(menuOptions2));
        mO = new ArrayAdapter<String>(this, R.layout.menu_options, menuOpts);
        menu2.setAdapter(mO);
         
        // Set up the main menu 3 (Subject ID and Exit)
        subID = (TextView)findViewById(R.id.showSubjectID);
         
        final ListView menu3 = (ListView)findViewById(R.id.subIDandExit);
        menuOpts = new ArrayList<String>();
        menuOpts.addAll(Arrays.asList(menuOptions3));
        mO = new ArrayAdapter<String>(this, R.layout.menu_options, menuOpts);
        menu3.setAdapter(mO);
         
        //Bluetooth Adapter Initialization
        Log.d(logName, "OnCreate-BluetootInit");
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        //Does device support bluetooth? if no, then quit
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported\nExiting...", Toast.LENGTH_LONG).show();
            finish();
        }
        //Is bluetooth running? if no, then enable it
        if (!btAdapter.isEnabled()) {
            Intent enableBT = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBT, REQUEST_BT_ENABLE);
        }
         
        // Selecting a menu option
        menu1.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int position,
                    long arg3) {
                Object selection = menu1.getItemAtPosition(position);
                Log.d(logName, selection.toString());
                menu1SelectedSlot = position;
                // Handle the case when ECG, EMG, and GSR are selected
                if (position > 0) {
                    Intent shimDevicesMenuIntent = new Intent(MainMenu.this, ShimmerDevicesMenu.class);
                    shimDevicesMenuIntent.putExtra("LocalDeviceID", selection.toString());
                    shimDevicesMenuIntent.putExtra("DeviceType", position - 1);
                    shimDevicesMenuIntent.putExtra("requestCode", REQUEST_SHIMMER_DEVICES_MENU);
                    startActivity(shimDevicesMenuIntent);
                }
                 
            }   
        });
         
        menu2.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> arg0, View arg1, int position,
                    long arg3) {
                Object selection = menu2.getItemAtPosition(position);
                Log.d(logName, selection.toString());
                menu2SelectedSlot = position;
                //TODO: Create intent once monitoring class has been created
            }   
        });
         
        menu3.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> arg0, View arg1, int position,
                    long arg3) {
                Object selection = menu3.getItemAtPosition(position);
                Log.d(logName, selection.toString());
                Intent intent = new Intent(MainMenu.this, SubjectIDMenu.class);
                intent.putExtra("Subject ID", subjectID);
                startActivityForResult(intent, REQUEST_SUBJECT_ID_MENU);
                }       
        });
         
    }
     
    /**Called when the main menu window is destroyed. This usually happens when the application
     * is terminated.
     */
    protected void onDestroy() {
        /*unregisterReceiver(shimmerReceiver);
        if (serviceBindShimmer)
            getApplicationContext().unbindService(shimmerServiceConnection);*/
        super.onDestroy();
        Intent intent = new Intent(this, ShimmerService.class);
        stopService(intent);
        finish();
    }
     
    /**Pauses the application window. This occurs when the user goes to another window in the app,
     * or to another application, but the app is still running. It unregisters the receiver and unbinds the Shimmer service.
     * 
     */
    protected void onPause() {
        super.onPause();
         
        unregisterReceiver(shimmerReceiver);
        //TODO: unregister eeg receiver
        if (serviceBindShimmer)
            getApplicationContext().unbindService(shimmerServiceConnection);
        //TODO: Same style if for eeg bind
    }
     
    /**
     * Called when the window is resumed. This occurs when the window becomes active; either after it has been created or
     * it returns from another window or app.
     */
    protected void onResume() {
        super.onResume();
        Log.d(logName, "On Resume");
         
        Intent intentShim = new Intent(MainMenu.this, ShimmerService.class);
        registerReceiver(shimmerReceiver, new IntentFilter("com.jpl_347E.bio_sigapp.ShimmerService"));
        getApplicationContext().bindService(intentShim, shimmerServiceConnection, Context.BIND_AUTO_CREATE);        
        //TODO: Do same for EEG service
    }
 
    /**
     * Variable that updates the menu when a Shimmer is connected to show which sensor is connected.
     */
    private BroadcastReceiver shimmerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra("ShimmerState", -1) != -1)
                updateMenu1();
        }   
    };
     
    //TODO: create broadcast receiver for EEG service
    //TODO: create service connection for EEG
     
    /**Handles the connection to the Shimmer Service. It allows the activity to communicate
     * with the Shimmer service.
     */
    private ServiceConnection shimmerServiceConnection = new ServiceConnection() {
         
        /**
         * Retrieves the binder from the Shimmer service. This allows for the communication
         * between the activity and the service.
         */
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            LocalShimmerBinder binder = (LocalShimmerBinder)arg1;
            serviceShim = binder.getService();
            serviceBindShimmer = true;
             
            //Update menu 1
            updateMenu1();
        }
         
        /**
         * Just states that the activity no longer has a bind to the service.
         */
        public void onServiceDisconnected(ComponentName name) {
            serviceBindShimmer = false;
        }
         
    };
 
    /**
    *Updates menu1's list to show the Shimmer device connected to that specific list item.
    * Only for ECG, EMG, or GSR since EEG uses audio jack.
    */
    public void updateMenu1() {
        menuOptions1 = new String[] {"EEG Device", "ECG Device", "EMG Device", "GSR Device"};
        HashMap<String, Object> temp = serviceShim.hashMapShimmer;
        Collection<Object> shimmers = temp.values();
        Iterator<Object> iterator = shimmers.iterator();
         
        //TODO: Change EEG device to show that it is plugged in
         
        // Change the menu options for 
        while(iterator.hasNext()) {
            Shimmer sTemp = (Shimmer)iterator.next();
            int sensor = sTemp.getEnabledSensors();             // Get the device name
            if (sensor == Shimmer.SENSOR_ECG)                   // If sensor is ECG then change its name
                menuOptions1[1] += (" (Connected)");
            else if (sensor == Shimmer.SENSOR_EMG)
                menuOptions1[2] += (" (Connected)");        // If sensor is EMG then change its name
            else if (sensor == Shimmer.SENSOR_GSR)
                menuOptions1[3] += (" (Connected)");        // if sensor is GSR then change its name                
        }
         
        // Update the menu
        final ListView menuOps = (ListView)findViewById(R.id.deviceView1);
        ArrayList<String> list = new ArrayList<String>();
        list.addAll(Arrays.asList(menuOptions1));
        ArrayAdapter<String> ls = new ArrayAdapter<String>(this, R.layout.menu_options, menuOptions1);
        menuOps.setAdapter(ls);
         
        // Update Subject ID
        subjectID = serviceShim.getSubjectID();
        subID.setText("Subject ID: " + subjectID);
    }
}