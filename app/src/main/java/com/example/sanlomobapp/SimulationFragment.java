package com.example.sanlomobapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.sanlomobapp.data.FirebaseRepository;
import com.example.sanlomobapp.model.DioramaReportedState;
import com.example.sanlomobapp.model.SimulationState;
import com.example.sanlomobapp.model.SystemStatus;
import com.example.sanlomobapp.ui.OperationStatus;

import java.util.Map;

/**
 * Simulation (main feature).
 *
 * Status shown per demonstration:
 *  - Offline   : no fresh MASTER heartbeat
 *  - Requested : app wrote the request; MASTER has not acknowledged yet
 *  - Accepted / Running / Completed / Failed : from MASTER-written request status
 *    or MASTER-reported simulation state. Never inferred from a Firebase write.
 */
public class SimulationFragment extends Fragment implements
        FirebaseRepository.ReportedStateListener,
        FirebaseRepository.SystemStatusListener,
        FirebaseRepository.RequestStatusListener {

    private static final String REQ_PATH = "simulation";

    private TextView tabIndividual;
    private TextView tabFull;
    private LinearLayout layoutIndividual;
    private LinearLayout layoutFull;

    private TextView tvSyncBadge;
    private TextView tvActiveSimIndicator;
    private TextView tvSimActiveTitle;
    private TextView tvSimActiveStep;
    private Button btnStopSimulation;
    private Button btnRunFullSystem;

    // index 0 = full system, 1..5 = individual ops (matches SimulationState.OP_*)
    private final TextView[] statusChips = new TextView[6];
    private final Button[] startButtons = new Button[6];

    private boolean masterOnline = false;
    private DioramaReportedState lastReported;
    private Map<String, Object> lastRequest;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeoutTick = this::renderStatuses;

    private final FirebaseRepository repository = FirebaseRepository.getInstance();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_simulation, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tabIndividual    = view.findViewById(R.id.tab_sim_individual);
        tabFull          = view.findViewById(R.id.tab_sim_full);
        layoutIndividual = view.findViewById(R.id.layout_individual_sim);
        layoutFull       = view.findViewById(R.id.layout_full_sim);

        tvSyncBadge          = view.findViewById(R.id.tv_sim_sync_badge);
        tvActiveSimIndicator = view.findViewById(R.id.tv_active_sim_indicator);
        tvSimActiveTitle     = view.findViewById(R.id.tv_sim_active_title);
        tvSimActiveStep      = view.findViewById(R.id.tv_sim_active_step);
        btnStopSimulation    = view.findViewById(R.id.btn_stop_simulation);
        btnRunFullSystem     = view.findViewById(R.id.btn_run_full_system);

        statusChips[0] = view.findViewById(R.id.tv_status_full);
        statusChips[SimulationState.OP_TRAM]          = view.findViewById(R.id.tv_status_op_tram);
        statusChips[SimulationState.OP_STREET_LIGHTS] = view.findViewById(R.id.tv_status_op_lights);
        statusChips[SimulationState.OP_RGB]           = view.findViewById(R.id.tv_status_op_rgb);
        statusChips[SimulationState.OP_WIND]          = view.findViewById(R.id.tv_status_op_wind);
        statusChips[SimulationState.OP_DRAINAGE]      = view.findViewById(R.id.tv_status_op_drainage);

        startButtons[0] = btnRunFullSystem;
        startButtons[SimulationState.OP_TRAM]          = view.findViewById(R.id.btn_run_op_tram);
        startButtons[SimulationState.OP_STREET_LIGHTS] = view.findViewById(R.id.btn_run_op_lights);
        startButtons[SimulationState.OP_RGB]           = view.findViewById(R.id.btn_run_op_rgb);
        startButtons[SimulationState.OP_WIND]          = view.findViewById(R.id.btn_run_op_wind);
        startButtons[SimulationState.OP_DRAINAGE]      = view.findViewById(R.id.btn_run_op_drainage);

        tabIndividual.setOnClickListener(v -> selectTab(true));
        tabFull.setOnClickListener(v -> selectTab(false));

        for (int op = 1; op <= 5; op++) {
            final int code = op;
            startButtons[op].setOnClickListener(v ->
                    sendRequest(SimulationState.MODE_INDIVIDUAL, code, "START"));
        }
        btnRunFullSystem.setOnClickListener(v -> sendRequest(SimulationState.MODE_FULL, 0, "START"));
        btnStopSimulation.setOnClickListener(v -> sendRequest(SimulationState.MODE_IDLE, 0, "STOP"));

        repository.addSystemStatusListener(this);
        repository.addReportedStateListener(this);
        repository.watchRequest(REQ_PATH, this);
    }

    private void selectTab(boolean individual) {
        TextView on = individual ? tabIndividual : tabFull;
        TextView off = individual ? tabFull : tabIndividual;
        on.setBackgroundResource(R.drawable.bg_tram_status_card);
        on.setTextColor(requireContext().getColor(R.color.white));
        off.setBackgroundResource(android.R.color.transparent);
        off.setTextColor(requireContext().getColor(R.color.text_muted));
        layoutIndividual.setVisibility(individual ? View.VISIBLE : View.GONE);
        layoutFull.setVisibility(individual ? View.GONE : View.VISIBLE);
    }

    private void sendRequest(String mode, int op, String action) {
        if (!masterOnline) {
            Toast.makeText(requireContext(),
                    "The diorama is offline. Connect it first from the Overview screen.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        repository.requestSimulation(mode, op, action, (error, ref) -> {
            if (!isAdded()) return;
            if (error != null) {
                Toast.makeText(requireContext(),
                        "Could not send the request. Check your internet connection.",
                        Toast.LENGTH_SHORT).show();
            }
            // On success the request watcher will show "Requested" until MASTER responds.
        });
    }

    // ---------------------------------------------------------------
    // Status derivation
    // ---------------------------------------------------------------

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** Which card (0 = full, 1..5) the latest request targets, or -1. */
    private int requestTarget() {
        if (lastRequest == null) return -1;
        Object action = lastRequest.get("action");
        if (!"START".equals(action)) return -1;
        Object mode = lastRequest.get("target_mode");
        if (SimulationState.MODE_FULL.equals(mode)) return 0;
        if (SimulationState.MODE_INDIVIDUAL.equals(mode)) {
            int op = (int) asLong(lastRequest.get("individual_op"));
            return (op >= 1 && op <= 5) ? op : -1;
        }
        return -1;
    }

    /** Which card MASTER reports as currently running, or -1. */
    private int reportedRunningTarget() {
        if (lastReported == null) return -1;
        SimulationState sim = lastReported.getSimulation();
        if (sim == null || !sim.isRunning()) return -1;
        if (SimulationState.MODE_FULL.equals(sim.getMode())) return 0;
        int op = sim.getIndividual_op();
        return (op >= 1 && op <= 5) ? op : -1;
    }

    private OperationStatus statusFor(int card) {
        if (!masterOnline) return OperationStatus.OFFLINE;
        if (reportedRunningTarget() == card) return OperationStatus.RUNNING;
        if (requestTarget() == card) {
            Object status = lastRequest.get("status");
            return OperationStatus.fromRequestStatus(
                    status != null ? status.toString() : null,
                    asLong(lastRequest.get("timestamp")));
        }
        return OperationStatus.READY;
    }

    private void renderStatuses() {
        if (!isAdded()) return;
        handler.removeCallbacks(timeoutTick);
        boolean anyPending = false;
        int running = reportedRunningTarget();

        for (int card = 0; card <= 5; card++) {
            OperationStatus s = statusFor(card);
            s.applyTo(statusChips[card]);
            boolean busy = s == OperationStatus.REQUESTED
                    || s == OperationStatus.ACCEPTED
                    || s == OperationStatus.RUNNING;
            if (startButtons[card] != null) {
                startButtons[card].setEnabled(masterOnline && !busy && running < 0);
                startButtons[card].setAlpha(startButtons[card].isEnabled() ? 1f : 0.45f);
            }
            if (s == OperationStatus.REQUESTED) anyPending = true;
        }
        // Re-check so an unacknowledged request flips to Failed after the timeout.
        if (anyPending) handler.postDelayed(timeoutTick, 1000);

        renderNowPlaying(running);
    }

    private void renderNowPlaying(int running) {
        if (!masterOnline) {
            tvActiveSimIndicator.setText("OFFLINE");
            tvActiveSimIndicator.setTextColor(requireContext().getColor(R.color.offline_gray));
            tvSimActiveTitle.setText("Diorama not connected");
            tvSimActiveStep.setText("Demonstrations become available when the diorama is online.");
            btnStopSimulation.setVisibility(View.GONE);
            return;
        }
        if (running >= 0) {
            SimulationState sim = lastReported.getSimulation();
            tvActiveSimIndicator.setText("RUNNING");
            tvActiveSimIndicator.setTextColor(requireContext().getColor(R.color.teal_pump));
            tvSimActiveTitle.setText(titleFor(running));
            String step = sim.getStep_description();
            tvSimActiveStep.setText(step.isEmpty() ? "Reported by the diorama" : step);
            btnStopSimulation.setVisibility(View.VISIBLE);
        } else {
            tvActiveSimIndicator.setText("READY");
            tvActiveSimIndicator.setTextColor(requireContext().getColor(R.color.sap_green));
            tvSimActiveTitle.setText("Nothing running");
            tvSimActiveStep.setText("Choose a demonstration below to start it on the diorama.");
            btnStopSimulation.setVisibility(View.GONE);
        }
    }

    private static String titleFor(int card) {
        switch (card) {
            case 0: return "Full System";
            case SimulationState.OP_TRAM: return "Tram Ride";
            case SimulationState.OP_STREET_LIGHTS: return "Day to Night";
            case SimulationState.OP_RGB: return "River RGB Lights";
            case SimulationState.OP_WIND: return "Wind and Breeze";
            case SimulationState.OP_DRAINAGE: return "Flood Warning & Drainage";
            default: return "Demonstration";
        }
    }

    // ---------------------------------------------------------------
    // Listeners
    // ---------------------------------------------------------------

    @Override
    public void onSystemStatusUpdated(SystemStatus status, boolean isOnline) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            masterOnline = isOnline;
            if (isOnline) {
                tvSyncBadge.setText("Diorama Online");
                tvSyncBadge.setBackgroundResource(R.drawable.bg_status_chip_green);
                tvSyncBadge.setTextColor(requireContext().getColor(R.color.sap_green));
            } else {
                tvSyncBadge.setText("Offline");
                tvSyncBadge.setBackgroundResource(R.drawable.bg_status_chip_coral);
                tvSyncBadge.setTextColor(requireContext().getColor(R.color.alert_red));
            }
            renderStatuses();
        });
    }

    @Override
    public void onReportedStateUpdated(DioramaReportedState reportedState) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            lastReported = reportedState;
            renderStatuses();
        });
    }

    @Override
    public void onRequestStatus(String path, @Nullable Map<String, Object> request) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            lastRequest = request;
            renderStatuses();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(timeoutTick);
        repository.removeReportedStateListener(this);
        repository.removeSystemStatusListener(this);
        repository.unwatchRequests(this);
    }
}
