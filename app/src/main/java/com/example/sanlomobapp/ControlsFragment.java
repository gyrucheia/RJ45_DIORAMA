package com.example.sanlomobapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

        Button btnDeployZipline = view.findViewById(R.id.btn_deploy_zipline);
        Button btnTraverseSpan  = view.findViewById(R.id.btn_traverse_span);
        Button btnPuffFog       = view.findViewById(R.id.btn_puff_fog);
        Button btnChirpStream   = view.findViewById(R.id.btn_chirp_stream);
        Button btnEmergencyStop = view.findViewById(R.id.btn_emergency_stop);
        Button btnSelfTest      = view.findViewById(R.id.btn_self_test);
        Button btnRgbPreset     = view.findViewById(R.id.btn_rgb_preset);
        Button btnGeminiSpeaker = view.findViewById(R.id.btn_gemini_speaker);
        Button btnModulatePump  = view.findViewById(R.id.btn_modulate_pump);

        btnDeployZipline.setOnClickListener(v ->
                showFeedback("Zipline deployed → Pulley link engaged"));

        btnTraverseSpan.setOnClickListener(v ->
                showFeedback("Cable car traversing span →"));

        btnPuffFog.setOnClickListener(v ->
                showFeedback("Canopy mist activated — puffing fog"));

        btnChirpStream.setOnClickListener(v ->
                showFeedback("Forest audio stream started ♪"));

        btnModulatePump.setOnClickListener(v ->
                showFeedback("River pump modulating at 62%"));

        btnEmergencyStop.setOnClickListener(v ->
                showFeedback("⚠ EMERGENCY STOP — all motors halted"));

        btnSelfTest.setOnClickListener(v ->
                showFeedback("Self test initiated — checking all channels"));

        btnRgbPreset.setOnClickListener(v ->
                showFeedback("RGB glow preset applied"));

        btnGeminiSpeaker.setOnClickListener(v ->
                showFeedback("Gemini speaker activated"));
    }

    private void showFeedback(String message) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
    }
}
