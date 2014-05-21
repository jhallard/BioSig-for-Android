package com.jpl_347E.bio_sigapp;  
    
import java.util.ArrayList;  
import java.util.Arrays;  
import java.util.Collection;  
import java.util.HashMap;  
import java.util.Iterator;  
    
  


import com.jpl_347E.bio_sigservices.ShimmerService;  
import com.jpl_347E.bio_sigservices.ShimmerService.LocalShimmerBinder;  
import com.shimmerresearch.driver.Shimmer;  
    
  


import android.app.Activity;  
import android.content.BroadcastReceiver;  
import android.content.ComponentName;  
import android.content.Context;  
import android.content.Intent;  
import android.content.IntentFilter;  
import android.content.ServiceConnection;  
import android.os.Bundle;  
import android.os.Handler;
import android.os.IBinder;  
import android.os.SystemClock; 
import android.util.Log;  
import android.view.View;  
import android.widget.AdapterView;  
import android.widget.AdapterView.OnItemClickListener;  
import android.widget.ArrayAdapter;  
import android.widget.ListAdapter;  
import android.widget.ListView;  
import android.widget.RadioButton;  
import android.widget.TextView;  
import android.widget.Toast;  
    
/**  
 *   
 * @author John Allard and Jose Figueroa.
 * This activity is called when a user selects either GSR/ECG/EMG device from the MainMenu Activity.  
 * This activity has a list of all of the possible actions that can be performed on one of the above listed devices.  
 * They can connect/disconnect a device, start/stop streaming data from a device, calibrate the sensors of a device,  
 * or plot the data coming from a device. The main UI features are a title, a list of menu option, and two radio buttons that  
 * are unclickable, they light up based of whether the specific device is connected and streaming respectively.  
 * It is important to note that this activity gets called on a specific device, so if the user comes to this activity  
 * after selected 'EEG', they cannot connect/stream/plot/calibrate a GSR or EMG device. To do that they must go back to the MainMenu   
 * and select those specific devices.  
 *   
 */
public class ShimmerDevicesMenu extends Activity{  
        
    //=======================================================================================  
    // DATE MEMBERS  
    //=======================================================================================  
    
    private String[] shimmermenuoptions = {"Connect", "Disconnect", "Start Streaming", "Stop Streaming", "Start Logging", "Stop Logging", "Calibrate", "Plot"};  
    private int menuchoice; // int that represent which choice they made on the main menu  
    private final String logName = " Device Options Menu";  
        
    // display objects  
    private TextView titledisplay; // "EMG/ECG/GSR Device Setup" title for the window  
    private AdapterView<ListAdapter> shimmermenu1; // listview for the menu that holds the device options  
    private RadioButton isdeviceconnected; // radio button for device connection  
    private RadioButton isdevicestreaming; // radio button for device streaming  
    private RadioButton isdevicelogging;   // radio button for device logging
        
    //intent extras that are passed from MainMenu.java  
    private String devicename = null;  
    private int deviceid = 0;    
    private String btAddress = "";  
        
    private ShimmerService serviceshim; //  variable through which we will work with the ShimmerService created in mainmenu.java  
        
    private boolean TRYING_TO_CONNECT = false;
    private Handler mHandler = new Handler();
        
        
    private final int MENU_CONNECT = 0;  
    private final int MENU_DISCONNECT = 1; 
    private final int MENU_START_STREAMING = 2;  
    private final int MENU_STOP_STREAMING = 3; 
    private final int MENU_START_LOGGING = 4;
    private final int MENU_STOP_LOGGING = 5;
    private final int MENU_CALIBRATE = 6;  
    private final int MENU_PLOT = 7;  
        
    private final int GET_BT_ADDRESS = 108;  // return code for connectDevice function
    
    
      
 
        
    public static final int ECG_DEVICE = 0,   
            				EMG_DEVICE = 1,   
            				GSR_DEVICE = 2,   
            				EEG_DEVICE = 4;  // currently unused
        
