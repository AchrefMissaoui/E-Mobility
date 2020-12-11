package hhn.embeddedSystems.magicPie;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.anastr.speedviewlib.SpeedView;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;



public class MainActivity extends AppCompatActivity {

    private final String TAG = MainActivity.class.getSimpleName();

    private static final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // "random" unique identifier

    // #defines for identifying shared types between calling functions
    private final static int REQUEST_ENABLE_BT = 1; // used to identify adding bluetooth names
    public final static int MESSAGE_READ = 2; // used in bluetooth handler to identify message update
    private final static int CONNECTING_STATUS = 3; // used in bluetooth handler to identify message status
    // #define hardware
    private final static double WHEEL_DIAMETER = 71.12; // in cm
    private final static int MOTOR_POLES = 4;
    private final static double CONSTANT_FOR_CALCULATING_KMH_SPEED = 0.001885;
    // #define requests
    private final static String REQUEST_STATE_HEX = "6642020000aa";
    private final static String REQUEST_PARAMETERS_HEX = "6602006866100076";
    private final static String REQUEST_FACTORY_RESET_HEX ="668120030405060708090a0b0c0d0e0f101112131415161718191b1c1d1e1f20212257";
    private final static String RESPONSE_STATE_HEADER ="10266115"; // 66420B05
    private final static String RESPONSE_PARAMETERS_HEADER = "10216323";//66102003
    private final static String WRITE_HEADER_HEX ="";
    private final static String WRITE__HEX ="";


    // GUI Components
    private View viewState;
    private TextView mBluetoothStatus;
    private TextView rpmView, powerView, currentView;
    private Button mScanBtn;
    private Button mOffBtn;
    private Button mListPairedDevicesBtn;
    private Button mDiscoverBtn;
    private ListView mDevicesListView;
    private SpeedView speedView;
    private ImageView settingsBtn;

    // GUI from controller
    private View viewController;
    private TextView cPAS ;
    private TextView cNomVolt;
    private TextView cOverVolt;
    private TextView cUnderVolt;
    private TextView cBatteryCurr;
    private TextView cRatedPhaseCurr;
    private TextView cMaxForwardRpm;
    private TextView cMaxReverseRpm;
    private TextView cMaxEBSPhaseCurr;
    private TextView cAcceleration;
    private Button cDownloadBtn;
    private Button cUploadBtn;
    private Button cResetBtn;


    private BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private ArrayAdapter<String> mBTArrayAdapter;

    private Handler mHandler; // Our main handler that will receive callback notifications
    private ConnectedThread mConnectedThread; // bluetooth background worker thread to send and receive data
    private BluetoothSocket mBTSocket = null; // bi-directional client-to-client data path
    public double MAX_SPEED = 0;
    public double MAX_RPM = 0;
    public String currentLayout ;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        currentLayout = "main";
        viewState =  findViewById(R.id.activity_state);
        viewController = findViewById(R.id.activity_controller);

        mBluetoothStatus = (TextView) findViewById(R.id.bluetooth_status);
        rpmView = (TextView) findViewById(R.id.rpmView);
        powerView = (TextView) findViewById(R.id.powerView);
        currentView = (TextView) findViewById(R.id.currentView);
        mScanBtn = (Button) findViewById(R.id.scan);
        mOffBtn = (Button) findViewById(R.id.off);
        mDiscoverBtn = (Button) findViewById(R.id.discover);
        mListPairedDevicesBtn = (Button) findViewById(R.id.paired_btn);
        speedView = (SpeedView) findViewById(R.id.speedView);
        settingsBtn = (ImageView) findViewById(R.id.settingButton);
        cDownloadBtn = (Button) findViewById(R.id.buttonDownload);
        cUploadBtn = (Button) findViewById(R.id.buttonUpload);
        cResetBtn = (Button) findViewById(R.id.buttonFactorySetting);



        viewController.setVisibility(View.GONE);


        mBTArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mBTAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio

