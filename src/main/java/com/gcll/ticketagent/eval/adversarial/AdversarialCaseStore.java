package com.gcll.ticketagent.eval.adversarial;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.eval.EvalCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 对抗 case 的可变文件存储：{@code data/eval/adversarial-cases.json}。
 *
 * <p>与只读的 golden 套件 {@code eval/eval-cases.json} 完全隔离——golden 套件由
 * {@code EvalRunner} 从 classpath 只读加载；本类负责运行时生成的对抗 case 的读写。
 *
 * <p>去重以 {@code description} 的 SHA-1 为键（对抗 case 的"内容"就是用户输入文本，
 * description 相同即视为重复输入）。{@link #appendAll(List)} 采用"读现态→去重合并→
 * 写临时文件→原子 rename"以保证并发/中断下不会产生半截文件。
 *
 * <p>任何 IO/解析异常都降级为"返回空/跳过本次写入"并记录 warn，绝不抛出——红队是发现工具，
 * 存储失败不应阻断主流程。
 */
@Component
public class AdversarialCaseStore {

    private static final Logger log = LoggerFactory.getLogger(AdversarialCaseStore.class);

    private final Path outputPath;
    private final ObjectMapper objectMapper;

    public AdversarialCaseStore(
            @Value("${opsmind.eval.adversarial.output-path:data/eval/adversarial-cases.json}") String outputPath,
            ObjectMapper objectMapper
    ) {
        this.outputPath = Path.of(outputPath);
        this.objectMapper = objectMapper;
    }

    /**
     * 加载已沉淀的对抗 case；文件不存在或解析失败时返回空 List（graceful）。
     */
    public List<EvalCase> load() {
        if (!Files.exists(outputPath)) {
            return List.of();
        }
        try {
            List<EvalCase> cases = objectMapper.readValue(
                    outputPath.toFile(),
                    new TypeReference<List<EvalCase>>() {
                    }
            );
            return cases == null ? List.of() : cases;
        } catch (IOException ex) {
            log.warn("Failed to read adversarial cases from {}, treating as empty: {}",
                    outputPath, ex.getMessage());
            return List.of();
        }
    }

    /**
     * 把新 case 追加进存储，按 description SHA-1 去重后原子写回。
     *
     * @param newCases 待沉淀的 case（通常来自裁判判定 fail 的输入）
     * @return 实际新增（写入）的 case 数量；已存在的重复项不计入
     */
    public int appendAll(List<EvalCase> newCases) {
        if (newCases == null || newCases.isEmpty()) {
            return 0;
        }
        List<EvalCase> existing = load();
        Set<String> seen = new HashSet<>();
        for (EvalCase c : existing) {
            seen.add(descriptionHash(c.description()));
        }
        List<EvalCase> merged = new ArrayList<>(existing);
        int added = 0;
        for (EvalCase c : newCases) {
            String hash = descriptionHash(c.description());
            if (seen.add(hash)) {
                merged.add(c);
                added++;
            }
        }
        if (added == 0) {
            return 0;
        }
        try {
            writeAtomic(merged);
        } catch (IOException ex) {
            log.warn("Failed to persist {} adversarial cases to {}: {}", added, outputPath, ex.getMessage());
            return 0;
        }
        return added;
    }

    private void writeAtomic(List<EvalCase> cases) throws IOException {
        Path parent = outputPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), cases);
        Files.move(tmp, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String descriptionHash(String description) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest((description == null ? "" : description).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            // SHA-1 总是可用的；兜底用原文本（极端情况下可能多写一条，但不会丢数据）
            return description == null ? "" : description;
        }
    }
}
