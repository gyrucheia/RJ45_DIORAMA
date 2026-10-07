package com.example.sanlomobapp.model;

public class SystemStatus {
    private long master_heartbeat = 0;
    private boolean master_online = false;
    private boolean slave1_online = false;
    private boolean slave2_online = false;
    private String firmware_version = "1.0.0-rj45";
    private int wifi_rssi = 0;

    private long local_received_at = 0;

    public SystemStatus() {}

    public long getMaster_heartbeat() { return master_heartbeat; }
    public void setMaster_heartbeat(long master_heartbeat) {
        this.master_heartbeat = master_heartbeat;
        this.local_received_at = System.currentTimeMillis();
    }

    public boolean isMaster_online() { return master_online; }
    public void setMaster_online(boolean master_online) {
        this.master_online = master_online;
        if (this.local_received_at == 0) {
            this.local_received_at = System.currentTimeMillis();
        }
    }

    public boolean isSlave1_online() { return slave1_online; }
    public void setSlave1_online(boolean slave1_online) { this.slave1_online = slave1_online; }

    public boolean isSlave2_online() { return slave2_online; }
    public void setSlave2_online(boolean slave2_online) { this.slave2_online = slave2_online; }

    public String getFirmware_version() { return firmware_version; }
    public void setFirmware_version(String firmware_version) { this.firmware_version = firmware_version; }

    public int getWifi_rssi() { return wifi_rssi; }
    public void setWifi_rssi(int wifi_rssi) { this.wifi_rssi = wifi_rssi; }

    /**
     * MASTER is considered truly online only if master_online is true AND
     * a heartbeat was received within the last 30 seconds.
     * Supports both UNIX epoch timestamps and relative ESP32 uptime millis().
     */
    public boolean isMasterActive() {
        if (!master_online || master_heartbeat <= 0) return false;

        // If master_heartbeat is a standard UNIX epoch timestamp (> 1 trillion ms)
        if (master_heartbeat > 1000000000000L) {
            long elapsed = System.currentTimeMillis() - master_heartbeat;
            return elapsed >= 0 && elapsed <= 30000;
        }

        // If master_heartbeat is ESP32 uptime millis(), evaluate freshness via local arrival timestamp
        if (local_received_at > 0) {
            long elapsed = System.currentTimeMillis() - local_received_at;
            return elapsed <= 30000;
        }

        return master_online;
    }
}
