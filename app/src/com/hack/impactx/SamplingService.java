package com.hack.impactx;

import com.hack.impactx.ISamplingService;
import com.hack.impactx.ISteps;
import android.app.Service;
import android.content.Intent;
import android.content.res.AssetManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.DeadObjectException;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.hardware.SensorManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

public class SamplingService extends Service implements SensorEventListener {
	static final String LOG_TAG = "SPEEDBUMP_SAMPLINGSERVICE";
	static final boolean DEBUG_GENERAL = true;
// Set this to null if you want real accel measurements
//	static final String FEED_FILE = null;
	static final String FEED_FILE = "test.txt";
	InputStreamReader test = null;
	
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand( intent, flags, startId );
		Log.d( LOG_TAG, "onStartCommand" );
		stopSampling();		// just in case the activity-level service management fails
        rate = SensorManager.SENSOR_DELAY_FASTEST;
		sensorManager = (SensorManager)getSystemService( SENSOR_SERVICE  );
		feedReader = null;
		if( FEED_FILE != null ) {
			try {
				AssetManager am = getResources().getAssets();
				test = new InputStreamReader( am.open( FEED_FILE ));
				feedReader = new BufferedReader( test );
			} catch( IOException ex ) {
				Log.e(LOG_TAG, ex.getMessage(),ex);
			}
		}
		startSampling();
		Log.d( LOG_TAG, "onStartCommand ends" );
		return START_NOT_STICKY;
	}

	public void onDestroy() {
		super.onDestroy();
		Log.d( LOG_TAG, "onDestroy" );
		stopSampling();
	}

	public IBinder onBind(Intent intent) {
		return serviceBinder;	// cannot bind
	}

