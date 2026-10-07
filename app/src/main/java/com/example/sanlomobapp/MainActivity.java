package com.example.sanlomobapp;

import android.graphics.PorterDuff;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {

    private FrameLayout navBgSensors, navBgSimulation, navBgControls, navBgExplore, navBgLogs;
    private ImageView navIconSensors, navIconSimulation, navIconControls, navIconExplore, navIconLogs;
    private TextView navLabelSensors, navLabelSimulation, navLabelControls, navLabelExplore, navLabelLogs;
    private com.google.android.material.card.MaterialCardView bottomNavCard;
    private LinearLayout btnAskSanLoGlobalView;

    private String currentNavTag = "sensors";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        navBgSensors    = findViewById(R.id.nav_bg_sensors);
        navBgSimulation = findViewById(R.id.nav_bg_simulation);
        navBgControls   = findViewById(R.id.nav_bg_controls);
        navBgExplore    = findViewById(R.id.nav_bg_explore);
        navBgLogs       = findViewById(R.id.nav_bg_logs);

        bottomNavCard         = findViewById(R.id.bottom_nav_card);
        btnAskSanLoGlobalView = findViewById(R.id.btn_ask_sanlo_global);

        navIconSensors    = findViewById(R.id.nav_icon_sensors);
        navIconSimulation = findViewById(R.id.nav_icon_simulation);
        navIconControls   = findViewById(R.id.nav_icon_controls);
        navIconExplore    = findViewById(R.id.nav_icon_explore);
        navIconLogs       = findViewById(R.id.nav_icon_logs);

        navLabelSensors    = findViewById(R.id.nav_label_sensors);
        navLabelSimulation = findViewById(R.id.nav_label_simulation);
        navLabelControls   = findViewById(R.id.nav_label_controls);
        navLabelExplore    = findViewById(R.id.nav_label_explore);
        navLabelLogs       = findViewById(R.id.nav_label_logs);

        if (savedInstanceState == null) {
            loadFragment(new DashboardFragment(), "sensors");
        } else {
            currentNavTag = savedInstanceState.getString("KEY_ACTIVE_NAV", "sensors");
            setActiveNav(currentNavTag);
        }

        LinearLayout navItemSensors    = findViewById(R.id.nav_item_sensors);
        LinearLayout navItemSimulation = findViewById(R.id.nav_item_simulation);
        LinearLayout navItemControls   = findViewById(R.id.nav_item_controls);
        LinearLayout navItemExplore    = findViewById(R.id.nav_item_explore);
        LinearLayout navItemLogs       = findViewById(R.id.nav_item_logs);

        if (navItemSensors != null) {
            navItemSensors.setOnClickListener(v -> {
                showNav(true);
                loadFragment(new DashboardFragment(), "sensors");
            });
        }

        if (navItemSimulation != null) {
            navItemSimulation.setOnClickListener(v -> {
                showNav(true);
                loadFragment(new SimulationFragment(), "simulation");
            });
        }

        if (navItemControls != null) {
            navItemControls.setOnClickListener(v -> {
                showNav(true);
                loadFragment(new ControlsFragment(), "controls");
            });
        }

        if (navItemExplore != null) {
            navItemExplore.setOnClickListener(v -> {
                showNav(true);
                loadFragment(new ExploreFragment(), "explore");
            });
        }

        if (navItemLogs != null) {
            navItemLogs.setOnClickListener(v -> {
                showNav(true);
                loadFragment(new LogsFragment(), "logs");
            });
        }

        if (btnAskSanLoGlobalView != null) {
            btnAskSanLoGlobalView.setOnClickListener(v -> {
                showNav(true);
                loadFragment(new GeminiFragment(), "gemini");
            });
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("KEY_ACTIVE_NAV", currentNavTag);
    }

    public void goHome() {
        showNav(true);
        loadFragment(new DashboardFragment(), "sensors");
    }

    public void showNav(boolean visible) {
        int vis = visible ? android.view.View.VISIBLE : android.view.View.GONE;
        if (bottomNavCard != null) bottomNavCard.setVisibility(vis);
        if (btnAskSanLoGlobalView != null) {
            if ("explore".equals(currentNavTag) || "gemini".equals(currentNavTag)) {
                btnAskSanLoGlobalView.setVisibility(android.view.View.GONE);
            } else {
                btnAskSanLoGlobalView.setVisibility(vis);
            }
        }
    }

    public void setActiveNav(String active) {
        currentNavTag = active;
        int activeColor   = getColor(R.color.sap_green);
        int inactiveColor = getColor(R.color.nav_inactive);

        if (navBgSensors != null) navBgSensors.setBackgroundResource(android.R.color.transparent);
        if (navBgSimulation != null) navBgSimulation.setBackgroundResource(android.R.color.transparent);
        if (navBgControls != null) navBgControls.setBackgroundResource(android.R.color.transparent);
        if (navBgExplore != null) navBgExplore.setBackgroundResource(android.R.color.transparent);
        if (navBgLogs != null) navBgLogs.setBackgroundResource(android.R.color.transparent);

        if (navIconSensors != null) navIconSensors.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        if (navIconSimulation != null) navIconSimulation.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        if (navIconControls != null) navIconControls.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        if (navIconExplore != null) navIconExplore.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);
        if (navIconLogs != null) navIconLogs.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN);

        if (navLabelSensors != null) navLabelSensors.setTextColor(inactiveColor);
        if (navLabelSimulation != null) navLabelSimulation.setTextColor(inactiveColor);
        if (navLabelControls != null) navLabelControls.setTextColor(inactiveColor);
        if (navLabelExplore != null) navLabelExplore.setTextColor(inactiveColor);
        if (navLabelLogs != null) navLabelLogs.setTextColor(inactiveColor);

        switch (active) {
            case "sensors":
            case "dashboard":
                if (navBgSensors != null) navBgSensors.setBackgroundResource(R.drawable.bg_nav_active_pill);
                if (navIconSensors != null) navIconSensors.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                if (navLabelSensors != null) navLabelSensors.setTextColor(activeColor);
                break;
            case "simulation":
                if (navBgSimulation != null) navBgSimulation.setBackgroundResource(R.drawable.bg_nav_active_pill);
                if (navIconSimulation != null) navIconSimulation.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                if (navLabelSimulation != null) navLabelSimulation.setTextColor(activeColor);
                break;
            case "controls":
                if (navBgControls != null) navBgControls.setBackgroundResource(R.drawable.bg_nav_active_pill);
                if (navIconControls != null) navIconControls.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                if (navLabelControls != null) navLabelControls.setTextColor(activeColor);
                break;
            case "explore":
            case "gemini":
                if (navBgExplore != null) navBgExplore.setBackgroundResource(R.drawable.bg_nav_active_pill);
                if (navIconExplore != null) navIconExplore.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                if (navLabelExplore != null) navLabelExplore.setTextColor(activeColor);
                break;
            case "logs":
            case "activity":
                if (navBgLogs != null) navBgLogs.setBackgroundResource(R.drawable.bg_nav_active_pill);
                if (navIconLogs != null) navIconLogs.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN);
                if (navLabelLogs != null) navLabelLogs.setTextColor(activeColor);
                break;
        }

        // Hide floating shortcut on Explore (since Explore has prominent entry) and when Guide itself is open
        if (btnAskSanLoGlobalView != null) {
            if ("explore".equals(active) || "gemini".equals(active)) {
                btnAskSanLoGlobalView.setVisibility(android.view.View.GONE);
            } else if (bottomNavCard != null && bottomNavCard.getVisibility() == android.view.View.VISIBLE) {
                btnAskSanLoGlobalView.setVisibility(android.view.View.VISIBLE);
            }
        }
    }

    public void loadFragment(Fragment fragment, String tag) {
        if (tag != null && tag.equals(currentNavTag) && getSupportFragmentManager().findFragmentById(R.id.fragment_container) != null) {
            // Do not reload fragment if user taps the same tab they are already viewing
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.setCustomAnimations(R.anim.fade_slide_in, R.anim.fade_slide_out);
        ft.replace(R.id.fragment_container, fragment);
        ft.commit();
        setActiveNav(tag);
    }

    public void loadFragment(Fragment fragment) {
        String tag = "sensors";
        if (fragment instanceof DashboardFragment) tag = "sensors";
        else if (fragment instanceof SimulationFragment) tag = "simulation";
        else if (fragment instanceof ControlsFragment) tag = "controls";
        else if (fragment instanceof ExploreFragment) tag = "explore";
        else if (fragment instanceof GeminiFragment) tag = "gemini";
        else if (fragment instanceof LogsFragment) tag = "logs";
        loadFragment(fragment, tag);
    }
}