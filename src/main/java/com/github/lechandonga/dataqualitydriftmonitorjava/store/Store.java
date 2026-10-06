package com.github.lechandonga.dataqualitydriftmonitorjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Baseline;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.BatchVerdict;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.ColumnProfile;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.Contract;
import com.github.lechandonga.dataqualitydriftmonitorjava.model.QualityRules;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 本地 JSON 文件存储：全部能力不依赖外部数据库。
 * 每次变更后原子写入（临时文件 + ATOMIC_MOVE），保证进程重启后历史批次、规则快照与基线仍可复现。
 *
 * <p>并发控制：按数据集名称做分段锁（64 个 stripe），同一数据集的提交串行化，
 * 不同数据集互不阻塞；全局锁仅在不存在的数据集初始化时短暂持有。</p>
 */
@Component
public class Store {

    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private static final int STRIPES = 64;

    public static class State {
        public Map<String, Contract> contracts = new LinkedHashMap<>();
        public Map<String, Map<String, QualityRules>> rulesByDatasetVersion = new LinkedHashMap<>();
        public Map<String, List<BatchVerdict>> verdictsByDataset = new LinkedHashMap<>();
        /** batchId -> 该批列画像（通过的批次才纳入） */
        public Map<String, Map<String, ColumnProfile>> profilesByBatch = new LinkedHashMap<>();
        public Map<String, Baseline> baselines = new LinkedHashMap<>();
    }

    private final ObjectMapper objectMapper;
    private final Path file;
    private final ReentrantLock persistLock = new ReentrantLock();
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    private volatile State state;

    public Store(ObjectMapper objectMapper,
                 @Value("${dq.store.dir:./.dq-data}") String storeDir) {
        this.objectMapper = objectMapper;
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
        this.file = Path.of(storeDir, "dq-state.json");
    }

    @PostConstruct
    public void init() {
        try {
            if (Files.exists(file)) {
                state = objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), State.class);
                log.info("已加载本地数据状态: 契约 {} 个, 批次 {} 个",
                        state.contracts.size(),
                        state.verdictsByDataset.values().stream().mapToInt(List::size).sum());
            } else {
                state = new State();
                Files.createDirectories(file.getParent());
                persist();
                log.info("初始化本地数据状态文件: {}", file.toAbsolutePath());
            }
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化本地存储: " + file, e);
        }
    }

    private ReentrantLock lockFor(String dataset) {
        return stripes[Math.floorMod(dataset.hashCode(), STRIPES)];
    }

    /** 在数据集锁内执行读操作（与同数据集的写互斥，保证读到一致快照）。 */
    public <T> T read(String dataset, Function<State, T> fn) {
        ReentrantLock lock = lockFor(dataset);
        lock.lock();
        try {
            return fn.apply(state);
        } finally {
            lock.unlock();
        }
    }

    /** 在数据集锁内执行变更并原子持久化。多批并发提交时逐批串行落盘，统计结果不会互相覆盖或丢失。 */
    public void mutate(String dataset, Consumer<State> fn) {
        ReentrantLock lock = lockFor(dataset);
        lock.lock();
        try {
            fn.accept(state);
            persist();
        } finally {
            lock.unlock();
        }
    }

    /** 同 {@link #mutate}，但允许在锁内返回计算结果（如首次生成的批次结论）。 */
    public <T> T mutateResult(String dataset, Function<State, T> fn) {
        ReentrantLock lock = lockFor(dataset);
        lock.lock();
        try {
            T result = fn.apply(state);
            persist();
            return result;
        } finally {
            lock.unlock();
        }
    }

    private void persist() {
        persistLock.lock();
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new IllegalStateException("持久化数据状态失败", e);
        } finally {
            persistLock.unlock();
        }
    }

    public List<BatchVerdict> listVerdicts(String dataset) {
        return read(dataset, s -> new ArrayList<>(s.verdictsByDataset.getOrDefault(dataset, List.of())));
    }

    public BatchVerdict findVerdict(String dataset, String batchId) {
        return read(dataset, s -> s.verdictsByDataset.getOrDefault(dataset, List.of()).stream()
                .filter(v -> v.batchId.equals(batchId)).findFirst().orElse(null));
    }

    public BatchVerdict findByContentHash(String dataset, String hash) {
        return read(dataset, s -> s.verdictsByDataset.getOrDefault(dataset, List.of()).stream()
                .filter(v -> v.contentHash.equals(hash)).findFirst().orElse(null));
    }

    public Map<String, Contract> allContracts() {
        return new LinkedHashMap<>(state.contracts);
    }

    public Baseline getBaseline(String dataset) {
        return read(dataset, s -> s.baselines.get(dataset));
    }

    /** 供测试使用：清空全部状态并落盘（全局操作）。 */
    public void resetForTesting() {
        persistLock.lock();
        try {
            state = new State();
            persist();
        } finally {
            persistLock.unlock();
        }
    }
}
