package com.example.sanlomobapp;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {

    private FrameLayout navBgSensors, navBgControls, navBgRgb, navBgLogs;
    private ImageView navIconSensors, navIconControls, navIconRgb, navIconLogs;
    private TextView navLabelSensors, navLabelControls, navLabelRgb, navLabelLogs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        navBgSensors  = findViewById(R.id.nav_bg_sensors);
        navBgControls = findViewById(R.id.nav_bg_controls);
        navBgRgb      = findViewById(R.id.nav_bg_rgb);
        navBgLogs     = findViewById(R.id.nav_bg_logs);

        navIconSensors  = findViewById(R.id.nav_icon_sensors);
        navIconControls = findViewById(R.id.nav_icon_controls);
        navIconRgb      = findViewById(R.id.nav_icon_rgb);
        navIconLogs     = findViewById(R.id.nav_icon_logs);

        navLabelSensors  = findViewById(R.id.nav_label_sensors);
        navLabelControls = findViewById(R.id.nav_label_controls);
        navLabelRgb      = findViewById(R.id.nav_label_rgb);
        navLabelLogs     = findViewById(R.id.nav_label_logs);

        ImageView fabVoiceGuide = findViewById(R.id.fab_voice_guide);

        if (savedInstanceState == null) {
            loadFragment(new DashboardFragment());
            setActiveNav("sensors");
        }

        LinearLayout navItemSensors  = findViewById(R.id.nav_item_sensors);
        LinearLayout navItemControls = findViewById(R.id.nav_item_controls);
        LinearLayout navItemGemini   = findViewById(R.id.nav_item_gemini);
        LinearLayout navItemRgb      = findViewById(R.id.nav_item_rgb);
        LinearLayout navItemLogs     = findViewById(R.id.nav_item_logs);

        navItemSensors.setOnClickListener(v -> {
            loadFragment(new DashboardFragment());
            setActiveNav("sensors");
        });

        navItemControls.setOnClickListener(v -> {
            loadFragment(new ControlsFragment());
            setActiveNav("controls");
        });

        navItemGemini.setOnClickListener(v -> {
            Toast.makeText(this, "Ask Ready? — Gemini AI activated", Toast.LENGTH_SHORT).show();
        });

        navItemRgb.setOnClickListener(v -> {
            loadFragment(new RgbLightFragment());
            setActiveNav("rgb");
        });

        navItemLogs.setOnClickListener(v -> {
            loadFragment(new LogsFragment());
            setActiveNav("logs");
        });
    }

    private void setActiveNav(String active) {
        int activeColor   = getColor(R.color.sap_green);
        int inactiveColor = getColor(R.color.nav_inactive);

        navBgSensors.setBackgroundResource(android.R.color.transparent);
        navBgControls.setBackgroundResource(android.R.color.transparent);
        navBgRgb.setBackgroundResource(android.R.color.transparent);
        navBgLogs.setBackgroundResource(android.R.color.transparent);

        navIconSensors.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        navIconControls.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        navIconRgb.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        navIconLogs.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);

        navLabelSensors.setTextColor(inactiveColor);
        navLabelControls.setTextColor(inactiveColor);
        navLabelRgb.setTextColor(inactiveColor);
        navLabelLogs.setTextColor(inactiveColor);

        switch (active) {
            case "sensors":
                navBgSensors.setBackgroundResource(R.drawable.bg_nav_active_pill);
                navIconSensors.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                navLabelSensors.setTextColor(activeColor);
                break;
            case "controls":
                navBgControls.setBackgroundResource(R.drawable.bg_nav_active_pill);
                navIconControls.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                navLabelControls.setTextColor(activeColor);
                break;
            case "rgb":
                navBgRgb.setBackgroundResource(R.drawable.bg_nav_active_pill);
                navIconRgb.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                navLabelRgb.setTextColor(activeColor);
                break;
            case "logs":
                navBgLogs.setBackgroundResource(R.drawable.bg_nav_active_pill);
                navIconLogs.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                navLabelLogs.setTextColor(activeColor);
                break;
        }
    }

    private void loadFragment(Fragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.fragment_container, fragment);
        ft.commit();
    }
}