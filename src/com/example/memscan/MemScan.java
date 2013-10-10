package com.example.memscan;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Date;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Bundle;
import android.os.Handler;
import com.example.memscan.R;


public class MemScan extends Activity {
	public static final String UPDATE_UI_INTENT = "com.example.memscan.UPDATE_UI_INTENT";
	public static final String REPORT_FILENAME = "report.txt";
	
	private DataUpdateReceiver mDataUpdateReceiver;
	private TextView mTextView;
	private Switch mSwitchWidget;
	
	// From:
	// http://stackoverflow.com/questions/600207/android-check-if-a-service-is-running/5921190#5921190
	private boolean isMyServiceRunning() {
	    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
	    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
	        if (MemScanService.class.getName().equals(service.service.getClassName())) {
	            return true;
	        }
	    }
	    return false;
	}
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);
        mTextView = (TextView) findViewById(R.id.textView1);
        
        if (mDataUpdateReceiver == null) {
        	mDataUpdateReceiver = new DataUpdateReceiver();
        }
        IntentFilter intentFilter = new IntentFilter(UPDATE_UI_INTENT);
        registerReceiver(mDataUpdateReceiver, intentFilter);
        
        mSwitchWidget = (Switch) findViewById(R.id.switchBackgroundService);
        mSwitchWidget.setChecked(isMyServiceRunning());
        
        updateFromFile();

        mSwitchWidget.setOnCheckedChangeListener(new OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
				if (isChecked) {
					// Clear any last report that is being shown
					mTextView.setText("");
			        Intent intent = new Intent(MemScan.this, MemScanService.class);
			        startService(intent);
				} else {
			        Intent intent = new Intent(MemScan.this, MemScanService.class);
			        stopService(intent);
				}
			}
		});
    }
    
    @Override
	protected void onDestroy() {
        if (mDataUpdateReceiver != null) {
        	unregisterReceiver(mDataUpdateReceiver);
        }

		super.onDestroy();
	}

	private void updateFromFile() {
    	File file = getFileStreamPath(REPORT_FILENAME);
		if (file.exists()) {
			Date d = new Date(file.lastModified());
			mTextView.append(String.format("Results from %s :\n\n", d.toString()));
			try {
				StringBuilder stringBuilder = new StringBuilder();
				char[] buf = new char[8 * 1024];
				InputStreamReader input = new InputStreamReader(openFileInput(REPORT_FILENAME));
				try {
					int len;
					while ((len = input.read(buf)) != -1) {
						stringBuilder.append(buf, 0, len);
					}
				} finally {
					input.close();
				}

				mTextView.append(stringBuilder.toString());
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
    	}
    }
    
    private class DataUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(UPDATE_UI_INTENT)) {
            	updateFromFile();
            	
            	// Service might still be running, so delay a little bit
				Handler h = new Handler();
				Runnable r = new Runnable() {
					public void run() {
						mSwitchWidget.setChecked(isMyServiceRunning());
					}
				};
				h.postDelayed(r, 500);
            }
        }
    }

	public static class MemScanService extends IntentService {

		public MemScanService() {
			super("MemScanService");
		}
		
		private Handler mHandler;
		
		private void showMessage(final String str) {
			mHandler.post(new Runnable() {					
				@Override
				public void run() {
	    			Toast.makeText(getApplicationContext(), str, Toast.LENGTH_LONG).show();
				}
			});
		}

		@Override
		public int onStartCommand(Intent intent, int flags, int startId) {
			// Make Handler when we're on the UI thread
			mHandler = new Handler();
			return super.onStartCommand(intent, flags, startId);
		}

		@Override
		protected void onHandleIntent(Intent intent) {
			try {
				startMemScan();
			} catch (final Throwable e) {
				showMessage(String.format("Error: %s", e.toString()));
			}
		}
		
		private void startMemScan() {
			File file = getFileStreamPath(REPORT_FILENAME);
			if (file.exists()) {
				file.delete();
			}
			
			int bytesOrig = getMemFree();
			int unused = 75 * 1024 * 1024;	// leave a little unused
			int bytes = bytesOrig - unused;
			if (bytes < 0) {	// don't let it go negative
				bytes = 0;
			}
			
			try {
				if (bytes < 75 * 1024 * 1024) {
					showMessage(String.format("Not enough free memory. Only %,d bytes available.", bytesOrig));
				} else {
					showMessage(String.format("Scanning %,d bytes for memory corruption in the background", bytes));
					
					String title = "MemScan running in the background";
					String text = String.format("Scanning %,d bytes for memory corruption", bytes);
					
					Notification notification = new Notification.Builder(getApplicationContext())
						.setContentTitle(title)
						.setContentText(text)
						.setSmallIcon(R.drawable.ram_drive_icon)
						.getNotification();
					Intent notificationIntent = new Intent(this, MemScan.class);
					PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
					notification.setLatestEventInfo(this, title, text, pendingIntent);
					startForeground(1, notification);
					
					// Continuously scan for memory corruption
					String result = memScan(bytes);
		
					// Write report to disk
					OutputStreamWriter output = null;
					try {
						output = new OutputStreamWriter(openFileOutput(REPORT_FILENAME, MODE_PRIVATE));
						if (result != null && ! result.isEmpty()) {
							output.write(result);
						} else {
							output.write("No memory corruption detected.\n");
						}
					} catch (FileNotFoundException e) {
						throw new RuntimeException(e);
					} catch (IOException e) {
						throw new RuntimeException(e);
					} finally {
						if (output != null) {
							try {
								output.close();
							} catch (IOException e) {
								throw new RuntimeException(e);
							}				
						}
					}
	
					// If result was bad, let the user know
					if (result != null && ! result.isEmpty()) {
						showMessage("MemScan detected memory corruption, run it to view details.");
					}
				}
			} finally {
				// Make sure to update UI in case of exception to get the switch right.
				
				// Update UI of running Activity
				Intent refreshIntent = new Intent(UPDATE_UI_INTENT);
				sendBroadcast(refreshIntent);
			}
		}
		
		@Override
		public void onDestroy() {
			// This will set a flag so that the memScan() call will return.
			stopMemScan();
			super.onDestroy();
		}
		
		// Adapted from:
		// http://stackoverflow.com/questions/11465251/proc-meminfo-returns-null-when-the-memory-info-is-read-via-a-service
		private int getMemFree() {
			int bytes = -1;
	        BufferedReader readStream = null;
			try {
				readStream = new BufferedReader(new FileReader("/proc/meminfo"));
		        String x = readStream.readLine();
		        while (x != null) {
		            if (x.startsWith("MemFree:")) {
		                bytes = Integer.parseInt(x.split("[ ]+", 3)[1]) * 1024;
		                break;
		            }
		            x = readStream.readLine();
		        }

			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				if (readStream != null) {
					try {
						readStream.close();
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}
			
			if (bytes == -1) {
				throw new RuntimeException("Could not determine MemFree");
			}
	        
	        return bytes;
		}
	}

    public static native String memScan(int bytes);
    public static native void stopMemScan();

    static {
        System.loadLibrary("memscan");
    }
}
