package com.jpl_347E.bio_sigapp;

import java.util.ArrayList;
import java.util.Arrays;




import java.util.Collection;

import com.jpl_347E.bio_sigservices.ShimmerService;
import com.jpl_347E.bio_sigservices.ShimmerService.LocalShimmerBinder;
import com.shimmerresearch.driver.FormatCluster;
import com.shimmerresearch.driver.ObjectCluster;
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
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 
 * @author John Allard and Jose Figeuroa
 * This class is an activity that gets called when the user selects the "Calibrate" option from the main menu in the
 * ShimmerDevicesMenu activity. This specific activity is only called when the user is on the ShimmerDevicesMenu for the
 * ECG shimmer, and thus this activity is used to calibrate the connected ECG shimmer
 *
 */
public class ECGCalibrateDialog extends Activity {
	
	String[] OffsetList = {"RA-LL Offset", "LA-LL Offset"};
	String[] GainList = {"RA-LL Gain Calibration", "LA-LL Gain Calibration"};
	int menuchoice = -1;
	
	// these defines are used to determine what part of the calibration process we are currently on
	private final static int RALLOFFSET = 0;
	private final static int LALLOFFSET = 1;
	private final static int RALLGAIN   = 2;
	private final static int LALLGAIN   = 3;
	private static int CalibrationStep  = RALLOFFSET;
	
	private static boolean iscalibrating = false;
	
	private static int OFFSETINDEX = 0; // this holds the current index of the OFFSET data arrays that we will be putting data into
										// this gets incremented once every time we get a message with OFFSet data
	private static boolean userready = false; // this is true when the user is ready to send data to be calibrated
	
	String btAddress = null;
	
	private ShimmerService serviceshim; //  variable through which we will work with the ShimmerService created in mainmenu.java 
	
	private final String[] subtitletext = {" RA-LL Offset Calibration", "LA-LL Offset Calibration", "RA-LL Gain Calibration", "LA-LL Gain Calibration"};
	private final String[] instructionstext = {"Please hold the RA and LL sensors together, then click 'Calibrate'",
											   "Please hold the LA and LL sensors together, then click 'Calibrate'",
											   "Please ",
											   "Please "};
	
	// final calibration data
	double RALLOffsetValue = 0;
	double LALLOffsetValue = 0;
	double RALLGainValue   = 0;
	double LALLGainValue   = 0;
	
	// Arrays of data that we get from the shimmers 
	// that is used to properly calibrate the devices
	private static int[] RALLOffsetArray;
	private static int[] LALLOffsetArray;
	private static int[] RALLGainArray;
	private static int[] LALLGainArray;
	
	
	//UI Elements below
	TextView titledisplay;       // displays the window title
	TextView subtitledisplay;    // displays the window subtitle
	TextView instructionsdisplay; // displays the instructions on how to calibrate
	Button calibratebutton;
	ProgressBar calibrateprogress;
	static String logName = "ECGCalibrationMenu";

	
	protected void onCreate(Bundle savedInstanceState) {
        
		super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calibrate_ecg);
        Log.d(logName,"OnCreate");
        
        // this gets the intent sent to this activity
        Intent activityintent = getIntent();
        // store the btAddress of the device we are calibrating
        btAddress = activityintent.getStringExtra("btAddress");
        
        
        // find and bind the shimmer service to this activity
        Intent intentShim = new Intent(ECGCalibrateDialog.this, ShimmerService.class);// intent that will be used to start the 
        // binds the shimmer service to this activity  
        bindService(intentShim, shimmerServiceConnection, Context.BIND_AUTO_CREATE);  
        // register the shimmer receiver  
        registerReceiver(shimmerReceiver, new IntentFilter("com.jpl_347E.bio_sigapp.ShimmerService"));  

        // this sets the title of the page
        setTitle( " ECG Device Calibration");
        
        // displays the subtitle that shows what step we are currently on
        subtitledisplay = (TextView) findViewById(R.id.currentstep);
        subtitledisplay.setText(subtitletext[CalibrationStep]);
        
        // displays the instructions on how to calibrate the current step
        instructionsdisplay = (TextView) findViewById(R.id.currentinstructions);
        instructionsdisplay.setText(instructionstext[CalibrationStep]);
        
