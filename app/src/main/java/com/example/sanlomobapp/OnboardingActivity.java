package com.example.sanlomobapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

public class OnboardingActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "sanlo_prefs";
    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    public static final String EXTRA_REPLAY = "replay_tutorial";

    private boolean isReplay = false;
    private ViewPager2 viewPager;
    private LinearLayout dotsContainer;
    private Button btnNext;
    private TextView btnSkip;

    private final int[] slideLayouts = {
            R.layout.onboarding_slide_1,
            R.layout.onboarding_slide_2,
            R.layout.onboarding_slide_3,
            R.layout.onboarding_slide_4
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isReplay = getIntent().getBooleanExtra(EXTRA_REPLAY, false);
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!isReplay && prefs.getBoolean(KEY_ONBOARDING_DONE, false)) {
            goToMain();
            return;
        }

        setContentView(R.layout.activity_onboarding);

        viewPager = findViewById(R.id.onboarding_viewpager);
        dotsContainer = findViewById(R.id.dots_container);
        btnNext = findViewById(R.id.btn_next);
        btnSkip = findViewById(R.id.btn_skip);

        viewPager.setAdapter(new SlidesAdapter());
        setupDots(0);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                setupDots(position);
                if (position == slideLayouts.length - 1) {
                    btnNext.setText(isReplay ? "Close Tutorial" : "Start Exploring");
                } else {
                    btnNext.setText("Next");
                }
            }
        });

        btnNext.setOnClickListener(v -> {
            int current = viewPager.getCurrentItem();
            if (current < slideLayouts.length - 1) {
                viewPager.setCurrentItem(current + 1);
            } else {
                finishOnboarding();
            }
        });

        btnSkip.setOnClickListener(v -> finishOnboarding());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        isReplay = intent.getBooleanExtra(EXTRA_REPLAY, false);
        if (viewPager != null) {
            viewPager.setCurrentItem(0, false);
        }
    }

    private void setupDots(int activeIndex) {
        dotsContainer.removeAllViews();
        int size = (int) (8 * getResources().getDisplayMetrics().density);
        int margin = (int) (5 * getResources().getDisplayMetrics().density);

        for (int i = 0; i < slideLayouts.length; i++) {
            ImageView dot = new ImageView(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(0, 0, margin, 0);
            dot.setLayoutParams(params);
            dot.setImageResource(i == activeIndex ? R.drawable.dot_active : R.drawable.dot_inactive);
            dotsContainer.addView(dot);
        }
    }

    private void finishOnboarding() {
        if (isReplay) {
            finish();
        } else {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply();
            goToMain();
        }
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }

    private class SlidesAdapter extends RecyclerView.Adapter<SlidesAdapter.SlideViewHolder> {

        @NonNull
        @Override
        public SlideViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(slideLayouts[viewType], parent, false);
            return new SlideViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull SlideViewHolder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return slideLayouts.length;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        class SlideViewHolder extends RecyclerView.ViewHolder {
            SlideViewHolder(@NonNull View itemView) {
                super(itemView);
            }
        }
    }
}
