package com.example.sanlomobapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class DashboardFragment extends Fragment {

    private TextView tvTempValue, tvTempStatus;
    private TextView tvHumidityValue, tvHumidityStatus;
    private TextView tvWaterValue, tvWaterStatus;
    private TextView tvVisitorsValue, tvVisitorsStatus;
    private TextView tvLastUpdated;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private double temperature = 28.4;
    private int humidity = 64;
    private double waterLevel = 1.2;
    private int visitors = 14;

    private final Runnable sensorUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            updateSensorValues();
            handler.postDelayed(this, 3000);
        }
    };

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

        tvTempValue = view.findViewById(R.id.tv_temp_value);
        tvTempStatus = view.findViewById(R.id.tv_temp_status);
        tvHumidityValue = view.findViewById(R.id.tv_humidity_value);
        tvHumidityStatus = view.findViewById(R.id.tv_humidity_status);
        tvWaterValue = view.findViewById(R.id.tv_water_value);
        tvWaterStatus = view.findViewById(R.id.tv_water_status);
        tvVisitorsValue = view.findViewById(R.id.tv_visitors_value);
        tvVisitorsStatus = view.findViewById(R.id.tv_visitors_status);
        tvLastUpdated = view.findViewById(R.id.tv_last_updated);

        renderValues();
        handler.postDelayed(sensorUpdater, 3000);
    }

    private void updateSensorValues() {
        temperature += (random.nextDouble() - 0.5) * 1.2;
        temperature = Math.max(15.0, Math.min(45.0, temperature));

        humidity += (int) ((random.nextDouble() - 0.5) * 6);
        humidity = Math.max(20, Math.min(100, humidity));

        waterLevel += (random.nextDouble() - 0.5) * 0.2;
        waterLevel = Math.max(0.1, Math.min(5.0, waterLevel));

        visitors += (int) ((random.nextDouble() - 0.5) * 4);
        visitors = Math.max(0, Math.min(200, visitors));

        renderValues();
    }

    private void renderValues() {
        tvTempValue.setText(String.format(Locale.US, "%.1f", temperature));
        tvHumidityValue.setText(String.valueOf(humidity));
        tvWaterValue.setText(String.format(Locale.US, "%.1f", waterLevel));
        tvVisitorsValue.setText(String.valueOf(visitors));

        updateTempStatus();
        updateHumidityStatus();
        updateWaterStatus();
        updateVisitorStatus();

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        tvLastUpdated.setText("Updated: " + sdf.format(new Date()));
    }

    private void updateTempStatus() {
        if (temperature < 20) {
            tvTempStatus.setText("Cool");
            tvTempStatus.setBackgroundResource(R.drawable.bg_pill_blue);
        } else if (temperature < 35) {
            tvTempStatus.setText("Normal");
            tvTempStatus.setBackgroundResource(R.drawable.bg_pill_green);
        } else {
            tvTempStatus.setText("High");
            tvTempStatus.setBackgroundResource(R.drawable.bg_pill_red);
        }
    }

    private void updateHumidityStatus() {
        if (humidity < 40) {
            tvHumidityStatus.setText("Dry");
            tvHumidityStatus.setBackgroundResource(R.drawable.bg_pill_yellow);
            tvHumidityStatus.setTextColor(requireContext().getColor(R.color.bitter_lemon_text));
        } else if (humidity <= 70) {
            tvHumidityStatus.setText("Optimal");
            tvHumidityStatus.setBackgroundResource(R.drawable.bg_pill_blue);
            tvHumidityStatus.setTextColor(requireContext().getColor(R.color.white));
        } else {
            tvHumidityStatus.setText("Humid");
            tvHumidityStatus.setBackgroundResource(R.drawable.bg_pill_red);
            tvHumidityStatus.setTextColor(requireContext().getColor(R.color.white));
        }
    }

    private void updateWaterStatus() {
        if (waterLevel < 1.5) {
            tvWaterStatus.setText("Safe");
            tvWaterStatus.setBackgroundResource(R.drawable.bg_pill_green);
        } else if (waterLevel < 3.0) {
            tvWaterStatus.setText("Rising");
            tvWaterStatus.setBackgroundResource(R.drawable.bg_pill_yellow);
            tvWaterStatus.setTextColor(requireContext().getColor(R.color.bitter_lemon_text));
        } else {
            tvWaterStatus.setText("FLOOD");
            tvWaterStatus.setBackgroundResource(R.drawable.bg_pill_red);
            tvWaterStatus.setTextColor(requireContext().getColor(R.color.white));
        }
    }

    private void updateVisitorStatus() {
        if (visitors == 0) {
            tvVisitorsStatus.setText("Empty");
            tvVisitorsStatus.setBackgroundResource(R.drawable.bg_pill_white);
            tvVisitorsStatus.setTextColor(requireContext().getColor(R.color.sap_green));
        } else if (visitors < 50) {
            tvVisitorsStatus.setText("Active");
            tvVisitorsStatus.setBackgroundResource(R.drawable.bg_pill_yellow);
            tvVisitorsStatus.setTextColor(requireContext().getColor(R.color.bitter_lemon_text));
        } else {
            tvVisitorsStatus.setText("Busy");
            tvVisitorsStatus.setBackgroundResource(R.drawable.bg_pill_red);
            tvVisitorsStatus.setTextColor(requireContext().getColor(R.color.white));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(sensorUpdater);
    }
}
