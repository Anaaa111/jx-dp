package com.jxdp.controller;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.jxdp.dto.Result;
import com.jxdp.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {
    @Autowired
    AliOssUtil aliOssUtil;
    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 截取原始文件名的后缀
            String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
            // 通过UUID生成新的文件名(防止文件名重复)
            String fileName = UUID.randomUUID().toString(true) + suffix;
            // 通过aliOssUtil将文件上传到云端
            String fileUrl = aliOssUtil.upload(image.getBytes(), fileName);
            // 返回结果
            log.debug("文件上传成功，{}", fileUrl);
            return Result.ok(fileUrl);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        aliOssUtil.delete(filename);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true);
        // 生成目录
        String name = UUID.randomUUID().toString(true);
        // int hash = name.hashCode();
        // int d1 = hash & 0xF;
        // int d2 = (hash >> 4) & 0xF;
        // // 判断目录是否存在
        // File dir = new File(SystemConstants.IMAGE_UPLOAD_DIR, StrUtil.format("/blogs/{}/{}", d1, d2));
        // if (!dir.exists()) {
        //     dir.mkdirs();
        // }
        // 生成文件名
        return StrUtil.format("{}.{}", name, suffix);
    }
}
