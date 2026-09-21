package com.park.energy.domain;

/**
 * 缺失口径：
 * MISSING_READING 相邻读数间隔明显超过正常抄表节奏 -> 该区间视为缺失，不计费、不当零；
 * BEFORE_FIRST    账期起点早于第一条读数；
 * AFTER_LAST      账期终点晚于最后一条读数（跨月窗口下同样显式登记）。
 */
public enum GapReason { MISSING_READING, BEFORE_FIRST, AFTER_LAST }
