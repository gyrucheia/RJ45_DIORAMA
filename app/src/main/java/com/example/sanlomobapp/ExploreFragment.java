package com.example.sanlomobapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.Locale;
import java.util.Random;

public class ExploreFragment extends Fragment {

    private TextView tvTemp, tvTempStatus, tvRiverLevel, tvWindValue, tvVisitors;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private double temp = 29.0;
    private double riverLevel = 1.2;
    private int wind = 34;
    private int visitors = 14;
    private boolean sensorGridVisible = true;

    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            updateValues();
            handler.postDelayed(this, 3000);
        }
    };

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

        LinearLayout sensorGrid   = view.findViewById(R.id.sensor_detail_grid);
        TextView tvSensorToggle   = view.findViewById(R.id.tv_sensor_toggle);

        if (tvSensorToggle != null && sensorGrid != null) {
            tvSensorToggle.setOnClickListener(v -> {
                sensorGridVisible = !sensorGridVisible;
                sensorGrid.setVisibility(sensorGridVisible ? View.VISIBLE : View.GONE);
                tvSensorToggle.setText(sensorGridVisible ? "Hide  ›" : "Show  ›");
            });
        }

        view.findViewById(R.id.btn_preview_alert).setOnClickListener(v ->
                Toast.makeText(requireContext(),
                        "High-water safety alert preview — coming soon", Toast.LENGTH_SHORT).show());

        renderValues();
        handler.postDelayed(updater, 3000);
    }

    private void updateValues() {
        temp += (random.nextDouble() - 0.5) * 0.8;
        temp = Math.max(15.0, Math.min(45.0, temp));
        riverLevel += (random.nextDouble() - 0.5) * 0.1;
        riverLevel = Math.max(0.1, Math.min(5.0, riverLevel));
        wind += (int) ((random.nextDouble() - 0.5) * 8);
        wind = Math.max(0, Math.min(100, wind));
        visitors += (int) ((random.nextDouble() - 0.5) * 3);
        visitors = Math.max(0, Math.min(200, visitors));
        renderValues();
    }

    private void renderValues() {
        if (tvTemp != null)
            tvTemp.setText(String.format(Locale.US, "%.0f°c", temp));
        if (tvTempStatus != null) {
            if (temp < 20) tvTempStatus.setText("Cool");
            else if (temp < 30) tvTempStatus.setText("Comfortably warm");
            else if (temp < 36) tvTempStatus.setText("Warm");
            else tvTempStatus.setText("Very hot");
        }
        if (tvRiverLevel != null)
            tvRiverLevel.setText(String.format(Locale.US, "%.1f", riverLevel));
        if (tvWindValue != null)
            tvWindValue.setText(String.format(Locale.US, "%d%%", wind));
        if (tvVisitors != null)
            tvVisitors.setText(String.valueOf(visitors));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(updater);
    }
}
