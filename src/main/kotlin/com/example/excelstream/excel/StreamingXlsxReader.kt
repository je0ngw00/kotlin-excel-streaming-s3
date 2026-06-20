package com.example.excelstream.excel

import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.util.CellReference
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler
import org.apache.poi.xssf.usermodel.XSSFComment
import org.xml.sax.InputSource
import java.nio.file.Path

class StreamingXlsxReader {

    /**
     * 워크북의 모든 시트를 순회하며, 각 시트의 첫 행을 헤더로 보고
     * 데이터 행을 "헤더명(소문자) → 값" 맵으로 콜백한다.
     * 컬럼 순서나 불필요한 컬럼(id 등)에 의존하지 않으므로, 내보내기로 만든 파일을
     * 그대로 다시 업로드해도 컬럼이 밀리지 않는다.
     */
    fun read(xlsxPath: Path, onRow: (Map<String, String?>) -> Unit) {
        OPCPackage.open(xlsxPath.toFile()).use { pkg ->
            readPackage(pkg, onRow)
        }
    }

    private fun readPackage(pkg: OPCPackage, onRow: (Map<String, String?>) -> Unit) {
        val strings = ReadOnlySharedStringsTable(pkg)
        val reader = XSSFReader(pkg)
        val styles = reader.stylesTable

        val sheets = reader.sheetsData as XSSFReader.SheetIterator
        while (sheets.hasNext()) {
            sheets.next().use { sheetStream ->
                // 시트마다 새 핸들러 → 각 시트의 첫 행을 그 시트의 헤더로 인식한다.
                val handler = RowHandler(onRow)
                val xmlReader = XMLHelper.newXMLReader()
                xmlReader.contentHandler =
                    XSSFSheetXMLHandler(styles, strings, handler, false)
                xmlReader.parse(InputSource(sheetStream))
            }
        }
    }

    private class RowHandler(
        private val onRow: (Map<String, String?>) -> Unit,
    ) : SheetContentsHandler {

        private val current = ArrayList<String?>()
        private var header: List<String>? = null

        override fun startRow(rowNum: Int) {
            current.clear()
        }

        override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
            val colIdx = cellReference?.let { CellReference(it).col.toInt() } ?: current.size
            while (current.size < colIdx) current.add(null)
            current.add(formattedValue)
        }

        override fun endRow(rowNum: Int) {
            val row = current.toList()
            val h = header
            if (h == null) {
                // 첫 행은 헤더. 컬럼명을 소문자/trim 정규화해 매핑 키로 쓴다.
                header = row.map { it?.trim()?.lowercase() ?: "" }
                return
            }
            val map = LinkedHashMap<String, String?>(h.size)
            for (i in h.indices) {
                val key = h[i]
                if (key.isNotEmpty()) map[key] = row.getOrNull(i)
            }
            onRow(map)
        }

        override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}
    }
}
