//1.0.2

/*Changes since beta 0.9 (1 January 2013)
 * 
 * - Packet Reception Rate is now provided, whenever a packet loss detected a message is sent via handler, see MESSAGE_PACKET_LOSS_DETECTED
 * - Changed Accel cal parameters
 * - Added default cal parameters for the other accel ranges, if default accel range is being used, changing the accel range will change the defaults automatically as well
 * - Revised the GSR calibration method, now uses a linear fit
 * - Batt Voltage Monitoring
 * - Sensor Conflict checks, have to wire the handler in order to see the msgs
 * - Bug fix, timer wasnt triggering when waiting for response which was not received, causing the driver to get stuck in a loop
 * - Added retrieve all,ecg & emg calibration parameters, only works with Boilerplate >= 1.0
 * - Rearranged the data reception section, to accommodate for in streaming ack detection
 * - Added uncalibrated heart rate, which is a pulse now, with the value being the time difference between the last pulse and the current one
 * - Updated the name of the formats, units and property names, so as to stay consistent with the rest of the instrument drivers
 * - Low Battery Voltage warning at 3.4V where LED turns yellow
 * - Added Packet Reception Rate monitoring
 * - Added MESSAGE_NOT_SYNC for MSS support
 * - Updated the initialization process, if a connection fails during the initialization process, disconnect is done immediately
 * - Update Toggle LED
 * - Switched to the use of createInsecureRfcommSocketToServiceRecord 
 * - ECG and EMG units have a * (mVolts*) as an indicator when default parameters are used
 * - SR 30 support
 * - Orientation 
 * - Support for low and high power Mag (high power == high sampling rate Mag)
 * - Support for different mag range
 * - Updated the execution model when transmitting commands, now uses a thread, and will improve Main UI thread latency 
 * 
 * Changes since beta 1.0 (21 May 2013)
 * - Added support for on the fly gyro offset calibration
 * - Added quartenions
 * - Convert to an instruction stack format, no longer supports Boilerplate
 * 
 * Changes since beta 1.0.1 (20 June 2013)
 * - Fix the no response bug, through the use of the function dummyreadSamplingRate()
 * - Updates to allow operation with Boilerplate
 * - add get functions for lower power mag and gyro cal on the fly
 * 
 * */


package com.shimmerresearch.driver;

import it.gerdavax.easybluetooth.BtSocket;
import it.gerdavax.easybluetooth.LocalDevice;
import it.gerdavax.easybluetooth.RemoteDevice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import javax.vecmath.AxisAngle4d;
import javax.vecmath.Quat4d;
import javax.vecmath.Vector3d;

import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;

import com.shimmerresearch.algorithms.GradDes3DOrientation;
import com.shimmerresearch.algorithms.GradDes3DOrientation.Quaternion;



import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
//import java.io.FileOutputStream;



public class Shimmer{
	//generic UUID for serial port protocol
	private UUID mSPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	// Message types sent from the Shimmer Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_ACK_RECEIVED = 4;
    public static final int MESSAGE_DEVICE_NAME = 5;
    public static final int MESSAGE_TOAST = 6;
    public static final int MESSAGE_SAMPLING_RATE_RECEIVED = 7;
    public static final int MESSAGE_INQUIRY_RESPONSE = 8;
    public static final int MESSAGE_STOP_STREAMING_COMPLETE = 9;
    public static final int MESSAGE_PACKET_LOSS_DETECTED = 11;
    public static final int MESSAGE_NOT_SYNC = 12;
    public static final int SHIMMER_1=0;
    public static final int SHIMMER_2=1;
    public static final int SHIMMER_2R=2;
    public static final int SHIMMER_3=3;
    public static final int SHIMMER_SR30=4;
    // Key names received from the Shimmer Handler 
    public static final String TOAST = "toast";
    private boolean mInitialized = false;
	private final BluetoothAdapter mAdapter;
	public final Handler mHandler;
	
	private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
	private int mState;
	private boolean mDummy=false;
	private boolean mInitializeFailureDetected = false;
	//Arduino
	private LocalDevice localDevice;
	
	// Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // The class is doing nothing
    public static final int STATE_CONNECTING = 1; // The class is now initiating an outgoing connection
    public static final int STATE_CONNECTED = 2;  // The class is now connected to a remote device
    public static final int MSG_STATE_FULLY_INITIALIZED = 3;  // This is the connected state, indicating the device has establish a connection + tx/rx commands and reponses (Initialized)
    public static final int MSG_STATE_STREAMING = 4;
    public static final int MSG_STATE_STOP_STREAMING = 5;
    //Sensor Bitmap
    public static final int SENSOR_ACCEL				   = 0x80;
    public static final int SENSOR_GYRO				   	   = 0x40;
    public static final int SENSOR_MAG					   = 0x20;
    public static final int SENSOR_ECG					   = 0x10;
    public static final int SENSOR_EMG					   = 0x08;
    public static final int SENSOR_GSR					   = 0x04;
    public static final int SENSOR_EXP_BOARD_A7		       = 0x02;
    public static final int SENSOR_EXP_BOARD_A0		       = 0x01;
    public static final int SENSOR_STRAIN				   = 0x8000;
    public static final int SENSOR_HEART				   = 0x4000;
    public static final int SENSOR_BATT	  			       = 0x10000; //THIS IS A DUMMY VALUE
        
    //Sensor Bitmap for SR30
    public static final int SENSOR_A_ACCEL_SR30				   = 0x01;
    public static final int SENSOR_GYRO_SR30			   	   = 0x02;
    public static final int SENSOR_VBATT_SR30				   = 0x04;
    public static final int SENSOR_MAG_SR30					   = 0x08;
    public static final int SENSOR_D_ACCEL_SR30				   = 0x10;
    
    //Constants describing the packet type
    private static final byte DATA_PACKET                      = (byte) 0x00;
    private static final byte INQUIRY_COMMAND                  = (byte) 0x01;
    private static final byte INQUIRY_RESPONSE                 = (byte) 0x02;
    private static final byte GET_SAMPLING_RATE_COMMAND 	   = (byte) 0x03;
    private static final byte SAMPLING_RATE_RESPONSE           = (byte) 0x04;
    private static final byte SET_SAMPLING_RATE_COMMAND        = (byte) 0x05;
    private static final byte TOGGLE_LED_COMMAND               = (byte) 0x06;
    private static final byte START_STREAMING_COMMAND          = (byte) 0x07;
    private static final byte SET_SENSORS_COMMAND              = (byte) 0x08;
    private static final byte SET_ACCEL_SENSITIVITY_COMMAND    = (byte) 0x09;
    private static final byte ACCEL_SENSITIVITY_RESPONSE       = (byte) 0x0A;
    private static final byte GET_ACCEL_SENSITIVITY_COMMAND    = (byte) 0x0B;
    private static final byte SET_5V_REGULATOR_COMMAND         = (byte) 0x0C;
    private static final byte SET_PMUX_COMMAND                 = (byte) 0x0D;
    private static final byte SET_CONFIG_BYTE0_COMMAND   	   = (byte) 0x0E;
    private static final byte CONFIG_BYTE0_RESPONSE      	   = (byte) 0x0F;
    private static final byte GET_CONFIG_BYTE0_COMMAND   	   = (byte) 0x10;
    private static final byte STOP_STREAMING_COMMAND           = (byte) 0x20;
    private static final byte ACCEL_CALIBRATION_RESPONSE       = (byte) 0x12;
    private static final byte GET_ACCEL_CALIBRATION_COMMAND    = (byte) 0x13;
    private static final byte GYRO_CALIBRATION_RESPONSE        = (byte) 0x15;
    private static final byte GET_GYRO_CALIBRATION_COMMAND     = (byte) 0x16;
    private static final byte MAG_CALIBRATION_RESPONSE         = (byte) 0x18;
    private static final byte GET_MAG_CALIBRATION_COMMAND      = (byte) 0x19;
    private static final byte SET_GSR_RANGE_COMMAND			   = (byte) 0x21;
    private static final byte GSR_RANGE_RESPONSE			   = (byte) 0x22;
    private static final byte GET_GSR_RANGE_COMMAND			   = (byte) 0x23;
    private static final byte GET_SHIMMER_VERSION_COMMAND      = (byte) 0x24;
    private static final byte GET_SHIMMER_VERSION_RESPONSE     = (byte) 0x25;
    private static final byte SET_EMG_CALIBRATION_COMMAND      = (byte) 0x26;
	private static final byte EMG_CALIBRATION_RESPONSE         = (byte) 0x27;
	private static final byte GET_EMG_CALIBRATION_COMMAND      = (byte) 0x28;
	private static final byte SET_ECG_CALIBRATION_COMMAND      = (byte) 0x29;
	private static final byte ECG_CALIBRATION_RESPONSE         = (byte) 0x2A;
	private static final byte GET_ECG_CALIBRATION_COMMAND      = (byte) 0x2B;
	private static final byte GET_ALL_CALIBRATION_COMMAND      = (byte) 0x2C;
	private static final byte GET_ALL_CALIBRATION_COMMAND_SR30 = (byte) 0x15;
	private static final byte ALL_CALIBRATION_RESPONSE         = (byte) 0x2D; 
	private static final byte ALL_CALIBRATION_RESPONSE_SR30    = (byte) 0x16; 
	private static final byte GET_FW_VERSION_COMMAND           = (byte) 0x2E;
	private static final byte FW_VERSION_RESPONSE              = (byte) 0x2F;
	private static final byte SET_BLINK_LED                    = (byte) 0x30;
	private static final byte BLINK_LED_RESPONSE               = (byte) 0x31;
	private static final byte GET_BLINK_LED                    = (byte) 0x32;
	private static final byte SET_GYRO_TEMP_VREF_COMMAND       = (byte) 0x33;
	private static final byte SET_BUFFER_SIZE_COMMAND          = (byte) 0x34;
	private static final byte BUFFER_SIZE_RESPONSE             = (byte) 0x35;
	private static final byte GET_BUFFER_SIZE_COMMAND          = (byte) 0x36;
	private static final byte SET_MAG_GAIN_COMMAND             = (byte) 0x37;
	private static final byte MAG_GAIN_RESPONSE                = (byte) 0x38;
	private static final byte GET_MAG_GAIN_COMMAND             = (byte) 0x39;
	private static final byte SET_MAG_SAMPLING_RATE_COMMAND    = (byte) 0x3A;
	private static final byte MAG_SAMPLING_RATE_RESPONSE       = (byte) 0x3B;
	private static final byte GET_MAG_SAMPLING_RATE_COMMAND    = (byte) 0x3C;
	private static final byte ACK_COMMAND_PROCESSED            = (byte) 0xff;
	private double mFWVersion;
	private int mFWInternal;
	private double mFWIdentifier;
	private String mFWVersionFullName;
    private final int ACK_TIMER_DURATION = 2; 									// Duration to wait for an ack packet (seconds)
    
    private double mLastReceivedTimeStamp=0;
    private double mCurrentTimeStampCycle=0;
    private boolean mStreaming =false;											// This is used to monitor whether the device is in streaming mode
	private double mSamplingRate; 	                                        	// 51.2Hz is the default sampling rate 
	protected int mEnabledSensors;												// This stores the enabled sensors
	private int mSetEnabledSensors = SENSOR_ACCEL;								// Only used during the initialization process, see initialize();
	protected String mMyName;														// This stores the user assigned name
	private byte mCurrentCommand;												// This variable is used to keep track of the current command being executed while waiting for an Acknowledge Packet. This allows the appropriate action to be taken once an Acknowledge Packet is received. 
	private double mTempDoubleValue;											// A temporary variable used to store Double value, used mainly to store a value while waiting for an acknowledge packet (e.g. when writeSamplingRate() is called, the sampling rate is stored temporarily and used to update SamplingRate when the acknowledge packet is received.
	private byte mTempByteValue;												// A temporary variable used to store Byte value	
	private int mTempIntValue;													// A temporary variable used to store Integer value, used mainly to store a value while waiting for an acknowledge packet (e.g. when writeGRange() is called, the range is stored temporarily and used to update GSRRange when the acknowledge packet is received.
	private boolean mWaitForAck=false;                                          // This indicates whether the device is waiting for an acknowledge packet from the Shimmer Device  
	private boolean mWaitForResponse=false; 									// This indicates whether the device is waiting for a response packet from the Shimmer Device 
	private int mPacketSize=0; 													// Default 2 bytes for time stamp and 6 bytes for accelerometer 
	private int mAccelRange=0;													// This stores the current accelerometer range being used. The accelerometer range is stored during two instances, once an ack packet is received after a writeAccelRange(), and after a response packet has been received after readAccelRange()  	
	private int mMagSamplingRate=4;												// This stores the current Mag Sampling rate, it is a value between 0 and 6; 0 = 0.5 Hz; 1 = 1.0 Hz; 2 = 2.0 Hz; 3 = 5.0 Hz; 4 = 10.0 Hz; 5 = 20.0 Hz; 6 = 50.0 Hz
	private int mMagRange=1;													// This stores the current Mag Range, it is a value between 0 and 6; 0 = 0.7 Ga; 1 = 1.0 Ga; 2 = 1.5 Ga; 3 = 2.0 Ga; 4 = 3.2 Ga; 5 = 3.8 Ga; 6 = 4.5 Ga
	protected int mGSRRange=4;													// This stores the current GSR range being used.
    private int mConfigByte0;
    protected int mNChannels=0;	                                                // Default number of sensor channels set to three because of the on board accelerometer 
    protected int mBufferSize;                   							
    protected int mShimmerVersion;
    private String mMyBluetoothAddress="";
    private String[] mSignalNameArray=new String[19];							// 19 is the maximum number of signal thus far
    protected String[] mSignalDataTypeArray=new String[19];						// 19 is the maximum number of signal thus far
    private String[] mGetDataInstruction={"a"}; 								// This is the default value to return all data in both calibrated and uncalibrated format for now only 'a' is supported
    protected boolean mDefaultCalibrationParametersECG = true;
    protected boolean mDefaultCalibrationParametersEMG = true;
    protected boolean mDefaultCalibrationParametersAccel = true;
	protected double[][] AlignmentMatrixAccel = {{-1,0,0},{0,-1,0},{0,0,1}}; 		//Default Values for Accelerometer Calibration //these values should be changed to {{-1,0,0},{0,-1,0},{0,0,1}}; once the Shimmer 9DOF Calibration user manual 1.2a is released	
	protected double[][] SensitivityMatrixAccel = {{38,0,0},{0,38,0},{0,0,38}}; 	//Default Values for Accelerometer Calibration
	protected double[][] OffsetVectorAccel = {{2048},{2048},{2048}};				//Default Values for Accelerometer Calibration
	
	protected double[][] SensitivityMatrixAccel1p5g = {{101,0,0},{0,101,0},{0,0,101}};
	protected double[][] SensitivityMatrixAccel2g = {{76,0,0},{0,76,0},{0,0,76}};
	protected double[][] SensitivityMatrixAccel4g = {{38,0,0},{0,38,0},{0,0,38}};
	protected double[][] SensitivityMatrixAccel6g = {{25,0,0},{0,25,0},{0,0,25}};
	protected List mListofEnabledSensors = new  ArrayList<String>();
	protected List<byte []> mListofInstructions = new  ArrayList<byte[]>();
	protected boolean mInstructionStackLock = false;
	protected double OffsetECGRALL=2060;
	protected double GainECGRALL=175;
	protected double OffsetECGLALL=2060;
	protected double GainECGLALL=175;
	protected double OffsetEMG=2060;
	protected double GainEMG=750;
	
	
	protected boolean mDefaultCalibrationParametersGyro = true;
    protected double[][] AlignmentMatrixGyro = {{0,-1,0},{-1,0,0},{0,0,-1}}; 				//Default Values for Gyroscope Calibration
    protected double[][] SensitivityMatrixGyro = {{2.73,0,0},{0,2.73,0},{0,0,2.73}}; 		//Default Values for Gyroscope Calibration
    protected double[][] OffsetVectorGyro = {{1843},{1843},{1843}};						//Default Values for Gyroscope Calibration
    protected boolean mDefaultCalibrationParametersMag = true;
	protected double[][] AlignmentMatrixMag = {{1,0,0},{0,1,0},{0,0,-1}}; 				//Default Values for Magnetometer Calibration
	protected double[][] SensitivityMatrixMag = {{580,0,0},{0,580,0},{0,0,580}}; 			//Default Values for Magnetometer Calibration
	protected double[][] OffsetVectorMag = {{0},{0},{0}};									//Default Values for Magnetometer Calibration
	private boolean mTransactionCompleted=true;									// Variable is used to ensure a command has finished execution prior to executing the next command (see initialize())
    private boolean mSync=true;													// Variable to keep track of sync
    private boolean mContinousSync=false;                                       // This is to select whether to continuously check the data packets 
	private boolean mSetupDevice=false;											// Used by the constructor when the user intends to write new settings to the Shimmer device after connection
    private boolean mLowPowerMag = false;
	private Timer mTimer;														// Timer variable used when waiting for an ack or response packet
    private int mBluetoothLib=0;												// 0 = default lib, 1 = arduino lib
	private BluetoothAdapter mBluetoothAdapter = null;
	private long mPacketLossCount=0;
	private double mPacketReceptionRate=100;
	private double mLastReceivedCalibratedTimeStamp=-1; 
	private boolean mFirstTimeCalTime=true;
	private double mCalTimeStart;
	private double mTempDouble1;
	private double mTempDouble2;
	private double mTempDouble3;
	private double mTempDouble4;
	private int mCurrentLEDStatus=0;
	private double mLowBattLimit=3.4;
	private double mLastKnownHeartRate=0;
	DescriptiveStatistics mVSenseBattMA= new DescriptiveStatistics(1024);
	private int mTempPacketCountforBatt=0; //reason for this is if the Shimmer has low battery, and the data is trying to be sync, the ack packet of the batt may be deleted
	Quat4d mQ = new Quat4d();
	GradDes3DOrientation mOrientationAlgo;
	private boolean mOrientationEnabled = false;
	private boolean mEnableOntheFlyGyroOVCal = false;
	private int mBufferSizeforGyroOVCal = 102;
	private double mGyroOVCalThreshold = 1.2;
	DescriptiveStatistics mGyroX;
	DescriptiveStatistics mGyroY;
	DescriptiveStatistics mGyroZ;
	DescriptiveStatistics mGyroXRaw;
	DescriptiveStatistics mGyroYRaw;
	DescriptiveStatistics mGyroZRaw;
	private boolean mFirstTime=true;
	  /**
     * Constructor. Prepares a new BluetoothChat session.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     * @param myname  To allow the user to set a unique identifier for each Shimmer device
     * @param countiousSync A boolean value defining whether received packets should be checked continuously for the correct start and end of packet.
     */
    public Shimmer(Context context, Handler handler, String myName, Boolean continousSync) {
    	mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
        mMyName=myName;
        mContinousSync=continousSync;
        mSetupDevice=false;
        mInitializeFailureDetected=false;
    }
	  /**
     * Constructor. Prepares a new BluetoothChat session. Additional fields allows the device to be set up immediately.
     * @param context  The UI Activity Context
     * @param handler  A Handler to send messages back to the UI Activity
     * @param myname  To allow the user to set a unique identifier for each Shimmer device
     * @param samplingRate Defines the sampling rate
     * @param accelRange Defines the Acceleration range. Valid range setting values for the Shimmer 2 are 0 (+/- 1.5g), 1 (+/- 2g), 2 (+/- 4g) and 3 (+/- 6g). Valid range setting values for the Shimmer 2r are 0 (+/- 1.5g) and 3 (+/- 6g).
     * @param gsrRange Numeric value defining the desired gsr range. Valid range settings are 0 (10kOhm to 56kOhm),  1 (56kOhm to 220kOhm), 2 (220kOhm to 680kOhm), 3 (680kOhm to 4.7MOhm) and 4 (Auto Range).
     * @param setEnabledSensors Defines the sensors to be enabled (e.g. 'Shimmer.SENSOR_ACCEL|Shimmer.SENSOR_GYRO' enables the Accelerometer and Gyroscope)
     * @param countiousSync A boolean value defining whether received packets should be checked continuously for the correct start and end of packet.
     */
    public Shimmer(Context context, Handler handler, String myName, double samplingRate, int accelRange, int gsrRange, int setEnabledSensors, boolean continousSync) {
    	mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
        mSamplingRate=samplingRate;
        mAccelRange=accelRange;
        mGSRRange=gsrRange;
        mSetEnabledSensors=setEnabledSensors;
        mMyName=myName;
        mSetupDevice=true;
        mContinousSync=continousSync;
        mInitializeFailureDetected=false;
    }
    
