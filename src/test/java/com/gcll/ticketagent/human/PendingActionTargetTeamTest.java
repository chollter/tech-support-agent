package com.gcll.ticketagent.human;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingActionTargetTeamTest {

    @Test
    void sixArgConstructorStoresTargetTeam() {
        PendingAction action = new PendingAction(
                "a-1", "run-1", PendingActionType.DISPATCH, "payload", "reason", "支付研发组");
        assertThat(action.getTargetTeam()).isEqualTo("支付研发组");
    }

    @Test
    void fiveArgConstructorDefaultsTargetTeamToNull() {
        PendingAction action = new PendingAction("a-2", "run-1", PendingActionType.ESCALATE, "p", "r");
        assertThat(action.getTargetTeam()).isNull();
    }
}
