package com.github.lechandonga.dataqualitydriftmonitorjava.parser;

import com.github.lechandonga.dataqualitydriftmonitorjava.model.StructuralIssue;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.util.ArrayList;
import java.util.List;

/**
 * 严格解码：任何畸形字节或不可映射字符都会产生可定位行号的 ENCODING_ERROR，
 * 绝不静默替换成问号或 U+FFFD。解码失败时使用 REPORT 策略记录首个错误位置，
 * 并用替换模式得到可读文本以便继续定位其它问题。
 */
public final class EncodingDecoder {

    private EncodingDecoder() {
    }

    public record DecodeResult(String text, List<StructuralIssue> issues) {
    }

    public static DecodeResult decode(byte[] bytes, String charsetName) {
        Charset charset;
        List<StructuralIssue> issues = new ArrayList<>();
        try {
            charset = charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        } catch (Exception e) {
            issues.add(new StructuralIssue("ENCODING_ERROR",
                    "不支持的字符集: " + charsetName, 1));
            charset = StandardCharsets.UTF_8;
        }

        CharsetDecoder strict = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return new DecodeResult(strict.decode(ByteBuffer.wrap(bytes)).toString(), issues);
        } catch (java.nio.charset.CharacterCodingException e) {
            int approxLine = 1;
            String text;
            try {
                text = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (java.nio.charset.CharacterCodingException ce) {
                text = "";
            }
            int idx = text.indexOf('\uFFFD');
            if (idx >= 0) {
                for (int i = 0; i < idx; i++) {
                    if (text.charAt(i) == '\n') {
                        approxLine++;
                    }
                }
            }
            issues.add(new StructuralIssue("ENCODING_ERROR",
                    "字节内容不符合 " + charset.name() + " 编码（畸形或不可映射字符）", approxLine));
            return new DecodeResult(text, issues);
        }
    }
}
