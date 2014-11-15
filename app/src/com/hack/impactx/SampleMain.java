package com.hack.impactx;

import java.util.ArrayList;
import java.util.Random;
import java.util.Set;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class SampleMain extends Activity implements SeekBar.OnSeekBarChangeListener
{
	static final String LOG_TAG = "SPEEDBUMP";
	static final String SAMPLING_SERVICE_ACTIVATED_KEY = "samplingServiceActivated";
	static final String STEPCOUNT_KEY = "stepCountTextKey";
	
	public enum BtState{
		DISCONNECTED, CONNNECTED, DISCOVERING
	};
	
	/* Bluetooth hardware*/
	BluetoothAdapter btAdapter;
	BluetoothDevice btDevice;

	/*btConnection manager object*/
	BtConnection mBluetooth;
	
	/*Android view related stuff */
	ListView btListView;
	ArrayAdapter<String> btDevicesAdaptor;
	ArrayList<String> pairedDevices;
	Button btnScan;
	TextView txtSend;
	TextView txtStatus;

	/* State Management*/
	BtState btState;
	
	/* 
	 * Messages from the Bluetooth Manager should come in here 
	 */
	private static Handler mHandler = new Handler(){
		@Override
		public void handleMessage(Message message){
			Log.d(LOG_TAG, "In Message Handler");	
		}
	};


    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
		Log.d( LOG_TAG, "onCreate" );
		if( savedInstanceState != null ) {
			stepCountText = savedInstanceState.getString( STEPCOUNT_KEY );
			samplingServiceActivated = savedInstanceState.getBoolean( SAMPLING_SERVICE_ACTIVATED_KEY, false );
        } else
            samplingServiceActivated = false;
        Log.d( LOG_TAG, "onCreate; samplingServiceActivated: "+samplingServiceActivated );
		bindSamplingService();
        setContentView( R.layout.main );
		stepCountTV = (TextView)findViewById( R.id.tv_stepcount );
		if( stepCountText != null ) {
			stepCountTV.setText( stepCountText );
			stepCountTV.setVisibility( View.VISIBLE );
        }
		CheckBox cb = (CheckBox)findViewById( R.id.cb_sampling );
        if( samplingServiceActivated )
            cb.setChecked( true );
        else
            stopSamplingService();
		cb.setOnCheckedChangeListener( new CompoundButton.OnCheckedChangeListener() {
			public void onCheckedChanged(CompoundButton  buttonView, boolean isChecked) {
				if( isChecked ) {
					Log.d( LOG_TAG, "sampling activated" );
					samplingServiceActivated = true;
					startSamplingService();
			        stepCountTV.setText( stepCountText );
					stepCountTV.setVisibility( View.VISIBLE );
				} else {
					Log.d( LOG_TAG, "sampling deactivated" );
					samplingServiceActivated = false;
					stepCountTV.setVisibility( View.INVISIBLE );
					stopSamplingService();
				}
			}
		});
		cb = (CheckBox)findViewById( R.id.cb_sound );
		cb.setOnCheckedChangeListener( new CompoundButton.OnCheckedChangeListener() {
			public void onCheckedChanged(CompoundButton  buttonView, boolean isChecked) {
                sound = isChecked;
            }
        });
		
		// Get a reference to the devices Bluetooth hardware
		btAdapter = BluetoothAdapter.getDefaultAdapter();

		if (btAdapter == null){
			// Bluetooth not supported.
			finish();
		}

		SeekBar sb = (SeekBar)findViewById( R.id.sb_sensitivity );
        sb.setOnSeekBarChangeListener( this );

        sensitivity = SENSITIVITY_DEFAULT;
        setSensitivitySb();

        Button setSensitivityButton = (Button)findViewById( R.id.bt_sensitivity );
        setSensitivityButton.setOnClickListener( new View.OnClickListener() {
            public void onClick( View view ) { 
                TextView tvSensitivity = (TextView)findViewById( R.id.et_sensitivity );
                String sensString = tvSensitivity.getText().toString();
                try {
                    sensitivity = Double.parseDouble( sensString );
                    if( ( sensitivity >= SENSITIVITY_MIN ) && ( sensitivity <= SENSITIVITY_MAX ) ) {
                        setSensitivitySb();
                        setSensitivity();
                    }
                } catch( NumberFormatException ex ) {
                }
            }
        } ); 
        
    }
    

	protected void onResume(){
		super.onResume();
		setUpUI();
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (mBluetooth!=null){
			mBluetooth.cancel();
		}
		if (btRegReciever != null){
			unregisterReceiver(btRegReciever);
		}
	}
    
	/*****************************************************
	 * Called by onCreate. 
	 * Tries to enable the Bluetooth hardware (if not already enabled).
	 * If already enabled initiates a BT scan
	 * 
	 ****************************************************/
	private void setUpUI() {
		btDevicesAdaptor = new ArrayAdapter<String>(getApplicationContext(), android.R.layout.simple_list_item_1);
		pairedDevices = new ArrayList<String>();
		btListView = (ListView)findViewById(R.id.listView1);
		btListView.setAdapter(btDevicesAdaptor);
		btnScan = (Button)findViewById(R.id.btnScan);
		
		txtStatus  = (TextView) findViewById(R.id.txtUpdate);

		// if not enabled. Enable the BT adapter
		if (!btAdapter.isEnabled()){
			Log.d(LOG_TAG, "BT Adapter not enabled... firing ACTION_REQUEST_ENABLE");
			Intent intent =  new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(intent, 0);
		}else{
			

		}


		// Listen out for a Bluetooth broadcast
		IntentFilter intentFilter = new IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
		registerReceiver(btRegReciever, intentFilter);

		/*
		 * Configure UI elements for default state.
		 */
		btnScan.setEnabled(true);
		btnScan.setText("Scan");
		btDevicesAdaptor.clear();
		txtStatus.setText("Bluetooth Status: Disconnected");
		setProgressBarIndeterminateVisibility(false);

		btState = BtState.DISCONNECTED;

		/*
		 * When user selects an item on the list lets try to connect to it.
		 * If it successfully connects update the connection state.
		 * The broadcast receiver above should also detect a device connection and update the UI
		 */
		Log.d("Test", "Reached here 1");
		btListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {

			public void onItemClick(AdapterView<?> parentAdapter, View view, int position,
					long id) {      

				btAdapter.cancelDiscovery();
				txtStatus.setText("Bluetooth Status: Connecting...");

				// We know the View is a TextView so we can cast it
				TextView clickedView = (TextView) view;
//				clickedView.setTextColor(Color.BLACK);

				String devAddress = (String) clickedView.getText();
				String[] parts = devAddress.split("\n");
				parts = parts[1].split(" ");

				btDevice =  btAdapter.getRemoteDevice(parts[0]);

				mBluetooth = new BtConnection(btAdapter, btDevice, mHandler);
				mBluetooth.start();

			}
		});

		/*
		 * Kicks off a  scan when user presses button.
		 */
		btnScan.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Log.d("Test", "Reached here 2");
				if (btState == BtState.CONNNECTED){
					if (mBluetooth != null){
						setProgressBarIndeterminateVisibility(true);
						txtStatus.setText("Bluetooth Status: Disconnecting");
						Log.d("Test", "Reached here 3");
						btnScan.setText("Wait..");
						Log.d("Test", "Reached here 4");
						mBluetooth.cancel();
					}
				}
				else if (btState == BtState.DISCOVERING){
					// Already scanning so do nothing
				}
				else{
					Log.d("Test", "Reached here 5");
					btScan();
					Log.d("Test", "Reached here 6");
				}
			}});

	}



	/*
	 *  Call this function when the Bluetooth adapter is enabled to start a BT scan
	 */
	private void btScan() {

		//Empty the adaptor or else duplicates will appear
		btDevicesAdaptor.clear();

		setProgressBarIndeterminateVisibility(true);
		Log.d("Test", "Reached here 7");
		btnScan.setEnabled(false);
		txtStatus.setText("Bluetooth Status: Scanning...");

		// Get a list of devices previously paired to
		Set<BluetoothDevice> psetDev = btAdapter.getBondedDevices();
		Log.d("Test", "Reached here 8");
		
		// Add name and address to a list 
		for (BluetoothDevice device : psetDev) {
			// Add the name and address to an array adapter to show in a ListView
			pairedDevices.add(device.getName() + "\n" + device.getAddress());
		}

		// Adapter is on. Start discovery
		if(btAdapter.startDiscovery()){
			// and resister various Bluetooth broadcast messages with our broadcast receiver
			registerReceiver(btRegReciever, new IntentFilter(BluetoothDevice.ACTION_FOUND));
			registerReceiver(btRegReciever, new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED));
//			registerReceiver(btRegReciever, new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED));
//			registerReceiver(btRegReciever, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED));
//			registerReceiver(btRegReciever, new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED));
		}

	}

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if( fromUser ) {
			SeekBar sbSensitivity = (SeekBar)findViewById( R.id.sb_sensitivity );
			if( seekBar == sbSensitivity ) {
            	sensitivity = ( ( (double)progress / 100.0 ) * ( SENSITIVITY_MAX - SENSITIVITY_MIN ) ) + SENSITIVITY_MIN;
            	sensitivity = Math.round( sensitivity * 100.0 ) / 100.0;
            	setSensitivityTv();
            	setSensitivity();
			} 
        }
    }

    public void onStartTrackingTouch(SeekBar seekBar) {}
    public void onStopTrackingTouch(SeekBar seekBar) {}

	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState( outState );
		Log.d( LOG_TAG, "onSaveInstanceState" );
		outState.putBoolean( SAMPLING_SERVICE_ACTIVATED_KEY, samplingServiceActivated ); 
		if( stepCountText != null )
			outState.putString( STEPCOUNT_KEY, stepCountText );
	}

	protected void onDestroy() {
		super.onDestroy();
		Log.d( LOG_TAG, "onDestroy" );
		releaseSamplingService();
	}

	private void startSamplingService() {
		if( samplingServiceRunning )	// shouldn't happen
			stopSamplingService();
		
        setSensitivity();
        stepCountText = "0";
        Intent i = new Intent();
        i.setClassName( "com.hack.impactx", "com.hack.impactx.SamplingService" );
        startService( i );
		samplingServiceRunning = true;	
	}

	private void stopSamplingService() {
		Log.d( LOG_TAG, "stopSamplingService" );
		if( samplingServiceRunning ) {
			stopSampling();
			samplingServiceRunning = false;
		}
	}

	private void bindSamplingService() {
		samplingServiceConnection = new SamplingServiceConnection();
		Intent i = new Intent();
		i.setClassName( "com.hack.impactx", "com.hack.impactx.SamplingService" );
		bindService( i, samplingServiceConnection, Context.BIND_AUTO_CREATE);
	}

	private void releaseSamplingService() {
		releaseCallbackOnService();
		unbindService( samplingServiceConnection );	  
		samplingServiceConnection = null;
	}

    private void setSensitivitySb() {
        double pos = 100.0 * ( sensitivity - SENSITIVITY_MIN ) / ( SENSITIVITY_MAX - SENSITIVITY_MIN );
		SeekBar sb = (SeekBar)findViewById( R.id.sb_sensitivity );
        sb.setProgress( (int)pos );
        setSensitivityTv();
    }

    private void setSensitivityTv() {
	    TextView sensitivityTV = (TextView)findViewById( R.id.tv_sensitivity );
        sensitivityTV.setText( "Sensitivity: "+Double.toString( sensitivity ) );
    }

    private void setSensitivity() {
            if( samplingService != null )
                try {
                    Log.d( LOG_TAG, "setSensitivity: "+sensitivity );
                    samplingService.setSensitivity( sensitivity );
                } catch( RemoteException ex ) {
                    Log.e( LOG_TAG, "Sensitivity", ex );
                }
    }

    private void setCallbackOnService() {
		if( samplingService == null )
			Log.e( LOG_TAG, "setCallbackOnService: Service not available" );
		else {
			try {
				samplingService.setCallback( iSteps.asBinder() );
			} catch( DeadObjectException ex ) {
				Log.e( LOG_TAG, "DeadObjectException",ex );
			} catch( RemoteException ex ) {
				Log.e( LOG_TAG, "RemoteException",ex );
			}
		}
	}

    private void releaseCallbackOnService() {
		if( samplingService == null )
			Log.e( LOG_TAG, "releaseCallbackOnService: Service not available" );
		else {
			try {
				samplingService.removeCallback();
			} catch( DeadObjectException ex ) {
				Log.e( LOG_TAG, "DeadObjectException",ex );
			} catch( RemoteException ex ) {
				Log.e( LOG_TAG, "RemoteException",ex );
			}
		}
	}

    private void updateSamplingServiceRunning() {
		if( samplingService == null )
			Log.e( LOG_TAG, "updateSamplingServiceRunning: Service not available" );
		else {
			try {
				samplingServiceRunning = samplingService.isSampling();
			} catch( DeadObjectException ex ) {
				Log.e( LOG_TAG, "DeadObjectException",ex );
			} catch( RemoteException ex ) {
				Log.e( LOG_TAG, "RemoteException",ex );
			}
		}
	}

    private void stopSampling() {
		Log.d( LOG_TAG, "stopSampling" );
		if( samplingService == null )
			Log.e( LOG_TAG, "stopSampling: Service not available" );
		else {
			try {
				samplingService.stopSampling();
			} catch( DeadObjectException ex ) {
				Log.e( LOG_TAG, "DeadObjectException",ex );
			} catch( RemoteException ex ) {
				Log.e( LOG_TAG, "RemoteException",ex );
			}
		}
	}

    private ISteps.Stub iSteps 
				= new ISteps.Stub() {
		public void step( int count ) {
			Log.d( LOG_TAG, "step count: "+count );
			stepCountText = Integer.toString( count );
			stepCountTV.setText( stepCountText );
            if( sound ) {
                AudioManager am = (AudioManager)getSystemService( AUDIO_SERVICE );
                am.playSoundEffect( AudioManager.FX_KEYPRESS_RETURN, 200.0f );
            }
		}
    };

    private ISamplingService samplingService = null;
    private SamplingServiceConnection samplingServiceConnection = null;
	private boolean samplingServiceRunning = false;
    private boolean samplingServiceActivated = false;
	private TextView stepCountTV;
	private String stepCountText = null;
    private static final double SENSITIVITY_MIN = 100;
    private static final double SENSITIVITY_MAX = 300;
    private static final double SENSITIVITY_DEFAULT = 160;
    private double sensitivity = 0.0;
    private boolean sound = false;

    class SamplingServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName className, 
			IBinder boundService ) {
        	samplingService = ISamplingService.Stub.asInterface((IBinder)boundService);
	    	setCallbackOnService();
			updateSamplingServiceRunning();
/*
            if( !samplingServiceRunning )
                startSamplingService();
*/                
		    CheckBox cb = (CheckBox)findViewById( R.id.cb_sampling );
            cb.setChecked( samplingServiceRunning );
		 	Log.d( LOG_TAG,"onServiceConnected" );
        }

        public void onServiceDisconnected(ComponentName className) {
        	samplingService = null;
			Log.d( LOG_TAG,"onServiceDisconnected" );
        }
    };
    
    /*
	 * Broadcast Receiver that listens for the following:
	 * 
	 * BluetoothDevice.ACTION_FOUND
	 * BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
	 * BluetoothDevice.ACTION_ACL_CONNECTED
	 */
	private final BroadcastReceiver btRegReciever = new BroadcastReceiver() {

		@Override
		public void onReceive(Context arg0, Intent intent) {
			String action = intent.getAction();	
			String toastText;
			String prevStateExtra = btAdapter.EXTRA_PREVIOUS_STATE;
			String stateExtra = BluetoothAdapter.EXTRA_STATE;
			int state = intent.getIntExtra(stateExtra,-1);
			//intent.getIntExtra(prevStateExtra, -1);

			Log.d(LOG_TAG, "BroadCast Reciver (Action):" + action);

			/*
			 * Handle Bluetooth Adapter state and messages
			 */
			switch(state){
			case(BluetoothAdapter.STATE_TURNING_ON):
			{
				toastText = "Bluetooth Turning On";
				break;
			}
			case(BluetoothAdapter.STATE_ON):
			{
				toastText = "Bluetooth On";
				Toast.makeText(SampleMain.this,toastText, Toast.LENGTH_SHORT).show();
				break;
			}
			case (BluetoothAdapter.STATE_TURNING_OFF):
			{
				toastText = "Bluetooth Turning Off";
				Toast.makeText(SampleMain.this,toastText, Toast.LENGTH_SHORT).show();
				break;
			}
			case (BluetoothAdapter.STATE_OFF):
			{
				toastText = "Bluetooth Off";
				Toast.makeText(SampleMain.this,toastText, Toast.LENGTH_SHORT).show();
				break;
			}	
			} 

			if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)){
				btnScan.setEnabled(true);
				setProgressBarIndeterminateVisibility(false);
				txtStatus.setText("Bluetooth Status: Not Connected");
				//Toast.makeText(getApplicationContext(), "Discovery Complete", Toast.LENGTH_SHORT).show();
			}

			/*
			 * Handle Bluetooth DEVICE messages
			 */
			if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				String deviceName = device.getName();
				//toastText = "Connected to "+ deviceName;
				//Toast.makeText(MainActivity.this,toastText, Toast.LENGTH_SHORT).show();
				txtStatus.setText("Bluetooth Status: Connected (" + deviceName +")");
				btState = BtState.CONNNECTED;
				btDevicesAdaptor.clear();
				btnScan.setText("Disconnect");
			}

			if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action) || BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED.equals(action) ) {
				// Get the BluetoothDevice object from the Intent
				setProgressBarIndeterminateVisibility(false);
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				String deviceName = device.getName();
				toastText = "Disconnected from "+ deviceName;
				Toast.makeText(SampleMain.this,toastText, Toast.LENGTH_SHORT).show();
				txtStatus.setText("Bluetooth Status: Disconnected");
				btnScan.setText("Scan");
				btState = BtState.DISCONNECTED;
			}

			if (BluetoothDevice.ACTION_FOUND.equals(action)) {
				// Get the BluetoothDevice object from the Intent
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

				for (int i = 0; i< pairedDevices.size(); i++){
					Log.d(LOG_TAG, "fuck: " + pairedDevices.get(i) + " device getname: " + device.getName());
					if (pairedDevices.get(i) != null && pairedDevices.get(i).contains( device.getName() ) ){
						btDevicesAdaptor.add(device.getName() + "\n" + device.getAddress() + " PAIRED");
						toastText = "Found: " + device.getName() + "Already Paired!";
						Toast.makeText(SampleMain.this,toastText, Toast.LENGTH_SHORT).show();
						return;  
					}
				}

				// Add the name and address to an array adapter to show in a ListView
				btDevicesAdaptor.add(device.getName() + "\n" + device.getAddress());
				toastText = "Found: " + device.getName();
				Toast.makeText(SampleMain.this,toastText, Toast.LENGTH_SHORT).show();
			}
		}
	};	


}
