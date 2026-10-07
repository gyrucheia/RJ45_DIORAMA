package com.example.sanlomobapp.model;

import java.util.HashMap;
import java.util.Map;

public class DioramaReportedState {
    private String active_mode = "IDLE"; // IDLE, SIMULATION_INDIVIDUAL, SIMULATION_FULL, MANUAL
    private SimulationState simulation = new SimulationState();

    // Tram
    private String tram_state = "STOPPED"; // FORWARD, REVERSE, STOPPED, HALL_BLOCKED
    private boolean hall_detected = false;
    private int gate_servo_deg = 0; // 0 (closed) or 90 (open)

    // Street Lights & Virtual Demonstration Clock
    private String street_lights_mode = "RTC"; // RTC or DEMO
    private boolean street_lights_on = false;
    private int effective_hour = 0;
    private int effective_minute = 0;
    private int rtc_hour = 0;
    private int rtc_minute = 0;
    private boolean demo_active = false;

    // RGB Lighting
    private String rgb_mode = "SENSOR"; // SENSOR or MANUAL
    private String rgb_color_hex = "#47AFC3";
    private int rgb_r = 71;
    private int rgb_g = 175;
    private int rgb_b = 195;
    private int rgb_brightness = 30;

    // Actuators
    private boolean fan_state = false;
    private boolean pump_state = false;

    // Voice guide
    private boolean voice_playing = false;
    private int voice_current_track = 0;

    private long last_updated = 0;

    public DioramaReportedState() {}

    public String getActive_mode() { return active_mode; }
    public void setActive_mode(String active_mode) { this.active_mode = active_mode; }

    public SimulationState getSimulation() { return simulation; }
    public void setSimulation(SimulationState simulation) { this.simulation = simulation; }

    public String getTram_state() { return tram_state; }
    public void setTram_state(String tram_state) { this.tram_state = tram_state; }

    public boolean isHall_detected() { return hall_detected; }
    public void setHall_detected(boolean hall_detected) { this.hall_detected = hall_detected; }

    public int getGate_servo_deg() { return gate_servo_deg; }
    public void setGate_servo_deg(int gate_servo_deg) { this.gate_servo_deg = gate_servo_deg; }

    public String getStreet_lights_mode() { return street_lights_mode; }
    public void setStreet_lights_mode(String street_lights_mode) { this.street_lights_mode = street_lights_mode; }

    public boolean isStreet_lights_on() { return street_lights_on; }
    public void setStreet_lights_on(boolean street_lights_on) { this.street_lights_on = street_lights_on; }

    public int getEffective_hour() { return effective_hour; }
    public void setEffective_hour(int effective_hour) { this.effective_hour = effective_hour; }

    public int getEffective_minute() { return effective_minute; }
    public void setEffective_minute(int effective_minute) { this.effective_minute = effective_minute; }

    public int getRtc_hour() { return rtc_hour; }
    public void setRtc_hour(int rtc_hour) { this.rtc_hour = rtc_hour; }

    public int getRtc_minute() { return rtc_minute; }
    public void setRtc_minute(int rtc_minute) { this.rtc_minute = rtc_minute; }

    public boolean isDemo_active() { return demo_active; }
    public void setDemo_active(boolean demo_active) { this.demo_active = demo_active; }

    public String getRgb_mode() { return rgb_mode; }
    public void setRgb_mode(String rgb_mode) { this.rgb_mode = rgb_mode; }

    public String getRgb_color_hex() { return rgb_color_hex != null ? rgb_color_hex : "#47AFC3"; }
    public void setRgb_color_hex(String rgb_color_hex) { this.rgb_color_hex = rgb_color_hex; }

    public int getRgb_r() { return rgb_r; }
    public void setRgb_r(int rgb_r) { this.rgb_r = rgb_r; }

    public int getRgb_g() { return rgb_g; }
    public void setRgb_g(int rgb_g) { this.rgb_g = rgb_g; }

    public int getRgb_b() { return rgb_b; }
    public void setRgb_b(int rgb_b) { this.rgb_b = rgb_b; }

    public int getRgb_brightness() { return rgb_brightness; }
    public void setRgb_brightness(int rgb_brightness) { this.rgb_brightness = rgb_brightness; }

    public boolean isFan_state() { return fan_state; }
    public void setFan_state(boolean fan_state) { this.fan_state = fan_state; }

    public boolean isPump_state() { return pump_state; }
    public void setPump_state(boolean pump_state) { this.pump_state = pump_state; }

    public boolean isVoice_playing() { return voice_playing; }
    public void setVoice_playing(boolean voice_playing) { this.voice_playing = voice_playing; }

    public int getVoice_current_track() { return voice_current_track; }
    public void setVoice_current_track(int voice_current_track) { this.voice_current_track = voice_current_track; }

    public long getLast_updated() { return last_updated; }
    public void setLast_updated(long last_updated) { this.last_updated = last_updated; }
}
