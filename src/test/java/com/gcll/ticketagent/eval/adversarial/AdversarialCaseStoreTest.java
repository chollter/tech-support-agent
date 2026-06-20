package com.gcll.ticketagent.eval.adversarial;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.eval.EvalCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdversarialCaseStore} 的直接测试：覆盖 load 空态、appendAll 去重、
 * 跨实例持久化（重新构造 store 仍能读回）、description 哈希去重等关键路径。
 *
 * <p>不启动 Spring 上下文，纯单元测试，快且不依赖 LLM/DB。
 */
class AdversarialCaseStoreTest {

    @TempDir
    Path tempDir;

    private AdversarialCaseStore newStore() {
        return new AdversarialCaseStore(
                tempDir.resolve("adversarial-cases.json").toString(),
                new ObjectMapper());
    }

    private EvalCase mkCase(String description) {
        return new EvalCase(
                "adv-x", "adversarial", "t", description,
                ReplyType.NEED_MORE_INFO, null, AgentRunStatus.WAIT_USER_INPUT,
                6, false, false, false, false, false, true, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    @Test
    void loadOnMissingFileReturnsEmpty() {
        assertThat(newStore().load()).isEmpty();
    }

    @Test
    void appendAllPersistsAndDedupsByDescription() {
        AdversarialCaseStore store = newStore();
        int added = store.appendAll(List.of(mkCase("线上挂了"), mkCase("支付回调500")));
        assertThat(added).isEqualTo(2);
        assertThat(store.load()).hasSize(2);

        // 重复 description（即使 id 不同）应被去重
        int addedAgain = store.appendAll(List.of(mkCase("线上挂了"), mkCase("新问题")));
        assertThat(addedAgain).isEqualTo(1); // 只有"新问题"是新
        assertThat(store.load()).hasSize(3);
    }

    @Test
    void newStoreInstanceReadsBackSameFile() {
        AdversarialCaseStore writer = newStore();
        writer.appendAll(List.of(mkCase("OOM"), mkCase("DB超时")));

        // 模拟重启：重新构造 store 指向同一路径
        AdversarialCaseStore reader = newStore();
        assertThat(reader.load()).hasSize(2);
        assertThat(reader.load().stream().map(EvalCase::description))
                .containsExactlyInAnyOrder("OOM", "DB超时");
    }

    @Test
    void appendAllEmptyOrNullIsNoop() {
        AdversarialCaseStore store = newStore();
        assertThat(store.appendAll(null)).isZero();
        assertThat(store.appendAll(List.of())).isZero();
        assertThat(store.load()).isEmpty();
    }
}