  //=======================================================================================  
    //  MEMBER FUNCTIONS  
  //=======================================================================================  
        
     protected void onCreate(Bundle savedInstanceState) 
     {  
            super.onCreate(savedInstanceState);  
            setContentView(R.layout.activity_shimmer_devices_menu);  
            Log.d(logName,"OnCreate");  
                
            Intent intent = getIntent(); // gets the intent sent to the activity  
            Intent intentShim = new Intent(ShimmerDevicesMenu.this, ShimmerService.class);// intent that will be used to start the 
            																			  // Shimmer service
                
            // binds the shimmer service to this activity  
            bindService(intentShim, shimmerServiceConnection, Context.BIND_AUTO_CREATE);  
                
             // register the shimmer receiver  
             registerReceiver(shimmerReceiver, new IntentFilter("com.jpl_347E.bio_sigapp.ShimmerService"));  
               
               
                
             // below we get all of the 'extra' information passed to us via the intent    
             deviceid = intent.getIntExtra("DeviceType", -1);  
                 
              
            // this next part sets the title of the window to include the name of the shimmer device being worked on  
            if(intent.getStringExtra("LocalDeviceID") != null)  
            {  
                // set the name of the device based on the int ID we receive from the parent  
                switch(deviceid)  
                {  
                    case(ECG_DEVICE):  
                     devicename = "ECG Device";  
                    break;  
                    case(EMG_DEVICE):  
                     devicename = "EMG Device";  
                    break;  
                    case(GSR_DEVICE):  
                     devicename = "GSR Device";  
                    break;  
                    case(EEG_DEVICE):  
                     devicename = "EEG Device";  
                    break;  
                    default:  
                        devicename = "";      
                }// end switch statement  
                Log.d(logName, "ShimmerDevicesMenu: LocalDeviceID registered fine");  
                    
                // set the activity title based on the shimmer device being setup  
                titledisplay = (TextView) findViewById(R.id.shimmerdialogtitle);  
                titledisplay.setText("\t" + devicename + " Setup");  
                
            }  
            else  
                Log.d(logName,"ShimmerDevicesMenu: LocalDeviceID extra entered as NULL");   
                
                
            // fill the list item menu  
             shimmermenu1 = (ListView)findViewById(R.id.shimmerDialogOptions);  
            ArrayList<String> menuOpts = new ArrayList<String>();  
            menuOpts.addAll(Arrays.asList(shimmermenuoptions));  
            ArrayAdapter<String> mO = new ArrayAdapter<String>(this, R.layout.menu_options, menuOpts);  
            shimmermenu1.setAdapter(mO);  
            shimmermenu1.setOnItemClickListener(shimmerMenu1Listener);
            
 
                
            // this next section handles the settings of the two radio  buttons on the bottom of the window  
            // it checks if the device is connected, if it is then the radio button will light up  
            // it checks if the device is currently streaming information, if so the other radio button will light up  
            isdeviceconnected = (RadioButton) findViewById(R.id.rbIsConnected); 
            isdevicestreaming = (RadioButton) findViewById(R.id.rbIsStreaming);
            isdevicelogging =   (RadioButton) findViewById(R.id.rbIsLogging);
         // these next 7 lines initialize the radio buttons  
            if(!isDeviceConnected()) 
            { 
                isdeviceconnected.setText("  " + devicename + " is not Connected");  
                isdeviceconnected.setChecked(false);  
                
                isdevicestreaming.setText("  " + devicename + " is not Streaming");  
                isdevicestreaming.setChecked(false);  
                
                isdevicelogging.setText("  " + devicename + " is not Logging");  
                isdevicelogging.setChecked(false);
            } 
            else
                updateInterface(); 
                   
     }// end onCreate  
         
         
     /**  
      * OnItemClickListener for the menu that is present in this activity.  
      * It holds a switch statement which reacts accordingly to whichever menu  
      * option was chosen. shimmermenu1 is the name of the menu that this   
      * OnItemClickListener is tied too.  
      */
     OnItemClickListener shimmerMenu1Listener = new OnItemClickListener()  
     {  
                
            @Override
            public void onItemClick(AdapterView<?> arg0, View arg1, int position, long arg3)   
            {  
                Object selection = shimmermenu1.getItemAtPosition(position);  
                Log.d(logName, selection.toString());  
                menuchoice = position;  
                    
                switch(menuchoice)  
                {  
                     case(MENU_CONNECT):  
                         connectDevice();  
                     break; 
                            
                     case(MENU_DISCONNECT):  
                         disconnectDevice();  
                     break;  
                         
                     case(MENU_START_STREAMING):  
                         startStreaming(); 
                     break;  
                         
                     case(MENU_STOP_STREAMING):  
                        stopStreaming();  
                     break; 
                        
                     case(MENU_START_LOGGING):
                     	startLogging();
                     break;
                        
                     case(MENU_STOP_LOGGING):
                    	 stopLogging();
                      break;
                            
                     case(MENU_CALIBRATE):  
                         calibrateDevice(deviceid); 
                     break;  
                            
                     case(MENU_PLOT):  
                         plotData(); 
                     break;   
                            
                 }// end switch statement on menuchoice  
                    
            }// end onItemClick   
        };// end onClickListener       
         
            
            