// SensorEventListener
    public void onAccuracyChanged (Sensor sensor, int accuracy) {
    }

    public void onSensorChanged(SensorEvent sensorEvent) {
		if( sensorEvent.values.length < 3 )
			return;
    	long timestamp = sensorEvent.timestamp;
    	float x = sensorEvent.values[0];
    	float y = sensorEvent.values[1];
    	float z = sensorEvent.values[2];
    	if( feedReader != null ) {
    		timestamp = System.currentTimeMillis();
    		x = 10.0f;
    		y = 0.0f;
    		z = 0.0f;
    		try {
    			String line = feedReader.readLine();
    			if( line == null )
    				feedReader.close();
    			else {
    				StringTokenizer st = new StringTokenizer(line,",");
    				String s = st.nextToken();	// timestamp
    				if( st.countTokens() != 3 ) {
    					s = st.nextToken();
    					if( !"sample".equals(s)) {
    						throw new NoSuchElementException("not 'sample'");
    					}
    				}
    				s = st.nextToken();
    				x = Float.parseFloat(s);
    				s = st.nextToken();
    				y = Float.parseFloat(s);
    				s = st.nextToken();
    				z = Float.parseFloat(s);
    			}
    		} catch( IOException ex ) {
    		} catch( NoSuchElementException ex ) {    			
    		} catch( NumberFormatException ex ) {    			
    		}
    	}
    	if( captureFile != null ) {
    		captureFile.println( timestamp+","+
    						"sample,"+
    						x+","+
    						y+","+
    						z);
    	}
    	Log.d( LOG_TAG, "x: "+x+"; y: "+y+"; z: "+z);
    	try {
			processSample( timestamp,x,y,z );
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }


	private void stopSampling() {
		if( !samplingStarted )
			return;
        if( sensorManager != null ) {
			Log.d( LOG_TAG, "unregisterListener/SamplingService" );
            sensorManager.unregisterListener( this );
		}
        if( captureFile != null ) {
            captureFile.close();
			captureFile = null;
        }
		samplingStarted = false;
	}

	private void startSampling() {
        Log.d("test", "about to start sampling");
		if( samplingStarted )
			return;

        initSampling();
        Log.d("test", "about to start sampling");
       	List<Sensor> sensors = sensorManager.getSensorList( Sensor.TYPE_ACCELEROMETER  );
      	ourSensor = sensors.size() == 0 ? null : sensors.get( 0 );

      	if( ourSensor != null ) {
			Log.d( LOG_TAG, "registerListener/SamplingService" );
           	sensorManager.registerListener( 
                            this, 
                            ourSensor,
                            rate );
		}
		captureFile = null;
        if( DEBUG_GENERAL ) {
			GregorianCalendar gcal = new GregorianCalendar();
			String fileName =
					"speedbump_" +
					gcal.get( Calendar.YEAR)+
					"_"+
					Integer.toString( gcal.get( Calendar.MONTH)+1)+
					"_"+
					gcal.get( Calendar.DAY_OF_MONTH)+
					"_"+
					gcal.get( Calendar.HOUR_OF_DAY)+
					"_"+
					gcal.get( Calendar.MINUTE)+
					"_"+
					gcal.get( Calendar.SECOND)+
					".csv";
            File captureFileName = new File( 
            		Environment.getExternalStorageDirectory(), 
            		fileName );
       	    try {
         	    captureFile = new PrintWriter( new FileWriter( captureFileName, false ) );
            } catch( IOException ex ) {
       		    Log.e( LOG_TAG, ex.getMessage(), ex );
       	    }
        }
		samplingStarted = true;
	}

    private void initSampling() {
    	state = STATE_GRAVITYACCEL;
    	gravityAccel = 0.0;
    	gravityAccelCtr = GRAVITYACCEL_MEAS_LEN;
    }


    private void initMeasuring() {
    	lpFilt = new FIR( lp_coeffs);
    	bw_0_9_filt = new FIR( bw_0_9 );
    	bw_1_0_filt = new FIR( bw_1_0 );
    	bw_1_1_filt = new FIR( bw_1_1 );
    	pw_0_9 = new PowerWindow( 20 );
    	pw_1_0 = new PowerWindow( 20 );
    	pw_1_1 = new PowerWindow( 20 );
    	pw_0_9_prevstate = false;
    	pw_1_0_prevstate = false;
    	pw_1_1_prevstate = false;	
    	state = STATE_MEASURING;
    	bumps = 0;
    	lastEdge = -1L;
    	sampleCounter = 0L;
    }
    
// Processes one sample
	private void processSample( long timestamp, float x,float y,float z ) throws IOException {
		double ampl = Math.sqrt( (double)x*x +
								 (double)y*y +
								 (double)z*z );
		switch( state ) {
		case STATE_GRAVITYACCEL:
			gravityAccel += ampl;
			--gravityAccelCtr;
			if( gravityAccelCtr <= 0 ) {
				gravityAccel /= ((double)GRAVITYACCEL_MEAS_LEN);
				initMeasuring();
			}
			break;
			
		case STATE_MEASURING:
			ampl -= gravityAccel;
			ampl = lpFilt.filter( ampl);
			double a0_9 = bw_0_9_filt.filter( ampl );
			double a1_0 = bw_1_0_filt.filter( ampl );
			double a1_1 = bw_1_1_filt.filter( ampl );
			double pw0_9 = pw_0_9.power( a0_9 );
			double pw1_0 = pw_1_0.power( a1_0 );
			double pw1_1 = pw_1_1.power( a1_1 );
			if( captureFile != null )
	    		captureFile.println( timestamp+","+
						"pws,"+
						pw0_9+","+
						pw1_0+","+
						pw1_1);
			boolean pw_0_9_state = pw0_9 > sensitivity;
			boolean pw_1_0_state = pw1_0 > sensitivity;
			boolean pw_1_1_state = pw1_1 > sensitivity;
			if( ( pw_0_9_state && !pw_0_9_prevstate ) ||
				( pw_1_0_state && !pw_1_0_prevstate ) ||
				( pw_1_1_state && !pw_1_1_prevstate ) ) {
				Log.d( LOG_TAG, "sampleCounter: "+sampleCounter+"; 0_9: "+pw0_9+"; 1_0: "+pw1_0+"; 1_1: "+pw1_1);
				if( ( lastEdge < 0L ) || ( ( sampleCounter - lastEdge ) > STABLEWINDOW ) ) {
					if( captureFile != null )
			    		captureFile.println( timestamp+","+
								"bump,"+bumps);
					++bumps;
					step( bumps );
//					if (feedReader != null) {
//							test.;
//							feedReader = new BufferedReader( test );
//						}
				}
				lastEdge = sampleCounter;
			}
			pw_0_9_prevstate = pw_0_9_state;
			pw_1_0_prevstate = pw_1_0_state;
			pw_1_1_prevstate = pw_1_1_state;
			++sampleCounter;
			break;
		}
	}

    private void step( int count ) {
        if( iSteps == null )
                Log.d( LOG_TAG, "step() calback: cannot call back (count: "+count+")" );
        else {
                Log.d( LOG_TAG, "step() callback: count: "+count );
                try {
                        iSteps.step( count );
                } catch( DeadObjectException ex ) {
                        Log.e( LOG_TAG,"step() callback", ex );
                } catch( RemoteException ex ) {
                        Log.e( LOG_TAG, "RemoteException",ex );
                }
        }
    }
		
    private ISteps iSteps = null;
    private final ISamplingService.Stub serviceBinder = 
			new ISamplingService.Stub() {
		public void setCallback( IBinder binder ) {
			Log.d( LOG_TAG,"setCallback" );
			iSteps = ISteps.Stub.asInterface( binder );
      	}

		public void removeCallback() {
			Log.d( LOG_TAG, "removeCallback" );
			iSteps = null;
		}

		public boolean isSampling() {
			return samplingStarted;
		}

		public void stopSampling() {
			Log.d( LOG_TAG, "stopSampling" );
			SamplingService.this.stopSampling();
			stopSelf();
		}

        public void setSensitivity( double sensitivity ) {
            Log.d( LOG_TAG, "setSensitivity: "+sensitivity );
            SamplingService.this.sensitivity = sensitivity;
        }

        public void setNoiseSensitivity( int noiseSensitivity ) {
            Log.d( LOG_TAG, "setNoiseSensitivity: "+noiseSensitivity );
        }

    };


    private static final int STABLEWINDOW = 160;
    private static final int GRAVITYACCEL_MEAS_LEN = 50;
    private static final int STATE_GRAVITYACCEL = 50;
    private static final int STATE_MEASURING = 2;
    private int gravityAccelCtr;
    private int state;
    private int rate;
    private SensorManager sensorManager;
    private PrintWriter captureFile;
	private Sensor ourSensor;
	private boolean samplingStarted = false;
    private double sensitivity = -1.0;
    private double gravityAccel = 0.0;
    private FIR lpFilt = null;
    private FIR bw_0_9_filt = null;
    private FIR bw_1_0_filt = null;
    private FIR bw_1_1_filt = null;
    private PowerWindow pw_0_9 = null;
    private PowerWindow pw_1_0 = null;
    private PowerWindow pw_1_1 = null;
    int bumps = 0;
    private long sampleCounter = 0L;
    private long lastEdge = -1L;
    private BufferedReader feedReader;
    private boolean pw_0_9_prevstate;
    private boolean pw_1_0_prevstate;
    private boolean pw_1_1_prevstate;
    
	private double lp_coeffs[] = {  0.01607052,  0.04608925,  0.1213877 ,  0.19989377,  
									0.23311754,
	                                0.19989377,  0.1213877 ,  0.04608925,  0.01607052 };
    
    private double bw_0_9[] = {
    		-0.91493901,
    		-0.90966564,
    		-0.91274175,
    		-0.91120365,
    		-0.91274175,
    		-0.91054449,
    		-0.91471932,
    		-0.90966564,
    		-0.91076427,
    		-0.91296153,
    		-0.91274175,
    		-0.91274175,
    		-0.90988533,
    		-0.91054449,
    		-0.90900639,
    		-0.91493901,
    		-0.90900639,
    		-0.9087867,
    		-0.92680425,
    		-1.40998167,
    		-3.5753814,
    		3.59823294,
    		-3.23041158,
    		1.88382078,
    		3.59988093,
    		0.87813495,
    		-3.43299897,
    		-3.59823294,
    		-2.94015366,
    		-2.5584894,
    		-1.69716357,
    		-0.635667336,
    		-0.54074565,
    		-1.02699927,
    		-0.81694125,
    		-0.8459451,
    		-0.89802018,
    		-0.95383053,
    		-0.99953352,
    		-0.94570065,
    		-0.88110126,
    		-0.91537848,
    		-0.93163824,
    		-0.93163824,
    		-0.94657959,
    		-0.88769304,
    		0.86615991  		
    };
	
    private double bw_1_0[] = {
    		-1.0165989,
    		-1.0107396,
    		-1.0141575,
    		-1.0124485,
    		-1.0141575,
    		-1.0117161,
    		-1.0163548,
    		-1.0107396,
    		-1.0119603,
    		-1.0144017,
    		-1.0141575,
    		-1.0141575,
    		-1.0109837,
    		-1.0117161,
    		-1.0100071,
    		-1.0165989,
    		-1.0100071,
    		-1.009763,
    		-1.0297825,
    		-1.5666463,
    		-3.972646,
    		-3.9980366,
    		-3.5893462,
    		2.0931342,
    		3.9998677,
    		0.9757055,
    		-3.8144433,
    		-3.9980366,
    		-3.2668374,
    		-2.842766,
    		-1.8857373,
    		-0.70629704,
    		-0.6008285,
    		-1.1411103,
    		-0.9077125,
    		-0.939939,
    		-0.9978002,
    		-1.0598117,
    		-1.1105928,
    		-1.0507785,
    		-0.9790014,
    		-1.0170872,
    		-1.0351536,
    		-1.0605441,
    		-1.0517551,
    		-0.9863256,
    		-0.9623999   		
    };

    private double bw_1_1[] = {
    		 -1.11825879,
    		 -1.11181356, 
    		 -1.11557325,
    		 -1.11369335,
    		 -1.11557325,
    		 -1.11288771,
    		 -1.11799028,
    		 -1.11181356,
    		 -1.11315633,
    		 -1.11584187,
    		 -1.11557325,
    		 -1.11557325,
    		 -1.11208207,
    		 -1.11288771
    		 -1.11100781,
    		 -1.11825879,
    		 -1.11100781,
    		 -1.1107393,
    		 -1.13276075,
    		 -1.72331093,
    		 -4.3699106,
    		 -4.39784026,
    		 -3.94828082,
    		 2.30244762,
    		 4.39985447,
    		 1.07327605,
    		 -4.19588763,
    		 -4.39784026,
    		 -3.59352114,
    		 -3.1270426,
    		 -2.07431103,
    		 -0.776926744,
    		 -0.66091135,
    		 -1.25522133,
    		 -0.99848375,
    		 -0.939939,
    		 -1.09758022,
    		 -1.16579287,
    		 -1.22165208,
    		 -1.15585635,
    		 -1.07690154,
    		 -1.11879592,
    		 -1.0351536,
    		 -1.16659851,
    		 -1.15693061,
    		 -1.08495816,
    		 -1.08495816  		
    };
    
	class FIR {
		public FIR( double coeffs[] ) {
			this.coeffs = coeffs;
			filter_len = coeffs.length;
			filter_buf = new double[ filter_len ];
			buf_ptr = 0;
			for( int i = 0 ; i < filter_len ; ++i )
				filter_buf[i] = 0.0;
		}

		public double filter( double inp ) {
			filter_buf[ buf_ptr ] = inp;
			int tmp_ptr = buf_ptr;
			double mac = 0.0;
			for(int i = 0 ; i < filter_len ; ++i ) {
				mac = mac + coeffs[i]*filter_buf[tmp_ptr];
				--tmp_ptr;
				if( tmp_ptr < 0 )
					tmp_ptr = filter_len - 1;
			}
			buf_ptr = ( ++buf_ptr ) % filter_len;
			return mac;
		}

		double coeffs[];
		double filter_buf[];
		int buf_ptr;
		int filter_len;
	}

	
	class PowerWindow {
		public PowerWindow( int len ) {
			filter_len = len;
			filter_buf = new double[ filter_len ];
			buf_ptr = 0;
			for( int i = 0 ; i < filter_len ; ++i )
				filter_buf[i] = 0.0;
		}

		public double power( double inp ) {
			filter_buf[ buf_ptr ] = inp;
			int tmp_ptr = buf_ptr;
			double mac = 0.0;
			for(int i = 0 ; i < filter_len ; ++i ) {
				mac = mac + filter_buf[tmp_ptr]*filter_buf[tmp_ptr];
				--tmp_ptr;
				if( tmp_ptr < 0 )
					tmp_ptr = filter_len - 1;
			}
			mac = mac / (double)filter_len;
			mac = Math.sqrt(mac);
			buf_ptr = ( ++buf_ptr ) % filter_len;
			return mac;
		}

		double filter_buf[];
		int buf_ptr;
		int filter_len;
	}
	
}

