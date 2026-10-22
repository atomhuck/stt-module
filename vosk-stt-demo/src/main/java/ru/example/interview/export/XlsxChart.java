package ru.example.interview.export;

import java.util.List;

/**
 * Столбчатая диаграмма по диапазону ячеек листа (данные берутся из самого листа, поэтому диаграмма «живая»).
 *
 * @param horizontal  true — горизонтальные полосы (удобно для длинных названий тем)
 * @param cats/vals   значения для кэша диаграммы (чтобы она рисовалась и в программах без пересчёта)
 * @version 1.0.0
 */
public record XlsxChart(String title, boolean horizontal, String sheetName, int firstRow, int lastRow,
                        int catCol, int valCol, List<String> cats, List<Double> vals,
                        double axisMax, String color, String labelFormat) {
}
