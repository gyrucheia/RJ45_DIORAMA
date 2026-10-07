package com.example.sanlomobapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.sanlomobapp.data.FirebaseRepository;
import com.example.sanlomobapp.model.DioramaTelemetry;
import com.example.sanlomobapp.model.SystemStatus;

import java.util.Locale;

public class ExploreFragment extends Fragment implements
        FirebaseRepository.TelemetryListener,
        FirebaseRepository.SystemStatusListener {

    private TextView tvTemp, tvTempStatus, tvRiverLevel, tvWindValue, tvVisitors;
    private boolean sensorGridVisible = true;

    private final FirebaseRepository repository = FirebaseRepository.getInstance();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_explore, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvTemp       = view.findViewById(R.id.tv_explore_temp_value);
        tvTempStatus = view.findViewById(R.id.tv_explore_temp_status);
        tvRiverLevel = view.findViewById(R.id.tv_river_level_value);
        tvWindValue  = view.findViewById(R.id.tv_wind_value);
        tvVisitors   = view.findViewById(R.id.tv_visitors_value);

        LinearLayout sensorGrid = view.findViewById(R.id.sensor_detail_grid);
        TextView tvSensorToggle = view.findViewById(R.id.tv_sensor_toggle);

        if (tvSensorToggle != null && sensorGrid != null) {
            tvSensorToggle.setOnClickListener(v -> {
                if (view instanceof ViewGroup) {
                    android.transition.TransitionManager.beginDelayedTransition((ViewGroup) view);
                }
                sensorGridVisible = !sensorGridVisible;
                sensorGrid.setVisibility(sensorGridVisible ? View.VISIBLE : View.GONE);
                tvSensorToggle.setText(sensorGridVisible ? "Hide  ›" : "Show  ›");
            });
        }

        View cardOpenVoiceGuide = view.findViewById(R.id.card_open_voice_guide);
        if (cardOpenVoiceGuide != null) {
            cardOpenVoiceGuide.setOnClickListener(v -> showAppTutorialDialog());
        }


        View cardAskSanLoGuide = view.findViewById(R.id.card_ask_sanlo_guide_entry);
        if (cardAskSanLoGuide != null) {
            cardAskSanLoGuide.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).loadFragment(new GeminiFragment());
                }
            });
        }

        View btnAskGuideExplore = view.findViewById(R.id.btn_ask_guide_explore);
        if (btnAskGuideExplore != null) {
            btnAskGuideExplore.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).loadFragment(new GeminiFragment());
                }
            });
        }

        renderDisconnected();
        repository.addTelemetryListener(this);
        repository.addSystemStatusListener(this);
    }

    private void renderDisconnected() {
        if (tvTemp != null) tvTemp.setText("--");
        if (tvTempStatus != null) tvTempStatus.setText("Awaiting Telemetry");
        if (tvRiverLevel != null) tvRiverLevel.setText("--");
        if (tvWindValue != null) tvWindValue.setText("--");
        if (tvVisitors != null) tvVisitors.setText("--");
    }

    @Override
    public void onTelemetryUpdated(DioramaTelemetry telemetry) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            boolean isOnline = repository.getCurrentSystemStatus().isMasterActive();
            if (!isOnline && !telemetry.hasData()) {
                renderDisconnected();
                return;
            }

            if (tvTemp != null) {
                if (telemetry.getTemperature() != null) {
                    double t = telemetry.getTemperature();
                    tvTemp.setText(String.format(Locale.US, "%.0f°c", t));
                    if (tvTempStatus != null) {
                        if (t < 22) tvTempStatus.setText("Cool");
                        else if (t < 31) tvTempStatus.setText("Comfortable");
                        else tvTempStatus.setText("Warm / Hot");
                    }
                } else {
                    tvTemp.setText("--");
                    if (tvTempStatus != null) tvTempStatus.setText("--");
                }
            }

            if (tvRiverLevel != null) {
                if (telemetry.getWater_level_pct() != null) {
                    tvRiverLevel.setText(telemetry.getWater_level_pct() + "%");
                } else {
                    tvRiverLevel.setText("--");
                }
            }

            if (tvWindValue != null) {
                if (telemetry.getFlex_raw() != null) {
                    boolean bent = Boolean.TRUE.equals(telemetry.getFlex_bent());
                    tvWindValue.setText(bent ? "Bent · Active" : "Normal · Calm");
                } else {
                    tvWindValue.setText("--");
                }
            }

            if (tvVisitors != null) {
                if (telemetry.getVisitor_count_today() != null) {
                    tvVisitors.setText(telemetry.getVisitor_count_today() + " events");
                } else if (Boolean.TRUE.equals(telemetry.getPir_active())) {
                    tvVisitors.setText("Active");
                } else {
                    tvVisitors.setText("--");
                }
            }
        });
    }

    @Override
    public void onSystemStatusUpdated(SystemStatus status, boolean isOnline) {
        if (!isAdded()) return;
        if (!isOnline) {
            requireActivity().runOnUiThread(this::renderDisconnected);
        }
    }

    private void showAppTutorialDialog() {
        if (!isAdded()) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Quick App Tutorial")
                .setMessage("Welcome to the SanLo Diorama interactive companion!\n\n"
                        + "1. Home & Explore: Observe live telemetry including microclimate temperature, river water level, valley wind, and visitor motion.\n\n"
                        + "2. Simulation: Run coordinated diorama exhibits (Tram Ride, Street Lights, River RGB, Wind Breeze, Flood Warning & Drainage) or Full System simulation.\n\n"
                        + "3. Manual Controls: Manually control tram movement, adjust lighting modes, test fan speeds, and manage the drainage pump.\n\n"
                        + "4. Ask SanLo Guide: Tap the Ask SanLo Guide card anytime to converse with the AI Guide about the exhibit history, sensors, and engineering.")
                .setPositiveButton("Got It", (dialog, which) -> dialog.dismiss())
                .show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        repository.removeTelemetryListener(this);
        repository.removeSystemStatusListener(this);
    }
}