     /**   
      * This function gets called when the user clicks the "Connect"  
      * option from the menu of options. This function will look for any devices in range  
      * with the same name as the 'devicename' that this activity is working on (GSR_Device, EMG_Device, etc)  
      * If we can successfully create, we will then update the interface.  
      * */
     private void connectDevice()  
     {  
        if(!isDeviceConnected())  
        {  
        	if(!TRYING_TO_CONNECT)
        	{
        		// start the bluetooth devices dialog window
        		Intent connectIntent = new Intent(ShimmerDevicesMenu.this, ConnectShimmerDialog.class);  
        		connectIntent.putExtra("devicename", devicename);  
        		startActivityForResult(connectIntent, GET_BT_ADDRESS); 
        	}
        	else
        		Toast.makeText(ShimmerDevicesMenu.this, "Currently trying to Connect..", Toast.LENGTH_SHORT).show();  
        }  
        else
            Toast.makeText(ShimmerDevicesMenu.this, devicename +" is already connected", Toast.LENGTH_SHORT).show();  
         
     }// end function connectDevice()  
         
         
     /**  
      * this function is called when the user wants to disconnect the device.  
      * This could happen if the user choosen the wrong shimmer  
      */
     private void disconnectDevice()  
     {   
         if(isDeviceConnected())  
         {
             serviceshim.disconnectShimmer(btAddress); 
             serviceshim.stopStreaming(btAddress);
             serviceshim.setLogging(false, btAddress);
         }
         else
        	 Toast.makeText(ShimmerDevicesMenu.this, " No device to disconnect ", Toast.LENGTH_SHORT).show();  
         
         SystemClock.sleep(200);
         updateInterface();  
     }  
       
     /** 
      *  This function gets called when the user hits the 'Start Streaming' button on the menu of this activity. 
      *  This will start the logging of the data coming frmo the specific shimmer device that this menu is made for  
      */
       
     private void startStreaming() 
     { 
         if(isDeviceConnected()) 
         { 
             if(!isDeviceStreaming()) 
             {
            	 serviceshim.startStreaming(btAddress);
            	 Log.d(logName, "ShimmerDevicesMenu: " + devicename + " started streaming"); 
             }
             else
                 Toast.makeText(ShimmerDevicesMenu.this, devicename + " is already streaming", Toast.LENGTH_SHORT).show(); 
               
         }// end if is the device is connected 
         else
             Toast.makeText(ShimmerDevicesMenu.this, " Must Connect a Device first", Toast.LENGTH_SHORT).show(); 
         
         SystemClock.sleep(100);
         updateInterface();   
     } 
       
       
       
       
     /** 
      *  This function gets called when the user hits the 'Stop Streaming' button on the menu of this activity. 
      *  This will only be called if the device is currently streaming, and it will stop the streaming of data from the 
      *  currently connected device. 
      */
       
