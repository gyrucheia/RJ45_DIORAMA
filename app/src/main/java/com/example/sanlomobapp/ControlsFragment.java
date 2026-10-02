package com.example.sanlomobapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ControlsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_controls, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Button btnStartTram     = view.findViewById(R.id.btn_start_tram);
        Button btnReturnTram    = view.findViewById(R.id.btn_return_tram);
        Button btnEmergencyStop = view.findViewById(R.id.btn_emergency_stop);
        LinearLayout layoutEmergency = view.findViewById(R.id.layout_emergency_stop);
        LinearLayout btnTryGesture   = view.findViewById(R.id.btn_try_gesture);

        Switch switchRiver  = view.findViewById(R.id.switch_river_flow);
        Switch switchSounds = view.findViewById(R.id.switch_nature_sounds);
        Switch switchLights = view.findViewById(R.id.switch_ambient_lights);

        SeekBar seekWind    = view.findViewById(R.id.seekbar_wind);
        TextView tvWindLvl  = view.findViewById(R.id.tv_wind_level);

        LinearLayout pillRight = view.findViewById(R.id.gesture_pill_right);
        LinearLayout pillLeft  = view.findViewById(R.id.gesture_pill_left);
        LinearLayout pillUp    = view.findViewById(R.id.gesture_pill_up);
        LinearLayout pillDown  = view.findViewById(R.id.gesture_pill_down);

        btnStartTram.setOnClickListener(v -> showFeedback("Tram departing → At Start Station"));
        btnReturnTram.setOnClickListener(v -> showFeedback("Tram returning ← to Start Station"));

        btnEmergencyStop.setOnClickListener(v -> showFeedback("⚠ EMERGENCY STOP — all motors halted"));
        if (layoutEmergency != null) layoutEmergency.setOnClickListener(v ->
                showFeedback("⚠ EMERGENCY STOP — all motors halted"));

        if (switchRiver != null) switchRiver.setOnCheckedChangeListener((btn, checked) ->
                showFeedback("River Flow " + (checked ? "ON" : "OFF")));
        if (switchSounds != null) switchSounds.setOnCheckedChangeListener((btn, checked) ->
                showFeedback("Nature Sounds " + (checked ? "ON" : "OFF")));
        if (switchLights != null) switchLights.setOnCheckedChangeListener((btn, checked) ->
                showFeedback("Ambient Lights " + (checked ? "ON" : "OFF")));

        if (seekWind != null && tvWindLvl != null) {
            seekWind.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    String label = p < 20 ? "Calm" : p < 40 ? "Low" : p < 65 ? "Moderate" : p < 85 ? "Strong" : "Max";
                    tvWindLvl.setText(label + " · " + p + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

        if (pillRight != null) pillRight.setOnClickListener(v -> {
            showFeedback("Gesture RIGHT → Tram Start");
            btnStartTram.performClick();
        });
        if (pillLeft != null) pillLeft.setOnClickListener(v -> {
            showFeedback("Gesture LEFT ← Tram Return");
            btnReturnTram.performClick();
        });
        if (pillUp != null) pillUp.setOnClickListener(v -> {
            showFeedback("Gesture UP ↑ → Lights");
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).showNav(true);
                ((MainActivity) getActivity()).loadFragment(new RgbLightFragment());
                ((MainActivity) getActivity()).setActiveNav("rgb");
            }
        });
        if (pillDown != null) pillDown.setOnClickListener(v -> {
            boolean isOn = switchRiver != null && switchRiver.isChecked();
            if (switchRiver != null) switchRiver.setChecked(!isOn);
            showFeedback("Gesture DOWN ↓ → Pump " + (!isOn ? "ON" : "OFF"));
        });

        if (btnTryGesture != null) btnTryGesture.setOnClickListener(v ->
                showFeedback("Wave a gesture near the sensor to control the diorama"));
    }

    private void showFeedback(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
