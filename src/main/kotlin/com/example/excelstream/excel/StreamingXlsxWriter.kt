package com.example.excelstream.excel

import com.example.excelstream.support.MemoryProbe
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream

class StreamingXlsxWriter {

    fun interface PageFetcher {
        fun fetch(pageNumber: Int, pageSize: Int): List<Array<Any?>>
    }

    fun write(out: OutputStream, pageSize: Int, fetcher: PageFetcher) {
        SXSSFWorkbook(100).use { wb ->
            val sheet = wb.createSheet("data")
            var rownum = 0

            val header = sheet.createRow(rownum++)
            header.createCell(0).setCellValue("email")
            header.createCell(1).setCellValue("name")
            header.createCell(2).setCellValue("amount")

            var page = 0
            while (true) {
                val rows = fetcher.fetch(page, pageSize)
                if (rows.isEmpty()) break
                for (data in rows) {
                    val r = sheet.createRow(rownum++)
                    r.createCell(0).setCellValue(data[0] as String?)
                    r.createCell(1).setCellValue(data[1] as String?)
                    r.createCell(2).setCellValue((data[2] as Number).toDouble())
                }
                MemoryProbe.log("download-write", rownum.toLong())
                page++
            }
            wb.write(out)
        }
    }
}