     private void stopStreaming() 
     { 
         if(isDeviceConnected()) 
         { 
             if(isDeviceStreaming()) 
             {  
                 // since we can't save data that we aren't streaming, set the logging to false
                 if(isDeviceLogging())
                	 serviceshim.setLogging(false, btAddress); 
                 
                 serviceshim.stopStreaming(btAddress);
                 Log.d(logName, "ShimmerDevicesMenu: " + devicename + " stopped streaming"); 
             } 
             else
                 Toast.makeText(ShimmerDevicesMenu.this, devicename + " is not streaming", Toast.LENGTH_SHORT).show();                
         }// end if is the device is connected 
         else
             Toast.makeText(ShimmerDevicesMenu.this, " Must Connect a Device first", Toast.LENGTH_SHORT).show(); 
         
         SystemClock.sleep(200); 
         updateInterface(); 
     } 
     
     /**
      * This function is called when the user selects 'Start Logging' from the device menu. When pressed, we will start
      * streaming data from the device if it is not already streaming data. We will then save data to a text file on the users
      * phone or tablet.
      * TODO: make logging compatible with the profiles
      */
     private void startLogging()
     {
    	if(isDeviceConnected())
    	{
    		if(!isDeviceStreaming()) // to log data we must first be streaming the data
    			startStreaming();
    	
    		if(!isDeviceLogging())
    			serviceshim.setLogging(true, btAddress);
    	}
    	 
    	updateInterface();
     }
     
     /**
      * This function stops the application from logging data from a shimmer device to a text file
      */
     private void stopLogging()
     {
    	if(isDeviceConnected())
    	{
    		if(isDeviceLogging())
    			serviceshim.setLogging(false, btAddress);
    	}
    	updateInterface();
     }
     
     /**
      * This function gets called from the ShimmerDevices Menu when the user selects the calibrate option.
      * The different shimmer devices get calibrated differently, so this function will start different 
      * calibration dialog windows depending on which shimmer device menu (ECG, EMG, GSR) we are currently at
      * @param deviceCode - This represents which device the menu is currently working with. This determines which calibration
      * menu pops up when the user selects the calibrate option from the menu.
      */
     private void calibrateDevice(int deviceCode)
     {
    	 if(isDeviceConnected())
    	 {
    	   //if(!isDeviceStreaming())
    		//  startStreaming();// the device must be streaming in order to calibrate data
    		 switch(deviceCode)
    		 {
    		 	case(ECG_DEVICE):
    			 	Intent calibrateIntent = new Intent(ShimmerDevicesMenu.this, ECGCalibrateDialog.class);  
    			 	calibrateIntent.putExtra("devicename", devicename);
    			 	calibrateIntent.putExtra("btAddress", btAddress);
    			 	startActivity(calibrateIntent); 
    	 		break;// end case calibrate ECG DEVICE
    	 
    		 	case(EMG_DEVICE):
    		 		//TODO: add dialog activity to calibrate EMG Device
    		 	break;// end case calibrate EMG DEVICE
    	 
    		 	case(GSR_DEVICE):
    		 		//TODO: add dialog activity to calibrate GSR Device
    		 	break;// end case calibrate GSR Device
    		 }// end switch statement
    	 }// end if device is connected
    	 else
    		 Toast.makeText(ShimmerDevicesMenu.this, "Must Connect A Device First", Toast.LENGTH_SHORT).show();
    	 
     }// end function calibrate
     
         
     /**
      *  	Make a simple graph of the data that is being plotted by the currently selected device.
      */
     private void plotData()
     {
    	 if(isDeviceConnected())
    	 {
    		 if(!isDeviceStreaming())
    			serviceshim.startStreaming(btAddress); 
    		 
    		 Intent graphIntent = new Intent(ShimmerDevicesMenu.this, ShimmerGraphActivity.class );
    		 graphIntent.putExtra("Bluetooth Address", btAddress);
    		 startActivity(graphIntent); 
    	 }
    	 else
    		 Toast.makeText(ShimmerDevicesMenu.this, " Must connect device first", Toast.LENGTH_SHORT).show();
     }
     
     
     /**  
      * This next function gets called automatically when the dialog windows  
      * that correspond to each menu choice are closed and the view  
      * returns to this activity  
      * @param requestCode the requestCode that was sent to the dialog window  
      * @param resultCode either success or failure code, lets us know if the action failed or succeeded  
      * @param data the intent that gets passed back from the dialog window  
      */
     protected void onActivityResult(int requestCode, int resultCode, Intent data)  
     {  
 
    	 TRYING_TO_CONNECT = true;
    	 updateInterface();
 
         if ((resultCode == RESULT_OK && serviceshim != null) && data.getStringExtra("device_address") != "")  
            {  
                 btAddress = data.getStringExtra("device_address");
                 serviceshim.connectShimmer(btAddress, deviceid);         
            }  
         
         //we use this postDelayed to wait a few seconds for the device to connect before we update the UI
         // we do it this way so as to not freeze the UI while still waiting for the shimmer device to connect
         mHandler.postDelayed(new Runnable() {
             public void run() {
                 TRYING_TO_CONNECT = false;
                 updateInterface();
             }
         }, 25);
     
   
     }// end onActivityResult  
         
         
         