        //calibrateprogress = (ProgressBar) findViewById(R.id.progressBar1);
        
        
        
        
        calibratebutton = (Button) findViewById(R.id.calibratebutton);
        calibratebutton.setOnClickListener(CalibrateButtonListener);

 
        
	}// end on create
	
	OnClickListener CalibrateButtonListener = new View.OnClickListener() 
	{
        public void onClick(View v) 
        {
        	
        	switch(CalibrationStep)
        	{
        		case(RALLOFFSET):
        			calibratebutton.setText("Calibrating..");
        			userready = true;
        			iscalibrating = true;
        			serviceshim.enableCalibration(handlerShim);
        			serviceshim.startStreaming(btAddress);
        			if(!(OFFSETINDEX<20)) // if this is true then we are done getting RALLOFFSET data from the handler
        			{
        				iscalibrating = false;
        				CalibrationStep = LALLOFFSET; // set this variable to indicate we are going to the next calibration step\
        				calibrateRALLOffset();
        				OFFSETINDEX = 0;
        			}
        			//serviceshim.disableCalibrating();
        		break;
        	
        		case(LALLOFFSET):
        			serviceshim.enableCalibration(handlerShim);
        			serviceshim.startStreaming(btAddress);
        			userready = true;
        			if(calibrateLALLOffset())
        				CalibrationStep = RALLGAIN;
        			else
        				Toast.makeText(ECGCalibrateDialog.this, "LA-LL Offset Calibration Failed", Toast.LENGTH_LONG).show();
        			serviceshim.disableCalibrating();
        		break;
        	
        		case(RALLGAIN):
        			
        		break;
        	
        		case(LALLGAIN):
        			
        		break;
        	
        		
        	
        	}//end swtich
        	
        	// change the button back to let the user know they can calibrate the next sensor
			//calibratebutton.setText("Calibrate");
			//userready = false;
			// change the titles and instructions to the next calibration step
			subtitledisplay.setText(subtitletext[CalibrationStep]);
			instructionsdisplay.setText(instructionstext[CalibrationStep]);

        }
    };
	
	
	/**
	 * This function is called to calibrate the offset of the RA-LL sensors on the EMG device
	 * @return true or false that lets the caller know if the calibration succeeded or not
	 */
	private void calibrateRALLOffset() 
	{
		// here is where we will average the values foudn in the RALLOFFSET array and find the offset value for the RALL sensors
	}
	
	/**
	 * This function is called to calibrate the offset of the LA-LL sensors on the EMg device
	 * @return true or false that lets the caller know if the calibration succeeded or not
	 */
	private boolean calibrateLALLOffset()
	{
		OFFSETINDEX = 0;
		long time = System.currentTimeMillis();
		while(OFFSETINDEX < 20 && System.currentTimeMillis() < time+5000)
		{
			// used to wait for the data to come in from the handler
		}
		return true;
	}
	
	/**
	 * This function is called to calibrate the gain of the RA-LL sensors on the EMG device
	 * @return true or false that lets the user know whether or not the gain calibration succeeded
	 */
	private boolean calibrateRALLGain()
	{
		return true;
	}
	
	/**
	 * This function is called to calibrate the gain of the LA-LL sensors on the EMG device
	 * @return true or false that lets the user know whther or not the gain calibration succeeded
	 */
	private boolean calibrateLALLGain()
	{
		return true;
	}
	
	
	/**Broadcast receiver for the shimmer service   
     * Updates the radio buttons and text when it get the signal  
     * that a new shimmer state has occurred  
     * */
    private BroadcastReceiver shimmerReceiver = new BroadcastReceiver()
    {  
           @Override
           public void onReceive(Context context, Intent intent)
           {   
               //TODO::
           }     
       }; 
       
       /** 
        *  Binds the ShimmerService to this activity  
        *  Specifically Binds the ShimmerService to  
        */
       private ServiceConnection shimmerServiceConnection = new ServiceConnection() 
       {    
              public void onServiceConnected(ComponentName arg0, IBinder arg1)
              {  
                  Log.d(logName,"ShimmerDevicesMenu: ServiceConnection called");  
                  LocalShimmerBinder binder = (LocalShimmerBinder)arg1;  
                  serviceshim = binder.getService();  

                  if(serviceshim == null)  
                      Log.d(logName, "ECGCalibrateMenu: Unable to bind shimmer service");     
              }  
      
              @Override
              public void onServiceDisconnected(ComponentName arg0) {  
              serviceshim = null;  
              }  
      
       };  
       
       
       
       private static Handler handlerShim = new Handler() { 
           public void handleMessage(Message msg) { 
               switch (msg.what) 
               { 
               
               		case(Shimmer.MESSAGE_READ):
               		{	
               			if (msg.obj instanceof ObjectCluster && userready == true)
               			{ 
               				ObjectCluster objCluster = (ObjectCluster)msg.obj;
               				switch(CalibrationStep)
               				{
                        	 
               					case(RALLOFFSET):
               						if(iscalibrating)
               						{
               							Collection<FormatCluster> formatCollection = objCluster.mPropertyCluster.get("ECG RA-LL"); 
               							RALLOffsetArray[OFFSETINDEX] = (int)((FormatCluster)ObjectCluster.returnFormatCluster(formatCollection, "RAW")).mData;
               							Log.d(logName,"Data - " + RALLOffsetArray[OFFSETINDEX]);
               							OFFSETINDEX++;
               						}
               					break;
               					
               					case(RALLGAIN):
                        		
               					break;
               					
               					case(LALLOFFSET):
               						Collection<FormatCluster> formatCollection1 = objCluster.mPropertyCluster.get("ECG LA-LL"); 
           							LALLOffsetArray[OFFSETINDEX] = (int)((FormatCluster)ObjectCluster.returnFormatCluster(formatCollection1, "RAW")).mData;
           							OFFSETINDEX++;
               					break;
                        	
               					case(LALLGAIN):
                        		
                        		break;
                        	
               					default:
               						
                        		break;
                        		
                        
               				} // end inner switch statement
               		
               			
               			}// end if statement
               			
               		}// end case MSG_READ
               		break;
               }// end switch on msg
           }
       };// end handler 
       
       
       /**
        *  unregister the service and unbind it from this activity
        *  reset the calibration step to the first one
        */
       protected void onDestroy()
       {	
    	   super.onDestroy();
    	   unregisterReceiver(shimmerReceiver);
    	   unbindService(shimmerServiceConnection);
    	   CalibrationStep = RALLOFFSET;
       }
       

}// end class
