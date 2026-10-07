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

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogsFragment extends Fragment {

    private LogsAdapter adapter;
    private final List<LogEntry> allEntries = new ArrayList<>();
    private final List<LogEntry> shown      = new ArrayList<>();

    private TextView chipAll, chipSimulation, chipTram, chipEnv, chipSystem, chipErrors;
    private String activeFilter = "all";

    private DatabaseReference logsRef;
    private ValueEventListener logsListener;

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

        chipAll        = view.findViewById(R.id.chip_all);
        chipSimulation = view.findViewById(R.id.chip_simulation);
        chipTram       = view.findViewById(R.id.chip_tram);
        chipEnv        = view.findViewById(R.id.chip_environment);
        chipSystem     = view.findViewById(R.id.chip_system);
        chipErrors     = view.findViewById(R.id.chip_errors);

        adapter = new LogsAdapter(requireContext(), shown);
        recyclerLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerLogs.setAdapter(adapter);

        setChipListeners();
        updateChipStyles();

        connectFirebaseLogs();
    }

    private void connectFirebaseLogs() {
        logsRef = FirebaseDatabase.getInstance(
                "https://sanlo-139ce-default-rtdb.asia-southeast1.firebasedatabase.app"
        ).getReference("logs");

        logsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!isAdded()) return;
                List<LogEntry> entries = new ArrayList<>();
                SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a", Locale.US);

                for (DataSnapshot child : snapshot.getChildren()) {
                    String title = child.child("title").getValue(String.class);
                    String detail = child.child("detail").getValue(String.class);
                    String typeStr = child.child("type").getValue(String.class);
                    Long ts = child.child("timestamp").getValue(Long.class);

                    String timeStr = ts != null ? sdf.format(new Date(ts)) : "--:--";
                    int type = LogEntry.TYPE_ACTION;
                    if ("PIR".equalsIgnoreCase(typeStr) || "VISITOR".equalsIgnoreCase(typeStr)) {
                        type = LogEntry.TYPE_PIR;
                    } else if ("ALERT".equalsIgnoreCase(typeStr)) {
                        type = LogEntry.TYPE_ALERT;
                    }

                    if (title != null) {
                        entries.add(0, new LogEntry(timeStr, title, detail != null ? detail : "", type));
                    }
                }

                allEntries.clear();
                allEntries.addAll(entries);
                applyFilter(activeFilter);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // If permission denied or empty, keep existing shown
            }
        };

        logsRef.limitToLast(50).addValueEventListener(logsListener);
    }

    private void setChipListeners() {
        if (chipAll        != null) chipAll.setOnClickListener(v        -> applyFilter("all"));
        if (chipSimulation != null) chipSimulation.setOnClickListener(v -> applyFilter("simulation"));
        if (chipTram       != null) chipTram.setOnClickListener(v       -> applyFilter("tram"));
        if (chipEnv        != null) chipEnv.setOnClickListener(v        -> applyFilter("environment"));
        if (chipSystem     != null) chipSystem.setOnClickListener(v     -> applyFilter("system"));
        if (chipErrors     != null) chipErrors.setOnClickListener(v     -> applyFilter("errors"));
    }

    private void applyFilter(String filter) {
        activeFilter = filter;
        shown.clear();
        for (LogEntry e : allEntries) {
            String title = e.getEventType() != null ? e.getEventType().toLowerCase(Locale.ROOT) : "";
            String detail = e.getDescription() != null ? e.getDescription().toLowerCase(Locale.ROOT) : "";
            int type = e.getType();

            if ("all".equals(filter)) {
                shown.add(e);
            } else if ("simulation".equals(filter)) {
                if (title.contains("sim") || title.contains("operation") || title.contains("run") || detail.contains("sim")) {
                    shown.add(e);
                }
            } else if ("tram".equals(filter)) {
                if (title.contains("tram") || title.contains("station") || title.contains("dock") || title.contains("gesture") ||
                    (type == LogEntry.TYPE_ACTION && !title.contains("system") && !title.contains("wifi"))) {
                    shown.add(e);
                }
            } else if ("environment".equals(filter)) {
                if (title.contains("temp") || title.contains("water") || title.contains("river") || title.contains("wind") ||
                    title.contains("flex") || title.contains("sensor") || title.contains("motion") || type == LogEntry.TYPE_PIR) {
                    shown.add(e);
                }
            } else if ("system".equals(filter)) {
                if (title.contains("system") || title.contains("wifi") || title.contains("ble") || title.contains("online") ||
                    title.contains("connected") || title.contains("status")) {
                    shown.add(e);
                }
            } else if ("errors".equals(filter)) {
                if (type == LogEntry.TYPE_ALERT || title.contains("error") || title.contains("fail") || title.contains("timeout") ||
                    title.contains("alert") || detail.contains("error")) {
                    shown.add(e);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateChipStyles();
    }

    private void updateChipStyles() {
        resetChip(chipAll);
        resetChip(chipSimulation);
        resetChip(chipTram);
        resetChip(chipEnv);
        resetChip(chipSystem);
        resetChip(chipErrors);

        TextView active;
        if ("simulation".equals(activeFilter))       active = chipSimulation;
        else if ("tram".equals(activeFilter))        active = chipTram;
        else if ("environment".equals(activeFilter)) active = chipEnv;
        else if ("system".equals(activeFilter))      active = chipSystem;
        else if ("errors".equals(activeFilter))      active = chipErrors;
        else                                         active = chipAll;

        if (active != null && isAdded()) {
            active.setBackgroundResource(R.drawable.bg_tram_status_card);
            active.setTextColor(requireContext().getColor(R.color.white));
        }
    }

    private void resetChip(TextView chip) {
        if (chip == null || !isAdded()) return;
        chip.setBackgroundResource(R.drawable.bg_tram_return_btn);
        chip.setTextColor(requireContext().getColor(R.color.text_dark));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (logsRef != null && logsListener != null) {
            logsRef.removeEventListener(logsListener);
        }
    }
}
