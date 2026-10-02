package com.example.sanlomobapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LogsFragment extends Fragment {

    private LogsAdapter adapter;
    private final List<LogEntry> allEntries  = new ArrayList<>();
    private final List<LogEntry> shown       = new ArrayList<>();

    private TextView chipAll, chipTram, chipEnv, chipVisitors, chipGestures, chipAlerts;
    private String activeFilter = "all";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView recyclerLogs = view.findViewById(R.id.recycler_logs);

        chipAll      = view.findViewById(R.id.chip_all);
        chipTram     = view.findViewById(R.id.chip_tram);
        chipEnv      = view.findViewById(R.id.chip_environment);
        chipVisitors = view.findViewById(R.id.chip_visitors);
        chipGestures = view.findViewById(R.id.chip_gestures);
        chipAlerts   = view.findViewById(R.id.chip_alerts);

        populateSampleLogs();
        shown.addAll(allEntries);

        adapter = new LogsAdapter(requireContext(), shown);
        recyclerLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerLogs.setAdapter(adapter);

        setChipListeners();
        updateChipStyles();
    }

    private void setChipListeners() {
        if (chipAll      != null) chipAll.setOnClickListener(v      -> applyFilter("all"));
        if (chipTram     != null) chipTram.setOnClickListener(v     -> applyFilter("tram"));
        if (chipEnv      != null) chipEnv.setOnClickListener(v      -> applyFilter("environment"));
        if (chipVisitors != null) chipVisitors.setOnClickListener(v -> applyFilter("visitors"));
        if (chipGestures != null) chipGestures.setOnClickListener(v -> applyFilter("gestures"));
        if (chipAlerts   != null) chipAlerts.setOnClickListener(v   -> applyFilter("alerts"));
    }

    private void applyFilter(String filter) {
        activeFilter = filter;
        shown.clear();
        for (LogEntry e : allEntries) {
            if (filter.equals("all")) {
                shown.add(e);
            } else if (filter.equals("tram") && e.getType() == LogEntry.TYPE_ACTION) {
                shown.add(e);
            } else if (filter.equals("visitors") && e.getType() == LogEntry.TYPE_PIR) {
                shown.add(e);
            } else if (filter.equals("alerts") && e.getType() == LogEntry.TYPE_ALERT) {
                shown.add(e);
            } else if (filter.equals("environment") && e.getType() == LogEntry.TYPE_ALERT) {
                shown.add(e);
            } else if (filter.equals("gestures") && e.getType() == LogEntry.TYPE_ACTION) {
                shown.add(e);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateChipStyles();
    }

    private void updateChipStyles() {
        resetChip(chipAll);
        resetChip(chipTram);
        resetChip(chipEnv);
        resetChip(chipVisitors);
        resetChip(chipGestures);
        resetChip(chipAlerts);

        TextView active;
        if ("tram".equals(activeFilter))             active = chipTram;
        else if ("environment".equals(activeFilter)) active = chipEnv;
        else if ("visitors".equals(activeFilter))    active = chipVisitors;
        else if ("gestures".equals(activeFilter))    active = chipGestures;
        else if ("alerts".equals(activeFilter))      active = chipAlerts;
        else                                         active = chipAll;

        if (active != null) {
            active.setBackgroundResource(R.drawable.bg_tram_status_card);
            active.setTextColor(requireContext().getColor(R.color.white));
        }
    }

    private void resetChip(TextView chip) {
        if (chip == null) return;
        chip.setBackgroundResource(R.drawable.bg_tram_return_btn);
        chip.setTextColor(requireContext().getColor(R.color.text_dark));
    }

    private void populateSampleLogs() {
        allEntries.add(new LogEntry("11:17 PM", "Tram started",                    "Gesture · RIGHT",     LogEntry.TYPE_ACTION));
        allEntries.add(new LogEntry("11:14 PM", "Visitor detected",                "PIR Sensor",          LogEntry.TYPE_PIR));
        allEntries.add(new LogEntry("11:12 PM", "River level returned to normal",  "1.2 m",               LogEntry.TYPE_ALERT));
        allEntries.add(new LogEntry("10:48 PM", "Forest lighting applied",         "Brightness · 72%",    LogEntry.TYPE_ACTION));
        allEntries.add(new LogEntry("9:32 PM",  "Pump toggled",                    "Gesture · DOWN",      LogEntry.TYPE_PIR));
        allEntries.add(new LogEntry("9:10 PM",  "Wind effect activated",           "Speed · 45%",         LogEntry.TYPE_ACTION));
        allEntries.add(new LogEntry("8:55 PM",  "Water level elevated",            "2.1 m — watch zone",  LogEntry.TYPE_ALERT));
        allEntries.add(new LogEntry("8:30 PM",  "Visitor detected",                "PIR Sensor Zone B",   LogEntry.TYPE_PIR));
    }
}
