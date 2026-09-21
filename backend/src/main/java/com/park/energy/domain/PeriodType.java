package com.park.energy.domain;

/** 时段类型：尖 SHARP / 峰 PEAK / 平 FLAT / 谷 VALLEY */
public enum PeriodType {
    SHARP("尖"),
    PEAK("峰"),
    FLAT("平"),
    VALLEY("谷");

    private final String label;

    PeriodType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