    /**   
     * this function get called to update the radio buttons  
     * and text based on if the device is connected/streaming/logging   
    */
    private void updateInterface()   
    {  
    	String middleword; // this string is either empty or contains the word 'not', used the change "Device is Connected" to "Device is not Connected"
            
            if(serviceshim != null)  
            {  
            	// if we are not currently trying to connect then we will update via this method
            	if(!TRYING_TO_CONNECT)
            	{ //
            		middleword = (isDeviceConnected())? "" : "not ";
            		isdeviceconnected.setText("  Device is " + middleword + "Connected"); 
            		isdeviceconnected.setChecked(isDeviceConnected());
            	
            		
            		middleword = (isDeviceStreaming())? "" : "not ";
            		isdevicestreaming.setText("  Device is " + middleword + "Streaming"); 
            		isdevicestreaming.setChecked(isDeviceStreaming());
            		
           			middleword = (isDeviceLogging())? "" : "not ";
           			isdevicelogging.setText("  Device is " + middleword + "Logging"); 
           			isdevicelogging.setChecked(isDeviceLogging());	
            	}
            	else 
            	{
            		// else we are currently trying to connect a shimmer in the background and we will inform the user of such
            		isdeviceconnected.setText("  Connecting Device..."); 
            		isdeviceconnected.setChecked(false);
            	}
            }// end if serviceshim not null  
            else 
                Log.d(logName,"ShimmerDevicesMenu: ShimmerService came back null when trying to updateInterface()");  
                
    }// end function updateInterface()  
         
         
    
    /**  
     * When the activity is paused, we unbind the service and unregister the reveiver  
     */
     protected void onPause() {  
            super.onPause();  
                 
            unregisterReceiver(shimmerReceiver);  
            //TODO: unregister eeg receiver  
            if (serviceshim != null)  
                getApplicationContext().unbindService(shimmerServiceConnection);  
            //TODO: Same style if for eeg bind  
        }  
         
/**  
 * onResume() function. This will restart the service and check to see if any  
 * connections were made  
 *   
 */
        protected void onResume() {  
            super.onResume();  
            Log.d(logName, "On Resume");  
                 
            Intent intentShim = new Intent(ShimmerDevicesMenu.this, ShimmerService.class);  
            registerReceiver(shimmerReceiver, new IntentFilter("com.jpl_347E.bio_sigapp.ShimmerService"));  
            getApplicationContext().bindService(intentShim, shimmerServiceConnection, Context.BIND_AUTO_CREATE);  
            startService(intentShim);  
              
            updateInterface(); 
                 
            //TODO: Do same for EEG service  
        }  
        
