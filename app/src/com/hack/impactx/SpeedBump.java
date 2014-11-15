package com.hack.impactx;

import com.hack.impactx.R;
import com.hack.impactx.ISamplingService;
import com.hack.impactx.ISteps;
import android.app.Activity;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.Button;

public class SpeedBump extends Activity implements SeekBar.OnSeekBarChangeListener
{
	static final String LOG_TAG = "SPEEDBUMP";
	static final String SAMPLING_SERVICE_ACTIVATED_KEY = "samplingServiceActivated";
	static final String STEPCOUNT_KEY = "stepCountTextKey";

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


}
