package ng.risktech.execplatform.vetting;

import ng.risktech.execplatform.common.ApiException;

import java.util.List;

import static ng.risktech.execplatform.vetting.VettingStatus.MORE_INFO_REQUIRED;
import static ng.risktech.execplatform.vetting.VettingStatus.REJECTED;
import static ng.risktech.execplatform.vetting.VettingStatus.SUBMITTED;
import static ng.risktech.execplatform.vetting.VettingStatus.UNDER_REVIEW;
import static ng.risktech.execplatform.vetting.VettingStatus.VERIFIED;

public final class VettingStateMachine {
    public enum Actor { EXECUTIVE, CONSULTANT }

    public record Rule(VettingStatus from, VettingStatus to, Actor actor, boolean messageRequired) {
    }

    static final List<Rule> RULES = List.of(
            new Rule(SUBMITTED, UNDER_REVIEW, Actor.CONSULTANT, false),
            new Rule(UNDER_REVIEW, MORE_INFO_REQUIRED, Actor.CONSULTANT, true),
            new Rule(UNDER_REVIEW, VERIFIED, Actor.CONSULTANT, false),
            new Rule(UNDER_REVIEW, REJECTED, Actor.CONSULTANT, true),
            new Rule(MORE_INFO_REQUIRED, UNDER_REVIEW, Actor.EXECUTIVE, false)
    );

    private VettingStateMachine() {
    }

    public static Rule require(VettingStatus from, VettingStatus to, Actor actor) {
        return RULES.stream()
                .filter(r -> r.from() == from && r.to() == to && r.actor() == actor)
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("INVALID_TRANSITION",
                        "This case cannot move from " + from + " to " + to));
    }

    public static List<Rule> available(VettingStatus from, Actor actor) {
        return RULES.stream().filter(r -> r.from() == from && r.actor() == actor).toList();
    }
}