    /**
     * Set the current state of the chat connection
     * @param state  An integer defining the current connection state
     */
    private synchronized void setState(int state) {
    	mState = state;
        // Give the new state to the Handler so the UI Activity can update
        mHandler.obtainMessage(Shimmer.MESSAGE_STATE_CHANGE, state, -1, new ObjectCluster(mMyName,getBluetoothAddress())).sendToTarget();
    }
    
    /**
     * Return the current connection state. */
    public synchronized int getShimmerState() {
        return mState;
    }
    
    /**
     * Start the ConnectThread to initiate a connection to a remote device. The purpose of having two libraries is because some Stock firmware do not implement the full Bluetooth Stack. In such cases use 'gerdavax'. If problems persist consider installing an aftermarket firmware, with a mature Bluetooth stack.
     * @param address Bluetooth Address of Device to connect too
     * @param bluetoothLibrary Supported libraries are 'default' and 'gerdavax'  
     */
    public synchronized void connect(final String address, String bluetoothLibrary) {
    	mListofInstructions.clear();
    	mFirstTime=true;
    	mInitializeFailureDetected=false;
    	if (bluetoothLibrary=="default"){
    		mMyBluetoothAddress=address;
	    	mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
	    	BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
	
	    	// Cancel any thread attempting to make a connection
	        if (mState == STATE_CONNECTING) {
	            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
	        }
	        // Cancel any thread currently running a connection
	        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
	
	        // Start the thread to connect with the given device
	        mConnectThread = new ConnectThread(device);
	        mConnectThread.start();
	        setState(STATE_CONNECTING);
    	} else if (bluetoothLibrary=="gerdavax"){
    		mMyBluetoothAddress=address;
   		 	// Cancel any thread attempting to make a connection
   		 	if (mState == STATE_CONNECTING) {
   	            if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
   	        }
   	        // Cancel any thread currently running a connection
   	        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
   		
   		if (address == null) return;
   		Log.d("ConnectionStatus","Get Local Device  " + address);
  
   		localDevice = LocalDevice.getInstance();
   		RemoteDevice device = localDevice.getRemoteForAddr(address);
		new ConnectThreadArduino(device).start();
		setState(STATE_CONNECTING);
   		/*localDevice.init(this, new ReadyListener() {
   			@Override
   			public synchronized void ready() {
   		 		
   				
   				//localDevice.destroy();
   				
   				
   			}
   		});*/
   		
   	
    	}
    }
    
    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     * @param socket  The BluetoothSocket on which the connection was made
     * @param device  The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
    	// Cancel the thread that completed the connection
        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();
        mMyBluetoothAddress = device.getAddress();
        // Send the name of the connected device back to the UI Activity
        Message msg = mHandler.obtainMessage(Shimmer.MESSAGE_DEVICE_NAME);
        mHandler.sendMessage(msg);
        while(!mConnectedThread.isAlive()){}; 
        setState(STATE_CONNECTED);
        initialize();
        
    }
    
    /**
     * Stop all threads
     */
    public synchronized void stop() {
    	setState(STATE_NONE);
    	mStreaming = false;
        mInitialized = false;
    	if (mConnectThread != null) {
    		mConnectThread.cancel(); 
    		mConnectThread = null;
    	}
        if (mConnectedThread != null) {
        	mConnectedThread.cancel(); 
        	mConnectedThread = null;
        	}
        
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }
    
    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
        setState(STATE_NONE);
        mInitialized = false;
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Unable to connect device");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }
    
    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        setState(STATE_NONE);
        mInitialized = false;
        // Send a failure message back to the Activity
        Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Device connection was lost");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
    }
    
    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            Log.d("Shimmer","Start of Default ConnectThread");
            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                tmp = device.createInsecureRfcommSocketToServiceRecord(mSPP_UUID); // If your device fails to pair try: device.createInsecureRfcommSocketToServiceRecord(mSPP_UUID)
            } catch (IOException e) {}
            mmSocket = tmp;
        }

        public void run() {
            setName("ConnectThread");

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            try {
                // Connect the device through the socket. This will block
                // until it succeeds or throws an exception
            	mmSocket.connect();
            } catch (IOException connectException) {
            	connectionFailed();
                // Unable to connect; close the socket and get out
                try {
                    mmSocket.close();
                } catch (IOException closeException) { }
                return;
            }

           
            // Reset the ConnectThread because we're done
            synchronized (Shimmer.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice);
          
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }
    //arduino
private class ConnectThreadArduino extends Thread {
		
		//private static final String TAG = "ConnectThread";
		private final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
		
		private final RemoteDevice mDevice;
		private BtSocket mSocket;

	    public ConnectThreadArduino(RemoteDevice device) {
	        mDevice = device;
	        Log.d("Shimmer"," Start of ArduinoConnectThread");
	    }

	    public void run() {
	      	try {
	      		boolean isPaired = false;
	      		
	      		try {
	      			isPaired = mDevice.ensurePaired();
	      			
	      			
	      		}
	      		catch (RuntimeException re){
		      		re.printStackTrace();
		      	}
	      		
	      		//add a timer to wait for the user to pair the device otherwise quit
	      		if (!isPaired){
      				Thread.sleep(10000);
      				isPaired = mDevice.ensurePaired();
      			}
	      		
	    		if (!isPaired){
	    			Log.d("Shimmer", "not paired!");
	    			connectionFailed();
	    			
	    			
	    		}
	    		else {
	    			Log.d("Shimmer", "is paired!");
	    			// Let main thread do some stuff to render UI immediately
		    		//Thread.yield();
		    		// Get a BluetoothSocket to connect with the given BluetoothDevice
		    		try {
						mSocket = mDevice.openSocket(SPP_UUID);
					} catch (Exception e) {
						Log.d("Shimmer", "Connection via SDP unsuccessful, try to connect via port directly");
						// 1.x Android devices only work this way since SDP was not part of their firmware then
						mSocket = mDevice.openSocket(1);
						//connectionFailed();
						Log.d("Shimmer", "I am here");
					}
		    		
		    		// Do work to manage the connection (in a separate thread)
		    		Log.d("Shimmer", "Going to Manage Socket");
		    		if (getShimmerState() != STATE_NONE){
		    			Log.d("Shimmer", "ManagingSocket");
		    			manageConnectedSocket(mSocket);
		    		}
	    		}
			}
	      	
	    	catch (Exception e) {
	    		Log.d("Shimmer","Connection Failed");
	    		//sendConnectionFailed(mDevice.getAddress());
	    		connectionFailed();
				e.printStackTrace();
				if (mSocket != null)
					try {
						mSocket.close();
						Log.d("Shimmer","Arduinothreadclose");
					} catch (IOException e1) {}
					
				return;
			}
	    }

	    /** Will cancel an in-progress connection, and close the socket */
	    @SuppressWarnings("unused")
		public void cancel() {
	        try {
	            if (mSocket != null) mSocket.close();
	            //sendConnectionDisconnected(mDevice.getAddress());
	        } 
	        catch (IOException e) { Log.e("Shimmer", "cannot close socket to " + mDevice.getAddress()); }
	    }
	    
