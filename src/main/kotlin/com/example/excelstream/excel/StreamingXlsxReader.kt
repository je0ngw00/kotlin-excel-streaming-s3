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

    fun read(xlsxPath: Path, onRow: (List<String?>) -> Unit) {
        OPCPackage.open(xlsxPath.toFile()).use { pkg ->
            readPackage(pkg, onRow)
        }
    }

    private fun readPackage(pkg: OPCPackage, onRow: (List<String?>) -> Unit) {
        val strings = ReadOnlySharedStringsTable(pkg)
        val reader = XSSFReader(pkg)
        val styles = reader.stylesTable

        val sheets = reader.sheetsData as XSSFReader.SheetIterator
        if (sheets.hasNext()) {
            sheets.next().use { sheetStream ->
                val handler = RowHandler(onRow)
                val xmlReader = XMLHelper.newXMLReader()
                xmlReader.contentHandler =
                    XSSFSheetXMLHandler(styles, strings, handler, false)
                xmlReader.parse(InputSource(sheetStream))
            }
        }
    }

    private class RowHandler(
        private val onRow: (List<String?>) -> Unit,
    ) : SheetContentsHandler {

        private val current = ArrayList<String?>()

        override fun startRow(rowNum: Int) {
            current.clear()
        }

        override fun cell(cellReference: String?, formattedValue: String?, comment: XSSFComment?) {
            val colIdx = cellReference?.let { CellReference(it).col.toInt() } ?: current.size
            while (current.size < colIdx) current.add(null)
            current.add(formattedValue)
        }

        override fun endRow(rowNum: Int) {
            if (rowNum == 0) return
            onRow(current.toList())
        }

        override fun headerFooter(text: String?, isHeader: Boolean, tagName: String?) {}
    }
}
