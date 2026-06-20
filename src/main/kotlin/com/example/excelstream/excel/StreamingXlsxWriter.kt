package com.example.excelstream.excel

import com.example.excelstream.support.MemoryProbe
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream

class StreamingXlsxWriter {

    /**
     * 행 시퀀스를 받아 SXSSF로 한 행씩 흘려 쓴다.
     * 시퀀스는 지연 평가되므로 호출자가 DB 키셋 페이징 등으로 조금씩 공급해도
     * 전체를 메모리에 올리지 않는다. 각 행은 [email, name, amount] 순서다.
     */
    fun write(out: OutputStream, rows: Sequence<Array<Any?>>) {
        SXSSFWorkbook(100).use { wb ->
            val sheet = wb.createSheet("data")
            var rownum = 0

            val header = sheet.createRow(rownum++)
            header.createCell(0).setCellValue("email")
            header.createCell(1).setCellValue("name")
            header.createCell(2).setCellValue("amount")

            for (data in rows) {
                val r = sheet.createRow(rownum++)
                r.createCell(0).setCellValue(data[0] as String?)
                r.createCell(1).setCellValue(data[1] as String?)
                r.createCell(2).setCellValue((data[2] as Number).toDouble())
                if (rownum % LOG_EVERY == 0) {
                    MemoryProbe.log("download-write", rownum.toLong())
                }
            }
            wb.write(out)
        }
    }

    companion object {
        private const val LOG_EVERY = 1000
    }
}
