package com.example.sanlomobapp.data;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.sanlomobapp.model.DioramaReportedState;
import com.example.sanlomobapp.model.DioramaTelemetry;
import com.example.sanlomobapp.model.SystemStatus;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class FirebaseRepository {
    private static final String TAG = "FirebaseRepository";
    private static final String DATABASE_URL = "https://sanlo-139ce-default-rtdb.asia-southeast1.firebasedatabase.app";

    private static FirebaseRepository instance;

    private final FirebaseDatabase database;
    private final FirebaseAuth auth;

    private SystemStatus currentSystemStatus = new SystemStatus();
    private DioramaTelemetry currentTelemetry = new DioramaTelemetry();
    private DioramaReportedState currentReportedState = new DioramaReportedState();

    private boolean isFirebaseConnected = false;

    public interface SystemStatusListener {
        void onSystemStatusUpdated(SystemStatus status, boolean isOnline);
    }

    public interface TelemetryListener {
        void onTelemetryUpdated(DioramaTelemetry telemetry);
    }

    public interface ReportedStateListener {
        void onReportedStateUpdated(DioramaReportedState reportedState);
    }

    /** Read-only view of an existing /requests/{path} node (status is MASTER-updated). */
    public interface RequestStatusListener {
        void onRequestStatus(String path, @androidx.annotation.Nullable Map<String, Object> request);
    }

    private final Map<RequestStatusListener, Map<String, ValueEventListener>> requestWatchers = new HashMap<>();

    private final List<SystemStatusListener> systemListeners = new ArrayList<>();
    private final List<TelemetryListener> telemetryListeners = new ArrayList<>();
    private final List<ReportedStateListener> reportedStateListeners = new ArrayList<>();

    private FirebaseRepository() {
        database = FirebaseDatabase.getInstance(DATABASE_URL);
        auth = FirebaseAuth.getInstance();
        initAuthAndListeners();
    }

    public static synchronized FirebaseRepository getInstance() {
        if (instance == null) {
            instance = new FirebaseRepository();
        }
        return instance;
    }

    private void initAuthAndListeners() {
        if (auth.getCurrentUser() == null) {
            auth.signInAnonymously()
                    .addOnSuccessListener(authResult -> {
                        Log.d(TAG, "FirebaseAuth anonymous sign-in success: " + authResult.getUser().getUid());
                        attachListeners();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "FirebaseAuth sign-in failed: " + e.getMessage());
                        // Even if auth fails, attach listeners (rules may permit reading or provide debug error)
                        attachListeners();
                    });
        } else {
            attachListeners();
        }
    }

    private void attachListeners() {
        // Connection state listener (.info/connected)
        database.getReference(".info/connected").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                isFirebaseConnected = Boolean.TRUE.equals(connected);
                notifySystemStatus();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, ".info/connected cancelled: " + error.getMessage());
            }
        });

        // /system listener
        database.getReference("system").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                SystemStatus status = snapshot.getValue(SystemStatus.class);
                if (status != null) {
                    currentSystemStatus = status;
                } else {
                    currentSystemStatus = new SystemStatus();
                }
                notifySystemStatus();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "system read cancelled: " + error.getMessage());
            }
        });

        // /telemetry listener
        database.getReference("telemetry").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                DioramaTelemetry telemetry = snapshot.getValue(DioramaTelemetry.class);
                if (telemetry != null) {
                    currentTelemetry = telemetry;
                } else {
                    currentTelemetry = new DioramaTelemetry();
                }
                for (TelemetryListener l : new ArrayList<>(telemetryListeners)) {
                    l.onTelemetryUpdated(currentTelemetry);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "telemetry read cancelled: " + error.getMessage());
            }
        });

        // /reported_state listener
        database.getReference("reported_state").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                DioramaReportedState state = snapshot.getValue(DioramaReportedState.class);
                if (state != null) {
                    currentReportedState = state;
                } else {
                    currentReportedState = new DioramaReportedState();
                }
                for (ReportedStateListener l : new ArrayList<>(reportedStateListeners)) {
                    l.onReportedStateUpdated(currentReportedState);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "reported_state read cancelled: " + error.getMessage());
            }
        });
    }

    private void notifySystemStatus() {
        boolean active = currentSystemStatus.isMasterActive();
        for (SystemStatusListener l : new ArrayList<>(systemListeners)) {
            l.onSystemStatusUpdated(currentSystemStatus, active);
        }
    }

    public void addSystemStatusListener(SystemStatusListener listener) {
        if (!systemListeners.contains(listener)) {
            systemListeners.add(listener);
            listener.onSystemStatusUpdated(currentSystemStatus, currentSystemStatus.isMasterActive());
        }
    }

    public void removeSystemStatusListener(SystemStatusListener listener) {
        systemListeners.remove(listener);
    }

    public void addTelemetryListener(TelemetryListener listener) {
        if (!telemetryListeners.contains(listener)) {
            telemetryListeners.add(listener);
            listener.onTelemetryUpdated(currentTelemetry);
        }
    }

    public void removeTelemetryListener(TelemetryListener listener) {
        telemetryListeners.remove(listener);
    }

    public void addReportedStateListener(ReportedStateListener listener) {
        if (!reportedStateListeners.contains(listener)) {
            reportedStateListeners.add(listener);
            listener.onReportedStateUpdated(currentReportedState);
        }
    }

    public void removeReportedStateListener(ReportedStateListener listener) {
        reportedStateListeners.remove(listener);
    }

    public SystemStatus getCurrentSystemStatus() { return currentSystemStatus; }
    public DioramaTelemetry getCurrentTelemetry() { return currentTelemetry; }
    public DioramaReportedState getCurrentReportedState() { return currentReportedState; }
    public boolean isFirebaseConnected() { return isFirebaseConnected; }

    // ============================================================
    // READ-ONLY REQUEST ACKNOWLEDGMENT WATCHING
    // Does not write anything. Lets the UI distinguish Requested from
    // MASTER-confirmed Accepted / Running / Completed / Failed.
    // ============================================================

    @SuppressWarnings("unchecked")
    public void watchRequest(String path, RequestStatusListener listener) {
        Map<String, ValueEventListener> byPath = requestWatchers.get(listener);
        if (byPath == null) {
            byPath = new HashMap<>();
            requestWatchers.put(listener, byPath);
        }
        if (byPath.containsKey(path)) return;
        ValueEventListener vel = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Object v = snapshot.getValue();
                listener.onRequestStatus(path, v instanceof Map ? (Map<String, Object>) v : null);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.w(TAG, "request watch cancelled (" + path + "): " + error.getMessage());
            }
        };
        byPath.put(path, vel);
        database.getReference("requests").child(path).addValueEventListener(vel);
    }

    public void unwatchRequests(RequestStatusListener listener) {
        Map<String, ValueEventListener> byPath = requestWatchers.remove(listener);
        if (byPath == null) return;
        for (Map.Entry<String, ValueEventListener> e : byPath.entrySet()) {
            database.getReference("requests").child(e.getKey()).removeEventListener(e.getValue());
        }
    }

    // ============================================================
    // COMMAND REQUESTS (SEPARATE FROM REPORTED STATE)
    // MASTER ESP32 validates, arbitrates, and executes these requests.
    // ============================================================

    public void requestTramAction(String action, DatabaseReference.CompletionListener listener) {
        Map<String, Object> req = new HashMap<>();
        req.put("action", action); // FORWARD, REVERSE, STOP
        req.put("req_id", "tram_" + UUID.randomUUID().toString().substring(0, 8));
        req.put("timestamp", System.currentTimeMillis());
        req.put("status", "PENDING");
        database.getReference("requests").child("tram").setValue(req, listener);
    }

    public void requestRgbColor(String mode, String colorHex, String colorName, DatabaseReference.CompletionListener listener) {
        Map<String, Object> req = new HashMap<>();
        req.put("mode", mode); // SENSOR or MANUAL
        req.put("color_hex", colorHex);
        req.put("color_name", colorName);
        req.put("req_id", "rgb_" + UUID.randomUUID().toString().substring(0, 8));
        req.put("timestamp", System.currentTimeMillis());
        req.put("status", "PENDING");
        database.getReference("requests").child("rgb").setValue(req, listener);
    }

    public void requestStreetLightsDemo(int hour, int minute, DatabaseReference.CompletionListener listener) {
        Map<String, Object> req = new HashMap<>();
        req.put("mode", "DEMO");
        req.put("demo_hour", hour);
        req.put("demo_minute", minute);
        req.put("req_id", "sl_" + UUID.randomUUID().toString().substring(0, 8));
        req.put("timestamp", System.currentTimeMillis());
        req.put("status", "PENDING");
        database.getReference("requests").child("street_lights").setValue(req, listener);
    }

    public void requestStreetLightsResetRtc(DatabaseReference.CompletionListener listener) {
        Map<String, Object> req = new HashMap<>();
        req.put("mode", "RTC");
        req.put("req_id", "sl_rtc_" + UUID.randomUUID().toString().substring(0, 8));
        req.put("timestamp", System.currentTimeMillis());
        req.put("status", "PENDING");
        database.getReference("requests").child("street_lights").setValue(req, listener);
    }

    public void requestSimulation(String targetMode, int individualOp, String action, DatabaseReference.CompletionListener listener) {
        Map<String, Object> req = new HashMap<>();
        req.put("target_mode", targetMode); // INDIVIDUAL or FULL or IDLE
        req.put("individual_op", individualOp); // 1..5
        req.put("action", action); // START or STOP
        req.put("req_id", "sim_" + UUID.randomUUID().toString().substring(0, 8));
        req.put("timestamp", System.currentTimeMillis());
        req.put("status", "PENDING");
        database.getReference("requests").child("simulation").setValue(req, listener);
    }

    public void requestActuator(String actuator, boolean state, DatabaseReference.CompletionListener listener) {
        Map<String, Object> req = new HashMap<>();
        req.put("actuator", actuator); // "fan" or "pump"
        req.put("target_state", state);
        req.put("req_id", "act_" + UUID.randomUUID().toString().substring(0, 8));
        req.put("timestamp", System.currentTimeMillis());
        req.put("status", "PENDING");
        database.getReference("requests").child("actuators").child(actuator).setValue(req, listener);
    }

    public void requestVoiceNarration(int track, DatabaseReference.CompletionListener listener) {
        Map<String, Object> req = new HashMap<>();
        req.put("track", track);
        req.put("req_id", "voice_" + UUID.randomUUID().toString().substring(0, 8));
        req.put("timestamp", System.currentTimeMillis());
        req.put("status", "PENDING");
        database.getReference("requests").child("voice_guide").setValue(req, listener);
    }
}
