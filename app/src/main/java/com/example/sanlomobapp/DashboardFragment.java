package com.example.sanlomobapp;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.sanlomobapp.data.FirebaseRepository;
import com.example.sanlomobapp.model.DioramaReportedState;
import com.example.sanlomobapp.model.DioramaTelemetry;
import com.example.sanlomobapp.model.SimulationState;
import com.example.sanlomobapp.model.SystemStatus;

import java.util.Locale;

public class DashboardFragment extends Fragment implements
        FirebaseRepository.TelemetryListener,
        FirebaseRepository.SystemStatusListener,
        FirebaseRepository.ReportedStateListener {

    private TextView tvOnlineStatus;
    private TextView tvTempValue;
    private TextView tvWaterValue;
    private TextView tvWindValue;
    private TextView tvMotionValue;
    private TextView tvSafetyStatus;
    private TextView tvTramStatusHome;
    private TextView tvActiveSimTitle;
    private TextView tvActiveSimDesc;

    private LinearLayout cardTramStatus;
    private Button btnGoSimulation;

    private final FirebaseRepository repository = FirebaseRepository.getInstance();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_dashboard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvOnlineStatus   = view.findViewById(R.id.tv_dash_status_online);
        tvTempValue      = view.findViewById(R.id.tv_temp_value);
        tvWaterValue     = view.findViewById(R.id.tv_water_value);
        tvWindValue      = view.findViewById(R.id.tv_wind_value);
        tvMotionValue    = view.findViewById(R.id.tv_motion_value);
        tvSafetyStatus   = view.findViewById(R.id.tv_safety_status);
        tvTramStatusHome = view.findViewById(R.id.tv_tram_status_home);
        tvActiveSimTitle = view.findViewById(R.id.tv_active_sim_title);
        tvActiveSimDesc  = view.findViewById(R.id.tv_active_sim_desc);

        if (tvOnlineStatus != null) {
            tvOnlineStatus.setOnClickListener(v -> {
                boolean isOnline = repository.getCurrentSystemStatus().isMasterActive();
                if (isOnline) {
                    Toast.makeText(requireContext(), "Diorama is already connected and online!", Toast.LENGTH_SHORT).show();
                } else {
                    com.example.sanlomobapp.ble.DioramaSetupDialogFragment dialog = new com.example.sanlomobapp.ble.DioramaSetupDialogFragment();
                    dialog.show(getParentFragmentManager(), "diorama_setup");
                }
            });
        }

        cardTramStatus  = view.findViewById(R.id.card_tram_status);
        btnGoSimulation = view.findViewById(R.id.btn_go_simulation);

        if (btnGoSimulation != null) {
            btnGoSimulation.setOnClickListener(v -> navigateToTab("simulation"));
        }

        if (cardTramStatus != null) {
            cardTramStatus.setOnClickListener(v -> navigateToTab("controls"));
        }

        View btnHomeTutorial = view.findViewById(R.id.btn_home_tutorial);
        if (btnHomeTutorial != null) {
            btnHomeTutorial.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), OnboardingActivity.class);
                intent.putExtra(OnboardingActivity.EXTRA_REPLAY, true);
                startActivity(intent);
            });
        }

        // Initialize with unavailable/loading placeholder states until live telemetry is received
        renderDisconnectedState();

        // Register repository listeners
        repository.addTelemetryListener(this);
        repository.addSystemStatusListener(this);
        repository.addReportedStateListener(this);
    }

    private void navigateToTab(String tabKey) {
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            activity.showNav(true);
            switch (tabKey) {
                case "simulation":
                    activity.loadFragment(new SimulationFragment());
                    activity.setActiveNav("simulation");
                    break;
                case "controls":
                    activity.loadFragment(new ControlsFragment());
                    activity.setActiveNav("controls");
                    break;
                case "explore":
                    activity.loadFragment(new ExploreFragment());
                    activity.setActiveNav("explore");
                    break;
                default:
                    activity.loadFragment(new DashboardFragment());
                    activity.setActiveNav("dashboard");
                    break;
            }
        }
    }

    private void renderDisconnectedState() {
        if (tvOnlineStatus != null) {
            tvOnlineStatus.setText("Offline");
            tvOnlineStatus.setBackgroundResource(R.drawable.bg_status_chip_coral);
            tvOnlineStatus.setTextColor(requireContext().getColor(R.color.alert_red));
        }
        if (tvTempValue != null) tvTempValue.setText("--");
        if (tvWaterValue != null) tvWaterValue.setText("--");
        if (tvWindValue != null) tvWindValue.setText("--");
        if (tvMotionValue != null) tvMotionValue.setText("--");
        if (tvSafetyStatus != null) tvSafetyStatus.setText("Diorama offline — Awaiting connection");
        if (tvTramStatusHome != null) tvTramStatusHome.setText("Awaiting Hardware");
    }

    @Override
    public void onTelemetryUpdated(DioramaTelemetry telemetry) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            boolean isOnline = repository.getCurrentSystemStatus().isMasterActive();

            if (!isOnline && !telemetry.hasData()) {
                renderDisconnectedState();
                return;
            }

            if (tvTempValue != null) {
                if (telemetry.getTemperature() != null) {
                    tvTempValue.setText(String.format(Locale.US, "%.0f°C", telemetry.getTemperature()));
                } else {
                    tvTempValue.setText("--");
                }
            }

            if (tvWaterValue != null) {
                if (telemetry.getWater_level_pct() != null) {
                    int pct = telemetry.getWater_level_pct();
                    tvWaterValue.setText(String.format(Locale.US, "%d%%", pct));
                    if (pct >= 50) {
                        tvWaterValue.setTextColor(requireContext().getColor(R.color.alert_red));
                        if (tvSafetyStatus != null) {
                            tvSafetyStatus.setText("WATER ALERT: High water level detected");
                            tvSafetyStatus.setTextColor(requireContext().getColor(R.color.alert_red));
                        }
                    } else {
                        tvWaterValue.setTextColor(requireContext().getColor(R.color.river_blue));
                        if (tvSafetyStatus != null) {
                            tvSafetyStatus.setText("System ready");
                            tvSafetyStatus.setTextColor(requireContext().getColor(R.color.sap_green));
                        }
                    }
                } else {
                    tvWaterValue.setText("--");
                }
            }

            if (tvWindValue != null) {
                if (telemetry.getFlex_raw() != null) {
                    boolean bent = Boolean.TRUE.equals(telemetry.getFlex_bent());
                    tvWindValue.setText(bent ? "Breeze Active" : "Calm (Idle)");
                } else {
                    tvWindValue.setText("--");
                }
            }

            if (tvMotionValue != null) {
                boolean pir = Boolean.TRUE.equals(telemetry.getPir_active());
                tvMotionValue.setText(pir ? "Motion Detected" : "Standby");
            }
        });
    }

    @Override
    public void onSystemStatusUpdated(SystemStatus status, boolean isOnline) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (tvOnlineStatus != null) {
                if (isOnline) {
                    tvOnlineStatus.setText("Connected");
                    tvOnlineStatus.setBackgroundResource(R.drawable.bg_status_chip_green);
                    tvOnlineStatus.setTextColor(requireContext().getColor(R.color.sap_green));
                    if (tvSafetyStatus != null && "Diorama offline — Awaiting connection".equals(tvSafetyStatus.getText().toString())) {
                        tvSafetyStatus.setText("Diorama Online — Ready");
                        tvSafetyStatus.setTextColor(requireContext().getColor(R.color.sap_green));
                    }
                } else {
                    tvOnlineStatus.setText("Offline");
                    tvOnlineStatus.setBackgroundResource(R.drawable.bg_status_chip_coral);
                    tvOnlineStatus.setTextColor(requireContext().getColor(R.color.alert_red));
                    if (tvSafetyStatus != null) {
                        tvSafetyStatus.setText("Diorama offline — Awaiting connection");
                        tvSafetyStatus.setTextColor(requireContext().getColor(R.color.text_muted));
                    }
                }
            }
        });
    }

    @Override
    public void onReportedStateUpdated(DioramaReportedState reportedState) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (tvTramStatusHome != null) {
                String state = reportedState.getTram_state();
                if (state != null) {
                    tvTramStatusHome.setText(state);
                } else {
                    tvTramStatusHome.setText("Unknown");
                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        repository.removeTelemetryListener(this);
        repository.removeSystemStatusListener(this);
        repository.removeReportedStateListener(this);
    }
}
