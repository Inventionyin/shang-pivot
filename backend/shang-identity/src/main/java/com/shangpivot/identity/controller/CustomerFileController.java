package com.shangpivot.identity.controller;

import cn.hutool.core.util.IdUtil;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.oss.ObjectStorage;
import com.shangpivot.common.oss.OssProperties;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.identity.entity.FileObject;
import com.shangpivot.identity.mapper.FileObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** C 端文件：预签名 / 确认 / 直传 / 查看（禁止走 /admin/files）。 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Tag(name = "C端文件")
public class CustomerFileController {

    private static final Set<String> ALLOWED = Set.of(
            "image/jpeg", "image/png", "image/webp");
    private static final Set<String> BIZ = Set.of("AVATAR", "AFTER_SALE", "REVIEW");

    private final ObjectStorage objectStorage;
    private final OssProperties ossProperties;
    private final FileObjectMapper fileObjectMapper;

    @GetMapping("/view")
    @Operation(summary = "查看文件")
    public void view(@RequestParam("objectKey") String objectKey, HttpServletResponse response) throws java.io.IOException {
        if (!StringUtils.hasText(objectKey) || objectKey.contains("..") || objectKey.startsWith("/")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 objectKey");
        }
        try {
            var meta = objectStorage.statObject(objectKey);
            if (StringUtils.hasText(meta.contentType())) {
                response.setContentType(meta.contentType());
            } else {
                response.setContentType("application/octet-stream");
            }
            response.setHeader("Cache-Control", "public, max-age=86400");
            try (InputStream in = objectStorage.getObject(objectKey)) {
                in.transferTo(response.getOutputStream());
            }
            response.flushBuffer();
        } catch (IllegalStateException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在或无法读取");
        }
    }

    @PostMapping("/upload")
    @Operation(summary = "直传上传")
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizType", defaultValue = "AFTER_SALE") String bizType) throws java.io.IOException {
        Long customerId = SecurityContexts.requireUserId();
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        String contentType = file.getContentType();
        validate(contentType, file.getSize());
        String biz = normalizeBiz(bizType);
        LocalDate now = LocalDate.now();
        String objectKey = biz.toLowerCase() + "/" + now.getYear() + "/" + String.format("%02d", now.getMonthValue())
                + "/" + IdUtil.fastSimpleUUID() + guessExt(file.getOriginalFilename(), contentType);
        objectStorage.putObject(objectKey, file.getBytes(), contentType);

        FileObject fo = new FileObject();
        fo.setBucket(ossProperties.getBucketName());
        fo.setObjectKey(objectKey);
        fo.setContentType(contentType);
        fo.setSizeBytes(file.getSize());
        fo.setBizType(biz);
        fo.setUploaderId(customerId);
        fo.setVisibility("AVATAR".equals(biz) ? "PUBLIC" : "PRIVATE");
        fileObjectMapper.insert(fo);

        Map<String, Object> data = new HashMap<>();
        data.put("id", fo.getId());
        data.put("objectKey", objectKey);
        data.put("url", "/api/files/view?objectKey=" + java.net.URLEncoder.encode(objectKey, java.nio.charset.StandardCharsets.UTF_8));
        return Result.ok(data);
    }

    @PostMapping("/presign")
    public Result<Map<String, Object>> presign(@RequestBody PresignReq req) {
        SecurityContexts.requireUserId();
        validate(req.getContentType(), req.getSizeBytes() != null ? req.getSizeBytes() : req.getSize());
        String biz = normalizeBiz(req.getBizType());
        LocalDate now = LocalDate.now();
        String objectKey = biz.toLowerCase() + "/" + now.getYear() + "/" + String.format("%02d", now.getMonthValue())
                + "/" + IdUtil.fastSimpleUUID() + guessExt(req.getFilename(), req.getContentType());
        var result = objectStorage.presignPut(objectKey, req.getContentType(), ossProperties.getPresignExpireSeconds());
        Map<String, Object> data = new HashMap<>();
        data.put("uploadUrl", result.uploadUrl());
        data.put("objectKey", result.objectKey());
        data.put("publicUrl", result.publicUrl());
        data.put("headers", result.headers());
        return Result.ok(data);
    }

    @PostMapping("/confirm")
    public Result<Map<String, Object>> confirm(@RequestBody ConfirmReq req) {
        Long customerId = SecurityContexts.requireUserId();
        if (!StringUtils.hasText(req.getObjectKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "objectKey不能为空");
        }
        String biz = normalizeBiz(req.getBizType());
        FileObject fo = new FileObject();
        fo.setBucket(ossProperties.getBucketName());
        fo.setObjectKey(req.getObjectKey());
        fo.setContentType(req.getContentType());
        fo.setSizeBytes(req.getSizeBytes());
        fo.setBizType(biz);
        fo.setUploaderId(customerId);
        fo.setVisibility("AVATAR".equals(biz) ? "PUBLIC" : "PRIVATE");
        fileObjectMapper.insert(fo);
        Map<String, Object> data = new HashMap<>();
        data.put("id", fo.getId());
        data.put("objectKey", fo.getObjectKey());
        data.put("url", "/api/files/view?objectKey=" + java.net.URLEncoder.encode(fo.getObjectKey(), java.nio.charset.StandardCharsets.UTF_8));
        return Result.ok(data);
    }

    private String normalizeBiz(String bizType) {
        String biz = StringUtils.hasText(bizType) ? bizType.trim().toUpperCase() : "AFTER_SALE";
        if (!BIZ.contains(biz)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "bizType 仅支持 AVATAR / AFTER_SALE / REVIEW");
        }
        return biz;
    }

    private void validate(String contentType, Long size) {
        if (!StringUtils.hasText(contentType) || !ALLOWED.contains(contentType)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件类型");
        }
        if (size != null && size > ossProperties.getMaxSizeBytes()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件超过大小限制");
        }
    }

    private String guessExt(String filename, String contentType) {
        if (StringUtils.hasText(filename) && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.'));
        }
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    @Data
    public static class PresignReq {
        private String bizType;
        private String contentType;
        private Long size;
        private Long sizeBytes;
        private String filename;
    }

    @Data
    public static class ConfirmReq {
        private String objectKey;
        private String contentType;
        private Long sizeBytes;
        private String bizType;
    }
}
