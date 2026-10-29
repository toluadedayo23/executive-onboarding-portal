package ng.risktech.execplatform.notification;

import ng.risktech.execplatform.notification.PushSender.Result;
import ng.risktech.execplatform.notification.PushSender.Target;
import ng.risktech.execplatform.vetting.VettingStatus;
import ng.risktech.execplatform.vetting.VettingStatusChangedEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VettingPushNotifierTest {
    final UUID executive = UUID.randomUUID();
    final Target gone = new Target(UUID.randomUUID(), "https://fcm.googleapis.com/a", "k", "a");
    final Target failing = new Target(UUID.randomUUID(), "https://fcm.googleapis.com/b", "k", "a");
    final Target fine = new Target(UUID.randomUUID(), "https://fcm.googleapis.com/c", "k", "a");
    final PushSubscriptionRepository repository = mock(PushSubscriptionRepository.class);
    final FakeSender sender = new FakeSender(Map.of(gone.id(), Result.GONE, failing.id(), Result.FAILED, fine.id(), Result.OK));
    final VettingPushNotifier notifier = new VettingPushNotifier(repository, sender, new PushConfig.PushStatus(true, "key"));

    @Test
    void sendsTheFixedGenericPayloadAndDeletesOnlyGoneSubscriptions() {
        when(repository.findForActiveUser(executive)).thenReturn(List.of(gone, failing, fine));

        notifier.onStatusChanged(new VettingStatusChangedEvent(executive, VettingStatus.VERIFIED));

        assertThat(sender.sentTo).containsExactly(gone.id(), failing.id(), fine.id());
        assertThat(sender.payloads).allSatisfy(p -> assertThat(new String(p, StandardCharsets.UTF_8)).isEqualTo(
                "{\"title\":\"RiskTech & Advisory\",\"body\":\"Your vetting status has been updated\",\"url\":\"/\",\"tag\":\"vetting-status\"}"));
        verify(repository).deleteById(gone.id());
        verify(repository, never()).deleteById(failing.id());
        verify(repository, never()).deleteById(fine.id());
    }

    @Test
    void notifiesOnlyWhenTheExecutiveHasToActOrThereIsAnOutcome() {
        when(repository.findForActiveUser(executive)).thenReturn(List.of(fine));

        notifier.onStatusChanged(new VettingStatusChangedEvent(executive, VettingStatus.SUBMITTED));
        notifier.onStatusChanged(new VettingStatusChangedEvent(executive, VettingStatus.UNDER_REVIEW));
        assertThat(sender.sentTo).isEmpty();

        for (VettingStatus s : List.of(VettingStatus.MORE_INFO_REQUIRED, VettingStatus.VERIFIED, VettingStatus.REJECTED)) {
            notifier.onStatusChanged(new VettingStatusChangedEvent(executive, s));
        }
        assertThat(sender.sentTo).hasSize(3);
    }

    @Test
    void sendsNothingWhenTheUserHasNoActiveSubscriptions() {
        when(repository.findForActiveUser(any())).thenReturn(List.of());
        notifier.onStatusChanged(new VettingStatusChangedEvent(executive, VettingStatus.REJECTED));
        assertThat(sender.sentTo).isEmpty();
    }

    static class FakeSender implements PushSender {
        final Map<UUID, Result> results;
        final List<UUID> sentTo = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();

        FakeSender(Map<UUID, Result> results) {
            this.results = results;
        }

        @Override
        public Result send(Target target, byte[] payload) {
            sentTo.add(target.id());
            payloads.add(payload);
            return results.get(target.id());
        }
    }
}
