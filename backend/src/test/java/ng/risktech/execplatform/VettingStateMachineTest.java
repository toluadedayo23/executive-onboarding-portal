package ng.risktech.execplatform;

import ng.risktech.execplatform.common.ApiException;
import ng.risktech.execplatform.vetting.VettingStateMachine;
import ng.risktech.execplatform.vetting.VettingStateMachine.Actor;
import ng.risktech.execplatform.vetting.VettingStatus;
import org.junit.jupiter.api.Test;

import static ng.risktech.execplatform.vetting.VettingStatus.MORE_INFO_REQUIRED;
import static ng.risktech.execplatform.vetting.VettingStatus.REJECTED;
import static ng.risktech.execplatform.vetting.VettingStatus.SUBMITTED;
import static ng.risktech.execplatform.vetting.VettingStatus.UNDER_REVIEW;
import static ng.risktech.execplatform.vetting.VettingStatus.VERIFIED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VettingStateMachineTest {
    @Test
    void allowsExactlyTheSpecifiedTransitions() {
        assertThat(VettingStateMachine.require(SUBMITTED, UNDER_REVIEW, Actor.CONSULTANT).messageRequired()).isFalse();
        assertThat(VettingStateMachine.require(UNDER_REVIEW, MORE_INFO_REQUIRED, Actor.CONSULTANT).messageRequired()).isTrue();
        assertThat(VettingStateMachine.require(UNDER_REVIEW, REJECTED, Actor.CONSULTANT).messageRequired()).isTrue();
        assertThat(VettingStateMachine.require(UNDER_REVIEW, VERIFIED, Actor.CONSULTANT).messageRequired()).isFalse();
        assertThat(VettingStateMachine.require(MORE_INFO_REQUIRED, UNDER_REVIEW, Actor.EXECUTIVE)).isNotNull();
    }

    @Test
    void refusesEverythingElse() {
        assertThatThrownBy(() -> VettingStateMachine.require(SUBMITTED, VERIFIED, Actor.CONSULTANT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> VettingStateMachine.require(MORE_INFO_REQUIRED, UNDER_REVIEW, Actor.CONSULTANT)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> VettingStateMachine.require(UNDER_REVIEW, VERIFIED, Actor.EXECUTIVE)).isInstanceOf(ApiException.class);
    }

    @Test
    void verifiedAndRejectedAreTerminal() {
        for (VettingStatus terminal : new VettingStatus[]{VERIFIED, REJECTED}) {
            for (Actor actor : Actor.values()) assertThat(VettingStateMachine.available(terminal, actor)).isEmpty();
        }
    }
}
