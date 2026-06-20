package com.example.excelstream.sample

import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.FileOutputStream

fun generate(rows: Int, path: String) {
    SXSSFWorkbook(100).use { wb ->
        FileOutputStream(path).use { out ->
            val sheet = wb.createSheet("data")
            val header = sheet.createRow(0)
            header.createCell(0).setCellValue("id")
            header.createCell(1).setCellValue("email")
            header.createCell(2).setCellValue("name")
            header.createCell(3).setCellValue("amount")
            for (i in 1..rows) {
                val r = sheet.createRow(i)
                r.createCell(0).setCellValue(i.toDouble())
                r.createCell(1).setCellValue("user$i@example.com")
                r.createCell(2).setCellValue("이름$i")
                r.createCell(3).setCellValue((i * 100).toDouble())
            }
            wb.write(out)
        }
    }
    println("generated $rows rows -> $path")
}

fun main(args: Array<String>) {
    val rows = args.getOrNull(0)?.toInt() ?: 100_000
    val path = args.getOrNull(1) ?: "/tmp/sample-$rows.xlsx"
    generate(rows, path)
}
