package com.example.excelstream.download

enum class ExportFormat(val ext: String, val contentType: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv"),
    ;

    companion object {
        fun from(value: String): ExportFormat =
            entries.firstOrNull { it.ext.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("지원하지 않는 포맷: $value")
    }
}
