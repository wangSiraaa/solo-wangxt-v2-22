package com.park.energy.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "calendar_day",
        uniqueConstraints = @UniqueConstraint(columnNames = "local_date"))
public class CalendarDay {
    @Id
    private String id;
    @Column(name = "local_date", nullable = false)
    private LocalDate localDate;
    @Column(name = "day_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private DayType dayType;

    protected CalendarDay() {}

    public CalendarDay(String id, LocalDate localDate, DayType dayType) {
        this.id = id;
        this.localDate = localDate;
        this.dayType = dayType;
    }

    public String getId() { return id; }
    public LocalDate getLocalDate() { return localDate; }
    public DayType getDayType() { return dayType; }
}
