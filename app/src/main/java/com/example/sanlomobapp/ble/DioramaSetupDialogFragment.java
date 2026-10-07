package com.example.sanlomobapp.ble;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;

import com.example.sanlomobapp.R;
import com.example.sanlomobapp.data.FirebaseRepository;
import com.example.sanlomobapp.model.SystemStatus;

import java.util.ArrayList;
import java.util.List;

public class DioramaSetupDialogFragment extends DialogFragment implements
        DioramaBleProvisioner.ProvisioningCallback,
        FirebaseRepository.SystemStatusListener {

    private TextView tvBleStatusChip;
    private TextView tvFeedback;
    private EditText etSsid;
    private EditText etPassword;
    private Button btnConnectMaster;
    private Button btnCancel;

    private DioramaBleProvisioner provisioner;
    private BluetoothDevice discoveredDevice;

    private boolean isAwaitingHeartbeat = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FirebaseRepository repository = FirebaseRepository.getInstance();

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!Boolean.TRUE.equals(granted)) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    startBleScan();
                } else {
                    tvBleStatusChip.setText("Permission needed");
                    tvBleStatusChip.setTextColor(requireContext().getColor(R.color.alert_red));
                    Toast.makeText(requireContext(), "Allow Bluetooth access to find the diorama.", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.Theme_SanLoMobApp);
        provisioner = new DioramaBleProvisioner(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_diorama_setup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvBleStatusChip  = view.findViewById(R.id.tv_ble_status_chip);
        tvFeedback       = view.findViewById(R.id.tv_provision_feedback);
        etSsid           = view.findViewById(R.id.et_wifi_ssid);
        etPassword       = view.findViewById(R.id.et_wifi_password);
        btnConnectMaster = view.findViewById(R.id.btn_connect_master);
        btnCancel        = view.findViewById(R.id.btn_cancel_setup);

        btnConnectMaster.setOnClickListener(v -> performProvisioning());
        btnCancel.setOnClickListener(v -> dismiss());

        repository.addSystemStatusListener(this);
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        if (needed.isEmpty()) {
            startBleScan();
        } else {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void startBleScan() {
        tvBleStatusChip.setText("Searching…");
        tvBleStatusChip.setTextColor(requireContext().getColor(R.color.celestial_blue));
        provisioner.startScan(this);
    }

    private void performProvisioning() {
        String ssid = etSsid.getText().toString().trim();
        String pass = etPassword.getText().toString().trim();

        if (ssid.isEmpty()) {
            Toast.makeText(requireContext(), "Type the Wi-Fi or hotspot name first.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (discoveredDevice == null) {
            Toast.makeText(requireContext(), "Still looking for the diorama. Keep your phone close.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnConnectMaster.setEnabled(false);
        btnConnectMaster.setText("Sending Wi-Fi details…");
        tvFeedback.setVisibility(View.VISIBLE);
        tvFeedback.setTextColor(requireContext().getColor(R.color.text_muted));
        tvFeedback.setText("Contacting the diorama over Bluetooth…");

        provisioner.connectAndProvision(discoveredDevice, ssid, pass, DioramaBleProvisioner.DEFAULT_POP_PIN);
    }

    @Override
    public void onDeviceDiscovered(BluetoothDevice device) {
        this.discoveredDevice = device;
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            tvBleStatusChip.setText("Found");
            tvBleStatusChip.setTextColor(requireContext().getColor(R.color.sap_green));
            tvBleStatusChip.setBackgroundResource(R.drawable.bg_status_chip_green);
        });
    }

    @Override
    public void onConnected() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            tvBleStatusChip.setText("Found");
            tvFeedback.setText("Diorama found. Preparing to send Wi-Fi details…");
        });
    }

    @Override
    public void onAuthenticated() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            tvFeedback.setText("Sending Wi-Fi details…");
        });
    }

    @Override
    public void onProvisioningSuccess() {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            btnConnectMaster.setText("Waiting for the diorama…");
            tvFeedback.setText("Details sent. Waiting for the diorama to join Wi-Fi and report online. This is not confirmed yet.");
            isAwaitingHeartbeat = true;

            // Timeout after 35 seconds if MASTER does not report heartbeat
            handler.postDelayed(() -> {
                if (isAwaitingHeartbeat && isAdded()) {
                    isAwaitingHeartbeat = false;
                    btnConnectMaster.setEnabled(true);
                    btnConnectMaster.setText("Try again");
                    tvFeedback.setText("The diorama did not come online. Check that the network is 2.4 GHz and the password is correct, then try again.");
                    tvFeedback.setTextColor(requireContext().getColor(R.color.alert_red));
                }
            }, 35000);
        });
    }

    @Override
    public void onError(String message) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            btnConnectMaster.setEnabled(true);
            btnConnectMaster.setText("Try again");
            tvFeedback.setVisibility(View.VISIBLE);
            tvFeedback.setText(message);
            tvFeedback.setTextColor(requireContext().getColor(R.color.alert_red));
        });
    }

    @Override
    public void onSystemStatusUpdated(SystemStatus status, boolean isOnline) {
        if (isAwaitingHeartbeat && isOnline && isAdded()) {
            isAwaitingHeartbeat = false;
            requireActivity().runOnUiThread(() -> {
                tvFeedback.setText("✔ Diorama is online.");
                tvFeedback.setTextColor(requireContext().getColor(R.color.sap_green));
                btnConnectMaster.setText("Connected");
                Toast.makeText(requireContext(), "Diorama connected.", Toast.LENGTH_SHORT).show();
                handler.postDelayed(this::dismiss, 1200);
            });
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        repository.removeSystemStatusListener(this);
        if (provisioner != null) {
            provisioner.close();
        }
    }
}
