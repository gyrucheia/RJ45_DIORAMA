package com.example.sanlomobapp.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class DioramaBleProvisioner {
    private static final String TAG = "DioramaBleProvisioner";

    // Target device name
    public static final String TARGET_DEVICE_NAME = "RJ45-MASTER";
    public static final String DEFAULT_POP_PIN = "RJ45-2026";

    // Custom GATT UUIDs
    public static final UUID SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB");
    public static final UUID CHAR_POP_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB");
    public static final UUID CHAR_WIFI_UUID = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB");
    public static final UUID CHAR_STATUS_UUID = UUID.fromString("0000FFF3-0000-1000-8000-00805F9B34FB");

    public interface ProvisioningCallback {
        void onDeviceDiscovered(BluetoothDevice device);
        void onConnected();
        void onAuthenticated();
        void onProvisioningSuccess();
        void onError(String message);
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner leScanner;
    private BluetoothGatt bluetoothGatt;

    private boolean isScanning = false;
    private BluetoothDevice connectedDevice;
    private ProvisioningCallback callback;

    private String pendingSsid;
    private String pendingPass;

    public DioramaBleProvisioner(Context context) {
        this.context = context.getApplicationContext();
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    @SuppressLint("MissingPermission")
    public void startScan(ProvisioningCallback cb) {
        this.callback = cb;
        if (!isBluetoothAvailable()) {
            if (callback != null) callback.onError("Bluetooth is disabled. Please turn on Bluetooth.");
            return;
        }

        // Check bonded (already paired in phone settings) devices first
        try {
            java.util.Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice dev : bonded) {
                    String dName = dev.getName();
                    if (dName != null && dName.contains(TARGET_DEVICE_NAME)) {
                        Log.d(TAG, "Found target device in paired/bonded list: " + dName);
                        connectedDevice = dev;
                        if (callback != null) callback.onDeviceDiscovered(dev);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}

        leScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (leScanner == null) {
            if (callback != null) callback.onError("BLE scanner unavailable.");
            return;
        }

        isScanning = true;
        // Auto-stop scan after 15 seconds
        handler.postDelayed(() -> {
            if (isScanning) {
                stopScan();
                if (connectedDevice == null && callback != null) {
                    callback.onError("Scan timeout: RJ45-MASTER not found nearby.");
                }
            }
        }, 15000);

        try {
            java.util.List<android.bluetooth.le.ScanFilter> filters = new java.util.ArrayList<>();
            filters.add(new android.bluetooth.le.ScanFilter.Builder().setDeviceName(TARGET_DEVICE_NAME).build());
            filters.add(new android.bluetooth.le.ScanFilter.Builder().setServiceUuid(new android.os.ParcelUuid(SERVICE_UUID)).build());

            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            leScanner.startScan(filters, settings, scanCallback);
            Log.d(TAG, "Scanning for " + TARGET_DEVICE_NAME + " with low-latency filters...");
        } catch (Exception e) {
            // Fallback to unfiltered scan
            try {
                leScanner.startScan(scanCallback);
                Log.d(TAG, "Fallback scanning for " + TARGET_DEVICE_NAME + "...");
            } catch (Exception ex) {
                if (callback != null) callback.onError("Failed to start BLE scan: " + ex.getMessage());
            }
        }
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        isScanning = false;
        if (leScanner != null) {
            try {
                leScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = device.getName();
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }

            if (name != null && name.contains(TARGET_DEVICE_NAME)) {
                Log.d(TAG, "Found target device: " + name + " [" + device.getAddress() + "]");
                stopScan();
                connectedDevice = device;
                handler.post(() -> {
                    if (callback != null) callback.onDeviceDiscovered(device);
                });
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE scan failed with error: " + errorCode);
            handler.post(() -> {
                if (callback != null) callback.onError("BLE Scan failed (code " + errorCode + ")");
            });
        }
    };

    @SuppressLint("MissingPermission")
    public void connectAndProvision(BluetoothDevice device, String ssid, String password, String popPin) {
        this.pendingSsid = ssid;
        this.pendingPass = password;

        if (device == null) {
            if (callback != null) callback.onError("No device specified.");
            return;
        }

        Log.d(TAG, "Connecting to GATT on " + device.getAddress());
        bluetoothGatt = device.connectGatt(context, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "GATT connected. Requesting MTU 512...");
                    handler.post(() -> {
                        if (callback != null) callback.onConnected();
                    });
                    if (!gatt.requestMtu(512)) {
                        Log.w(TAG, "requestMtu failed, discovering services directly.");
                        gatt.discoverServices();
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "GATT disconnected.");
                    close();
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                Log.d(TAG, "MTU changed to " + mtu + " (status " + status + "). Discovering services...");
                gatt.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    BluetoothGattService service = gatt.getService(SERVICE_UUID);
                    if (service != null) {
                        Log.d(TAG, "Provisioning service found. Sending Proof-of-Possession...");
                        writePoP(gatt, service, popPin != null ? popPin : DEFAULT_POP_PIN);
                    } else {
                        Log.w(TAG, "Provisioning service not found on device.");
                        handler.post(() -> {
                            if (callback != null) callback.onError("Device does not support RJ45 Provisioning service.");
                        });
                    }
                }
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (CHAR_POP_UUID.equals(characteristic.getUuid())) {
                        Log.d(TAG, "PoP accepted! Now writing Wi-Fi credentials...");
                        handler.post(() -> {
                            if (callback != null) callback.onAuthenticated();
                        });
                        BluetoothGattService service = gatt.getService(SERVICE_UUID);
                        if (service != null) {
                            writeWifiPayload(gatt, service, pendingSsid, pendingPass);
                        }
                    } else if (CHAR_WIFI_UUID.equals(characteristic.getUuid())) {
                        Log.d(TAG, "Wi-Fi credentials written successfully!");
                        handler.post(() -> {
                            if (callback != null) callback.onProvisioningSuccess();
                        });
                        // Allow MASTER to acknowledge and shut down BLE
                        handler.postDelayed(() -> close(), 2000);
                    }
                } else {
                    handler.post(() -> {
                        if (callback != null) callback.onError("GATT write failed with status " + status);
                    });
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void writePoP(BluetoothGatt gatt, BluetoothGattService service, String pin) {
        BluetoothGattCharacteristic charPoP = service.getCharacteristic(CHAR_POP_UUID);
        if (charPoP != null) {
            charPoP.setValue(pin.getBytes(StandardCharsets.UTF_8));
            gatt.writeCharacteristic(charPoP);
        }
    }

    @SuppressLint("MissingPermission")
    private void writeWifiPayload(BluetoothGatt gatt, BluetoothGattService service, String ssid, String pass) {
        BluetoothGattCharacteristic charWifi = service.getCharacteristic(CHAR_WIFI_UUID);
        if (charWifi != null) {
            try {
                // Send newline-delimited format (SSID\nPASS) which fits safely within single BLE packet
                String payload = ssid + "\n" + pass;
                charWifi.setValue(payload.getBytes(StandardCharsets.UTF_8));
                gatt.writeCharacteristic(charWifi);
            } catch (Exception e) {
                if (callback != null) callback.onError("Failed to encode Wi-Fi payload: " + e.getMessage());
            }
        }
    }

    @SuppressLint("MissingPermission")
    public void close() {
        stopScan();
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
        connectedDevice = null;
    }
}
