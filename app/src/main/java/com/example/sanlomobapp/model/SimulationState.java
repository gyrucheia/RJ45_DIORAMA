package com.example.sanlomobapp.model;

public class SimulationState {
    public static final String MODE_IDLE = "IDLE";
    public static final String MODE_INDIVIDUAL = "INDIVIDUAL";
    public static final String MODE_FULL = "FULL";

    public static final int OP_NONE = 0;
    public static final int OP_TRAM = 1;
    public static final int OP_STREET_LIGHTS = 2;
    public static final int OP_RGB = 3;
    public static final int OP_WIND = 4;
    public static final int OP_DRAINAGE = 5;

    private String mode = MODE_IDLE;
    private int individual_op = OP_NONE;
    private String op_name = "NONE";
    private boolean running = false;
    private String step_description = "System ready";
    private long started_at = 0;

    public SimulationState() {}

    public String getMode() { return mode != null ? mode : MODE_IDLE; }
    public void setMode(String mode) { this.mode = mode; }

    public int getIndividual_op() { return individual_op; }
    public void setIndividual_op(int individual_op) { this.individual_op = individual_op; }

    public String getOp_name() { return op_name != null ? op_name : "NONE"; }
    public void setOp_name(String op_name) { this.op_name = op_name; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public String getStep_description() { return step_description != null ? step_description : ""; }
    public void setStep_description(String step_description) { this.step_description = step_description; }

    public long getStarted_at() { return started_at; }
    public void setStarted_at(long started_at) { this.started_at = started_at; }

    public static String getOperationTitle(int op) {
        switch (op) {
            case OP_TRAM: return "1. Tram (Gesture -> Motor -> Hall)";
            case OP_STREET_LIGHTS: return "2. Street Lights (RTC/Demo -> LEDs)";
            case OP_RGB: return "3. RGB Lighting (TCS34725/Manual -> RGB)";
            case OP_WIND: return "4. Wind (Fan -> Flex)";
            case OP_DRAINAGE: return "5. Drainage (Water Sensor -> Pump -> Servo)";
            default: return "None";
        }
    }
}
