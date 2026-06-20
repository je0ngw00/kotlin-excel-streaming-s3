package com.example.excelstream.upload

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class UploadController(private val service: UploadService) {

    @PostMapping("/upload")
    fun upload(@RequestParam("file") file: MultipartFile): String {
        val inserted = service.handle(file)
        return "inserted=$inserted"
    }
}