        mDevicesListView = (ListView) findViewById(R.id.devices_list_view);
        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        // Ask for location permission if not already allowed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);


        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MESSAGE_READ) {
                    byte[] recBytes = (byte[]) msg.obj;
                    String recHeader = String.format("%s%s%s%s",recBytes[0],recBytes[1],recBytes[2],recBytes[3]);
                    System.out.println(RESPONSE_STATE_HEADER);
                    if(recHeader.equals(RESPONSE_STATE_HEADER)) {
                        int[] recievedState = new int[15];
                        for (int i = 0; i < 15; i++) {
                            recievedState[i] = convertNumbers(recBytes[i]);
                        }

                        double currentPower = ((recievedState[10] * 26.5) + (recievedState[11] / 10));


                        double currentRpm = calculateRPM(recievedState[5], recievedState[6]);
                        if (currentRpm < 1) {
                            currentRpm = 0;
                        }
                        double currentSpeed = currentRpm * WHEEL_DIAMETER * CONSTANT_FOR_CALCULATING_KMH_SPEED;


                        double currentCurr = recievedState[13] * 256 + recievedState[14];

                        if (currentSpeed > MAX_SPEED) {
                            MAX_SPEED = currentSpeed;
                        }
                        if (currentRpm > MAX_RPM) {
                            MAX_RPM = currentRpm;
                        }

                        rpmView.setText(String.format("  RPM : %s", currentRpm));
                        powerView.setText(String.format("  POW : %s", currentPower));
                        currentView.setText("curr : " + recievedState[13] + " - " + recievedState[14]);
                        speedView.setWithTremble(false);
                        speedView.speedTo((float) currentSpeed);
                    }else if(recHeader.equals(RESPONSE_PARAMETERS_HEADER)){

                        cPAS.setText(String.format("%s",recBytes[4]));
                        cNomVolt.setText(String.format("%s",recBytes[5]));
                        cOverVolt.setText(String.format("%s",recBytes[6]));
                        cUnderVolt.setText(String.format("%s",recBytes[7]));

                    }
                }

                if (msg.what == CONNECTING_STATUS) {
                    if (msg.arg1 == 1) {
                        mBluetoothStatus.setText("Connected to Device: " + msg.obj);
                        changeVisibilityWhenConnected(true);
                    } else
                        mBluetoothStatus.setText("Connection Failed");


                }
            }
        };

        if (mBTArrayAdapter == null) {
            // Device does not support Bluetooth
            mBluetoothStatus.setText("Status: Bluetooth not found");
            Toast.makeText(getApplicationContext(), "Bluetooth device not found!", Toast.LENGTH_SHORT).show();
        } else {

            mScanBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    bluetoothOn();
                }
            });

            mOffBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    bluetoothOff();
                }
            });

            mListPairedDevicesBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    listPairedDevices();
                }
            });

            mDiscoverBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    discover();
                }
            });


            settingsBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    changeView();
                }

            });

            cDownloadBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if(mConnectedThread != null)
                        mConnectedThread.write(REQUEST_PARAMETERS_HEX);
                }
            });

            cResetBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if(mConnectedThread != null)
                        mConnectedThread.write(REQUEST_FACTORY_RESET_HEX);
                }
            });
        }
    }

    @Override
    public void onBackPressed()
    {
        viewController.setVisibility(View.GONE);
        viewState.setVisibility(View.VISIBLE);
        settingsBtn.setVisibility(View.VISIBLE);
        currentLayout="main";
    }

    public void changeView(){
        currentLayout = "controller";
        viewState.setVisibility(View.GONE);
        viewController.setVisibility(View.VISIBLE);
        settingsBtn.setVisibility(View.GONE);

        cAcceleration = (TextView) findViewById(R.id.textViewAcceleration);
        cBatteryCurr = (TextView) findViewById(R.id.textViewBatteryDrawnCurrent);
        cMaxEBSPhaseCurr = (TextView) findViewById(R.id.textViewMaxEBSPhaseCurrent);
        cMaxForwardRpm = (TextView) findViewById(R.id.textViewMaxForwardRpm);
        cMaxReverseRpm = (TextView) findViewById(R.id.textViewMaxReverseRpm);
        cNomVolt = (TextView) findViewById(R.id.texViewNominalBatteryVoltage);
        cOverVolt =  (TextView) findViewById(R.id.texViewOvervoltageProtectionValue);
        cUnderVolt =  (TextView) findViewById(R.id.textViewUnderVoltageProtectionValue);
        cRatedPhaseCurr =  (TextView) findViewById(R.id.textViewRatedPhaseCurrent);
        cPAS = (TextView) findViewById(R.id.texViewPAS);





    }





    /**
     * calculates rpm using two hex numbers
     */
    private double calculateRPM(int first, int second){
        double frequency = 0;
        double numSeconds = 0;
            numSeconds = first*256 + second;
            numSeconds = numSeconds/1000;
            if(numSeconds!=0){
            frequency= 1/numSeconds;}
        return (frequency * 60 * 2) / MOTOR_POLES; // no-load rpm
    }

    /**
     * changes items visibility when connected/disconnected to a BT device
     */
    private void changeVisibilityWhenConnected(boolean isConnected){
        if(isConnected){
            mDevicesListView.setVisibility(View.GONE);
            rpmView.setVisibility(View.VISIBLE);
            powerView.setVisibility(View.VISIBLE);
            currentView.setVisibility(View.VISIBLE);
            mDiscoverBtn.setVisibility(View.GONE);
            mListPairedDevicesBtn.setVisibility(View.GONE);
            speedView.setVisibility(View.VISIBLE);
        }else {
            mDevicesListView.setVisibility(View.VISIBLE);
            rpmView.setVisibility(View.GONE);
            powerView.setVisibility(View.GONE);
            currentView.setVisibility(View.GONE);
            mDiscoverBtn.setVisibility(View.VISIBLE);
            mListPairedDevicesBtn.setVisibility(View.VISIBLE);
            speedView.setVisibility(View.GONE);
        }
    }


    /**
     *converts numbers from range -127--127 to 0--255
     * returns int
     */
    private int convertNumbers(int number){
        if(number<0){
            return Math.abs(number + 256)%256;
        }else{
        return number;}
    }

    private void bluetoothOn(){
        if (!mBTAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            mBluetoothStatus.setText("Bluetooth enabled");
            Toast.makeText(getApplicationContext(),"Bluetooth turned on",Toast.LENGTH_SHORT).show();

        }
        else{
            Toast.makeText(getApplicationContext(),"Bluetooth is already on", Toast.LENGTH_SHORT).show();
        }
    }

    // Enter here after user selects "yes" or "no" to enabling radio
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent Data){
        // Check which request we're responding to
        if (requestCode == REQUEST_ENABLE_BT) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                // The user picked a contact.
                // The Intent's data Uri identifies which contact was selected.
                mBluetoothStatus.setText("Enabled");
            }
            else
                mBluetoothStatus.setText("Disabled");
        }
    }

    private void bluetoothOff(){
        if(mBluetoothStatus.getText().toString().contains("Connected")){
            changeVisibilityWhenConnected(false);
        }
        mBTAdapter.disable(); // turn off
        mBluetoothStatus.setText("Bluetooth disabled");
        Toast.makeText(getApplicationContext(),"Bluetooth turned Off", Toast.LENGTH_SHORT).show();

    }

    private void discover(){
        // Check if the device is already discovering
        if(mBTAdapter.isDiscovering()){
            mBTAdapter.cancelDiscovery();
            Toast.makeText(getApplicationContext(),"Discovery stopped",Toast.LENGTH_SHORT).show();
        }
        else{
            if(mBTAdapter.isEnabled()) {
                mBTArrayAdapter.clear(); // clear items
                mBTAdapter.startDiscovery();
                Toast.makeText(getApplicationContext(), "Discovery started", Toast.LENGTH_SHORT).show();
                registerReceiver(blReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            }
            else{
                Toast.makeText(getApplicationContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
            }
        }
    }

    final BroadcastReceiver blReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // add the name to the list
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                mBTArrayAdapter.notifyDataSetChanged();
            }
        }
    };

    private void listPairedDevices(){
        mBTArrayAdapter.clear();
        mPairedDevices = mBTAdapter.getBondedDevices();
        if(mBTAdapter.isEnabled()) {
            // put it's one to the adapter
            for (BluetoothDevice device : mPairedDevices)
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());

            Toast.makeText(getApplicationContext(), "Show Paired Devices", Toast.LENGTH_SHORT).show();
        }
        else
            Toast.makeText(getApplicationContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
    }

    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            if(!mBTAdapter.isEnabled()) {
                Toast.makeText(getBaseContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
                return;
            }

            mBluetoothStatus.setText("Connecting...");
            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) view).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0,info.length() - 17);

            // Spawn a new thread to avoid blocking the GUI one
            new Thread()
            {
                @Override
                public void run() {
                    boolean fail = false;

                    BluetoothDevice device = mBTAdapter.getRemoteDevice(address);

                    try {
                        mBTSocket = createBluetoothSocket(device);
                    } catch (IOException e) {
                        fail = true;
                        Toast.makeText(getBaseContext(), "Socket creation failed", Toast.LENGTH_SHORT).show();
                    }
                    // Establish the Bluetooth socket connection.
                    try {
                        mBTSocket.connect();
                    } catch (IOException e) {
                        try {
                            fail = true;
                            mBTSocket.close();
                            mHandler.obtainMessage(CONNECTING_STATUS, -1, -1)
                                    .sendToTarget();
                        } catch (IOException e2) {
                            //insert code to deal with this
                            Toast.makeText(getBaseContext(), "Socket creation failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                    if(!fail) {
                        mConnectedThread = new ConnectedThread(mBTSocket, mHandler);
                        mConnectedThread.start();

                        mHandler.obtainMessage(CONNECTING_STATUS, 1, -1, name)
                                .sendToTarget();
                        if(mConnectedThread != null) //First check to make sure thread created
                        {
                            Timer _timer = new Timer();
                            _timer.schedule(new TimerTask() {
                                @Override
                                public void run() {
                                    // use runOnUiThread(Runnable action)
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if(currentLayout.equals("main"))
                                            mConnectedThread.write(REQUEST_STATE_HEX);
                                        }
                                    });
                                }
                            }, 250,250);
                        }
                    }
                }
            }.start();


        }
    };

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        try {
            final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
            return (BluetoothSocket) m.invoke(device, BT_MODULE_UUID);
        } catch (Exception e) {
            Log.e(TAG, "Could not create Insecure RFComm Connection",e);
        }
        return  device.createRfcommSocketToServiceRecord(BT_MODULE_UUID);
    }
}