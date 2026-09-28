package com.example.sanlomobapp;

public class LogEntry {

    public static final int TYPE_PIR = 0;
    public static final int TYPE_ACTION = 1;
    public static final int TYPE_ALERT = 2;

    private final String timestamp;
    private final String eventType;
    private final String description;
    private final int type;

    public LogEntry(String timestamp, String eventType, String description, int type) {
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.description = description;
        this.type = type;
    }

    public String getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
    public String getDescription() { return description; }
    public int getType() { return type; }
}
