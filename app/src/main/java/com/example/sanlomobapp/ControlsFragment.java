package com.example.sanlomobapp;

import android.content.res.ColorStateList;
import android.graphics.Color;
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
import com.example.sanlomobapp.model.SystemStatus;
import com.example.sanlomobapp.ui.OperationStatus;
import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Controls — optional manual overrides, kept separate from Simulation.
 *
 * Every card shows a status chip:
 *  Offline  -> no fresh MASTER heartbeat
 *  Ready    -> online, no outstanding request
 *  Requested-> app wrote the request, MASTER has not acknowledged
 *  Accepted / Running / Completed / Failed -> MASTER-written request status
 *
 * Request payloads and Firebase paths are unchanged from the existing repository.
 */
public class ControlsFragment extends Fragment implements
        FirebaseRepository.ReportedStateListener,
        FirebaseRepository.SystemStatusListener,
        FirebaseRepository.RequestStatusListener {

    // Card keys -> existing /requests/{path}
    private static final String[][] CARDS = {
            {"tram", "tram"},
            {"lights", "street_lights"},
            {"rgb", "rgb"},
            {"wind", "actuators/fan"},
            {"drainage", "actuators/pump"},
            {"audio", "voice_guide"},
    };

    // Swatches are app presets the operator may request; MASTER confirms the real color.
    private static final String[] SWATCH_HEX  = {"#FF0000", "#FFA500", "#FFFF00", "#00FF00", "#0066FF", "#800080", "#FFFFFF"};
    private static final String[] SWATCH_NAME = {"Red", "Orange", "Yellow", "Green", "Blue", "Purple", "White"};

    private final Map<String, TextView> chips = new HashMap<>();
    private final Map<String, Map<String, Object>> lastRequests = new HashMap<>();
    private final Map<String, View> bodies = new HashMap<>();
    private final Map<String, TextView> chevrons = new HashMap<>();

    private TextView tvOnline, tvOfflineNote;
    private TextView tvSumTram, tvSumLights, tvSumRgb, tvSumWind, tvSumDrainage, tvSumAudio;
    private View rootContent;

    private boolean masterOnline = false;
    private DioramaReportedState reported;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::renderChips;
    private final FirebaseRepository repository = FirebaseRepository.getInstance();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_controls, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        rootContent = view;

        tvOnline      = view.findViewById(R.id.tv_controls_online);
        tvOfflineNote = view.findViewById(R.id.tv_controls_offline_note);
        tvSumTram     = view.findViewById(R.id.tv_summary_tram);
        tvSumLights   = view.findViewById(R.id.tv_summary_lights);
        tvSumRgb      = view.findViewById(R.id.tv_summary_rgb);
        tvSumWind     = view.findViewById(R.id.tv_summary_wind);
        tvSumDrainage = view.findViewById(R.id.tv_summary_drainage);
        tvSumAudio    = view.findViewById(R.id.tv_summary_audio);

        bindCard(view, "tram", R.id.header_tram, R.id.body_tram, R.id.chevron_tram, R.id.chip_tram);
        bindCard(view, "lights", R.id.header_lights, R.id.body_lights, R.id.chevron_lights, R.id.chip_lights);
        bindCard(view, "rgb", R.id.header_rgb, R.id.body_rgb, R.id.chevron_rgb, R.id.chip_rgb);
        bindCard(view, "wind", R.id.header_wind, R.id.body_wind, R.id.chevron_wind, R.id.chip_wind);
        bindCard(view, "drainage", R.id.header_drainage, R.id.body_drainage, R.id.chevron_drainage, R.id.chip_drainage);
        bindCard(view, "audio", R.id.header_audio, R.id.body_audio, R.id.chevron_audio, R.id.chip_audio);

        // Tram
        click(view, R.id.btn_tram_forward, () -> repository.requestTramAction("FORWARD", done()));
        click(view, R.id.btn_tram_reverse, () -> repository.requestTramAction("REVERSE", done()));
        click(view, R.id.btn_tram_stop,    () -> repository.requestTramAction("STOP", done()));

        // Street lights (existing demo-clock request values)
        click(view, R.id.btn_time_day,     () -> repository.requestStreetLightsDemo(10, 0, done()));
        click(view, R.id.btn_time_evening, () -> repository.requestStreetLightsDemo(17, 30, done()));
        click(view, R.id.btn_time_night,   () -> repository.requestStreetLightsDemo(20, 0, done()));
        click(view, R.id.btn_time_real,    () -> repository.requestStreetLightsResetRtc(done()));

        // RGB
        click(view, R.id.btn_rgb_sensor, () -> repository.requestRgbColor("SENSOR", "#47AFC3", "Auto", done()));
        buildSwatches(view.findViewById(R.id.row_rgb_swatches));

        // Wind (fan) and drainage (pump)
        click(view, R.id.btn_fan_on,   () -> repository.requestActuator("fan", true, done()));
        click(view, R.id.btn_fan_off,  () -> repository.requestActuator("fan", false, done()));
        click(view, R.id.btn_pump_on,  () -> repository.requestActuator("pump", true, done()));
        click(view, R.id.btn_pump_off, () -> repository.requestActuator("pump", false, done()));

        // Narration
        click(view, R.id.btn_play_narration, () -> repository.requestVoiceNarration(1, done()));

        // Stop tram, fan and pump (same requests as the previous emergency stop)
        click(view, R.id.btn_emergency_stop, () -> {
            repository.requestTramAction("STOP", done());
            repository.requestActuator("pump", false, null);
            repository.requestActuator("fan", false, null);
        });

        repository.addSystemStatusListener(this);
        repository.addReportedStateListener(this);
        for (String[] c : CARDS) repository.watchRequest(c[1], this);
    }

    // ---------------------------------------------------------------
    // UI helpers
    // ---------------------------------------------------------------

    private void bindCard(View root, String key, int headerId, int bodyId, int chevronId, int chipId) {
        View header = root.findViewById(headerId);
        View body = root.findViewById(bodyId);
        TextView chevron = root.findViewById(chevronId);
        bodies.put(key, body);
        chevrons.put(key, chevron);
        chips.put(key, root.findViewById(chipId));
        header.setOnClickListener(v -> {
            if (rootContent instanceof ViewGroup) {
                android.transition.TransitionManager.beginDelayedTransition((ViewGroup) rootContent);
            }
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setText(open ? "▴" : "▾");
        });
    }


    private void click(View root, int id, Runnable action) {
        View b = root.findViewById(id);
        if (b == null) return;
        b.setOnClickListener(v -> {
            if (!masterOnline) {
                toast("The diorama is offline. Controls are paused.");
                return;
            }
            action.run();
        });
    }

    private DatabaseReference.CompletionListener done() {
        return (error, ref) -> {
            if (error != null) toast("Could not send the request. Check your internet connection.");
            // Success only means "Requested"; the chip waits for MASTER's acknowledgment.
        };
    }

    private void buildSwatches(LinearLayout row) {
        if (row == null) return;
        int size = (int) (32 * getResources().getDisplayMetrics().density);
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < SWATCH_HEX.length; i++) {
            final int idx = i;
            View sw = new View(requireContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            sw.setLayoutParams(lp);
            sw.setBackgroundResource(R.drawable.bg_pill_white);
            sw.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor(SWATCH_HEX[i])));
            sw.setContentDescription(SWATCH_NAME[i]);
            sw.setOnClickListener(v -> {
                if (!masterOnline) {
                    toast("The diorama is offline. Controls are paused.");
                    return;
                }
                repository.requestRgbColor("MANUAL", SWATCH_HEX[idx], SWATCH_NAME[idx], done());
            });
            row.addView(sw);
        }
    }

    private void toast(String msg) {
        if (isAdded()) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    // ---------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------

    private void renderChips() {
        if (!isAdded()) return;
        handler.removeCallbacks(tick);
        boolean pending = false;
        for (String[] c : CARDS) {
            OperationStatus s;
            if (!masterOnline) {
                s = OperationStatus.OFFLINE;
            } else {
                Map<String, Object> req = lastRequests.get(c[1]);
                if (req == null) {
                    s = OperationStatus.READY;
                } else {
                    Object st = req.get("status");
                    s = OperationStatus.fromRequestStatus(st != null ? st.toString() : null,
                            asLong(req.get("timestamp")));
                }
            }
            s.applyTo(chips.get(c[0]));
            if (s == OperationStatus.REQUESTED) pending = true;
        }
        if (pending) handler.postDelayed(tick, 1000);
    }

    /** Summary lines come only from MASTER-reported state; otherwise "--". */
    private void renderSummaries() {
        boolean valid = masterOnline && reported != null && reported.getLast_updated() > 0;
        if (!valid) {
            for (TextView t : new TextView[]{tvSumTram, tvSumLights, tvSumRgb, tvSumWind, tvSumDrainage, tvSumAudio}) {
                if (t != null) t.setText("--");
            }
            return;
        }
        tvSumTram.setText(tramLabel(reported.getTram_state()));
        String lights = reported.isStreet_lights_on() ? "Lights on" : "Lights off";
        String clock = "DEMO".equals(reported.getStreet_lights_mode()) ? "demo time" : "real clock";
        tvSumLights.setText(String.format(Locale.US, "%s · %s", lights, clock));
        tvSumRgb.setText("SENSOR".equals(reported.getRgb_mode())
                ? "Following color sensor"
                : "Chosen color " + reported.getRgb_color_hex());
        tvSumWind.setText(reported.isFan_state() ? "Fan on" : "Fan off");
        tvSumDrainage.setText(reported.isPump_state() ? "Pump on" : "Pump off");
        tvSumAudio.setText(reported.isVoice_playing() ? "Playing" : "Not playing");
    }

    private static String tramLabel(String state) {
        if (state == null) return "--";
        switch (state) {
            case "FORWARD": return "Moving forward";
            case "REVERSE": return "Moving back";
            case "STOPPED": return "Stopped";
            case "HALL_BLOCKED": return "Stopped at station";
            default: return state;
        }
    }

    private void renderOnline() {
        if (masterOnline) {
            tvOnline.setText("Online");
            tvOnline.setBackgroundResource(R.drawable.bg_status_chip_green);
            tvOnline.setTextColor(requireContext().getColor(R.color.sap_green));
            tvOfflineNote.setVisibility(View.GONE);
        } else {
            tvOnline.setText("Offline");
            tvOnline.setBackgroundResource(R.drawable.bg_status_chip_coral);
            tvOnline.setTextColor(requireContext().getColor(R.color.alert_red));
            tvOfflineNote.setVisibility(View.VISIBLE);
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
            renderOnline();
            renderChips();
            renderSummaries();
        });
    }

    @Override
    public void onReportedStateUpdated(DioramaReportedState reportedState) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            reported = reportedState;
            renderSummaries();
        });
    }

    @Override
    public void onRequestStatus(String path, @Nullable Map<String, Object> request) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (request == null) lastRequests.remove(path); else lastRequests.put(path, request);
            renderChips();
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(tick);
        repository.removeReportedStateListener(this);
        repository.removeSystemStatusListener(this);
        repository.unwatchRequests(this);
    }
}
