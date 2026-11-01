package com.shangpivot.identity.controller;

import cn.hutool.core.util.IdUtil;
import com.shangpivot.common.audit.OpLog;
import com.shangpivot.common.exception.BizException;
import com.shangpivot.common.oss.ObjectStorage;
import com.shangpivot.common.oss.OssProperties;
import com.shangpivot.common.result.ErrorCode;
import com.shangpivot.common.result.Result;
import com.shangpivot.common.security.SecurityContexts;
import com.shangpivot.identity.entity.FileObject;
import com.shangpivot.identity.mapper.FileObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin/files")
@RequiredArgsConstructor
@Tag(name = "文件上传", description = "对象存储预签名上传与上传确认")
public class FileController {

    private static final Set<String> ALLOWED = Set.of(
            "image/jpeg", "image/png", "image/webp", "video/mp4");

    private final ObjectStorage objectStorage;
    private final OssProperties ossProperties;
    private final FileObjectMapper fileObjectMapper;

    @GetMapping("/view")
    @Operation(summary = "查看/下载文件", description = "从 OSS 代理输出文件流，供前端 img 展示（桶私有时不可直链）")
    public void view(
            @RequestParam("objectKey") String objectKey,
            HttpServletResponse response) throws java.io.IOException {
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
    @OpLog(module = "file", action = "upload", saveParams = false)
    @Operation(summary = "直传上传文件", description = "服务端接收文件并写入 OSS，返回可访问 URL（避免浏览器直传 CORS 问题）")
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "bizType", required = false) String bizType) throws java.io.IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        String contentType = file.getContentType();
        validate(contentType, file.getSize());
        String ext = guessExt(file.getOriginalFilename(), contentType);
        String biz = StringUtils.hasText(bizType) ? bizType.toLowerCase() : "product";
        LocalDate now = LocalDate.now();
        String objectKey = biz + "/" + now.getYear() + "/" + String.format("%02d", now.getMonthValue())
                + "/" + IdUtil.fastSimpleUUID() + ext;
        objectStorage.putObject(objectKey, file.getBytes(), contentType);

        FileObject fo = new FileObject();
        fo.setBucket(ossProperties.getBucketName());
        fo.setObjectKey(objectKey);
        fo.setContentType(contentType);
        fo.setSizeBytes(file.getSize());
        fo.setBizType(StringUtils.hasText(bizType) ? bizType : "PRODUCT");
        fo.setUploaderId(SecurityContexts.requireUserId());
        fo.setVisibility("PUBLIC");
        fileObjectMapper.insert(fo);

        Map<String, Object> data = new HashMap<>();
        data.put("id", fo.getId());
        data.put("objectKey", objectKey);
        data.put("url", objectStorage.publicUrl(objectKey));
        return Result.ok(data);
    }

    @PostMapping("/presign")
    @Operation(summary = "获取预签名上传地址", description = "生成 OSS 预签名 PUT URL，客户端直传文件")
    public Result<Map<String, Object>> presign(@RequestBody PresignReq req) {
        validate(req.getContentType(), req.getSize());
        String ext = guessExt(req.getFilename(), req.getContentType());
        String biz = StringUtils.hasText(req.getBizType()) ? req.getBizType().toLowerCase() : "other";
        LocalDate now = LocalDate.now();
        String objectKey = biz + "/" + now.getYear() + "/" + String.format("%02d", now.getMonthValue())
                + "/" + IdUtil.fastSimpleUUID() + ext;
        var result = objectStorage.presignPut(objectKey, req.getContentType(), ossProperties.getPresignExpireSeconds());
        Map<String, Object> data = new HashMap<>();
        data.put("uploadUrl", result.uploadUrl());
        data.put("objectKey", result.objectKey());
        data.put("publicUrl", result.publicUrl());
        data.put("headers", result.headers());
        return Result.ok(data);
    }

    @PostMapping("/confirm")
    @OpLog(module = "file", action = "confirm")
    @Operation(summary = "确认上传完成", description = "客户端直传成功后回调，登记文件元数据并返回访问地址")
    public Result<Map<String, Object>> confirm(@RequestBody ConfirmReq req) {
        if (!StringUtils.hasText(req.getObjectKey())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "objectKey不能为空");
        }
        FileObject fo = new FileObject();
        fo.setBucket(ossProperties.getBucketName());
        fo.setObjectKey(req.getObjectKey());
        fo.setContentType(req.getContentType());
        fo.setSizeBytes(req.getSizeBytes());
        fo.setBizType(StringUtils.hasText(req.getBizType()) ? req.getBizType() : "OTHER");
        fo.setUploaderId(SecurityContexts.requireUserId());
        fo.setVisibility(StringUtils.hasText(req.getVisibility()) ? req.getVisibility() : "PUBLIC");
        fileObjectMapper.insert(fo);
        Map<String, Object> data = new HashMap<>();
        data.put("id", fo.getId());
        data.put("objectKey", fo.getObjectKey());
        data.put("url", objectStorage.publicUrl(fo.getObjectKey()));
        return Result.ok(data);
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
            case "video/mp4" -> ".mp4";
            default -> "";
        };
    }

    @Data
    @Schema(description = "预签名上传请求")
    public static class PresignReq {
        @Schema(description = "业务类型：PRODUCT / AVATAR 等", example = "PRODUCT")
        private String bizType;
        @Schema(description = "文件 MIME 类型", example = "image/jpeg")
        private String contentType;
        @Schema(description = "文件大小（字节）", example = "102400")
        private Long size;
        @Schema(description = "原始文件名", example = "chair.jpg")
        private String filename;
    }

    @Data
    @Schema(description = "上传确认请求")
    public static class ConfirmReq {
        @Schema(description = "对象存储键", example = "product/2026/09/uuid.jpg")
        private String objectKey;
        @Schema(description = "文件 MIME 类型", example = "image/jpeg")
        private String contentType;
        @Schema(description = "文件大小（字节）", example = "102400")
        private Long sizeBytes;
        @Schema(description = "业务类型：PRODUCT / AVATAR 等", example = "PRODUCT")
        private String bizType;
        @Schema(description = "可见性：PUBLIC / PRIVATE", example = "PUBLIC")
        private String visibility;
    }
}
