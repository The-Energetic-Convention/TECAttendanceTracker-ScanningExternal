package com.TheEnergeticCon.AttendanceTrackerJava;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION;

import com.acs.smartcard.Reader;
import com.acs.smartcard.ReaderException;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static java.lang.System.arraycopy;

import com.TheEnergeticCon.AttendanceTrackerJava.databinding.ActivityMainBinding;

import org.apache.hc.client5.http.fluent.Form;
import org.apache.hc.client5.http.fluent.Request;

public class MainActivity extends AppCompatActivity {

    private static String authKey;
    private static String baseAddress;
    private static final String keyFile = "/storage/emulated/0/.TECConfig/Config.txt";
    private static final String TAG = MainActivity.class.getName();
    private static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    private Boolean allowScanning = true;
    private Boolean ready = false;

    private Boolean shutdown = false;

    private PendingIntent mPermissionIntent;

    private int SendCommand(byte[] data, byte[] out) throws ReaderException {
        return mReader.control(0, Reader.IOCTL_CCID_ESCAPE, data, data.length, out, out.length);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {

                synchronized (this) {

                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);

                    if (intent.getBooleanExtra( UsbManager.EXTRA_PERMISSION_GRANTED, false )) {

                        if (device != null) {

                            // Open reader
                            Log.d(TAG, "Opening reader: " + device.getDeviceName() + "...");
                            new Thread(new OpenTask(device)).start();
                        }
                    } else {

                        assert device != null;
                        Log.d(TAG, "Permission denied for device " + device.getDeviceName());
                    }
                }

            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "Reader Disconnected");
                runOnUiThread(() -> {
                    findViewById(R.id.PermsButton).setAlpha(1);
                    findViewById(R.id.PermsButton).setEnabled(true);
                    ((TextView) findViewById(R.id.StatusText)).setText(R.string.openReader);
                });
            }
        }
    };

    private class OpenTask implements Runnable {

        private final UsbDevice mDevice;

        public OpenTask(UsbDevice device) {
            mDevice = device;
        }

        @Override
        public void run() {

            Exception result = doInBackground(mDevice);
            runOnUiThread(() -> onPostExecute(result));
        }

        private Exception doInBackground(UsbDevice... params) {

            Exception result = null;

            try {

                runOnUiThread(() -> findViewById(R.id.PermsButton).setEnabled(false));
                ((TextView) findViewById(R.id.StatusText)).setText(R.string.opening);
                mReader.open(params[0]);

                // for some fucking reason, waiting a bit between each works...
                // but just going one after the other without delay doesn't work at all. IDK
                Thread.sleep(10);

                //turn off the damn buzzer
                SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x52, (byte) 0x00, (byte) 0x00}, new byte[2]);
                Thread.sleep(10);

                //turn off auto polling
                SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x51, (byte) 0x01, (byte) 0x00}, new byte[2]);
                Thread.sleep(10);

                //turn off the led
                SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x40, (byte) 0x0C, (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00}, new byte[2]);
                Thread.sleep(10);

                //turn auto polling on differently
                //send this command when checking for a card as well, works for that
                byte[] response = new byte[50];
                int respLen = SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x05, (byte) 0xD4, (byte) 0x60, (byte) 0xFF, (byte) 0x01, (byte) 0x10}, response);
                Thread.sleep(10);

                new Thread(new PollingTask()).start();
            } catch (Exception e) {

                result = e;
                Log.d(TAG, "SHIT! " + e.getMessage(), e);
            }
            return result;
        }

        private void onPostExecute(Exception result) {

            if (result != null) {

                findViewById(R.id.PermsButton).setEnabled(true);
                Log.d(TAG, result.toString());

            } else {

                Log.d(TAG, "Reader name: " + mReader.getReaderName());

                int numSlots = mReader.getNumSlots();
                Log.d(TAG, "Number of slots: " + numSlots);
                findViewById(R.id.PermsButton).setAlpha(0);
                ((TextView) findViewById(R.id.StatusText)).setText(R.string.scanBadge);
                ready = true;

            }
        }
    }

    private class PollingTask implements Runnable {


        public PollingTask() { }

        @Override
        public void run() {

            Exception result = doInBackground();
            runOnUiThread(() -> {
                if (result == null){
                    resetText();
                }
            });
        }

        private Exception doInBackground() {

            Exception result = null;

            // wait for reader to be ready lol
            while (!ready) { /* Camellia - SPIN ETERNALLY starts playing */ }

            boolean cardScanned = false;
            while (true) {
                try {

                    // start polling for tags
                    // scan a tag when found,
                    // and do the scan in/out stuff

                    //background loop to continually scan cards
                    while (true) {
                        // while scanning is allowed, eg. no errors
                        while (allowScanning) {
                            byte[] response = new byte[50];

                            //keep checking for cards
                            while (!cardScanned && allowScanning) {
                                try {

                                    SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x05, (byte) 0xD4, (byte) 0x60, (byte) 0xFF, (byte) 0x01, (byte) 0x10}, response);
                                    Thread.sleep(10);
                                    if (response[0] != (byte) 0x00 && allowScanning) {
                                        SetLEDGreen();

                                        runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.scanning));
                                        cardScanned = true;
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, e.getMessage(), e);
                                }
                                Thread.sleep(10);
                            }

                            // found card, scan it and do stuff with data :3
                            response = new byte[64];
                            arraycopy(Objects.requireNonNull(readFrom((byte) 0x04)), 0, response, 0, 16);
                            Thread.sleep(10);
                            arraycopy(Objects.requireNonNull(readFrom((byte) 0x08)), 0, response, 16, 16);
                            Thread.sleep(10);
                            arraycopy(Objects.requireNonNull(readFrom((byte) 0x0C)), 0, response, 32, 16);
                            Thread.sleep(10);
                            arraycopy(Objects.requireNonNull(readFrom((byte) 0x10)), 0, response, 48, 16);
                            Thread.sleep(10);
                            //Log.d(TAG, "Response " + response.length + " bytes: " + toHexString(response));

                            if (response[0] != (byte) 0x90) {

                                SetLEDOff();

                                // process data
                                int index1 = FirstNull(response);
                                byte[] buffer = new byte[response.length - 7];
                                arraycopy(response, 7, buffer, 0, buffer.length);

                                response = new byte[buffer.length];
                                arraycopy(buffer, 0, response, 0, response.length);
                                index1 = FirstNull(response);
                                if (response[index1 - 1] == (byte) 0xFE) {
                                    index1--;
                                } //if FE terminated, don't include it
                                buffer = new byte[index1];
                                arraycopy(response, 0, buffer, 0, index1);
                                String tagData = new String(buffer, StandardCharsets.UTF_8);

                                ready = false;

                                // scan attendee in/out
                                new Thread(new ScanTask(tagData)).start();
                            }
                            cardScanned = false;

                            // wait for reader to be ready again lol
                            while (!ready) { /* Camellia - SPIN ETERNALLY starts playing */ }
                            // (only way i could think of to keep it from reading over and over when not ready yet... at 11:45 pm)
                            // but at least it works lol, only reads once, then waits for either the requests to be done, or reset to be hit if an error
                        }

                        if (shutdown){
                            return result;
                        }
                    }

                } catch (Exception e) {

                    //set led red!
                    allowScanning = false;
                    error(e);
                    result = e;
                    runOnUiThread(() -> {
                        findViewById(R.id.ResetButton).setAlpha(1);
                        findViewById(R.id.ResetButton).setEnabled(true);
                    });
                }

                if (shutdown){
                    return result;
                }
            }
        }
        private void resetText(){ ((TextView) findViewById(R.id.StatusText)).setText(R.string.scanBadge); }
    }

    private int FirstNull(byte[] in){
        for (int i = 0; i < in.length; i++){
            if (in[i] == (byte)0x00){
                return i;
            }
        }
        return -1;
    }

    private class ScanTask implements Runnable {

        private final String tagData;

        public ScanTask(String tagDataIn) { tagData = tagDataIn; }

        @Override
        public void run() {

            Exception result = doInBackground();
            runOnUiThread(() -> {
                if (result == null){
                    ready = true;
                    resetText();
                }
            });
        }

        private Exception doInBackground() {


            Exception result = null;
            try {
                //scan attendee in/out
                String attendeeString = Request.get(baseAddress + "/GetAttendee?" + tagData.split("&")[1]).execute().returnContent().asString();

                if (Objects.equals(attendeeString, "[null]")) {
                    runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.notRegistered));
                    SetLEDRed();
                    Thread.sleep(2000);
                    SetLEDOff();
                    return null;
                }
                Gson gson = new Gson();
                Attendee attendee = gson.fromJson(attendeeString, Attendee[].class)[0];

                // check if name on badge matches server
                // if not, invalid badge!
                if (!("Name=" + attendee.name).equals(tagData.split("&")[0].split("//")[1])) {
                    runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.invalid));
                    SetLEDRed();
                    Thread.sleep(2000);
                    SetLEDOff();
                    return null;
                }

                // make request to scan in/out

                runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(attendee.atCon ? getString(R.string.scanOut, attendee.name) : getString(R.string.scanIn, attendee.name)));

                String resultStr = Request.post(baseAddress + "/Attendee" + (attendee.atCon ? "Left" : "Join") + "?ID=" + attendee.id).bodyForm(Form.form().add("Auth", authKey).build()).execute().returnContent().asString();

                switch (resultStr) {
                    case "SUCCESS":
                        runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.ok));
                        SetLEDOff();
                        Thread.sleep(1000);
                        break;
                    case "NOID":
                        runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.noID));
                        SetLEDRed();
                        Thread.sleep(1000);
                        SetLEDOff();
                        break;
                    case "AuthError":
                        runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.serverAuth));
                        SetLEDRed();
                        Thread.sleep(1000);
                        SetLEDOff();
                        break;
                    case "NOTFOUND":
                        runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.notFound));
                        SetLEDRed();
                        Thread.sleep(1000);
                        SetLEDOff();
                        break;
                    default:
                        runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(R.string.unknown));
                        SetLEDRed();
                        Thread.sleep(1000);
                        SetLEDOff();
                        break;
                }
            }
            catch (Exception e){
                allowScanning = false;
                error(e);
                result = e;
                runOnUiThread(() -> {
                    findViewById(R.id.ResetButton).setAlpha(1);
                    findViewById(R.id.ResetButton).setEnabled(true);
                });
            }

            return result;
        }

        private void resetText(){ ((TextView) findViewById(R.id.StatusText)).setText(R.string.scanBadge); }
    }

    private void SetLEDRed(){
        try{
            SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x40, (byte) 0x0D, (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00}, new byte[2]);
        }
        catch (Exception ex){ Log.d(TAG, "Unable to set led red: " + ex.getMessage(), ex);}
    }

    private void SetLEDGreen(){
        try{
            SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x40, (byte) 0x0E, (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00}, new byte[2]);
        }
        catch (Exception ex){ Log.d(TAG, "Unable to set led green: " + ex.getMessage(), ex);}
    }

    private void SetLEDOff(){
        try{
            SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x40, (byte) 0x0C, (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00}, new byte[2]);
        }
        catch (Exception ex){ Log.d(TAG, "Unable to set led off: " + ex.getMessage(), ex);}
    }

    private byte[] readFrom(byte page){
        byte[] response = new byte[16];
        try {
            byte[] buffer = new byte[31];
            SendCommand(new byte[]{(byte) 0xFF, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x05, (byte) 0xD4, (byte) 0x40, (byte) 0x01, (byte) 0x30, page}, buffer);
            arraycopy(buffer,3, response, 0, 16);
        }
        catch (Exception e){
            error(e);
            return null;
        }

        return response;
    }

    private void error(Exception e){
        SetLEDRed();
        runOnUiThread(() -> ((TextView) findViewById(R.id.StatusText)).setText(getString(R.string.errorMessage, e.getMessage())));
        Log.d(TAG, "Error: " + e.getMessage(), e);
    }

    private byte[] toByteArray(String hexString) {

        int hexStringLength = hexString.length();
        byte[] byteArray;
        int count = 0;
        char c;
        int i;

        // Count number of hex characters
        for (i = 0; i < hexStringLength; i++) {

            c = hexString.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'A' && c <= 'F' || c >= 'a'
                    && c <= 'f') {
                count++;
            }
        }

        byteArray = new byte[(count + 1) / 2];
        boolean first = true;
        int len = 0;
        int value;
        for (i = 0; i < hexStringLength; i++) {

            c = hexString.charAt(i);
            if (c >= '0' && c <= '9') {
                value = c - '0';
            } else if (c >= 'A' && c <= 'F') {
                value = c - 'A' + 10;
            } else if (c >= 'a' && c <= 'f') {
                value = c - 'a' + 10;
            } else {
                value = -1;
            }

            if (value >= 0) {

                if (first) {

                    byteArray[len] = (byte) (value << 4);

                } else {

                    byteArray[len] |= (byte) value;
                    len++;
                }

                first = !first;
            }
        }

        return byteArray;
    }

    private String toHexString(int i) {

        String hexString = Integer.toHexString(i);
        if (hexString.length() % 2 != 0) {
            hexString = "0" + hexString;
        }

        return hexString.toUpperCase();
    }

    /**
     * Converts the byte array to HEX string.
     *
     * @param buffer the buffer.
     * @return the HEX string.
     */
    private String toHexString(byte[] buffer) {

        StringBuilder bufferString = new StringBuilder();

        for (byte b : buffer) {

            String hexChar = Integer.toHexString(b & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }

            bufferString.append(hexChar.toUpperCase()).append(" ");
        }

        return bufferString.toString();
    }

    UsbManager manager;
    Reader mReader;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        com.TheEnergeticCon.AttendanceTrackerJava.databinding.ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        flags |= PendingIntent.FLAG_MUTABLE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags |= PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT;
        }
        mPermissionIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION), flags);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, mReceiver, filter, ContextCompat.RECEIVER_EXPORTED);

        Button clickButton = findViewById(R.id.PermsButton);
        clickButton.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(MainActivity.this, ACTION_USB_PERMISSION) != PERMISSION_GRANTED){
                manager = (UsbManager)getSystemService(Context.USB_SERVICE);
                mReader = new Reader(manager);
                for (UsbDevice dev: manager.getDeviceList().values() ) {
                    if (dev.getVendorId() == 1839){
                        manager.requestPermission(dev, mPermissionIntent);
                    }
                }
            }
        });

        Button resetButton = findViewById(R.id.ResetButton);
        resetButton.setOnClickListener(v -> {
            //reset the system in case of error or sumn
            SetLEDOff();
            runOnUiThread(() -> {
                        ((TextView) findViewById(R.id.StatusText)).setText(R.string.scanBadge);
                        findViewById(R.id.ResetButton).setAlpha(0);
                        findViewById(R.id.ResetButton).setEnabled(false);
                    });
            allowScanning = true;
            ready = true;
        });

        boolean settingup = true;
        boolean askingPerms = false;
        while (settingup) {
            if (Environment.isExternalStorageManager()) {
                StringBuilder config = new StringBuilder();
                try {
                    File myFile = new File(keyFile);
                    FileInputStream fIn = new FileInputStream(myFile);
                    BufferedReader myReader = new BufferedReader(new InputStreamReader(fIn));
                    String aDataRow;
                    while ((aDataRow = myReader.readLine()) != null) {
                        config.append(aDataRow).append("\n");
                    }
                    myReader.close();
                } catch (IOException e) {
                    Log.e(TAG, e.getMessage(), e);
                }

                String[] configArr = config.toString().split("\n");
                authKey = configArr[0];
                baseAddress = "http://" + configArr[1] + ":6969";
                settingup = false;
            } else if (!askingPerms) {
                askingPerms = true;
                startActivity(new Intent(ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();

    }

    @Override
    public void onPause() {
        super.onPause();
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    public void toast(String message) {
        Toast toast = Toast.makeText(this, message, Toast.LENGTH_LONG);
        toast.show();
    }
}