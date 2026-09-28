package com.example.sanlomobapp;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class LogsFragment extends Fragment {

    private LogsAdapter adapter;
    private final List<LogEntry> logEntries = new ArrayList<>();

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
        Button btnClearLogs = view.findViewById(R.id.btn_clear_logs);

        populateSampleLogs();

        adapter = new LogsAdapter(requireContext(), logEntries);
        recyclerLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerLogs.setAdapter(adapter);

        btnClearLogs.setOnClickListener(v -> adapter.clearAll());
    }

    private void populateSampleLogs() {
        logEntries.add(new LogEntry("00:13:02", "PIR Motion", "Motion detected — Zone A (Entrance)", LogEntry.TYPE_PIR));
        logEntries.add(new LogEntry("00:13:05", "Auto Action", "Tram departure triggered automatically", LogEntry.TYPE_ACTION));
        logEntries.add(new LogEntry("00:12:47", "PIR Motion", "Motion detected — Zone B (River Bridge)", LogEntry.TYPE_PIR));
        logEntries.add(new LogEntry("00:12:31", "PIR Motion", "Motion detected — Zone C (Forest Path)", LogEntry.TYPE_PIR));
        logEntries.add(new LogEntry("00:12:15", "Auto Action", "Ambient lights activated (dusk trigger)", LogEntry.TYPE_ACTION));
        logEntries.add(new LogEntry("00:11:58", "Alert", "Water level rising — threshold 2.8 m exceeded", LogEntry.TYPE_ALERT));
        logEntries.add(new LogEntry("00:11:42", "Auto Action", "Sound ambience loop started", LogEntry.TYPE_ACTION));
        logEntries.add(new LogEntry("00:11:20", "PIR Motion", "Motion detected — Zone A (Entrance)", LogEntry.TYPE_PIR));
        logEntries.add(new LogEntry("00:10:55", "Auto Action", "Tram returned to depot", LogEntry.TYPE_ACTION));
        logEntries.add(new LogEntry("00:10:30", "Alert", "Temperature spike — 38.2°C detected", LogEntry.TYPE_ALERT));
    }
}
