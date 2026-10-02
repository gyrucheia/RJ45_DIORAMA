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

import java.util.Locale;
import java.util.Random;

public class DashboardFragment extends Fragment {

    private TextView tvTempValue;
    private TextView tvHumidityValue;
    private TextView tvWaterValue;
    private TextView tvVisitorsValue;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private double temperature = 29.0;
    private int humidity = 66;
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

        tvTempValue     = view.findViewById(R.id.tv_temp_value);
        tvHumidityValue = view.findViewById(R.id.tv_humidity_value);
        tvWaterValue    = view.findViewById(R.id.tv_water_value);
        tvVisitorsValue = view.findViewById(R.id.tv_visitors_value);

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
        if (tvTempValue != null)
            tvTempValue.setText(String.format(Locale.US, "%.0f°", temperature));
        if (tvHumidityValue != null)
            tvHumidityValue.setText(String.format(Locale.US, "%d%%", humidity));
        if (tvWaterValue != null)
            tvWaterValue.setText(String.format(Locale.US, "%.1f m", waterLevel));
        if (tvVisitorsValue != null)
            tvVisitorsValue.setText(String.valueOf(visitors));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(sensorUpdater);
    }
}
