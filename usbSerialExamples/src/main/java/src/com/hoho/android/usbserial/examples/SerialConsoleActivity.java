/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

package src.com.hoho.android.usbserial.examples;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.examples.R;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Monitors a single {@link UsbSerialPort} instance, showing all data
 * received.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialConsoleActivity extends Activity {

    private final String TAG = SerialConsoleActivity.class.getSimpleName();

    /**
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialPort)}.
     *
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */
    private static UsbSerialPort sPort = null;

    private TextView mTitleTextView;
    private TextView mDumpTextView;
    private ScrollView mScrollView;
    private CheckBox chkDTR;
    private CheckBox chkRTS;
    private EditText mInputEdit;
    private EditText mRepeatEdit;
    private EditText mDelayEdit;
    private Button mSendButton;

    private boolean mStop = false;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

        @Override
        public void onRunError(Exception e) {
            Log.d(TAG, "Runner stopped.");
        }

        @Override
        public void onNewData(final byte[] data) {
            SerialConsoleActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SerialConsoleActivity.this.updateReceivedData(data);
                }
            });
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);
        mTitleTextView = (TextView) findViewById(R.id.demoTitle);
        mDumpTextView = (TextView) findViewById(R.id.consoleText);
        mScrollView = (ScrollView) findViewById(R.id.demoScroller);
        chkDTR = (CheckBox) findViewById(R.id.checkBoxDTR);
        chkRTS = (CheckBox) findViewById(R.id.checkBoxRTS);
        mInputEdit = (EditText) findViewById(R.id.editInput);
        mRepeatEdit = (EditText) findViewById(R.id.editRepeat);
        mDelayEdit = (EditText) findViewById(R.id.editDelay);
        mSendButton = (Button) findViewById(R.id.sendButton);



        chkDTR.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    sPort.setDTR(isChecked);
                }catch (IOException x){}
            }
        });

        chkRTS.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    sPort.setRTS(isChecked);
                }catch (IOException x){}
            }
        });

        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mSendButton.getText().toString().equals(getString(R.string.send))) {
                    mStop = false;
                    sendDataToUsb();
                } else {
                    mStop = true;
                }
            }
        });

    }

    private void sendDataToUsb() {

        if (sPort == null) {
            Toast.makeText(this, "Port is not open", Toast.LENGTH_SHORT).show();
            return;
        }

        String data = mInputEdit.getText().toString();
        String repeatStr = mRepeatEdit.getText().toString();
        int repeat = 0;

        if (data.isEmpty()) {
            Toast.makeText(this, "Input is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!repeatStr.isEmpty()) {
            repeat = Integer.parseInt(repeatStr);
        }


        if (repeat == 1) {
            byte[] buf = data.getBytes();
            try {
                if (sPort.write(buf, 500) != data.getBytes().length) {
                    Log.e(TAG, "Error sending data out");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (repeat >= 0) {
            String delayStr = mDelayEdit.getText().toString();
            int delay = Integer.parseInt(delayStr);

            if (delay < 100) {
                Toast.makeText(this, "Repeat Delay must be greater than 100 ms", Toast.LENGTH_SHORT).show();
                return;
            }

            mRepeatEdit.setEnabled(false);
            mInputEdit.setEnabled(false);
            mDelayEdit.setEnabled(false);

            mSendButton.setText(getString(R.string.stop));
            new SendDataRepeatedlyTask().execute(repeat);
        }

    }

    private class SendDataRepeatedlyTask extends AsyncTask<Integer, Void, Integer> {
        private byte[] mData;
        private int mRepeats;
        private int mDelay;

        @Override
        protected void onPreExecute() {
            String data = mInputEdit.getText().toString();
            mData = data.getBytes();

            String delay = mDelayEdit.getText().toString();
            mDelay = Integer.parseInt(delay);
        }


        @Override
        protected Integer doInBackground(Integer... repeats) {
            mRepeats = repeats[0];
            boolean foreverMode = false;

            // If user wanted to repeat endlessly, turn on foreverMode;
            if (mRepeats == 0) foreverMode = true;

            while(!mStop) {
                try {
                    if (sPort.write(mData, 500) != mData.length) {
                        Log.e(TAG, "Error sending data out");
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Send every 250 milliseconds.
                SystemClock.sleep(250);

                if (foreverMode) continue;
                else mRepeats--;

                // Once we deplete "repeats" stop
                if (mRepeats == 0) break;

            }

            if (mStop) return 1;    // User abruptly stopped the repeat process.

            return 0;
        }

        @Override
        protected void onPostExecute(Integer result) {

            if (result == 1) {
                Toast.makeText(SerialConsoleActivity.this, "Sending Process was interrupted", Toast.LENGTH_SHORT).show();
            }

            mRepeatEdit.setEnabled(true);
            mInputEdit.setEnabled(true);
            mDelayEdit.setEnabled(true);
            mSendButton.setText(getString(R.string.send));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopIoManager();
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e) {
                // Ignore.
            }
            sPort = null;
        }
        finish();
    }

    void showStatus(TextView theTextView, String theLabel, boolean theValue){
        String msg = theLabel + ": " + (theValue ? "enabled" : "disabled") + "\n";
        theTextView.append(msg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Resumed, port=" + sPort);
        if (sPort == null) {
            mTitleTextView.setText("No serial device.");
        } else {
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            UsbDeviceConnection connection = usbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                mTitleTextView.setText("Opening device failed");
                return;
            }

            try {
                sPort.open(connection);
                sPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                showStatus(mDumpTextView, "CD  - Carrier Detect", sPort.getCD());
                showStatus(mDumpTextView, "CTS - Clear To Send", sPort.getCTS());
                showStatus(mDumpTextView, "DSR - Data Set Ready", sPort.getDSR());
                showStatus(mDumpTextView, "DTR - Data Terminal Ready", sPort.getDTR());
                showStatus(mDumpTextView, "DSR - Data Set Ready", sPort.getDSR());
                showStatus(mDumpTextView, "RI  - Ring Indicator", sPort.getRI());
                showStatus(mDumpTextView, "RTS - Request To Send", sPort.getRTS());

            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                mTitleTextView.setText("Error opening device: " + e.getMessage());
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                return;
            }
            mTitleTextView.setText("Serial device: " + sPort.getClass().getSimpleName());
        }
        onDeviceStateChange();
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    private void updateReceivedData(byte[] data) {
        final String message = "Read " + data.length + " bytes: \n"
                + HexDump.dumpHexString(data) + "\n\n";
        mDumpTextView.append(message);
        mScrollView.smoothScrollTo(0, mDumpTextView.getBottom());
    }

    /**
     * Starts the activity, using the supplied driver instance.
     *
     * @param context - Context
     * @param port - USB Serial Port
     */
    static void show(Context context, UsbSerialPort port) {
        sPort = port;
        final Intent intent = new Intent(context, SerialConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }

}
