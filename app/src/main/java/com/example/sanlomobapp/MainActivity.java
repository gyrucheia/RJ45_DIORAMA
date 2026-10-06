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

    private FrameLayout navBgSensors, navBgControls, navBgRgb, navBgLogs, navBgGemini;
    private ImageView navIconSensors, navIconControls, navIconRgb, navIconLogs, navIconGemini;
    private TextView navLabelSensors, navLabelControls, navLabelRgb, navLabelLogs, navLabelGemini;
    private com.google.android.material.card.MaterialCardView bottomNavCard;
    private LinearLayout btnAskSanLoGlobalView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        navBgSensors  = findViewById(R.id.nav_bg_sensors);
        navBgControls = findViewById(R.id.nav_bg_controls);
        navBgRgb      = findViewById(R.id.nav_bg_rgb);
        navBgLogs     = findViewById(R.id.nav_bg_logs);
        navBgGemini   = findViewById(R.id.nav_bg_gemini);

        bottomNavCard       = findViewById(R.id.bottom_nav_card);
        btnAskSanLoGlobalView = findViewById(R.id.btn_ask_sanlo_global);

        navIconSensors  = findViewById(R.id.nav_icon_sensors);
        navIconControls = findViewById(R.id.nav_icon_controls);
        navIconRgb      = findViewById(R.id.nav_icon_rgb);
        navIconLogs     = findViewById(R.id.nav_icon_logs);
        navIconGemini   = findViewById(R.id.fab_voice_guide);

        navLabelSensors  = findViewById(R.id.nav_label_sensors);
        navLabelControls = findViewById(R.id.nav_label_controls);
        navLabelRgb      = findViewById(R.id.nav_label_rgb);
        navLabelLogs     = findViewById(R.id.nav_label_logs);
        navLabelGemini   = findViewById(R.id.nav_label_gemini);

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
            showNav(true);
            loadFragment(new DashboardFragment());
            setActiveNav("sensors");
        });

        navItemControls.setOnClickListener(v -> {
            showNav(true);
            loadFragment(new ControlsFragment());
            setActiveNav("controls");
        });

        navItemGemini.setOnClickListener(v -> {
            showNav(true);
            loadFragment(new ExploreFragment());
            setActiveNav("gemini");
        });

        navItemRgb.setOnClickListener(v -> {
            showNav(true);
            loadFragment(new RgbLightFragment());
            setActiveNav("rgb");
        });

        navItemLogs.setOnClickListener(v -> {
            showNav(true);
            loadFragment(new LogsFragment());
            setActiveNav("logs");
        });

        LinearLayout cardExploreControls = findViewById(R.id.card_explore_controls);
        LinearLayout cardExploreSensors  = findViewById(R.id.card_explore_sensors);
        LinearLayout cardExploreLights   = findViewById(R.id.card_explore_lights);
        LinearLayout cardExploreAi       = findViewById(R.id.card_explore_ai);
        LinearLayout cardTramStatus      = findViewById(R.id.card_tram_status);
        LinearLayout btnAskSanLo         = findViewById(R.id.btn_ask_sanlo);

        if (cardExploreControls != null) cardExploreControls.setOnClickListener(v -> {
            loadFragment(new ControlsFragment());
            setActiveNav("controls");
        });
        if (cardExploreSensors != null) cardExploreSensors.setOnClickListener(v ->
            Toast.makeText(this, "Explore Sensors — coming soon", Toast.LENGTH_SHORT).show());
        if (cardExploreLights != null) cardExploreLights.setOnClickListener(v -> {
            loadFragment(new RgbLightFragment());
            setActiveNav("rgb");
        });
        if (cardExploreAi != null) cardExploreAi.setOnClickListener(v -> openGuide());
        if (cardTramStatus != null) cardTramStatus.setOnClickListener(v -> {
            showNav(true);
            loadFragment(new ControlsFragment());
            setActiveNav("controls");
        });
        if (btnAskSanLo != null) btnAskSanLo.setOnClickListener(v -> openGuide());

        LinearLayout btnAskSanLoGlobal = btnAskSanLoGlobalView;
        if (btnAskSanLoGlobal != null) btnAskSanLoGlobal.setOnClickListener(v -> openGuide());
    }

    public void openGuide() {
        showNav(false);
        loadFragment(new GeminiFragment());
        setActiveNav("");
    }

    public void goHome() {
        showNav(true);
        loadFragment(new DashboardFragment());
        setActiveNav("sensors");
    }

    public void showNav(boolean visible) {
        int vis = visible ? android.view.View.VISIBLE : android.view.View.GONE;
        if (bottomNavCard != null)       bottomNavCard.setVisibility(vis);
        if (btnAskSanLoGlobalView != null) btnAskSanLoGlobalView.setVisibility(
                visible ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    public void setActiveNav(String active) {
        int activeColor   = getColor(R.color.sap_green);
        int inactiveColor = getColor(R.color.nav_inactive);

        navBgSensors.setBackgroundResource(android.R.color.transparent);
        navBgControls.setBackgroundResource(android.R.color.transparent);
        navBgRgb.setBackgroundResource(android.R.color.transparent);
        navBgLogs.setBackgroundResource(android.R.color.transparent);
        if (navBgGemini != null) navBgGemini.setBackgroundResource(android.R.color.transparent);

        navIconSensors.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        navIconControls.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        navIconRgb.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        navIconLogs.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        if (navIconGemini != null) navIconGemini.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);

        navLabelSensors.setTextColor(inactiveColor);
        navLabelControls.setTextColor(inactiveColor);
        navLabelRgb.setTextColor(inactiveColor);
        navLabelLogs.setTextColor(inactiveColor);
        if (navLabelGemini != null) navLabelGemini.setTextColor(inactiveColor);

        switch (active) {
            case "":
                break;
            case "gemini":
                navBgGemini.setBackgroundResource(R.drawable.bg_nav_active_pill);
                navIconGemini.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                navLabelGemini.setTextColor(activeColor);
                break;
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

    public void loadFragment(Fragment fragment) {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.fragment_container, fragment);
        ft.commit();
    }
}