	    private void manageConnectedSocket(BtSocket socket){
//	    	Logger.d(TAG, "connection established.");
	    	// pass the socket to a worker thread
	    	String address = mDevice.getAddress();
	    	mConnectedThread = new ConnectedThread(socket, address);
	    	Log.d("Shimmer", "ConnectedThread is about to start");
	    	mConnectedThread.start();
	    	// Send the name of the connected device back to the UI Activity
	    	mMyBluetoothAddress = mDevice.getAddress();
	    	Message msg = mHandler.obtainMessage(Shimmer.MESSAGE_DEVICE_NAME);
	        mHandler.sendMessage(msg);
	        // Send the name of the connected device back to the UI Activity
	        while(!mConnectedThread.isAlive()){}; 
	        Log.d("Shimmer", "alive!!");
	        setState(STATE_CONNECTED);
	        //startStreaming();
	        initialize();
	    }
	}




    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
    	private BluetoothSocket mmSocket=null;
        private final InputStream mInStream;
        private final OutputStream mmOutStream;
        private BtSocket mSocket=null;
        byte[] tb ={0};
		Stack<Byte> packetStack = new Stack<Byte>();
		byte[] newPacket=new byte[mPacketSize+1];
        public ConnectedThread(BluetoothSocket socket) {
        	
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {}

            mInStream = tmpIn;
            mmOutStream = tmpOut;
        }
        
        public ConnectedThread(BtSocket socket, String address) {
	        mSocket = socket;
	        //this.mAddress = address;
	        InputStream tmpIn = null;
	        OutputStream tmpOut = null;
	        
	        // Get the input and output streams, using temp objects because
	        // member streams are final
	        try {
	            tmpIn = socket.getInputStream();
	            tmpOut = socket.getOutputStream();
	        } catch (Exception e) { Log.d("Shimmer","Connected Thread Error");}

	        mInStream = tmpIn;
	        mmOutStream = tmpOut;
	    
		}

		/**
         *The received packets are processed here 
         */
        public synchronized void run() {
        	
        	
        	
        	
        	
        	
        	
        	
        	
        	
        	
            
            // Keep listening to the InputStream while connected
            while (true) {
            	
			/////////////////////////
		    	// is an instruction running ? if not proceed
		    	if (mInstructionStackLock==false){
		    		// check instruction stack, are there any other instructions left to be executed?
		    		if (!mListofInstructions.isEmpty()){
		    			mInstructionStackLock=true;
		    			byte[] insBytes = (byte[]) mListofInstructions.get(0);
		    			mCurrentCommand=insBytes[0];
		    			mWaitForAck=true;
		    			write(insBytes);
		    			if (mCurrentCommand==STOP_STREAMING_COMMAND){
		    				mStreaming=false;
		    			} else {
		    				if (mCurrentCommand==GET_FW_VERSION_COMMAND){
		    					responseTimer(ACK_TIMER_DURATION);
		    				} else if (mCurrentCommand==GET_SAMPLING_RATE_COMMAND){
		    					responseTimer(ACK_TIMER_DURATION);
		    				} else {
		    					responseTimer(ACK_TIMER_DURATION+10);
		    				}
		    			}
		    			mTransactionCompleted=false;
		    		}
		    		
		    	}
            	
                try {
                	
                	//Log.d("Shimmer","byte " + Byte.toString(tb[0]) + " " + Boolean.toString(mWaitForAck) + " " + Boolean.toString(mWaitForResponse) );	
                	//Is the device waiting for an Ack/Response if so look out for the appropriate command
                	if (mWaitForAck==true && mStreaming ==false) {
                		if (mInStream.available()!=0){
                		mInStream.read(tb,0,1);
                		Log.d("ShimmerREAD",mMyBluetoothAddress + " :: " +Byte.toString(tb[0]));
                		
                		
                		if (mCurrentCommand==STOP_STREAMING_COMMAND) { //due to not receiving the ack from stop streaming command we will skip looking for it.
                			mTimer.cancel(); //cancel the ack timer
                 		    mTimer.purge();
                            Log.d("Shimmer", "ACK Received for Device: " + mMyBluetoothAddress + "; Command Issued: " + mCurrentCommand );
            		    	mStreaming=false;
                		    mTransactionCompleted=true;
                		    mWaitForAck=false;
                		    try {
								Thread.sleep(200);	// Wait to ensure that we dont missed any bytes which need to be cleared
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    packetStack.clear();
                		    if(mInStream.available()>0){ //this is to clear the buffer 
								byte[] tbtemp =new byte[mInStream.available()];
                		    	mInStream.read(tbtemp,0,mInStream.available());
							}
							mHandler.obtainMessage(Shimmer.MESSAGE_STATE_CHANGE, MSG_STATE_STOP_STREAMING, -1, new ObjectCluster(mMyName,getBluetoothAddress())).sendToTarget();
							Message msg = mHandler.obtainMessage(MESSAGE_STOP_STREAMING_COMPLETE);
                	        Bundle bundle = new Bundle();
                	        bundle.putBoolean("Stop Streaming", true);
                	        bundle.putString("Bluetooth Address", mMyBluetoothAddress);
                	        msg.setData(bundle);
                	        mHandler.sendMessage(msg);
						    Log.d("Shimmer","Streaming Stop Done" + "Bytes still available:" + Integer.toString(mInStream.available()));
            			    mListofInstructions.remove(0);
                		    mInstructionStackLock=false;
            		    }
                		
                		if ((byte)tb[0]==ACK_COMMAND_PROCESSED)
                		{	
                		    
                            Log.d("Shimmer", "ACK Received for Device: " + mMyBluetoothAddress + "; Command Issued: " + mCurrentCommand );
                           
                		    if (mCurrentCommand==START_STREAMING_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                		    	mStreaming=true;
                			    mTransactionCompleted=true;
                			    packetStack.clear();
                			    isNowStreaming();
                			    mWaitForAck=false;
                			    mHandler.obtainMessage(Shimmer.MESSAGE_STATE_CHANGE, MSG_STATE_STREAMING, -1, new ObjectCluster(mMyName,getBluetoothAddress())).sendToTarget();
                			    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                	        	}
                		    /*else if (mCurrentCommand==STOP_STREAMING_COMMAND) {
                		    	
                		    	mStreaming=false;
                    		    mTransactionCompleted=true;
                    		    mWaitForAck=false;
                    		    packetStack.clear();
								if(mInStream.available()>0){ //this is to clear the buffer 
									byte[] tbtemp =new byte[mInStream.available()];
                    		    	mInStream.read(tbtemp,0,mInStream.available());
								}
								mHandler.obtainMessage(Shimmer.MESSAGE_STATE_CHANGE, MSG_STATE_STOP_STREAMING, -1, new ObjectCluster(mMyName,getBluetoothAddress())).sendToTarget();
								Message msg = mHandler.obtainMessage(MESSAGE_STOP_STREAMING_COMPLETE);
                    	        Bundle bundle = new Bundle();
                    	        bundle.putBoolean("Stop Streaming", true);
                    	        bundle.putString("Bluetooth Address", mMyBluetoothAddress);
                    	        msg.setData(bundle);
                    	        mHandler.sendMessage(msg);
								
								
                    		    Log.d("Shimmer","Streaming Stop Done" + "Bytes still available:" + Integer.toString(mInStream.available()));
                    		    
                		    	}*/
                		    else if (mCurrentCommand==SET_SAMPLING_RATE_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                		    	Log.d("Shimmer", "SR rx" + Double.toString(mSamplingRate));
                    		    mTransactionCompleted=true;
                    		    mWaitForAck=false;
                    		    byte[] instruction=mListofInstructions.get(0);
                    		    double tempdouble=1024/instruction[1];
                    		    mSamplingRate = Double.parseDouble(new DecimalFormat("#.#").format(tempdouble));
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                			}
                		    else if (mCurrentCommand==SET_BUFFER_SIZE_COMMAND) {
                		        mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    			mTransactionCompleted = true;
                    		    mWaitForAck=false;
                    		    mBufferSize=(int)((byte[])mListofInstructions.get(0))[1];
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                			}
                		    else if (mCurrentCommand==INQUIRY_COMMAND) {
                		    	mWaitForResponse=true;
                		    	mWaitForAck=false;
                		    	mListofInstructions.remove(0);
                		    	}
                		    else if (mCurrentCommand==GET_BUFFER_SIZE_COMMAND) {
                		    	mWaitForAck=false;
                		    	mWaitForResponse=true;
                		    	mListofInstructions.remove(0);
                    		    }
                		    else if (mCurrentCommand==GET_BLINK_LED) {
                		    	mWaitForAck=false;
                		    	mWaitForResponse=true;
                		    	mListofInstructions.remove(0);
                    		    }
                		    else if (mCurrentCommand==GET_MAG_SAMPLING_RATE_COMMAND) {
                		    	mWaitForAck=false;
                		    	mWaitForResponse=true;
                		    	mListofInstructions.remove(0);
                    		    }
                		    else if (mCurrentCommand==GET_MAG_GAIN_COMMAND) {
                		    	mWaitForAck=false;
                		    	mWaitForResponse=true;
                		    	mListofInstructions.remove(0);
                    		    }
                		    else if (mCurrentCommand==GET_ACCEL_SENSITIVITY_COMMAND) {
                		    	mWaitForAck=false;
                		    	mWaitForResponse=true;
                    		    }
                		    else if (mCurrentCommand==GET_GSR_RANGE_COMMAND) {
                		    	mWaitForAck=false;
                		    	mWaitForResponse=true;
                		    	mListofInstructions.remove(0);
                    		    }
                		    else if (mCurrentCommand==GET_FW_VERSION_COMMAND) {
                		    	mWaitForResponse = true;
                		    	mWaitForAck=false;
                		    	}
                		    else if (mCurrentCommand==GET_ECG_CALIBRATION_COMMAND) {
                		    	mWaitForResponse = true;
                		    	mWaitForAck=false;
                		    	mListofInstructions.remove(0);
                		    	}
                		    else if (mCurrentCommand==GET_EMG_CALIBRATION_COMMAND) {
                		    	mWaitForResponse = true;
                		    	mWaitForAck=false;
                		    	mListofInstructions.remove(0);
                		    	}
                		    else if (mCurrentCommand==SET_BLINK_LED) {
                		    	mCurrentLEDStatus=(int)((byte[])mListofInstructions.get(0))[1];
                    		    mTransactionCompleted = true;
                    		    //mWaitForAck=false;
                    		    mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                    		    }
                		    else if (mCurrentCommand==SET_GSR_RANGE_COMMAND) {
                		    	
                    		    mTransactionCompleted = true;
                    		    mWaitForAck=false;
                    		    mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    		    mGSRRange=(int)((byte [])mListofInstructions.get(0))[1];
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                    		    }
                		    else if (mCurrentCommand==GET_SAMPLING_RATE_COMMAND) {
                    		    mWaitForResponse=true;
                    		    mWaitForAck=false;
                    		    
                    		}
                		    else if (mCurrentCommand==GET_CONFIG_BYTE0_COMMAND) {
                		    	mWaitForResponse=true;
                		    	mWaitForAck=false;
                		    	mListofInstructions.remove(0);
                		    	}
                		    else if (mCurrentCommand==SET_CONFIG_BYTE0_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                		    	mConfigByte0=(int)((byte [])mListofInstructions.get(0))[1];;
                    		    mWaitForAck=false;
                    		    mTransactionCompleted=true;
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    	}
                		    else if (mCurrentCommand==SET_PMUX_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                		    	if (((byte[])mListofInstructions.get(0))[1]==1) {
                					mConfigByte0=(byte) ((byte) (mConfigByte0|64)&(0xFF)); 
                				}
                				else if (((byte[])mListofInstructions.get(0))[1]==0) {
                					mConfigByte0=(byte) ((byte)(mConfigByte0 & 191)&(0xFF));
                				}
                		    	mTransactionCompleted=true;
                    		    mWaitForAck=false;
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    	}
                		    else if (mCurrentCommand==SET_GYRO_TEMP_VREF_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                		    	mTransactionCompleted=true;
                    		    mConfigByte0=mTempByteValue;
                    		    mWaitForAck=false;
                		    	}
                		    else if (mCurrentCommand==SET_5V_REGULATOR_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                		    	if (((byte[])mListofInstructions.get(0))[1]==1) {
                					mConfigByte0=(byte) (mConfigByte0|128); 
                				}
                				else if (((byte[])mListofInstructions.get(0))[1]==0) {
                					mConfigByte0=(byte)(mConfigByte0 & 127);
                				}
                		    	mTransactionCompleted=true;
                    		    mWaitForAck=false;
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    }
                		    else if (mCurrentCommand==SET_ACCEL_SENSITIVITY_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    			mTransactionCompleted=true;
                    		    mWaitForAck=false;
                    		    
                    		    if (mDefaultCalibrationParametersAccel == true){
                    				if (getAccelRange()==0){
	                    				SensitivityMatrixAccel = SensitivityMatrixAccel1p5g; 
	                    			} else if (getAccelRange()==1){
	                    				SensitivityMatrixAccel = SensitivityMatrixAccel2g; 
	                    			} else if (getAccelRange()==2){
	                    				SensitivityMatrixAccel = SensitivityMatrixAccel4g; 
	                    			} else if (getAccelRange()==3){
	                    				SensitivityMatrixAccel = SensitivityMatrixAccel6g; 
	                    			}
                    		    }
                    		    mAccelRange=(int)(((byte[])mListofInstructions.get(0))[1]);
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                    		} 
                		    else if (mCurrentCommand==SET_MAG_SAMPLING_RATE_COMMAND){
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    			mTransactionCompleted = true;
                    		    mMagSamplingRate = mTempIntValue;
                    		    mWaitForAck = false;
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    } else if (mCurrentCommand==SET_SENSORS_COMMAND) {
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                		    	mWaitForAck=false;
                		    	byte[] instruction=mListofInstructions.get(0);
                		    	mEnabledSensors=instruction[1]+((instruction[2]<<8)&65280);
                		    	packetStack.clear(); // Always clear the packetStack after setting the sensors, this is to ensure a fresh start
                    		    mTransactionCompleted=true;
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                			}
                		    else if (mCurrentCommand==SET_MAG_GAIN_COMMAND){
                		    	mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    			mTransactionCompleted = true;
                    		    mWaitForAck = false;
                    		    mMagRange=(int)((byte [])mListofInstructions.get(0))[1];
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    }
                		    
                		    else if (mCurrentCommand==GET_ACCEL_CALIBRATION_COMMAND || mCurrentCommand==GET_GYRO_CALIBRATION_COMMAND || mCurrentCommand==GET_MAG_CALIBRATION_COMMAND || mCurrentCommand==GET_ALL_CALIBRATION_COMMAND || mCurrentCommand==GET_ALL_CALIBRATION_COMMAND_SR30) {
                		    	mWaitForResponse = true;
                		    	mWaitForAck=false;
                		    	mListofInstructions.remove(0);
                    		}	
                		    else if (mCurrentCommand==GET_SHIMMER_VERSION_COMMAND) {
                		    	mWaitForResponse = true;
                		    	mWaitForAck=false;
                		    	mListofInstructions.remove(0);
                    		}
                		    else if (mCurrentCommand==SET_ECG_CALIBRATION_COMMAND){
                		    	//mGSRRange=mTempIntValue;
                		    	mDefaultCalibrationParametersECG = false;
                		    	OffsetECGLALL=(double)((((byte[])mListofInstructions.get(0))[0]&0xFF)<<8)+(((byte[])mListofInstructions.get(0))[1]&0xFF);
                 		   		GainECGLALL=(double)((((byte[])mListofInstructions.get(0))[2]&0xFF)<<8)+(((byte[])mListofInstructions.get(0))[3]&0xFF);
                 		   		OffsetECGRALL=(double)((((byte[])mListofInstructions.get(0))[4]&0xFF)<<8)+(((byte[])mListofInstructions.get(0))[5]&0xFF);
             		   			GainECGRALL=(double)((((byte[])mListofInstructions.get(0))[6]&0xFF)<<8)+(((byte[])mListofInstructions.get(0))[7]&0xFF);
                    		    mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    		    mTransactionCompleted = true;
                    		    mWaitForAck=false;
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    }
                		    else if (mCurrentCommand==SET_EMG_CALIBRATION_COMMAND){
                		    	//mGSRRange=mTempIntValue;
                		    	mDefaultCalibrationParametersEMG = false;
                 		   		OffsetEMG=(double)((((byte[])mListofInstructions.get(0))[0]&0xFF)<<8)+(((byte[])mListofInstructions.get(0))[1]&0xFF);
                 		   		GainEMG=(double)((((byte[])mListofInstructions.get(0))[2]&0xFF)<<8)+(((byte[])mListofInstructions.get(0))[3]&0xFF);
                		        mTransactionCompleted = true;
                    		    mWaitForAck=false;
                    		    mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    }
                		    else if (mCurrentCommand==TOGGLE_LED_COMMAND){
                		    	//mGSRRange=mTempIntValue;
                    		    mTransactionCompleted = true;
                    		    mWaitForAck=false;
                    		    mTimer.cancel(); //cancel the ack timer
                    		    mTimer.purge();
                    		    mListofInstructions.remove(0);
                    		    mInstructionStackLock=false;
                		    }
                		    
                		}
                		}
                	} else if (mWaitForResponse==true) {
                		if (mFirstTime){
                			mInStream.skip(mInStream.available());
                			mFirstTime=false;
                		} else if (mInStream.available()!=0){
                			
                		mInStream.read(tb,0,1);
                		
                		if (tb[0]==FW_VERSION_RESPONSE){
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			
                		    try {
								Thread.sleep(200);	// Wait to ensure the packet has been fully received
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    byte[] bufferInquiry = new byte[6]; 
                		    mInStream.read(bufferInquiry, 0, 6);
                		    Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mFWIdentifier=(double)((bufferInquiry[1]&0xFF)<<8)+(double)(bufferInquiry[0]&0xFF);
                		    mFWVersion=(double)((bufferInquiry[3]&0xFF)<<8)+(double)(bufferInquiry[2]&0xFF)+((double)((bufferInquiry[4]&0xFF))/10);
                		    mFWInternal=(int)(bufferInquiry[5]&0xFF);
                		    if (((double)((bufferInquiry[4]&0xFF))/10)==0){
                		    	mFWVersionFullName = "BtStream " + Double.toString(mFWVersion) + "."+ Integer.toString(mFWInternal);
                		    } else {
                		    	mFWVersionFullName = "BtStream " + Double.toString(mFWVersion) + "."+ Integer.toString(mFWInternal);
                		    }
                		    Log.d("Shimmer","Version:" + mFWVersionFullName);
                		    Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
                	        Bundle bundle = new Bundle();
                	        bundle.putString(TOAST, "Firmware Version: " +mFWVersionFullName);
                	        msg.setData(bundle);
                	        if (!mDummy){
                	        	mHandler.sendMessage(msg);
                	        }
                	        mListofInstructions.remove(0);
                            mInstructionStackLock=false;
                		    mTransactionCompleted=true;
                		    initializeStageTwo();
                		} else if (tb[0]==INQUIRY_RESPONSE) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    try {
								Thread.sleep(200);	// Wait to ensure the packet has been fully received
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
	                		    if (mShimmerVersion!=4){
	                		    byte[] bufferInquiry = new byte[30]; 
	                		    mInStream.read(bufferInquiry, 0, 30);
	                		    mPacketSize = 2+bufferInquiry[3]*2; 
	                		    mSamplingRate = (double)1024/bufferInquiry[0];
	                    	    mAccelRange = bufferInquiry[1];
	                    	    mConfigByte0 = bufferInquiry[2] & 0xFF; //convert the byte to unsigned integer
	                    	    mNChannels = bufferInquiry[3];
	                    	    mBufferSize = bufferInquiry[4];
	                    	    byte[] signalIdArray = new byte[mNChannels];
	                    	    System.arraycopy(bufferInquiry, 5, signalIdArray, 0, mNChannels);
	                    	    interpretdatapacketformat(mNChannels,signalIdArray);
	                            Log.d("Shimmer","Inquiry Response Received for Device-> "+mMyBluetoothAddress + " "+ bufferInquiry[0]+ " "+ bufferInquiry[1]+ " "+bufferInquiry[2]+ " " +bufferInquiry[3]+ " "+bufferInquiry[4]+ " " +bufferInquiry[5]+ " " +bufferInquiry[6]+ " " +bufferInquiry[7]+ " "+bufferInquiry[8]+ " " +bufferInquiry[9]+ " " +bufferInquiry[10] + " " +bufferInquiry[11]+ " " +bufferInquiry[12]+ " " +bufferInquiry[13]+ " " +bufferInquiry[14]+ " " +bufferInquiry[15] + " " +bufferInquiry[16] + " " +bufferInquiry[17] + " " +bufferInquiry[18] );
                		    } else { //no config byte so adjust accordingly
                    		    byte[] bufferInquiry = new byte[30]; 
                    		    mInStream.read(bufferInquiry, 0, 30);
                    		    mPacketSize = 2+bufferInquiry[2]*2; 
                    		    mSamplingRate = (double)1024/bufferInquiry[0];
                        	    mAccelRange = bufferInquiry[1];
                        	    mNChannels = bufferInquiry[2];
                        	    mBufferSize = bufferInquiry[3];
                        	    byte[] signalIdArray = new byte[mNChannels];
                        	    System.arraycopy(bufferInquiry, 4, signalIdArray, 0, mNChannels); // this is 4 because there is no config byte
                        	    interpretdatapacketformat(mNChannels,signalIdArray);
                                Log.d("Shimmer","Inquiry Response Received for Device-> "+mMyBluetoothAddress + " "+ bufferInquiry[0]+ " "+ bufferInquiry[1]+ " "+bufferInquiry[2]+ " " +bufferInquiry[3]+ " "+bufferInquiry[4]+ " " +bufferInquiry[5]+ " " +bufferInquiry[6]+ " " +bufferInquiry[7]+ " "+bufferInquiry[8]+ " " +bufferInquiry[9]+ " " +bufferInquiry[10] + " " +bufferInquiry[11]+ " " +bufferInquiry[12]+ " " +bufferInquiry[13]+ " " +bufferInquiry[14]+ " " +bufferInquiry[15] + " " +bufferInquiry[16] + " " +bufferInquiry[17] + " " +bufferInquiry[18] );
                		    }
                            inquiryDone();
                            mWaitForResponse = false;
                            mTransactionCompleted=true;
                            mInstructionStackLock=false;
                        } else if(tb[0] == GSR_RANGE_RESPONSE) {
                        	mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    mTransactionCompleted=true;
                		    byte[] bufferGSRRange = new byte[1]; 
                 		    mInStream.read(bufferGSRRange, 0, 1);
                 	        mGSRRange=bufferGSRRange[0];
                 	        mInstructionStackLock=false;
                		} else if(tb[0] == MAG_SAMPLING_RATE_RESPONSE) {
                        	mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    mTransactionCompleted=true;
                		    byte[] bufferAns = new byte[1]; 
                 		    mInStream.read(bufferAns, 0, 1);
                 	        mMagSamplingRate=bufferAns[0];
                 	        mInstructionStackLock=false;
                		} else if(tb[0] == MAG_GAIN_RESPONSE) {
                        	mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    mTransactionCompleted=true;
                		    byte[] bufferAns = new byte[1]; 
                 		    mInStream.read(bufferAns, 0, 1);
                 	        mMagRange=bufferAns[0];
                 	        mInstructionStackLock=false;
                		}else if(tb[0]==BUFFER_SIZE_RESPONSE) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    mTransactionCompleted=true;
                		    byte[] byteled = new byte[1]; 
                		    mInStream.read(byteled, 0, 1);
                		    mBufferSize = byteled[0] & 0xFF;
                		    mInstructionStackLock=false;
                		} else if(tb[0]==BLINK_LED_RESPONSE) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    mTransactionCompleted=true;
                		    byte[] byteled = new byte[1]; 
                		    mInStream.read(byteled, 0, 1);
                		    mCurrentLEDStatus = byteled[0]&0xFF;
                		    mInstructionStackLock=false;
                		} else if(tb[0]==ACCEL_SENSITIVITY_RESPONSE) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    mTransactionCompleted=true;
                		    byte[] bufferAccelSensitivity = new byte[1]; 
                 		    mInStream.read(bufferAccelSensitivity, 0, 1);
                 	        mAccelRange=bufferAccelSensitivity[0];
	                        mListofInstructions.remove(0);
                 	        mInstructionStackLock=false;
                		} else if (tb[0]==SAMPLING_RATE_RESPONSE) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    if(mStreaming==false) {
                			    byte[] bufferSR = new byte[1]; 
                        	    mInStream.read(bufferSR, 0, 1); //read the sampling rate
                        	    if (mCurrentCommand==GET_SAMPLING_RATE_COMMAND) { // this is a double check, not necessary 
                        		    double val=(double)(bufferSR[0] & (byte) ACK_COMMAND_PROCESSED);
                        		    mSamplingRate=1024/val;
                        		    }
                			}
                			mTransactionCompleted=true;
                			mListofInstructions.remove(0);
                            mInstructionStackLock=false;
                		} else if (tb[0]==ACCEL_CALIBRATION_RESPONSE ) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    mWaitForResponse=false;
                		    byte[] bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        int packetType=tb[0];
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, packetType);
                 	        mTransactionCompleted=true;
                 	        mInstructionStackLock=false;
                 		}  else if (tb[0]==ALL_CALIBRATION_RESPONSE ) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                		    mWaitForResponse=false;
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                			
                			//get accel
                			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    byte[] bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, ACCEL_CALIBRATION_RESPONSE);
                 	        
                 	        //get gyro
                			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, GYRO_CALIBRATION_RESPONSE);
                 	        
                 	        //get mag
                			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, MAG_CALIBRATION_RESPONSE);
                 	        
                 	       try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                 	       bufferCalibrationParameters = new byte[12]; 
                		   mInStream.read(bufferCalibrationParameters, 0, 12); //just read the EMG and ECG values for now.
                 	       
                		   if (bufferCalibrationParameters[0]==-1 && bufferCalibrationParameters[1] == -1 && bufferCalibrationParameters[2] == -1 && bufferCalibrationParameters[3]==-1){
                			   mDefaultCalibrationParametersEMG = true;
                		   } else {
                			   mDefaultCalibrationParametersEMG = false;
                			   OffsetEMG=(double)((bufferCalibrationParameters[0]&0xFF)<<8)+(bufferCalibrationParameters[1]&0xFF);
                			   GainEMG=(double)((bufferCalibrationParameters[2]&0xFF)<<8)+(bufferCalibrationParameters[3]&0xFF);
                		   }
                		   if (bufferCalibrationParameters[4]==-1 && bufferCalibrationParameters[5] == -1 && bufferCalibrationParameters[6] == -1 && bufferCalibrationParameters[7]==-1){
                			   mDefaultCalibrationParametersECG = true;
                		   } else {
                			   mDefaultCalibrationParametersECG = false;
                			   OffsetECGLALL=(double)((bufferCalibrationParameters[4]&0xFF)<<8)+(bufferCalibrationParameters[5]&0xFF);
                			   GainECGLALL=(double)((bufferCalibrationParameters[6]&0xFF)<<8)+(bufferCalibrationParameters[7]&0xFF);
                			   OffsetECGRALL=(double)((bufferCalibrationParameters[8]&0xFF)<<8)+(bufferCalibrationParameters[9]&0xFF);
                			   GainECGRALL=(double)((bufferCalibrationParameters[10]&0xFF)<<8)+(bufferCalibrationParameters[11]&0xFF);
                		   }
                		   
                		   mTransactionCompleted=true;
                		   mInstructionStackLock=false;
                 		} else if (tb[0]==ALL_CALIBRATION_RESPONSE_SR30 ) {
                			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                		    mWaitForResponse=false;
                			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                			
                			//get accel
                			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    byte[] bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, ACCEL_CALIBRATION_RESPONSE);
                 	        
                 	        //get gyro
                			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, GYRO_CALIBRATION_RESPONSE);
                 	        
                 	        //get mag
                			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, MAG_CALIBRATION_RESPONSE);
                 	        mTransactionCompleted=true;
                 	        mInstructionStackLock=false;
                 		} else if (tb[0]==GYRO_CALIBRATION_RESPONSE) {
                 			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                 			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                 			try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    mWaitForResponse=false;
                		    byte[] bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        int packetType=tb[0];
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, packetType);
                 	        mTransactionCompleted=true;
                 	        mInstructionStackLock=false;
                 		} else if (tb[0]==MAG_CALIBRATION_RESPONSE ) {
                 			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                 			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    mWaitForResponse=false;
                		    try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                		    byte[] bufferCalibrationParameters = new byte[21]; 
                 		    mInStream.read(bufferCalibrationParameters, 0, 21);
                 	        int packetType=tb[0];
                 	        retrievecalibrationparametersfrompacket(bufferCalibrationParameters, packetType);
                 	        mTransactionCompleted=true;
                 	        mInstructionStackLock=false;
                 		} else if(tb[0]==CONFIG_BYTE0_RESPONSE) {
                 			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                 			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                		    byte[] bufferConfigByte0 = new byte[1]; 
                 		    mInStream.read(bufferConfigByte0, 0, 1);
                 		    mConfigByte0 = bufferConfigByte0[0] & 0xFF;
                 		    mTransactionCompleted=true;
                		    mInstructionStackLock=false;
                 		} else if(tb[0]==GET_SHIMMER_VERSION_RESPONSE) {
                 			mTimer.cancel(); //cancel the ack timer
                		    mTimer.purge();
                		    try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
                 			Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
                			byte[] bufferShimmerVersion = new byte[1]; 
                 			mInStream.read(bufferShimmerVersion, 0, 1);
                 			mShimmerVersion=(int)bufferShimmerVersion[0];
                 			mTransactionCompleted=true;
                 			mInstructionStackLock=false;
                		} else if (tb[0]==ECG_CALIBRATION_RESPONSE){
							mTimer.cancel(); //cancel the ack timer
							mTimer.purge();
							Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
							try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
							byte[] bufferCalibrationParameters = new byte[8]; 
							mInStream.read(bufferCalibrationParameters, 0, 4); //just read the EMCG and ECG values for now.
                  	        //get ecg 
                 		   	if (bufferCalibrationParameters[0]==-1 && bufferCalibrationParameters[1] == -1 && bufferCalibrationParameters[2] == -1 && bufferCalibrationParameters[3]==-1){
                 		   		mDefaultCalibrationParametersECG = true;
                 		   	} else {
                 		   		mDefaultCalibrationParametersECG = false;
                 		   		OffsetECGLALL=(double)((bufferCalibrationParameters[0]&0xFF)<<8)+(bufferCalibrationParameters[1]&0xFF);
                 		   		GainECGLALL=(double)((bufferCalibrationParameters[2]&0xFF)<<8)+(bufferCalibrationParameters[3]&0xFF);
                 		   		OffsetECGRALL=(double)((bufferCalibrationParameters[4]&0xFF)<<8)+(bufferCalibrationParameters[5]&0xFF);
             		   			GainECGRALL=(double)((bufferCalibrationParameters[6]&0xFF)<<8)+(bufferCalibrationParameters[7]&0xFF);
                 		   	}
                 		   	mTransactionCompleted=true;
                 		   	mInstructionStackLock=false;
                		} else if (tb[0]==EMG_CALIBRATION_RESPONSE){
                			mTimer.cancel(); //cancel the ack timer
							mTimer.purge();
							Log.d("Shimmer","Response received: " + Integer.toString(mCurrentCommand));
							try {
								Thread.sleep(100);	// Due to the nature of the Bluetooth SPP stack a delay has been added to ensure the buffer is filled before it is read
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
							byte[] bufferCalibrationParameters = new byte[4]; 
							mInStream.read(bufferCalibrationParameters, 0, 4); //just read the EMCG and ECG values for now.
                  	        //get ecg 
                 		   	if (bufferCalibrationParameters[0]==-1 && bufferCalibrationParameters[1] == -1 && bufferCalibrationParameters[2] == -1 && bufferCalibrationParameters[3]==-1){
                 		   		mDefaultCalibrationParametersEMG = true;
                 		   	} else {
                 		   		mDefaultCalibrationParametersEMG = false;
                 		   		OffsetEMG=(double)((bufferCalibrationParameters[0]&0xFF)<<8)+(bufferCalibrationParameters[1]&0xFF);
                 		   		GainEMG=(double)((bufferCalibrationParameters[2]&0xFF)<<8)+(bufferCalibrationParameters[3]&0xFF);
                 		   	}
                 		   	mTransactionCompleted=true;
                 		   	mInstructionStackLock=false;
                		}
                		}
                	}      	
                	if (mStreaming==true) {
                		mInStream.read(tb,0,1);
                		
                		
                		
                		//Log.d("Shimmer","Incoming Byte: " + Byte.toString(tb[0])); // can be commented out to watch the incoming bytes
        			    if (mSync==true) {        //if the stack is full
        			    	if (mWaitForAck==true && (byte)tb[0]==ACK_COMMAND_PROCESSED && packetStack.size()==mPacketSize+1){ //this is to handle acks during mid stream, acks only are received between packets.
                        		if (mCurrentCommand==SET_BLINK_LED){
	        			    		Log.d("ShimmerCMD","LED_BLINK_ACK_DETECTED");
	                        		mWaitForAck=false;
	                        		mTransactionCompleted = true;   
	                        		mTimer.cancel(); //cancel the ack timer
	                    		    mTimer.purge();
	                    		    mCurrentLEDStatus=(int)((byte[])mListofInstructions.get(0))[1];
	                    		    mListofInstructions.remove(0);
	                    		    mInstructionStackLock=false;
                        		}
                        	} else { // the first time you start streaming it will go through this piece of code to make sure the data streaming is alligned/sync
                        		if (packetStack.size()==mPacketSize+1){
		            			    if (tb[0]==DATA_PACKET && packetStack.firstElement()==DATA_PACKET) { //check for the starting zero of the packet, and the starting zero of the subsequent packet, this causes a delay equivalent to the transmission duration between two packets
		            			    	newPacket=convertstacktobytearray(packetStack,mPacketSize);
			            			    ObjectCluster objectCluster=(ObjectCluster) buildMsg(newPacket, mGetDataInstruction); 
			            			    //printtofile(newmsg.UncalibratedData);
			            		        mHandler.obtainMessage(MESSAGE_READ, objectCluster)
			                        	        .sendToTarget();
			                            packetStack.clear();
			                            if (mContinousSync==false) {         //disable continuous synchronizing 
			                         	    mSync=false;
			                           	}
			            			}
                        		}
	            			    /*if (mStreaming==true && mWaitForAck==true && (byte)tb[0]==ACK_COMMAND_PROCESSED && (packetStack.size()==0)){ //this is to handle acks during mid stream, acks only are received between packets.
	                        		Log.d("ShimmerCMD","LED_BLINK_ACK_DETECTED");
	                        		mWaitForAck=false;
	                        		mCurrentLEDStatus=mTempIntValue;
	                    		    mTransactionCompleted = true;
	                        	} */
	            			    packetStack.push((tb[0])); //push new sensor data into the stack
	            			    if (packetStack.size()>mPacketSize+1) { //if the stack has reached the packet size remove an element from the stack
	            				    packetStack.removeElementAt(0);
	            				    Log.d("ShimmerCMD","Throwing Data");
	            		        }
                        	}
            			} else if (mSync==false){
            				if (mWaitForAck==true && (byte)tb[0]==ACK_COMMAND_PROCESSED && packetStack.size()==0){ //this is to handle acks during mid stream, acks only are received between packets.
            					if (mCurrentCommand==SET_BLINK_LED){
	        			    		Log.d("ShimmerCMD","LED_BLINK_ACK_DETECTED");
	                        		mWaitForAck=false;
	                        		mTransactionCompleted = true;   
	                        		mTimer.cancel(); //cancel the ack timer
	                    		    mTimer.purge();
	                    		    mCurrentLEDStatus=(int)((byte[])mListofInstructions.get(0))[1];
	                    		    mListofInstructions.remove(0);
	                    		    mInstructionStackLock=false;
                        		}
                        	} else {
                        		packetStack.push((tb[0])); //push new sensor data into the stack
                        		if(packetStack.firstElement()==DATA_PACKET && (packetStack.size()==mPacketSize+1)) {         //only used when continous sync is disabled
		        			    	newPacket=convertstacktobytearray(packetStack,mPacketSize);
		            			    ObjectCluster objectCluster=(ObjectCluster) buildMsg(newPacket, mGetDataInstruction);    //the packet which is an array of bytes is converted to the data structure
		            		        mHandler.obtainMessage(MESSAGE_READ, objectCluster)
		                        			.sendToTarget();
		            		        packetStack.clear();
		                        }
	        			    	
	        			    	if (packetStack.size()>mPacketSize) { //if the stack has reached the packet size remove an element from the stack
	            				    packetStack.removeElementAt(0);
	            				    Log.d("ShimmerCMD","Throwing Data");
	            		        }
                        	}
        			    }
        			    
        			    
        			   
        			    
        		    }
                	
                	

                
                	
                	
        	    } catch (IOException e) {
        	    	  Log.d("Shimmer", e.toString());
        	    	  connectionLost();
                      break;
                }
            	
                
            }
        }
         
    
        
        
        
        /**
         * Write to the connected OutStream.
         * @param buffer  The bytes to write
         */
        private void write(byte[] buffer) {
            try {
            	mmOutStream.write(buffer);
                Log.d("Shimmer", "Command transmitted: " + mMyBluetoothAddress + "; Command Issued: " + mCurrentCommand );
                
            } catch (IOException e) {
            	 Log.d("Shimmer", "Command NOT transmitted: " + mMyBluetoothAddress + "; Command Issued: " + mCurrentCommand );
            }
        }
             
        public void cancel() {
        	if(mInStream != null) {
        		try {
        			mInStream.close();
        		} catch (IOException e) {}
        	}
        	if(mmOutStream != null) {
        		try {
        			mmOutStream.close();
        		} catch (IOException e) {}
        	}
        	if(mmSocket != null) {
	            try {
	                if (mBluetoothLib==0){
	                	mmSocket.close();
	                }	else {
	                	mSocket.close();
	                }
	            } catch (IOException e) {}
        	}
        }
    }
    

	
	
  public synchronized void responseTimer(int seconds) {
        if (mTimer!=null) {
		  	mTimer.cancel();
	        mTimer.purge();
        }
        Log.d("ShimmerTimer",Integer.toString(mCurrentCommand));
	  	mTimer = new Timer();
        mTimer.schedule(new responseTask(), seconds*1000);
	}
    
    class responseTask extends TimerTask {
        public void run() {
        	 {
        		  if (mCurrentCommand==GET_FW_VERSION_COMMAND){
        			Log.d("ShimmerFW", "FW Response Timeout");
                	mFWVersion=0.1;
                	mFWInternal=0;
                	mFWVersionFullName="BoilerPlate 0.1.0";
                	Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
          	        Bundle bundle = new Bundle();
          	        bundle.putString(TOAST, "Firmware Version: " +mFWVersionFullName);
          	        msg.setData(bundle);
          	        if (!mDummy){
          	        	//mHandler.sendMessage(msg);
          	        }
          	        mWaitForAck=false;
                    mTransactionCompleted=true; //should be false, so the driver will know that the command has to be executed again, this is not supported at the moment 
                    mTimer.cancel(); //Terminate the timer thread
                    mTimer.purge();
                    mFirstTime=false;
        		    mListofInstructions.remove(0);
        		    mInstructionStackLock=false;
        		    initializeStageTwo();
                } else if(mCurrentCommand==GET_SAMPLING_RATE_COMMAND && mInitialized==false){
                	Log.d("ShimmerFW", "FW Response Timeout");
                	mWaitForAck=false;
                    mTransactionCompleted=true; //should be false, so the driver will know that the command has to be executed again, this is not supported at the moment 
                    mTimer.cancel(); //Terminate the timer thread
                    mTimer.purge();
                    mFirstTime=false;
        		    mListofInstructions.remove(0);
        		    mInstructionStackLock=false;
                } else {
                	mInitializeFailureDetected=true;
                	Log.d("Shimmer", "Command " + Integer.toString(mCurrentCommand) +" failed; Killing Connection  " + Double.toString(mSamplingRate));
                	if (mWaitForResponse){
                		Log.d("Shimmer", "Response not received");

                    	Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
                        Bundle bundle = new Bundle();
                        bundle.putString(TOAST, "Response not received, please reset Shimmer Device." + mMyBluetoothAddress);
                        msg.setData(bundle);
                        mHandler.sendMessage(msg);
                	}
	                mWaitForAck=false;
	                mTransactionCompleted=true; //should be false, so the driver will know that the command has to be executed again, this is not supported at the moment 
	                stop(); //If command fail exit device 

                }
            }
        }
    }
    

	private synchronized void initialize() {	    	//See two constructors for Shimmer
    	Log.d("Shimmer","Get FW Version"); // no ack means it boilerplate version 0.0 
    	//InstructionsThread instructionsThread = new InstructionsThread();
		//instructionsThread.start();
		dummyreadSamplingRate(); // it actually acts to clear the write buffer
		readFWVersion();
		//mShimmerVersion=4;
		
    }
	    
	private void initializeStageTwo(){
		readSamplingRate();
		Log.d("Shimmer","Device " + mMyBluetoothAddress + " initializing"); 
    	if (!mInitializeFailureDetected){
			if (mFWVersion==0.1 && mFWInternal==0){
				readConfigByte0();
				readCalibrationParameters("Accelerometer");
				readCalibrationParameters("Magnetometer");
				readCalibrationParameters("Gyroscope");
			} else {
				readShimmerVersion(); // for read shimmer version the while loop is required
				readMagSamplingRate();
		    	if (mShimmerVersion!=4){ //if not SR30
					writeBufferSize(1);
					readBlinkLED();
					readConfigByte0();
				}
				readCalibrationParameters("All");
			}
	    	if (mSetupDevice==true && mShimmerVersion!=4){
	    		writeAccelRange(mAccelRange);
	 			writeGSRRange(mGSRRange);
	 			writeSamplingRate(mSamplingRate);	
	 			writeEnabledSensors(mSetEnabledSensors);
	 			setContinuousSync(mContinousSync);
	    	} else {
	    		inquiry();
	    	}
    	}
	}
	
    private void inquiryDone() {
    	Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Inquiry done for device-> " + mMyBluetoothAddress);
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        isReadyForStreaming();
    }   
	
    private void isReadyForStreaming(){
		Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Device " + mMyBluetoothAddress +" is ready for Streaming");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        if (mInitialized == false){
        	//only do this during the initialization process to indicate that it is fully initialized, dont do this for a normal inqiuiry
        	mHandler.obtainMessage(Shimmer.MESSAGE_STATE_CHANGE, MSG_STATE_FULLY_INITIALIZED, -1, new ObjectCluster(mMyName,getBluetoothAddress())).sendToTarget();
        	mInitialized = true;
        }
        Log.d("Shimmer","Shimmer " + mMyBluetoothAddress +" Initialization completed and is ready for Streaming");
    }
	    
    private void isNowStreaming() {
    	
    	Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(TOAST, "Device " + mMyBluetoothAddress + " is now Streaming");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        Log.d("Shimmer","Shimmer " + mMyBluetoothAddress +" is now Streaming");
    }
    
   /*
    * Set and Get Methods
    * */    
   public void setContinuousSync(boolean continousSync){
	   mContinousSync=continousSync;
   }

   public boolean getStreamingStatus(){
	   return mStreaming;
   }
   
   public String getBluetoothAddress(){
	   return  mMyBluetoothAddress;
   }

   public int getEnabledSensors() {
	   return mEnabledSensors;
   }
   
   public void setgetdatainstruction(String... instruction) {
   	mGetDataInstruction=instruction;
   }
   
	/**
	 * This returns the variable mTransactionCompleted which indicates whether the Shimmer device is in the midst of a command transaction. True when no transaction is taking place. This is deprecated since the update to a thread model for executing commands
	 * @return mTransactionCompleted
	 */
	public boolean getInstructionStatus()
	{	
		boolean instructionStatus=false;
		if (mTransactionCompleted == true) {
			instructionStatus=true;
		} else {
			instructionStatus=false;
		}
		return instructionStatus;
	}
	
	public double getSamplingRate(){
		return mSamplingRate;
	}
	
   
  	/*
  	 * Data Methods
  	 * */  
   
    /**
    * Converts the raw packet byte values, into the corresponding calibrated and uncalibrated sensor values, the Instruction String determines the output 
    * @param newPacket a byte array containing the current received packet
    * @param Instructions an array string containing the commands to execute. It is currently not fully supported
    * @return
    */
   
   protected int[] parsedData(byte[] data,String[] dataType)
	{
		int iData=0;
		int[] formattedData=new int[dataType.length];

		for (int i=0;i<dataType.length;i++)
			if (dataType[i]=="u8") {
				formattedData[i]=(int)data[iData];
				iData=iData+1;
			}
			else if (dataType[i]=="i8") {
				formattedData[i]=calculatetwoscomplement((int)((int)0xFF & data[iData]),8);
				iData=iData+1;
			}
			else if (dataType[i]=="u12") {
				
				formattedData[i]=(int)((int)(data[iData] & 0xFF) + ((int)(data[iData+1] & 0xFF) << 8));
				iData=iData+2;
			}
			else if (dataType[i]=="u16") {
				
				formattedData[i]=(int)((int)(data[iData] & 0xFF) + ((int)(data[iData+1] & 0xFF) << 8));
				iData=iData+2;
			}
			else if (dataType[i]=="i16") {
				
				formattedData[i]=calculatetwoscomplement((int)((int)(data[iData] & 0xFF) + ((int)(data[iData+1] & 0xFF) << 8)),16);
				iData=iData+2;
			}
		return formattedData;
	}
	
	private int[] formatdatapacketreverse(byte[] data,String[] dataType)
	{
		int iData=0;
		int[] formattedData=new int[dataType.length];

		for (int i=0;i<dataType.length;i++)
			if (dataType[i]=="u8") {
				formattedData[i]=(int)data[iData];
				iData=iData+1;
			}
			else if (dataType[i]=="i8") {
				formattedData[i]=calculatetwoscomplement((int)((int)0xFF & data[iData]),8);
				iData=iData+1;
			}
			else if (dataType[i]=="u12") {
				
				formattedData[i]=(int)((int)(data[iData+1] & 0xFF) + ((int)(data[iData] & 0xFF) << 8));
				iData=iData+2;
			}
			else if (dataType[i]=="u16") {
				
				formattedData[i]=(int)((int)(data[iData+1] & 0xFF) + ((int)(data[iData] & 0xFF) << 8));
				iData=iData+2;
			}
			else if (dataType[i]=="i16") {
				
				formattedData[i]=calculatetwoscomplement((int)((int)(data[iData+1] & 0xFF) + ((int)(data[iData] & 0xFF) << 8)),16);
				iData=iData+2;
			}
		return formattedData;
	}
	
	private int calculatetwoscomplement(int signedData, int bitLength)
	{
		int newData=signedData;
		if (signedData>(1<<(bitLength-1))) {
			newData=-((signedData^(int)(Math.pow(2, bitLength)-1))+1);
		}

		return newData;
	}
	
	protected int getSignalIndex(String signalName) {
	int iSignal=0; //used to be -1, putting to zero ensure it works eventhough it might be wrong SR30
		for (int i=0;i<mSignalNameArray.length;i++) {
			if (signalName==mSignalNameArray[i]) {
				iSignal=i;
			}
		}

		return iSignal;
	}
	
	private void interpretdatapacketformat(int nC, byte[] signalid)
	{
		String [] signalNameArray=new String[19];
		String [] signalDataTypeArray=new String[19];
		signalNameArray[0]="TimeStamp";
		signalDataTypeArray[0]="u16";
		int packetSize=2; // Time stamp
		int enabledSensors= 0x00;
		for (int i=0;i<nC;i++) {
			if ((byte)signalid[i]==(byte)0x00)
			{
				if (mShimmerVersion==4){
					signalNameArray[i+1]="Low Noise Accelerometer X";
					signalDataTypeArray[i+1] = "u12";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_A_ACCEL_SR30);
				} else {
					signalNameArray[i+1]="Accelerometer X";
					signalDataTypeArray[i+1] = "u12";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_ACCEL);
				}
			}
			else if ((byte)signalid[i]==(byte)0x01)
			{
				if (mShimmerVersion==4){
					signalNameArray[i+1]="Low Noise Accelerometer Y";
					signalDataTypeArray[i+1] = "u12";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_A_ACCEL_SR30);
				} else {
					signalNameArray[i+1]="Accelerometer Y";
					signalDataTypeArray[i+1] = "u12";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_ACCEL);
				}
			}
			else if ((byte)signalid[i]==(byte)0x02)
			{
				if (mShimmerVersion==4){
					signalNameArray[i+1]="Low Noise Accelerometer Z";
					signalDataTypeArray[i+1] = "u12";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_A_ACCEL_SR30);
				} else {
					signalNameArray[i+1]="Accelerometer Z";
					signalDataTypeArray[i+1] = "u12";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_ACCEL);
				}
			}
			else if ((byte)signalid[i]==(byte)0x03)
			{
				signalNameArray[i+1]="Gyroscope X";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				if (mShimmerVersion==4){
					enabledSensors= (enabledSensors|SENSOR_GYRO_SR30);
				} else {
					enabledSensors= (enabledSensors|SENSOR_GYRO);
				}
			}
			else if ((byte)signalid[i]==(byte)0x04)
			{
				signalNameArray[i+1]="Gyroscope Y";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				if (mShimmerVersion==4){
					enabledSensors= (enabledSensors|SENSOR_GYRO_SR30);
				} else {
					enabledSensors= (enabledSensors|SENSOR_GYRO);
				}
			}
			else if ((byte)signalid[i]==(byte)0x05)
			{
				signalNameArray[i+1]="Gyroscope Z";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				if (mShimmerVersion==4){
					enabledSensors= (enabledSensors|SENSOR_GYRO_SR30);
				} else {
					enabledSensors= (enabledSensors|SENSOR_GYRO);
				}
			}
			else if ((byte)signalid[i]==(byte)0x06)
			{
				if(mShimmerVersion==4){
					signalNameArray[i+1]="VSenseBatt"; //should be the battery but this will do for now
					enabledSensors= (enabledSensors|SENSOR_VBATT_SR30);	
				} else {
					signalNameArray[i+1]="Magnetometer X";
					enabledSensors= (enabledSensors|SENSOR_MAG);
				}
				signalDataTypeArray[i+1] = "i16";
				packetSize=packetSize+2;
				
			}
			else if ((byte)signalid[i]==(byte)0x07)
			{
				if(mShimmerVersion==4){
					signalNameArray[i+1]="High Range Accelerometer X";
					enabledSensors= (enabledSensors|SENSOR_D_ACCEL_SR30);
				} else {
					signalNameArray[i+1]="Magnetometer Y";
					enabledSensors= (enabledSensors|SENSOR_MAG);
				}
				signalDataTypeArray[i+1] = "i16";
				packetSize=packetSize+2;
				
			}
			else if ((byte)signalid[i]==(byte)0x08)
			{	
				if(mShimmerVersion==4){
					signalNameArray[i+1]="High Range Accelerometer Y";
					enabledSensors= (enabledSensors|SENSOR_D_ACCEL_SR30);
				} else {
					signalNameArray[i+1]="Magnetometer Z";
					enabledSensors= (enabledSensors|SENSOR_MAG);
				}
				signalDataTypeArray[i+1] = "i16";
				packetSize=packetSize+2;
				
			}
			else if ((byte)signalid[i]==(byte)0x09)
			{
				if(mShimmerVersion==4){
					signalNameArray[i+1]="High Range Accelerometer Z";
					signalDataTypeArray[i+1] = "i16";
					enabledSensors= (enabledSensors|SENSOR_D_ACCEL_SR30);
				} else {
					signalNameArray[i+1]="ECG RA LL";
					signalDataTypeArray[i+1] = "u12";
					enabledSensors= (enabledSensors|SENSOR_ECG);
				}
				packetSize=packetSize+2;
				
			}
			else if ((byte)signalid[i]==(byte)0x0A)
			{
				if(mShimmerVersion==4){
					signalNameArray[i+1]="Magnetometer X";
					signalDataTypeArray[i+1] = "i16";			
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_MAG_SR30);
				} else {
					signalNameArray[i+1]="ECG LA LL";
					signalDataTypeArray[i+1] = "u12";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_ECG);
				}
			}
			else if ((byte)signalid[i]==(byte)0x0B)
			{
				if(mShimmerVersion==4){
					signalNameArray[i+1]="Magnetometer Y";
					signalDataTypeArray[i+1] = "i16";			
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_MAG_SR30);
				} else {
					signalNameArray[i+1]="GSR Raw";
					signalDataTypeArray[i+1] = "u16";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_GSR);
				}
			}
			else if ((byte)signalid[i]==(byte)0x0C)
			{
				if(mShimmerVersion==4){
					signalNameArray[i+1]="Magnetometer Z";
					signalDataTypeArray[i+1] = "i16";			
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_MAG_SR30);
				} else {
					signalNameArray[i+1]="GSR Res";
					signalDataTypeArray[i+1] = "u16";
					packetSize=packetSize+2;
					enabledSensors= (enabledSensors|SENSOR_GSR);
				}
			}
			else if ((byte)signalid[i]==(byte)0x0D)
			{
				signalNameArray[i+1]="EMG";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				enabledSensors= (enabledSensors|SENSOR_EMG);
			}
			else if ((byte)signalid[i]==(byte)0x0E)
			{
				signalNameArray[i+1]="Exp Board A0";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				enabledSensors= (enabledSensors|SENSOR_EXP_BOARD_A0);
			}
			else if ((byte)signalid[i]==(byte)0x0F)
			{
				signalNameArray[i+1]="Exp Board A7";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				enabledSensors= (enabledSensors|SENSOR_EXP_BOARD_A7);
			}
			else if ((byte)signalid[i]==(byte)0x10)
			{
				signalNameArray[i+1]="Strain Gauge High";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				enabledSensors= (enabledSensors|SENSOR_STRAIN);
			}
			else if ((byte)signalid[i]==(byte)0x11)
			{
				signalNameArray[i+1]="Strain Gauge Low";
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
				enabledSensors= (enabledSensors|SENSOR_STRAIN);
			}
			else if ((byte)signalid[i]==(byte)0x12)
			{
				signalNameArray[i+1]="Heart Rate";
				if (mFWVersion==0.1){
					signalDataTypeArray[i+1] = "u8";
					packetSize=packetSize+1;
				} else {
					signalDataTypeArray[i+1] = "u16"; 
					packetSize=packetSize+2;
				}
				enabledSensors= (enabledSensors|SENSOR_HEART);
			}
			else
			{
				signalNameArray[i+1]=Byte.toString(signalid[i]);
				signalDataTypeArray[i+1] = "u12";
				packetSize=packetSize+2;
			}
		}
		
		
		
		
		mSignalNameArray=signalNameArray;
		mSignalDataTypeArray=signalDataTypeArray;
		mPacketSize=packetSize;
		mEnabledSensors=enabledSensors;
	}
	
   private void retrievecalibrationparametersfrompacket(byte[] bufferCalibrationParameters, int packetType)
	{
		String[] dataType={"i16","i16","i16","i16","i16","i16","i8","i8","i8","i8","i8","i8","i8","i8","i8"}; 
		int[] formattedPacket=formatdatapacketreverse(bufferCalibrationParameters,dataType); // using the datatype the calibration parameters are converted
	   	double[] AM=new double[9];
	   	for (int i=0;i<9;i++)
		{
			AM[i]=((double)formattedPacket[6+i])/100;
		}
			
		double[][] AlignmentMatrix = {{AM[0],AM[1],AM[2]},{AM[3],AM[4],AM[5]},{AM[6],AM[7],AM[8]}}; 				
		double[][] SensitivityMatrix = {{formattedPacket[3],0,0},{0,formattedPacket[4],0},{0,0,formattedPacket[5]}}; 
		double[][] OffsetVector = {{formattedPacket[0]},{formattedPacket[1]},{formattedPacket[2]}};
		
		
		if (packetType==ACCEL_CALIBRATION_RESPONSE && SensitivityMatrix[0][0]!=-1) {   //used to be 65535 but changed to -1 as we are now using i16
			mDefaultCalibrationParametersAccel = false;
			Log.d("Shimmer","Accel Offet Vector(0,0): " + Double.toString(OffsetVector[0][0]) + "; Accel Sen Matrix(0,0): " + Double.toString(SensitivityMatrix[0][0]) +"; Accel Align Matrix(0,0): " + Double.toString(AlignmentMatrix[0][0]));
			AlignmentMatrixAccel = AlignmentMatrix;
			OffsetVectorAccel = OffsetVector;
			SensitivityMatrixAccel = SensitivityMatrix;
		} else if(packetType==ACCEL_CALIBRATION_RESPONSE && SensitivityMatrix[0][0]==-1){
			if (getAccelRange()==0){
				SensitivityMatrixAccel = SensitivityMatrixAccel1p5g; 
			} else if (getAccelRange()==1){
				SensitivityMatrixAccel = SensitivityMatrixAccel2g; 
			} else if (getAccelRange()==2){
				SensitivityMatrixAccel = SensitivityMatrixAccel4g; 
			} else if (getAccelRange()==3){
				SensitivityMatrixAccel = SensitivityMatrixAccel6g; 
			}
		} else if (packetType==GYRO_CALIBRATION_RESPONSE && SensitivityMatrix[0][0]!=-1) {
			mDefaultCalibrationParametersGyro = false;
			AlignmentMatrixGyro = AlignmentMatrix;
			OffsetVectorGyro = OffsetVector;
			SensitivityMatrixGyro = SensitivityMatrix;
			SensitivityMatrixGyro[0][0] = SensitivityMatrixGyro[0][0]/100;
			SensitivityMatrixGyro[1][1] = SensitivityMatrixGyro[1][1]/100;
			SensitivityMatrixGyro[2][2] = SensitivityMatrixGyro[2][2]/100;
			Log.d("Shimmer","Gyro Offet Vector(0,0): " + Double.toString(OffsetVector[0][0]) + "; Gyro Sen Matrix(0,0): " + Double.toString(SensitivityMatrix[0][0]) +"; Gyro Align Matrix(0,0): " + Double.toString(AlignmentMatrix[0][0]));
		} else if (packetType==MAG_CALIBRATION_RESPONSE && SensitivityMatrix[0][0]!=-1) {
			mDefaultCalibrationParametersMag = false;
			AlignmentMatrixMag = AlignmentMatrix;
			OffsetVectorMag = OffsetVector;
			SensitivityMatrixMag = SensitivityMatrix;
			Log.d("Shimmer","Mag Offet Vector(0,0): " + Double.toString(OffsetVector[0][0]) + "; Mag Sen Matrix(0,0): " + Double.toString(SensitivityMatrix[0][0]) +"; Mag Align Matrix(0,0): " + Double.toString(AlignmentMatrix[0][0]));
		}
	}

   private double[][] matrixinverse3x3(double[][] data) {
	    double a,b,c,d,e,f,g,h,i;
	    a=data[0][0];
	    b=data[0][1];
	    c=data[0][2];
	    d=data[1][0];
	    e=data[1][1];
	    f=data[1][2];
	    g=data[2][0];
	    h=data[2][1];
	    i=data[2][2];
	    //
	    double deter=a*e*i+b*f*g+c*d*h-c*e*g-b*d*i-a*f*h;
	    double[][] answer=new double[3][3];
	    answer[0][0]=(1/deter)*(e*i-f*h);
	    
	    answer[0][1]=(1/deter)*(c*h-b*i);
	    answer[0][2]=(1/deter)*(b*f-c*e);
	    answer[1][0]=(1/deter)*(f*g-d*i);
	    answer[1][1]=(1/deter)*(a*i-c*g);
	    answer[1][2]=(1/deter)*(c*d-a*f);
	    answer[2][0]=(1/deter)*(d*h-e*g);
	    answer[2][1]=(1/deter)*(g*b-a*h);
	    answer[2][2]=(1/deter)*(a*e-b*d);
	    return answer;
	    }
	private double[][] matrixminus(double[][] a ,double[][] b) {
		          int aRows = a.length,
			      aColumns = a[0].length,
			      bRows = b.length,
			      bColumns = b[0].length;
		          if (( aColumns != bColumns )&&( aRows != bRows )) {
		    		    throw new IllegalArgumentException(" Matrix did not match");
		    		  }
	    		  double[][] resultant = new double[aRows][bColumns];
		          for(int i = 0; i < aRows; i++) { // aRow
		        	  for(int k = 0; k < aColumns; k++) { // aColumn
		        	
		        		  resultant[i][k]=a[i][k]-b[i][k];
		        		  
		        	  }
		          }
		        	return resultant;
	}
   
   private double[][] matrixmultiplication(double[][] a,double[][] b) {
   
	          int aRows = a.length,
		      aColumns = a[0].length,
		      bRows = b.length,
		      bColumns = b[0].length;
   		 
   		  if ( aColumns != bRows ) {
   		    throw new IllegalArgumentException("A:Rows: " + aColumns + " did not match B:Columns " + bRows + ".");
   		  }
   		 
   		  double[][] resultant = new double[aRows][bColumns];
   		 
   		  for(int i = 0; i < aRows; i++) { // aRow
   		    for(int j = 0; j < bColumns; j++) { // bColumn
   		      for(int k = 0; k < aColumns; k++) { // aColumn
   		        resultant[i][j] += a[i][k] * b[k][j];
   		      }
   		    }
   		  }
   		 
   		  return resultant;
   }
	    
   protected double calibrateTimeStamp(double timeStamp){
   	//first convert to continuous time stamp
   	double calibratedTimeStamp=0;
   	if (mLastReceivedTimeStamp>(timeStamp+(65536*mCurrentTimeStampCycle))){ 
   		mCurrentTimeStampCycle=mCurrentTimeStampCycle+1;
   	}
   	
   	mLastReceivedTimeStamp=(timeStamp+(65536*mCurrentTimeStampCycle));
   	calibratedTimeStamp=mLastReceivedTimeStamp/32768*1000;   // to convert into mS
   	if (mFirstTimeCalTime){
   		mFirstTimeCalTime=false;
   		mCalTimeStart = calibratedTimeStamp;
   	}
   	if (mLastReceivedCalibratedTimeStamp!=-1){
   		double timeDifference=calibratedTimeStamp-mLastReceivedCalibratedTimeStamp;
   		if (timeDifference>(1/(mSamplingRate-1))*1000){
   			mPacketLossCount=mPacketLossCount+1;
   			Long mTotalNumberofPackets=(long) ((calibratedTimeStamp-mCalTimeStart)/(1/mSamplingRate*1000));
   			Log.d("SHIMMERPACKETRR"," " + Long.toString(mPacketLossCount) + " " + Double.toString(timeDifference) + " " + Double.toString((double)(mTotalNumberofPackets-mPacketLossCount)/(double)mTotalNumberofPackets));
   			mPacketReceptionRate = (double)((mTotalNumberofPackets-mPacketLossCount)/(double)mTotalNumberofPackets)*100;
   			mHandler.obtainMessage(Shimmer.MESSAGE_PACKET_LOSS_DETECTED,  new ObjectCluster(mMyName,getBluetoothAddress())).sendToTarget();
   		}
   	}	
   	mLastReceivedCalibratedTimeStamp=calibratedTimeStamp;
   	return calibratedTimeStamp;
   }
	    
   protected double[] calibrateInertialSensorData(double[] data, double[][] AM, double[][] SM, double[][] OV) {
		/*  Based on the theory outlined by Ferraris F, Grimaldi U, and Parvis M.  
           in "Procedure for effortless in-field calibration of three-axis rate gyros and accelerometers" Sens. Mater. 1995; 7: 311-30.            
           C = [R^(-1)] .[K^(-1)] .([U]-[B])
			where.....
			[C] -> [3 x n] Calibrated Data Matrix 
			[U] -> [3 x n] Uncalibrated Data Matrix
			[B] ->  [3 x n] Replicated Sensor Offset Vector Matrix 
			[R^(-1)] -> [3 x 3] Inverse Alignment Matrix
			[K^(-1)] -> [3 x 3] Inverse Sensitivity Matrix
			n = Number of Samples
			*/
      double [][] data2d=new double [3][1];
      data2d[0][0]=data[0];
      data2d[1][0]=data[1];
      data2d[2][0]=data[2];
      data2d= matrixmultiplication(matrixmultiplication(matrixinverse3x3(AM),matrixinverse3x3(SM)),matrixminus(data2d,OV));
      data[0]=data2d[0][0];
      data[1]=data2d[1][0];
      data[2]=data2d[2][0];
      return data;
	}
	    
   protected double calibrateU12AdcValue(double uncalibratedData,double offset,double vRefP,double gain){
		double calibratedData=(uncalibratedData-offset)*(((vRefP*1000)/gain)/4095);
   	return calibratedData;
   }
	    
   protected double calibrateGsrData(double gsrUncalibratedData,double p1, double p2){
       gsrUncalibratedData = (double)((int)gsrUncalibratedData & 4095); 
       //the following polynomial is deprecated and has been replaced with a more accurate linear one, see GSR user guide for further details
       //double gsrCalibratedData = (p1*Math.pow(gsrUncalibratedData,4)+p2*Math.pow(gsrUncalibratedData,3)+p3*Math.pow(gsrUncalibratedData,2)+p4*gsrUncalibratedData+p5)/1000;
       //the following is the new linear method see user GSR user guide for further details
       double gsrCalibratedData = (1/(p1*gsrUncalibratedData+p2))*1000; //kohms 
       return gsrCalibratedData;  
   }
	    

	protected Object buildMsg(byte[] newPacket, String... Instructions) {
	   	ObjectCluster objectCluster=new ObjectCluster(mMyName,getBluetoothAddress());
	   	double [] calibratedData=new double[mNChannels + 1]; //plus 1 because of the time stamp
	   	int[] newPacketInt=parsedData(newPacket,mSignalDataTypeArray);
	   	double[] tempData=new double[3];
	   	Vector3d accelerometer = new Vector3d();
	   	Vector3d magnetometer = new Vector3d();
	   	Vector3d gyroscope = new Vector3d();
	   	mTempPacketCountforBatt=mTempPacketCountforBatt+1;
	   	for (int i=0;i<Instructions.length;i++) {
	   		if ((Instructions[i]=="a" || Instructions[i]=="c")) {
	   			int iTimeStamp=getSignalIndex("TimeStamp"); //find index
	   			tempData[0]=(double)newPacketInt[1];
	   			objectCluster.mPropertyCluster.put("Timestamp",new FormatCluster("RAW","no units",(double)newPacketInt[iTimeStamp]));
				objectCluster.mPropertyCluster.put("Timestamp",new FormatCluster("CAL","mSecs",calibrateTimeStamp((double)newPacketInt[iTimeStamp])));
					
				if (mShimmerVersion==4){
		   		    if (((mEnabledSensors & 0xFF)& SENSOR_A_ACCEL_SR30) > 0){
					    int iAccelX=getSignalIndex("Low Noise Accelerometer X"); //find index
					    int iAccelY=getSignalIndex("Low Noise Accelerometer Y"); //find index
					    int iAccelZ=getSignalIndex("Low Noise Accelerometer Z"); //find index
					    tempData[0]=(double)newPacketInt[iAccelX];
					    tempData[1]=(double)newPacketInt[iAccelY];
					    tempData[2]=(double)newPacketInt[iAccelZ];
					    double[] accelCalibratedData=calibrateInertialSensorData(tempData, AlignmentMatrixAccel, SensitivityMatrixAccel, OffsetVectorAccel);
					    calibratedData[iAccelX]=accelCalibratedData[0];
					    calibratedData[iAccelY]=accelCalibratedData[1];
					    calibratedData[iAccelZ]=accelCalibratedData[2];
					    
					    objectCluster.mPropertyCluster.put("Low Noise Accelerometer X",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelX]));
					    objectCluster.mPropertyCluster.put("Low Noise Accelerometer Y",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelY]));
					    objectCluster.mPropertyCluster.put("Low Noise Accelerometer Z",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelZ]));
					    if (mDefaultCalibrationParametersAccel == true) {
	    				    objectCluster.mPropertyCluster.put("Low Noise Accelerometer X",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[0]));
	    				    objectCluster.mPropertyCluster.put("Low Noise Accelerometer Y",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[1]));
	    				    objectCluster.mPropertyCluster.put("Low Noise Accelerometer Z",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[2]));
					    } else {
					    	objectCluster.mPropertyCluster.put("Low Noise Accelerometer X",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[0]));
		   				    objectCluster.mPropertyCluster.put("Low Noise Accelerometer Y",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[1]));
		   				    objectCluster.mPropertyCluster.put("Low Noise Accelerometer Z",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[2]));
					    }	
					}
		   		    if (((mEnabledSensors & 0xFF)& SENSOR_D_ACCEL_SR30) > 0){
					    int iAccelX=getSignalIndex("High Range Accelerometer X"); //find index
					    int iAccelY=getSignalIndex("High Range Accelerometer Y"); //find index
					    int iAccelZ=getSignalIndex("High Range Accelerometer Z"); //find index
					    tempData[0]=(double)newPacketInt[iAccelX];
					    tempData[1]=(double)newPacketInt[iAccelY];
					    tempData[2]=(double)newPacketInt[iAccelZ];
					    double[] accelCalibratedData=calibrateInertialSensorData(tempData, AlignmentMatrixAccel, SensitivityMatrixAccel, OffsetVectorAccel);
					    calibratedData[iAccelX]=accelCalibratedData[0];
					    calibratedData[iAccelY]=accelCalibratedData[1];
					    calibratedData[iAccelZ]=accelCalibratedData[2];
					    
					    objectCluster.mPropertyCluster.put("High Range Accelerometer X",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelX]));
					    objectCluster.mPropertyCluster.put("High Range Accelerometer Y",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelY]));
					    objectCluster.mPropertyCluster.put("High Range Accelerometer Z",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelZ]));
					    if (mDefaultCalibrationParametersAccel == true) {
	    				    objectCluster.mPropertyCluster.put("High Range Accelerometer X",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[0]));
	    				    objectCluster.mPropertyCluster.put("High Range Accelerometer Y",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[1]));
	    				    objectCluster.mPropertyCluster.put("High Range Accelerometer Z",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[2]));
					    } else {
					    	objectCluster.mPropertyCluster.put("High Range Accelerometer X",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[0]));
		   				    objectCluster.mPropertyCluster.put("High Range Accelerometer Y",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[1]));
		   				    objectCluster.mPropertyCluster.put("High Range Accelerometer Z",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[2]));
					    }	
					}
		   		    
		   		 if (((mEnabledSensors & 0xFF)& SENSOR_GYRO_SR30) > 0) {
					    int iGyroX=getSignalIndex("Gyroscope X");
					    int iGyroY=getSignalIndex("Gyroscope Y");
					    int iGyroZ=getSignalIndex("Gyroscope Z");
					    tempData[0]=(double)newPacketInt[iGyroX];
					    tempData[1]=(double)newPacketInt[iGyroY];
					    tempData[2]=(double)newPacketInt[iGyroZ];
					    double[] gyroCalibratedData=calibrateInertialSensorData(tempData, AlignmentMatrixGyro, SensitivityMatrixGyro, OffsetVectorGyro);
					    calibratedData[iGyroX]=gyroCalibratedData[0];
					    calibratedData[iGyroY]=gyroCalibratedData[1];
					    calibratedData[iGyroZ]=gyroCalibratedData[2];
				    
					    objectCluster.mPropertyCluster.put("Gyroscope X",new FormatCluster("RAW","no units",(double)newPacketInt[iGyroX]));
					    objectCluster.mPropertyCluster.put("Gyroscope Y",new FormatCluster("RAW","no units",(double)newPacketInt[iGyroY]));
					    objectCluster.mPropertyCluster.put("Gyroscope Z",new FormatCluster("RAW","no units",(double)newPacketInt[iGyroZ]));
					    if (mDefaultCalibrationParametersGyro == true) {
	    				    objectCluster.mPropertyCluster.put("Gyroscope X",new FormatCluster("CAL","deg/sec*",gyroCalibratedData[0]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Y",new FormatCluster("CAL","deg/sec*",gyroCalibratedData[1]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Z",new FormatCluster("CAL","deg/sec*",gyroCalibratedData[2]));
					    } else {
	    				    objectCluster.mPropertyCluster.put("Gyroscope X",new FormatCluster("CAL","deg/sec",gyroCalibratedData[0]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Y",new FormatCluster("CAL","deg/sec",gyroCalibratedData[1]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Z",new FormatCluster("CAL","deg/sec",gyroCalibratedData[2]));
					    } 
					    
					}
					if (((mEnabledSensors & 0xFF)& SENSOR_MAG_SR30) > 0) {
					    int iMagX=getSignalIndex("Magnetometer X");
					    int iMagY=getSignalIndex("Magnetometer Y");
					    int iMagZ=getSignalIndex("Magnetometer Z");
					    tempData[0]=(double)newPacketInt[iMagX];
					    tempData[1]=(double)newPacketInt[iMagY];
					    tempData[2]=(double)newPacketInt[iMagZ];
					    double[] magCalibratedData=calibrateInertialSensorData(tempData, AlignmentMatrixMag, SensitivityMatrixMag, OffsetVectorMag);
					    calibratedData[iMagX]=magCalibratedData[0];
					    calibratedData[iMagY]=magCalibratedData[1];
					    calibratedData[iMagZ]=magCalibratedData[2];
					    
					    objectCluster.mPropertyCluster.put("Magnetometer X",new FormatCluster("RAW","no units",(double)newPacketInt[iMagX]));
					    objectCluster.mPropertyCluster.put("Magnetometer Y",new FormatCluster("RAW","no units",(double)newPacketInt[iMagY]));
					    objectCluster.mPropertyCluster.put("Magnetometer Z",new FormatCluster("RAW","no units",(double)newPacketInt[iMagZ]));
					    if (mDefaultCalibrationParametersMag == true) {
		    				    objectCluster.mPropertyCluster.put("Magnetometer X",new FormatCluster("CAL","local*",magCalibratedData[0]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Y",new FormatCluster("CAL","local*",magCalibratedData[1]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Z",new FormatCluster("CAL","local*",magCalibratedData[2]));
					    } else {
					    		objectCluster.mPropertyCluster.put("Magnetometer X",new FormatCluster("CAL","local",magCalibratedData[0]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Y",new FormatCluster("CAL","local",magCalibratedData[1]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Z",new FormatCluster("CAL","local",magCalibratedData[2]));
					    }
					}
					
					if (((mEnabledSensors & 0xFF) & SENSOR_VBATT_SR30) > 0) {
					    int iA0 = getSignalIndex("VSenseBatt");
					    tempData[0] = (double)newPacketInt[iA0];
				    	 calibratedData[iA0]=calibrateU12AdcValue(tempData[0],0,3,1)*1.988;
				    	 objectCluster.mPropertyCluster.put("VSenseBatt",new FormatCluster("RAW","no Units",(double)newPacketInt[iA0]));
						 objectCluster.mPropertyCluster.put("VSenseBatt",new FormatCluster("CAL","mVolts",calibratedData[iA0]));
							 
				
					}
		   		    
				} else {
					
		   		    if (((mEnabledSensors & 0xFF)& SENSOR_ACCEL) > 0){
					    int iAccelX=getSignalIndex("Accelerometer X"); //find index
					    int iAccelY=getSignalIndex("Accelerometer Y"); //find index
					    int iAccelZ=getSignalIndex("Accelerometer Z"); //find index
					    tempData[0]=(double)newPacketInt[iAccelX];
					    tempData[1]=(double)newPacketInt[iAccelY];
					    tempData[2]=(double)newPacketInt[iAccelZ];
					    double[] accelCalibratedData=calibrateInertialSensorData(tempData, AlignmentMatrixAccel, SensitivityMatrixAccel, OffsetVectorAccel);
					    calibratedData[iAccelX]=accelCalibratedData[0];
					    calibratedData[iAccelY]=accelCalibratedData[1];
					    calibratedData[iAccelZ]=accelCalibratedData[2];
					    
					    objectCluster.mPropertyCluster.put("Accelerometer X",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelX]));
					    objectCluster.mPropertyCluster.put("Accelerometer Y",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelY]));
					    objectCluster.mPropertyCluster.put("Accelerometer Z",new FormatCluster("RAW","no units",(double)newPacketInt[iAccelZ]));
					    if (mDefaultCalibrationParametersAccel == true) {
	    				    objectCluster.mPropertyCluster.put("Accelerometer X",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[0]));
	    				    objectCluster.mPropertyCluster.put("Accelerometer Y",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[1]));
	    				    objectCluster.mPropertyCluster.put("Accelerometer Z",new FormatCluster("CAL","m/(sec^2)*",accelCalibratedData[2]));
	    				    accelerometer.x=accelCalibratedData[0];
		   				    accelerometer.y=accelCalibratedData[1];
		   				    accelerometer.z=accelCalibratedData[2];
					    } else {
					    	objectCluster.mPropertyCluster.put("Accelerometer X",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[0]));
		   				    objectCluster.mPropertyCluster.put("Accelerometer Y",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[1]));
		   				    objectCluster.mPropertyCluster.put("Accelerometer Z",new FormatCluster("CAL","m/(sec^2)",accelCalibratedData[2]));
		   				    accelerometer.x=accelCalibratedData[0];
		   				    accelerometer.y=accelCalibratedData[1];
		   				    accelerometer.z=accelCalibratedData[2];
					    }
					    	
					}
		   		    
					if (((mEnabledSensors & 0xFF)& SENSOR_GYRO) > 0) {
					    int iGyroX=getSignalIndex("Gyroscope X");
					    int iGyroY=getSignalIndex("Gyroscope Y");
					    int iGyroZ=getSignalIndex("Gyroscope Z");
					    tempData[0]=(double)newPacketInt[iGyroX];
					    tempData[1]=(double)newPacketInt[iGyroY];
					    tempData[2]=(double)newPacketInt[iGyroZ];
					    double[] gyroCalibratedData=calibrateInertialSensorData(tempData, AlignmentMatrixGyro, SensitivityMatrixGyro, OffsetVectorGyro);
					    calibratedData[iGyroX]=gyroCalibratedData[0];
					    calibratedData[iGyroY]=gyroCalibratedData[1];
					    calibratedData[iGyroZ]=gyroCalibratedData[2];
				    
					    objectCluster.mPropertyCluster.put("Gyroscope X",new FormatCluster("RAW","no units",(double)newPacketInt[iGyroX]));
					    objectCluster.mPropertyCluster.put("Gyroscope Y",new FormatCluster("RAW","no units",(double)newPacketInt[iGyroY]));
					    objectCluster.mPropertyCluster.put("Gyroscope Z",new FormatCluster("RAW","no units",(double)newPacketInt[iGyroZ]));
					    if (mDefaultCalibrationParametersGyro == true) {
	    				    objectCluster.mPropertyCluster.put("Gyroscope X",new FormatCluster("CAL","deg/sec*",gyroCalibratedData[0]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Y",new FormatCluster("CAL","deg/sec*",gyroCalibratedData[1]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Z",new FormatCluster("CAL","deg/sec*",gyroCalibratedData[2]));
	    				    gyroscope.x=gyroCalibratedData[0]*Math.PI/180;
		   				    gyroscope.y=gyroCalibratedData[1]*Math.PI/180;
		   				    gyroscope.z=gyroCalibratedData[2]*Math.PI/180;
					    } else {
	    				    objectCluster.mPropertyCluster.put("Gyroscope X",new FormatCluster("CAL","deg/sec",gyroCalibratedData[0]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Y",new FormatCluster("CAL","deg/sec",gyroCalibratedData[1]));
	    				    objectCluster.mPropertyCluster.put("Gyroscope Z",new FormatCluster("CAL","deg/sec",gyroCalibratedData[2]));
		   				    gyroscope.x=gyroCalibratedData[0]*Math.PI/180;
		   				    gyroscope.y=gyroCalibratedData[1]*Math.PI/180;
		   				    gyroscope.z=gyroCalibratedData[2]*Math.PI/180;
		   				    if (mEnableOntheFlyGyroOVCal){
		   				    	mGyroX.addValue(gyroCalibratedData[0]);
		   				    	mGyroY.addValue(gyroCalibratedData[1]);
		   				    	mGyroZ.addValue(gyroCalibratedData[2]);
		   				    	mGyroXRaw.addValue((double)newPacketInt[iGyroX]);
		   				    	mGyroYRaw.addValue((double)newPacketInt[iGyroY]);
		   				    	mGyroZRaw.addValue((double)newPacketInt[iGyroZ]);
		   				    	if (mGyroX.getStandardDeviation()<mGyroOVCalThreshold && mGyroY.getStandardDeviation()<mGyroOVCalThreshold && mGyroZ.getStandardDeviation()<mGyroOVCalThreshold){
		   				    		OffsetVectorGyro[0][0]=mGyroXRaw.getMean();
		   				    		OffsetVectorGyro[1][0]=mGyroYRaw.getMean();
		   				    		OffsetVectorGyro[2][0]=mGyroZRaw.getMean();
		   				    	}
		   				    }
					    } 
					    
					}
					if (((mEnabledSensors & 0xFF)& SENSOR_MAG) > 0) {
					    int iMagX=getSignalIndex("Magnetometer X");
					    int iMagY=getSignalIndex("Magnetometer Y");
					    int iMagZ=getSignalIndex("Magnetometer Z");
					    tempData[0]=(double)newPacketInt[iMagX];
					    tempData[1]=(double)newPacketInt[iMagY];
					    tempData[2]=(double)newPacketInt[iMagZ];
					    double[] magCalibratedData=calibrateInertialSensorData(tempData, AlignmentMatrixMag, SensitivityMatrixMag, OffsetVectorMag);
					    calibratedData[iMagX]=magCalibratedData[0];
					    calibratedData[iMagY]=magCalibratedData[1];
					    calibratedData[iMagZ]=magCalibratedData[2];
					    
					    objectCluster.mPropertyCluster.put("Magnetometer X",new FormatCluster("RAW","no units",(double)newPacketInt[iMagX]));
					    objectCluster.mPropertyCluster.put("Magnetometer Y",new FormatCluster("RAW","no units",(double)newPacketInt[iMagY]));
					    objectCluster.mPropertyCluster.put("Magnetometer Z",new FormatCluster("RAW","no units",(double)newPacketInt[iMagZ]));
					    if (mDefaultCalibrationParametersMag == true) {
		    				    objectCluster.mPropertyCluster.put("Magnetometer X",new FormatCluster("CAL","local*",magCalibratedData[0]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Y",new FormatCluster("CAL","local*",magCalibratedData[1]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Z",new FormatCluster("CAL","local*",magCalibratedData[2]));
		    				    magnetometer.x=magCalibratedData[0];
		    				    magnetometer.y=magCalibratedData[1];
		    				    magnetometer.z=magCalibratedData[2];
					    } else {
					    		objectCluster.mPropertyCluster.put("Magnetometer X",new FormatCluster("CAL","local",magCalibratedData[0]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Y",new FormatCluster("CAL","local",magCalibratedData[1]));
		    				    objectCluster.mPropertyCluster.put("Magnetometer Z",new FormatCluster("CAL","local",magCalibratedData[2]));
		    				    magnetometer.x=magCalibratedData[0];
		    				    magnetometer.y=magCalibratedData[1];
		    				    magnetometer.z=magCalibratedData[2];
					    }
					}
					
					
					if (((mEnabledSensors & 0xFF)& SENSOR_ACCEL) > 0 && ((mEnabledSensors & 0xFF)& SENSOR_GYRO) > 0 && ((mEnabledSensors & 0xFF)& SENSOR_MAG) > 0 && mOrientationEnabled ){
					
						Quaternion q = mOrientationAlgo.update(accelerometer.x,accelerometer.y,accelerometer.z, gyroscope.x,gyroscope.y,gyroscope.z, magnetometer.x,magnetometer.y,magnetometer.z);
	
						
						double theta, Rx, Ry, Rz, rho;
                        rho = Math.acos(q.q1);
                        theta = rho * 2;
                        Rx = q.q2 / Math.sin(rho);
                        Ry = q.q3 / Math.sin(rho);
                        Rz = q.q4 / Math.sin(rho);
						

						objectCluster.mPropertyCluster.put("Axis Angle A",new FormatCluster("CAL","local",theta));
    				    objectCluster.mPropertyCluster.put("Axis Angle X",new FormatCluster("CAL","local",Rx));
    				    objectCluster.mPropertyCluster.put("Axis Angle Y",new FormatCluster("CAL","local",Ry));
    				    objectCluster.mPropertyCluster.put("Axis Angle Z",new FormatCluster("CAL","local",Rz));
    				   
    				    
    				    objectCluster.mPropertyCluster.put("Quartenion 0",new FormatCluster("CAL","local",q.q1));
    				    objectCluster.mPropertyCluster.put("Quartenion 1",new FormatCluster("CAL","local",q.q2));
    				    objectCluster.mPropertyCluster.put("Quartenion 2",new FormatCluster("CAL","local",q.q3));
    				    objectCluster.mPropertyCluster.put("Quartenion 3",new FormatCluster("CAL","local",q.q4));
					}
					
					
					
					if (((mEnabledSensors & 0xFF) & SENSOR_GSR) > 0) {
					    int iGSR = getSignalIndex("GSR Raw");
					    tempData[0] = (double)newPacketInt[iGSR];
					    int newGSRRange = -1; // initialized to -1 so it will only come into play if mGSRRange = 4  
					    
					    double p1=0,p2=0;//,p3=0,p4=0,p5=0;
			    		if (mGSRRange==4){
			    		    newGSRRange=(49152 & (int)tempData[0])>>14; 
			    		}
		                if (mGSRRange==0 || newGSRRange==0) { //Note that from FW 1.0 onwards the MSB of the GSR data contains the range
		                   // the polynomial function used for calibration has been deprecated, it is replaced with a linear function
		                	/* p1 = 6.5995E-9;
		                    p2 = -6.895E-5;
		                    p3 = 2.699E-1;
		                    p4 = -4.769835E+2;
		                    p5 = 3.403513341E+5;*/
		                	p1 = 0.0373;
		                	p2 = -24.9915;
		                } else if (mGSRRange==1 || newGSRRange==1) {
		                    /*p1 = 1.3569627E-8;
		                    p2 = -1.650399E-4;
		                    p3 = 7.54199E-1;
		                    p4 = -1.5726287856E+3;
		                    p5 = 1.367507927E+6;*/
		                	p1 = 0.0054;
		                	p2 = -3.5194;
		                } else if (mGSRRange==2 || newGSRRange==2) {
		                    /*p1 = 2.550036498E-8;
		                    p2 = -3.3136E-4;
		                    p3 = 1.6509426597E+0;
		                    p4 = -3.833348044E+3;
		                    p5 = 3.8063176947E+6;*/
		                	p1 = 0.0015;
		                	p2 = -1.0163;
		                } else if (mGSRRange==3  || newGSRRange==3) {
		                    /*p1 = 3.7153627E-7;
		                    p2 = -4.239437E-3;
		                    p3 = 1.7905709E+1;
		                    p4 = -3.37238657E+4;
		                    p5 = 2.53680446279E+7;*/
		                	p1 = 4.5580e-04;
		                	p2 = -0.3014;
		                }
					    
		                calibratedData[iGSR] = calibrateGsrData(tempData[0],p1,p2);
		                objectCluster.mPropertyCluster.put("GSR",new FormatCluster("RAW","no units",(double)newPacketInt[iGSR]));
					    objectCluster.mPropertyCluster.put("GSR",new FormatCluster("CAL","kOhms",calibratedData[iGSR]));
					}
					if (((mEnabledSensors & 0xFF) & SENSOR_ECG) > 0) {
					    int iECGRALL = getSignalIndex("ECG RA LL");
					    int iECGLALL = getSignalIndex("ECG LA LL");
					    tempData[0] = (double)newPacketInt[iECGRALL];
					    tempData[1] = (double)newPacketInt[iECGLALL];
					    calibratedData[iECGRALL]=calibrateU12AdcValue(tempData[0],OffsetECGRALL,3,GainECGRALL);
					    calibratedData[iECGLALL]=calibrateU12AdcValue(tempData[1],OffsetECGLALL,3,GainECGLALL);
					    objectCluster.mPropertyCluster.put("ECG RA-LL",new FormatCluster("RAW","no units",(double)newPacketInt[iECGRALL]));
					    objectCluster.mPropertyCluster.put("ECG LA-LL",new FormatCluster("RAW","no units",(double)newPacketInt[iECGLALL]));
					    if (mDefaultCalibrationParametersECG == true) {
					    	objectCluster.mPropertyCluster.put("ECG RA-LL",new FormatCluster("CAL","mVolts*",calibratedData[iECGRALL]));
					    	objectCluster.mPropertyCluster.put("ECG LA-LL",new FormatCluster("CAL","mVolts*",calibratedData[iECGLALL]));
					    } else {
					    	objectCluster.mPropertyCluster.put("ECG RA-LL",new FormatCluster("CAL","mVolts",calibratedData[iECGRALL]));
					    	objectCluster.mPropertyCluster.put("ECG LA-LL",new FormatCluster("CAL","mVolts",calibratedData[iECGLALL]));
					    }
					}
					if (((mEnabledSensors & 0xFF) & SENSOR_EMG) > 0) {
					    int iEMG = getSignalIndex("EMG");
					    tempData[0] = (double)newPacketInt[iEMG];
					    calibratedData[iEMG]=calibrateU12AdcValue(tempData[0],OffsetEMG,3,GainEMG);
					    objectCluster.mPropertyCluster.put("EMG",new FormatCluster("RAW","no units",(double)newPacketInt[iEMG]));
					    
					    if (mDefaultCalibrationParametersEMG == true){
					    	objectCluster.mPropertyCluster.put("EMG",new FormatCluster("CAL","mVolts*",calibratedData[iEMG]));
					    } else {
					    	objectCluster.mPropertyCluster.put("EMG",new FormatCluster("CAL","mVolts",calibratedData[iEMG]));
					    }
					}
					if (((mEnabledSensors & 0xFF00) & SENSOR_STRAIN) > 0) {
					    int iSGHigh = getSignalIndex("Strain Gauge High");
					    int iSGLow = getSignalIndex("Strain Gauge Low");
					    tempData[0] = (double)newPacketInt[iSGHigh];
					    tempData[1] = (double)newPacketInt[iSGLow];
					    calibratedData[iSGHigh]=calibrateU12AdcValue(tempData[0],60,3,551*2.8);
					    calibratedData[iSGLow]=calibrateU12AdcValue(tempData[0],1950,3,183.7*2.8);
					    objectCluster.mPropertyCluster.put("Strain Gauge High",new FormatCluster("RAW","no units",(double)newPacketInt[iSGHigh]));
					    objectCluster.mPropertyCluster.put("Strain Gauge High",new FormatCluster("CAL","mVolts",calibratedData[iSGHigh]));
					    objectCluster.mPropertyCluster.put("Strain Gauge Low",new FormatCluster("RAW","no units",(double)newPacketInt[iSGLow]));
					    objectCluster.mPropertyCluster.put("Strain Gauge Low",new FormatCluster("CAL","mVolts",calibratedData[iSGLow]));
					}
					if (((mEnabledSensors & 0xFF00) & SENSOR_HEART) > 0) {
					    int iHeartRate = getSignalIndex("Heart Rate");
					    tempData[0] = (double)newPacketInt[iHeartRate];
					    calibratedData[iHeartRate]=tempData[0];
					    if (mFWVersion==0.1){
					    
					    } else {
					    	if (tempData[0]==0){
					    		calibratedData[iHeartRate]=	mLastKnownHeartRate;
					    	} else {
					    		calibratedData[iHeartRate]=(int)(1024/tempData[0]*60);
					    		mLastKnownHeartRate=calibratedData[iHeartRate];
					    	}
					    }
					    
					    objectCluster.mPropertyCluster.put("Heart Rate",new FormatCluster("CAL","BPM",calibratedData[iHeartRate]));
					    objectCluster.mPropertyCluster.put("Heart Rate",new FormatCluster("RAW","no units",tempData[0]));
		    		}
					if (((mEnabledSensors & 0xFF) & SENSOR_EXP_BOARD_A0) > 0) {
					    int iA0 = getSignalIndex("Exp Board A0");
					    tempData[0] = (double)newPacketInt[iA0];
					    if (getPMux()==0){
						    calibratedData[iA0]=calibrateU12AdcValue(tempData[0],0,3,1);
						    objectCluster.mPropertyCluster.put("ExpBoard A0",new FormatCluster("RAW","no units",(double)newPacketInt[iA0]));
						    objectCluster.mPropertyCluster.put("ExpBoard A0",new FormatCluster("CAL","mVolts",calibratedData[iA0]));
					    } else {
					    	 calibratedData[iA0]=calibrateU12AdcValue(tempData[0],0,3,1)*1.988;
					    	 objectCluster.mPropertyCluster.put("VSenseReg",new FormatCluster("RAW","no Units",(double)newPacketInt[iA0]));
							 objectCluster.mPropertyCluster.put("VSenseReg",new FormatCluster("CAL","mVolts",calibratedData[iA0]));
							 
					    }
					}
					if (((mEnabledSensors & 0xFF) & SENSOR_EXP_BOARD_A7) > 0) {
						int iA7 = getSignalIndex("Exp Board A7");
					    tempData[0] = (double)newPacketInt[iA7];
					    if (getPMux()==0){
						    calibratedData[iA7]=calibrateU12AdcValue(tempData[0],0,3,1);
						    objectCluster.mPropertyCluster.put("ExpBoard A7",new FormatCluster("RAW","no units",(double)newPacketInt[iA7]));
						    objectCluster.mPropertyCluster.put("ExpBoard A7",new FormatCluster("CAL","mVolts",calibratedData[iA7]));
					    } else {
					    	calibratedData[iA7]=calibrateU12AdcValue(tempData[0],0,3,1)*2;
						    objectCluster.mPropertyCluster.put("VSenseBatt",new FormatCluster("RAW","no units",(double)newPacketInt[iA7]));
						    objectCluster.mPropertyCluster.put("VSenseBatt",new FormatCluster("CAL","mVolts",calibratedData[iA7]));
						    
							 mVSenseBattMA.addValue(calibratedData[iA7]);
							 if (!mWaitForAck) {
							 
								 if (mVSenseBattMA.getMean()<mLowBattLimit*1000) {
							    	if (mCurrentLEDStatus!=1) {
							    		writeLEDCommand(1);
							    	}
							    } else if(mVSenseBattMA.getMean()>mLowBattLimit*1000+100) { //+100 is to make sure the limits are different to prevent excessive switching when the batt value is at the threshold
							    	if (mCurrentLEDStatus!=0) {
							    		writeLEDCommand(0);
							    	}
							    }
								 
							 }
					    }
					}
				}
			}
	   	}
	   	return objectCluster;
	}

       
   	private byte[] convertstacktobytearray(Stack<Byte> b,int packetSize) {
   	    byte[] returnByte=new byte[packetSize];
   	    b.remove(0); //remove the Data Packet identifier 
   		for (int i=0;i<packetSize;i++) {
   			returnByte[packetSize-1-i]=(byte) b.pop();
   		}
   		return returnByte;
   	}
   	
   	
   	/*
   	 * Configure/Read Settings Methods
   	 * */
   	
 
        
   	public boolean sensorConflictCheck(int enabledSensors){
   		boolean pass=true;
   		if (((enabledSensors & 0xFF)& SENSOR_GYRO) > 0){
   			if (((enabledSensors & 0xFF)& SENSOR_EMG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_ECG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_GSR) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF00) & SENSOR_STRAIN) > 0){
   				pass=false;
   			}
   		}
   		
   		if (((enabledSensors & 0xFF)& SENSOR_MAG) > 0){
   			if (((enabledSensors & 0xFF)& SENSOR_EMG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_ECG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_GSR) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF00) & SENSOR_STRAIN) > 0){
   				pass=false;
   			}
   		}
   		
   		if (((enabledSensors & 0xFF)& SENSOR_EMG) > 0){
   			if (((enabledSensors & 0xFF)& SENSOR_GYRO) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_MAG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_ECG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_GSR) > 0){
   				pass=false;
   			}else if (((enabledSensors & 0xFF00) & SENSOR_STRAIN) > 0){
   				pass=false;
   			}
   		}
   		
   		if (((enabledSensors & 0xFF)& SENSOR_ECG) > 0){
   			if (((enabledSensors & 0xFF)& SENSOR_GYRO) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_MAG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_EMG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_GSR) > 0){
   				pass=false;
   			}else if (((enabledSensors & 0xFF00) & SENSOR_STRAIN) > 0){
   				pass=false;
   			}
   		}
   		
   		if (((enabledSensors & 0xFF)& SENSOR_GSR) > 0){
   			if (((enabledSensors & 0xFF)& SENSOR_GYRO) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_MAG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_EMG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_ECG) > 0){
   				pass=false;
   			}else if (((enabledSensors & 0xFF00) & SENSOR_STRAIN) > 0){
   				pass=false;
   			}
   		}
   		
   		if (((enabledSensors & 0xFF00) & SENSOR_STRAIN) > 0){
   			if (((enabledSensors & 0xFF)& SENSOR_GYRO) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_MAG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_EMG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_ECG) > 0){
   				pass=false;
   			} else if (((enabledSensors & 0xFF)& SENSOR_GSR) > 0){
   				pass=false;
   			} else if (get5VReg()==1){ // if the 5volt reg is set 
   				pass=false;
   			}
   		}
   		
   		if (((enabledSensors & 0xFF) & SENSOR_EXP_BOARD_A0) > 0) {
   			if (((enabledSensors & 0xFFFFF) & SENSOR_BATT) > 0) {
   				pass=false;
   			} else if (getPMux()==1){
   				Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
   	            Bundle bundle = new Bundle();
   	            bundle.putString(TOAST, "Disabling PMux.");
   	            msg.setData(bundle);
   	            mHandler.sendMessage(msg);	
   	            writePMux(0);
   			}
		}
   		
   		if (((enabledSensors & 0xFF) & SENSOR_EXP_BOARD_A7) > 0) {
   			if (((enabledSensors & 0xFFFFF) & SENSOR_BATT) > 0) {
   				pass=false;
   			}else if (getPMux()==1){
   				Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
   	            Bundle bundle = new Bundle();
   	            bundle.putString(TOAST, "Disabling PMux.");
   	            msg.setData(bundle);
   	            mHandler.sendMessage(msg);	
   				writePMux(0);
   			}
		}
   		
   		if (((enabledSensors & 0xFFFFF) & SENSOR_BATT) > 0) {
   			if (((enabledSensors & 0xFF) & SENSOR_EXP_BOARD_A7) > 0){
   				pass=false;
   			} 
   			if (((enabledSensors & 0xFF) & SENSOR_EXP_BOARD_A0) > 0){
   				pass=false;
   			}
   			if (((enabledSensors & 0xFFFFF) & SENSOR_BATT) > 0){
	   			if (getPMux()==0){
	   				Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
	   	            Bundle bundle = new Bundle();
	   	            bundle.putString(TOAST, "Enabling PMux.");
	   	            msg.setData(bundle);
	   	            mHandler.sendMessage(msg);	
	   				writePMux(1);
	   			}
	   		}
   		}
   		if (!pass){
   		   Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
           Bundle bundle = new Bundle();
           bundle.putString(TOAST, "Error in Sensor Settings.");
           msg.setData(bundle);
           mHandler.sendMessage(msg);
   		}
   		
   		return pass;
   	}
   	
    /**
     * writeAccelRange(range) sets the Accelerometer range on the Shimmer to the value of the input range. When setting/changing the accel range, please ensure you have the correct calibration parameters. Note that the Shimmer device can only carry one set of accel calibration parameters at a single time.
     * @param range is a numeric value defining the desired accelerometer range. Valid range setting values for the Shimmer 2 are 0 (+/- 1.5g), 1 (+/- 2g), 2 (+/- 4g) and 3 (+/- 6g). Valid range setting values for the Shimmer 2r are 0 (+/- 1.5g) and 3 (+/- 6g).
     */
    public void writeAccelRange(int range) {
    	mListofInstructions.add(new byte[]{SET_ACCEL_SENSITIVITY_COMMAND, (byte)range});
    }
    
    /**
	 * @param rate Defines the sampling rate to be set (e.g.51.2 sets the sampling rate to 51.2Hz). User should refer to the document Sampling Rate Table to see all possible values.
	 */
	public void writeSamplingRate(double rate) {
    	if (getShimmerState() == STATE_CONNECTED) {
			rate=1024/rate; //the equivalent hex setting
			mListofInstructions.add(new byte[]{SET_SAMPLING_RATE_COMMAND, (byte)Math.rint(rate), 0x00});
			//check if low power mag mode is enabled
		    if (!mLowPowerMag){
    		    if (mSamplingRate>35){
    		    	writeMagSamplingRate(6);
    		    } else if (mSamplingRate>15) {
    		    	writeMagSamplingRate(5);
    		    } else if (mSamplingRate>7.5) {
    		    	writeMagSamplingRate(4);
    		    } else {
    		    	writeMagSamplingRate(3);
    		    }
		    } else {
		    	writeMagSamplingRate(4);
		    }
		}
	}
    
    /**
     * writeMagSamplingRate(range) sets the MagSamplingRate on the Shimmer to the value of the input range. When setting/changing the accel range, please ensure you have the correct calibration parameters. Note that the Shimmer device can only carry one set of accel calibration parameters at a single time.
     * @param rate it is a value between 0 and 6; 0 = 0.5 Hz; 1 = 1.0 Hz; 2 = 2.0 Hz; 3 = 5.0 Hz; 4 = 10.0 Hz; 5 = 20.0 Hz; 6 = 50.0 Hz
     */
    private void writeMagSamplingRate(int rate) {
    	while(getInstructionStatus()==false) {};
    	if (mFWVersion==0.1 && mFWInternal==0){
    		
    	} else {
    		mTempIntValue=rate;
    	   	mListofInstructions.add(new byte[]{SET_MAG_SAMPLING_RATE_COMMAND, (byte)rate});
    	   	mTransactionCompleted=false;
    	   	responseTimer(ACK_TIMER_DURATION);
    	   	
    	}
    }
    
    
  	/**
   	 * Transmits a command to the Shimmer device to enable the sensors. To enable multiple sensors an or operator should be used (e.g. writeEnabledSensors(Shimmer.SENSOR_ACCEL|Shimmer.SENSOR_GYRO|Shimmer.SENSOR_MAG)). Command should not be used consecutively. Valid values are SENSOR_ACCEL, SENSOR_GYRO, SENSOR_MAG, SENSOR_ECG, SENSOR_EMG, SENSOR_GSR, SENSOR_EXP_BOARD_A7, SENSOR_EXP_BOARD_A0, SENSOR_STRAIN and SENSOR_HEART.
    SENSOR_BATT
   	 * @param enabledSensors e.g SENSOR_ACCEL|SENSOR_GYRO|SENSOR_MAG
   	 */
   	public void writeEnabledSensors(int enabledSensors) {
   		if (!sensorConflictCheck(enabledSensors)){ //sensor conflict check
			Log.d("Shimmer","Sensor Conflict Error");
    	} else {
    		//check if the batt volt is enabled 
    		if (((enabledSensors & 0xFFFFF) & SENSOR_BATT) > 0){
    			enabledSensors = enabledSensors & 0xFFFF;
    			enabledSensors = enabledSensors|SENSOR_EXP_BOARD_A0|SENSOR_EXP_BOARD_A7;
    		}
    		 
	    	byte highByte=(byte)((enabledSensors&65280)>>8);
	    	byte lowByte=(byte)(enabledSensors & 0xFF);
	    	//write(new byte[]{SET_SENSORS_COMMAND,(byte) lowByte, highByte});
	    	mListofInstructions.add(new byte[]{SET_SENSORS_COMMAND,(byte) lowByte, highByte});
	    	inquiry();
    	}
   		
	}
    
   	/**
	 * @param sensor is a string value that defines the sensor. Accepted sensor values are "Accelerometer","Gyroscope","Magnetometer","ECG","EMG","All"
	 */
	public void readCalibrationParameters(String sensor) {
    	if (getShimmerState() == STATE_CONNECTED) {
    		if (!mInitialized){
		    	if (mFWVersion==0.1 && mFWInternal==0) {
		    		mFWVersionFullName="BoilerPlate 0.1.0";
                	Message msg = mHandler.obtainMessage(MESSAGE_TOAST);
          	        Bundle bundle = new Bundle();
          	        bundle.putString(TOAST, "Firmware Version: " +mFWVersionFullName);
          	        msg.setData(bundle);
          	        mHandler.sendMessage(msg);
          	    }	
	    	}
			if (sensor.equals("Accelerometer")) {
				mListofInstructions.add(new byte[]{GET_ACCEL_CALIBRATION_COMMAND});
				}
			else if (sensor.equals("Gyroscope")) {
				mListofInstructions.add(new byte[]{GET_GYRO_CALIBRATION_COMMAND});
				}
			else if (sensor.equals("Magnetometer")) {
				mListofInstructions.add(new byte[]{GET_MAG_CALIBRATION_COMMAND});
				}
			else if (sensor.equals("All")){
				if (mShimmerVersion!=4){
					mListofInstructions.add(new byte[]{GET_ALL_CALIBRATION_COMMAND});
				} else {
					mListofInstructions.add(new byte[]{GET_ALL_CALIBRATION_COMMAND_SR30});
				}
			} 
			else if (sensor.equals("ECG")){
				mListofInstructions.add(new byte[]{GET_ECG_CALIBRATION_COMMAND});
			} 
			else if (sensor.equals("EMG")){
				mListofInstructions.add(new byte[]{GET_EMG_CALIBRATION_COMMAND});
			}
		}
	}
   	
   	
    /**
     * writeAccelRange(range) sets the Accelerometer range on the Shimmer to the value of the input range. When setting/changing the accel range, please ensure you have the correct calibration parameters. Note that the Shimmer device can only carry one set of accel calibration parameters at a single time.
     * @param range is a numeric value defining the desired accelerometer range. Valid range setting values for the Shimmer 2 are 0 (+/- 1.5g), 1 (+/- 2g), 2 (+/- 4g) and 3 (+/- 6g). Valid range setting values for the Shimmer 2r are 0 (+/- 1.5g) and 3 (+/- 6g).
     */
    public void writeBufferSize(int size) {
    	mListofInstructions.add(new byte[]{SET_BUFFER_SIZE_COMMAND, (byte)size});
    }
   		
	public void readFWVersion() {
		mDummy=false;//false
		mListofInstructions.add(new byte[]{GET_FW_VERSION_COMMAND});
	}
	
	/**
	 * The reason for this is because sometimes the 1st response is not received by the phone
	 */
	private void dummyreadFWVersion() {
		mDummy=true;
		mListofInstructions.add(new byte[]{GET_FW_VERSION_COMMAND});
	}
	
	/**
	 * The reason for this is because sometimes the 1st response is not received by the phone
	 */
	private void dummyreadSamplingRate() {
		mDummy=true;
		mListofInstructions.add(new byte[]{GET_SAMPLING_RATE_COMMAND});
	}
	
	public void stopStreaming() {
		mListofInstructions.add(new byte[]{STOP_STREAMING_COMMAND});
		mCurrentLEDStatus=-1;
	}
    
	public void startStreaming() {
		mTempPacketCountforBatt=0;
		mPacketLossCount = 0;
    	mPacketReceptionRate = 100;
    	mFirstTimeCalTime=true;
    	mLastReceivedCalibratedTimeStamp = -1;
    	mOrientationAlgo = new GradDes3DOrientation(0.1, (double)1/mSamplingRate, 1, 0, 0,0);
		mSync=true; // a backup sync done every time you start streaming
		mListofInstructions.add(new byte[]{START_STREAMING_COMMAND});
	}
	
	
    /**
     * writeGSRRange(range) sets the GSR range on the Shimmer to the value of the input range. 
     * @param range numeric value defining the desired GSR range. Valid range settings are 0 (10kOhm to 56kOhm), 1 (56kOhm to 220kOhm), 2 (220kOhm to 680kOhm), 3 (680kOhm to 4.7MOhm) and 4 (Auto Range).
     */
    public void writeGSRRange(int range) {
    	mListofInstructions.add(new byte[]{SET_GSR_RANGE_COMMAND, (byte)range});
    }
   	
	public void readSamplingRate() {
		mListofInstructions.add(new byte[]{GET_SAMPLING_RATE_COMMAND});
	}
    
   	/**
	 * An inquiry is used to request for the current configuration parameters from the Shimmer device (e.g. Accelerometer settings, Configuration Byte, Sampling Rate, Number of Enabled Sensors and Sensors which have been enabled). 
	 */
	public void inquiry() {
		mListofInstructions.add(new byte[]{INQUIRY_COMMAND});
    }
   	
    
    /**
     * writeMagRange(range) sets the MagSamplingRate on the Shimmer to the value of the input range. When setting/changing the accel range, please ensure you have the correct calibration parameters. Note that the Shimmer device can only carry one set of accel calibration parameters at a single time.
     * @param rate it is a value between 0 and 6; 0 = 0.5 Hz; 1 = 1.0 Hz; 2 = 2.0 Hz; 3 = 5.0 Hz; 4 = 10.0 Hz; 5 = 20.0 Hz; 6 = 50.0 Hz
     */
    private void writeMagRange(int range) {
    	
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","Command not supported on this firmware version");
    	} else {
	    	mListofInstructions.add(new byte[]{SET_MAG_GAIN_COMMAND, (byte)range});
	    }
    }
    
    
    public void writeLEDCommand(int command) {
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","This Shimmer Version does not support the command");
		} else {
			mListofInstructions.add(new byte[]{SET_BLINK_LED, (byte)command});
		}
    }
    
    /*public void writeGyroTempVref(int value){
    	
    }*/
    

    
    public void writeECGCalibrationParameters(int offsetrall, int gainrall,int offsetlall, int gainlall) {
    	byte[] data = new byte[8];
    	data[0] = (byte) ((offsetlall>>8)& 0xFF); //MSB offset
    	data[1] = (byte) ((offsetlall)& 0xFF);
    	data[2] = (byte) ((gainlall>>8)& 0xFF); //MSB gain
    	data[3] = (byte) ((gainlall)& 0xFF);
    	data[4] = (byte) ((offsetrall>>8)& 0xFF); //MSB offset
    	data[5] = (byte) ((offsetrall)& 0xFF);
    	data[6] = (byte) ((gainrall>>8)& 0xFF); //MSB gain
    	data[7] = (byte) ((gainrall)& 0xFF);
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","This Shimmer Version does not support the command");
		} else {
			mListofInstructions.add(new byte[]{SET_ECG_CALIBRATION_COMMAND,data[0],data[1],data[2],data[3],data[4],data[5],data[6],data[7]});
		}
    }
    
    public void writeEMGCalibrationParameters(int offset, int gain) {
    	byte[] data = new byte[4];
    	data[0] = (byte) ((offset>>8)& 0xFF); //MSB offset
    	data[1] = (byte) ((offset)& 0xFF);
    	data[2] = (byte) ((gain>>8)& 0xFF); //MSB gain
    	data[3] = (byte) ((gain)& 0xFF);
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","This Shimmer Version does not support the command");
		} else {
			mListofInstructions.add(new byte[]{SET_EMG_CALIBRATION_COMMAND,data[0],data[1],data[2],data[3]});
		}
    }
    
    public void readGSRRange() {
    	mListofInstructions.add(new byte[]{GET_GSR_RANGE_COMMAND});
    }
    
    public void readAccelRange() {
    	mListofInstructions.add(new byte[]{GET_ACCEL_SENSITIVITY_COMMAND});
    }
    
    public void readBufferSize() {
    	mListofInstructions.add(new byte[]{GET_BUFFER_SIZE_COMMAND});
    }
    
    public void readMagSamplingRate() {
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","This Shimmer Version does not support the command");
		} else {
			mListofInstructions.add(new byte[]{GET_MAG_SAMPLING_RATE_COMMAND});
		}
	}

    public void readMagRange() {
    	mListofInstructions.add(new byte[]{GET_MAG_GAIN_COMMAND});
	}
    
    public void readBlinkLED() {
    	mListofInstructions.add(new byte[]{GET_BLINK_LED});
    }
    
    
    public void readECGCalibrationParameters() {
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","This Shimmer Version does not support the command");
		} else {
			mListofInstructions.add(new byte[]{GET_ECG_CALIBRATION_COMMAND});
		}
    }
    
    public void readEMGCalibrationParameters() {
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","This Shimmer Version does not support the command");
		} else {
			mListofInstructions.add(new byte[]{GET_EMG_CALIBRATION_COMMAND});
		}
    }
    
    public void readShimmerVersion() {
    	if (mFWVersion==0.1 && mFWInternal==0){
    		Log.d("Shimmer","This Shimmer Version does not support the command");
		} else {
			mListofInstructions.add(new byte[]{GET_SHIMMER_VERSION_COMMAND});
		}
	}
	
	public void readConfigByte0() {
    	mListofInstructions.add(new byte[]{GET_CONFIG_BYTE0_COMMAND});
	}
	
	/**
	 * writeConfigByte0(configByte0) sets the config byte0 value on the Shimmer to the value of the input configByte0. 
	 * @param configByte0 is an unsigned 8 bit value defining the desired config byte 0 value.
	 */
	public void writeConfigByte0(byte configByte0) {
		mListofInstructions.add(new byte[]{SET_CONFIG_BYTE0_COMMAND,(byte) configByte0});
	}

	public void writeInstruction(){
		if (getShimmerState() == STATE_CONNECTED) {
			byte[] instruction = (byte[]) mListofInstructions.get(0);
			write(instruction);
		}
	}

	
	
	
	/**
	 * Sets the Pmux bit value on the Shimmer to the value of the input SETBIT. The PMux bit is the 2nd MSB of config byte0.
	 * @param setBit value defining the desired setting of the PMux (1=ON, 0=OFF).
	 */
	public void writePMux(int setBit) {
    		mListofInstructions.add(new byte[]{SET_PMUX_COMMAND,(byte) setBit});
	}
	
	/**
	 * Sets the configGyroTempVref bit value on the Shimmer to the value of the input SETBIT. The configGyroTempVref bit is the 2nd MSB of config byte0.
	 * @param setBit value defining the desired setting of the Gyro Vref (1=ON, 0=OFF).
	 */
	/*public void writeConfigGyroTempVref(int setBit) {
    	while(getInstructionStatus()==false) {};
			//Bit value defining the desired setting of the PMux (1=ON, 0=OFF).
			if (setBit==1) {
				mTempByteValue=(byte) (mConfigByte0|32); 
			} else if (setBit==0) {
				mTempByteValue=(byte)(mConfigByte0 & 223);
			}
			mCurrentCommand=SET_GYRO_TEMP_VREF_COMMAND;
			write(new byte[]{SET_GYRO_TEMP_VREF_COMMAND,(byte) setBit});
			mWaitForAck=true;
			mTransactionCompleted=false;
			responseTimer(ACK_TIMER_DURATION);
	}*/
	
	
	/**
	 * Enable/disable the 5 Volt Regulator on the Shimmer ExpBoard board
	 * @param setBit value defining the desired setting of the Volt regulator (1=ENABLED, 0=DISABLED).
	 */
	public void writeFiveVoltReg(int setBit) {
		mListofInstructions.add(new byte[]{SET_5V_REGULATOR_COMMAND,(byte) setBit});
	}
	public void toggleLed() {
		mListofInstructions.add(new byte[]{TOGGLE_LED_COMMAND});
	}
	
	public String getDeviceName(){
		return mMyName;
	}

	public int getAccelRange(){
		return mAccelRange;
	}
	
	public int getGSRRange(){
		return mGSRRange;
	}
	
	public boolean getInitialized(){
		return mInitialized;
	}
	
	public double getPacketReceptionRate(){
		return mPacketReceptionRate;
	}
	
	public int getPMux(){
	    if ((mConfigByte0 & (byte)64)!=0) {
			//then set ConfigByte0 at bit position 7
		   return 1;
	    } else{
		    return 0;
		}
	}
	
	public int get5VReg(){
	    if ((mConfigByte0 & (byte)128)!=0) {
			//then set ConfigByte0 at bit position 7
		   return 1;
	    } else {
		   return 0;
		}
	 }
	
	public int getCurrentLEDStatus() {
		return mCurrentLEDStatus;
	}
	
	public double getFirmwareVersion(){
		return mFWVersion;
	}
	public void setDeviceName(String deviceName) {
		// TODO Auto-generated method stub
		mMyName = deviceName;
	}
	
	/**
	 * Set the battery voltage limit, when the Shimmer device goes below the limit while streaming the LED on the Shimmer device will turn Yellow, in order to use battery voltage monitoring the Battery has to be enabled. See writeenabledsensors.
	 * @param limit
	 */
	public void setBattLimitWarning(double limit){
		mLowBattLimit=limit;
	}
	
	public double getBattLimitWarning(){
		return mLowBattLimit;
	}
	
	public int getShimmerVersion(){
		return mShimmerVersion;
	}
	
	public boolean isUsingDefaultAccelParam(){
		return mDefaultCalibrationParametersAccel;
	}
	
	public boolean isUsingDefaultGyroParam(){
		return mDefaultCalibrationParametersGyro;
	}
	public boolean isUsingDefaultMagParam(){
		return mDefaultCalibrationParametersMag;
	}
	public boolean isUsingDefaultECGParam(){
		return mDefaultCalibrationParametersECG;
	}
	public boolean isUsingDefaultEMGParam(){
		return mDefaultCalibrationParametersEMG;
	}
	
	public void resetCalibratedTimeStamp(){
		mLastReceivedCalibratedTimeStamp = -1;
		mFirstTimeCalTime = true;
		mCurrentTimeStampCycle = 0;
	}
	
	public void enable3DOrientation(boolean enable){
		if (enable){
			//enable the sensors if they have not been enabled 
			if (((mEnabledSensors & 0xFF)& SENSOR_ACCEL) > 0 && ((mEnabledSensors & 0xFF)& SENSOR_GYRO) > 0 && ((mEnabledSensors & 0xFF)& SENSOR_MAG) > 0 ){
				
			} else {
				writeEnabledSensors(Shimmer.SENSOR_ACCEL|Shimmer.SENSOR_MAG|Shimmer.SENSOR_GYRO);
			}
			mOrientationEnabled = true;
			
		} else {
			mOrientationEnabled = false;
		}
	}
	
	/**
	 * This enables the low power mag option. When not enabled the sampling rate of the mag is set to the closest value to the actual sampling rate that it can achieve. In low power mode it defaults to 10Hz
	 * @param enable
	 */
	public void enableLowPowerMag(boolean enable){
		mLowPowerMag = enable;
		if (!mLowPowerMag){
			if (mSamplingRate>35){
 		    	writeMagSamplingRate(6);
 		    } else if (mSamplingRate>15) {
 		    	writeMagSamplingRate(5);
 		    } else if (mSamplingRate>7.5) {
 		    	writeMagSamplingRate(4);
 		    } else {
 		    	writeMagSamplingRate(3);
 		    }
	    } else {
	    	writeMagSamplingRate(4);
	    }
	}
	
	public boolean isLowPowerMagEnabled(){
		return mLowPowerMag;
	}
	
	
	
	/**
	 * @param enable this enables the calibration of the gyroscope while streaming
	 * @param bufferSize sets the buffersize of the window used to determine the new calibration parameters, see implementation for more details
	 * @param threshold sets the threshold of when to use the incoming data to recalibrate gyroscope offset, this is in degrees, and the default value is 1.2
	 */
	public void enableOnTheFlyGyroCal(boolean enable,int bufferSize,double threshold){
		if (enable){
			mGyroOVCalThreshold=threshold;
			mGyroX=new DescriptiveStatistics(bufferSize);
			mGyroY=new DescriptiveStatistics(bufferSize);
			mGyroZ=new DescriptiveStatistics(bufferSize);
			mGyroXRaw=new DescriptiveStatistics(bufferSize);
			mGyroYRaw=new DescriptiveStatistics(bufferSize);
			mGyroZRaw=new DescriptiveStatistics(bufferSize);
		}
	}
	
	public boolean isGyroOnTheFlyCalEnabled(){
		return mEnableOntheFlyGyroOVCal;
	}
	
	public boolean is3DOrientatioEnabled(){
		return mOrientationEnabled;
	}
}