    /**  
     * When the activity is destroyed, we unbind the service and receiver.  
     * We also set the result that gets sent back to the parent window.  
     */
        protected void onDestroy()  
        {  
            super.onDestroy();  
            Log.d(logName,"On Destroy");  
           
            unbindService(shimmerServiceConnection);   
        }  
         
            
     /** 
      *  Binds the ShimmerService to this activity  
      *  Specifically Binds the ShimmerService to  
      */
     private ServiceConnection shimmerServiceConnection = new ServiceConnection() {    
    	 
            public void onServiceConnected(ComponentName arg0, IBinder arg1) {  
            	
                Log.d(logName,"ShimmerDevicesMenu: ServiceConnection called");  
                LocalShimmerBinder binder = (LocalShimmerBinder)arg1;  
                serviceshim = binder.getService();  

                if(serviceshim == null)  
                    Log.d(logName, "ShimmerDevicesMenu: Unable to bind shimmer service");  
                updateInterface();    
            }  
    
            @Override
            public void onServiceDisconnected(ComponentName arg0) {  
            serviceshim = null;  
            }  
    
     };  
         
     /**Broadcast receiver for the shimmer service   
      * Updates the radio buttons and text when it get the signal  
      * that a new shimmer state has occurred  
      * */
     private BroadcastReceiver shimmerReceiver = new BroadcastReceiver() {  
            @Override
            public void onReceive(Context context, Intent intent) {   
                updateInterface();  
            }     
        };  
          
        /** 
         * this function determines if the specific shimmer device is connected to the shimmer service. It has it's own function 
         * because the fucntionality gets used so many times 
         * @return 
         */
        private boolean isDeviceConnected() 
        { 
          
             if(serviceshim != null)  
             {  
                     // if the user returns from the Bluetooth finder then we will have the btAddress  
                     // of the device already and we can set the radio button without having to create  
                     // a list of the connected shimmer devices through the hashMapShimmer  
                     if(btAddress != "" && serviceshim.isDeviceConnected(btAddress))  
                         return true; 
                     
                     else
                     {  
                         HashMap<String, Object> temp = serviceshim.hashMapShimmer;  
                         Collection<Object> shimmers = temp.values();  
                         Iterator<Object> iterator = shimmers.iterator();  
                         while(iterator.hasNext())  
                         {  
                             Shimmer sTemp = (Shimmer)iterator.next();  
                             int sensor = sTemp.getEnabledSensors();  
                         
                             if((sensor == Shimmer.SENSOR_ECG && deviceid == ECG_DEVICE)  
                             || (sensor == Shimmer.SENSOR_EMG && deviceid == EMG_DEVICE)  
                             || (sensor == Shimmer.SENSOR_GSR && deviceid == GSR_DEVICE))  
                             {  
                                 btAddress = sTemp.getBluetoothAddress(); 
                                 return true;     
                             }  
                             //TODO: add section for EEG device  
                         }// end while loop
                         
                     }//end else statement  
     
                 }// end if serviceshim not null 
             return false; 
          
        }// end function isDevicConnected() 
          
          
        /** 
         * this function determines if the specific shimmer device is currently streaming. 
         * @return boolean thats true if the device is streaming and false if not 
         */
        private boolean isDeviceStreaming() 
        {  
             return (isDeviceConnected() && serviceshim.isDeviceStreaming(btAddress)); 
        } 
        
        
        /**
         * This function determines if the current device is having its data logged to a file.
         * @return boolean that corresponds to if the device is logging data currently
         */
        private boolean isDeviceLogging()
        {	
        	if(isDeviceConnected())
        	   return (serviceshim.getLogging(btAddress));
        	
        	return false;
        }
            
            
}// end shimmer devices menu class