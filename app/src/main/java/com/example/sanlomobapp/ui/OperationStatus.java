package com.example.sanlomobapp.ui;

import android.content.Context;
import android.widget.TextView;

import com.example.sanlomobapp.R;

/**
 * Visitor-facing lifecycle of a requested diorama operation.
 *
 * IMPORTANT: Only REQUESTED is set by the app itself (a Firebase write succeeded).
 * ACCEPTED / RUNNING / COMPLETED / FAILED must come from MASTER-written status
 * or MASTER-reported state. A Firebase write is never treated as hardware execution.
 */
public enum OperationStatus {
    OFFLINE("Offline", R.drawable.bg_pill_gray, R.color.offline_gray),
    READY("Ready", R.drawable.bg_pill_white, R.color.sap_green),
    REQUESTED("Requested", R.drawable.bg_pill_orange, R.color.white),
    ACCEPTED("Accepted", R.drawable.bg_pill_blue, R.color.white),
    RUNNING("Running", R.drawable.bg_pill_teal, R.color.white),
    COMPLETED("Completed", R.drawable.bg_tram_status_card, R.color.white),
    FAILED("Failed", R.drawable.bg_pill_red, R.color.white);

    /** A request with no MASTER acknowledgment after this long is shown as Failed (no response). */
    public static final long ACK_TIMEOUT_MS = 15000;

    public final String label;
    private final int backgroundRes;
    private final int textColorRes;

    OperationStatus(String label, int backgroundRes, int textColorRes) {
        this.label = label;
        this.backgroundRes = backgroundRes;
        this.textColorRes = textColorRes;
    }

    public void applyTo(TextView chip) {
        if (chip == null) return;
        Context c = chip.getContext();
        chip.setText(label);
        chip.setBackgroundResource(backgroundRes);
        chip.setTextColor(c.getColor(textColorRes));
    }

    /**
     * Maps the status string stored at /requests/{cmd}/status.
     * The app writes "PENDING"; anything else is assumed to be written by MASTER.
     */
    public static OperationStatus fromRequestStatus(String status, long requestTimestamp) {
        if (status == null) return READY;
        switch (status.trim().toUpperCase()) {
            case "PENDING":
            case "REQUESTED":
                if (requestTimestamp > 0
                        && System.currentTimeMillis() - requestTimestamp > ACK_TIMEOUT_MS) {
                    return FAILED; // no MASTER acknowledgment in time
                }
                return REQUESTED;
            case "ACCEPTED":
            case "ACK":
                return ACCEPTED;
            case "RUNNING":
            case "EXECUTING":
                return RUNNING;
            case "COMPLETED":
            case "DONE":
            case "SUCCESS":
                return COMPLETED;
            case "FAILED":
            case "REJECTED":
            case "TIMEOUT":
            case "ERROR":
                return FAILED;
            default:
                return READY;
        }
    }
}
