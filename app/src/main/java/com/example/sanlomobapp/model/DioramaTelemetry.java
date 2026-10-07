package com.example.sanlomobapp.model;

public class DioramaTelemetry {
    private Double temperature = null;
    private Double humidity = null;
    private Integer water_raw = null;
    private Integer water_level_pct = null;
    private String water_status = "NORMAL"; // NORMAL, ELEVATED, CRITICAL
    private Integer flex_raw = null;
    private Boolean flex_bent = null;
    private Boolean pir_active = null;
    private Integer visitor_count_today = null;
    private Long timestamp = null;

    public DioramaTelemetry() {}

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }

    public Integer getWater_raw() { return water_raw; }
    public void setWater_raw(Integer water_raw) { this.water_raw = water_raw; }

    public Integer getWater_level_pct() { return water_level_pct; }
    public void setWater_level_pct(Integer water_level_pct) { this.water_level_pct = water_level_pct; }

    public String getWater_status() { return water_status != null ? water_status : "NORMAL"; }
    public void setWater_status(String water_status) { this.water_status = water_status; }

    public Integer getFlex_raw() { return flex_raw; }
    public void setFlex_raw(Integer flex_raw) { this.flex_raw = flex_raw; }

    public Boolean getFlex_bent() { return flex_bent; }
    public void setFlex_bent(Boolean flex_bent) { this.flex_bent = flex_bent; }

    public Boolean getPir_active() { return pir_active; }
    public void setPir_active(Boolean pir_active) { this.pir_active = pir_active; }

    public Integer getVisitor_count_today() { return visitor_count_today; }
    public void setVisitor_count_today(Integer visitor_count_today) { this.visitor_count_today = visitor_count_today; }

    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    public boolean hasData() {
        return temperature != null || humidity != null || water_level_pct != null || flex_raw != null;
    }
}
