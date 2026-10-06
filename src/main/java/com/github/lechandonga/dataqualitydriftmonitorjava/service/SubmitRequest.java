package com.github.lechandonga.dataqualitydriftmonitorjava.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;

/**
 * 批次提交请求。
 *
 * @param dataset    数据集名称；对应已注册契约名
 * @param format     csv 或 jsonl
 * @param encoding   文本编码，默认 UTF-8；解码异常会被显式拒绝
 * @param contentBase64 Base64 编码的批次内容（可携带任意畸形字节以测试编码异常）；
 *                      与 content 二选一
 * @param content    直接以 JSON 字符串给出的批次内容（UTF-8）；与 contentBase64 二选一
 * @param rulesVersion 指定使用的规则版本；缺省使用数据集当前规则版本
 * @param inlineContract 随请求声明契约（首次使用或隔离测试时不必预先注册）
 * @param inlineRules    随请求声明规则集（version 必填，会注册并快照）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitRequest(
        String dataset,
        String format,
        String encoding,
        String contentBase64,
        String content,
        String rulesVersion,
        Contract inlineContract,
        QualityRules inlineRules
) {
}
