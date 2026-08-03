package com.bidding.file.adapter.web;

import com.bidding.common.api.ApiResponse;
import com.bidding.file.application.DocumentAppService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 标包下文件接口（招标方）：上传 / 列表 / 删除 / 下载 / PDF 预览。
 */
@RestController
@RequestMapping("/api/packages/{packageId}/files")
public class DocumentController {

    private final DocumentAppService documentAppService;

    public DocumentController(DocumentAppService documentAppService) {
        this.documentAppService = documentAppService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentAppService.DocumentSummary> upload(
            @PathVariable("packageId") long packageId,
            @RequestParam("fileType") String fileType,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(documentAppService.upload(packageId, fileType, file));
    }

    @GetMapping
    public ApiResponse<List<DocumentAppService.DocumentSummary>> list(
            @PathVariable("packageId") long packageId) {
        return ApiResponse.ok(documentAppService.listByPackage(packageId));
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<InputStreamResource> download(
            @PathVariable("packageId") long packageId,
            @PathVariable("documentId") long documentId) {
        return toFileResponse(documentAppService.openForDownload(packageId, documentId));
    }

    @GetMapping("/{documentId}/preview")
    public ResponseEntity<InputStreamResource> preview(
            @PathVariable("packageId") long packageId,
            @PathVariable("documentId") long documentId) {
        return toFileResponse(documentAppService.openForPreview(packageId, documentId));
    }

    @DeleteMapping("/{documentId}")
    public ApiResponse<Void> delete(
            @PathVariable("packageId") long packageId,
            @PathVariable("documentId") long documentId) {
        documentAppService.delete(packageId, documentId);
        return ApiResponse.ok();
    }

    private ResponseEntity<InputStreamResource> toFileResponse(DocumentAppService.FileContent content) {
        ContentDisposition disposition = content.isInline()
                ? ContentDisposition.inline().filename(content.getFileName(), StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(content.getFileName(), StandardCharsets.UTF_8).build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(content.getContentType()))
                .contentLength(content.getSizeBytes())
                .body(new InputStreamResource(content.getInputStream()));
    }
}
