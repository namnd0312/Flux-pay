package com.fluxpay.gateway.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the transition table: for every (from, to) pair across the full enum product, the
 * machine must allow exactly the moves this independent expected table lists. Keeping a
 * second copy of the rules here means a change to the production table that is not
 * mirrored in the spec (or vice versa) fails the test on purpose.
 */
class PaymentStateMachineTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = new EnumMap<>(PaymentStatus.class);

    static {
        EXPECTED.put(PaymentStatus.CREATED, Set.of(PaymentStatus.PENDING, PaymentStatus.FAILED));
        EXPECTED.put(PaymentStatus.PENDING,
                Set.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.EXPIRED, PaymentStatus.CANCELLED));
        EXPECTED.put(PaymentStatus.SUCCEEDED, Set.of());
        EXPECTED.put(PaymentStatus.FAILED, Set.of());
        EXPECTED.put(PaymentStatus.EXPIRED, Set.of());
        EXPECTED.put(PaymentStatus.CANCELLED, Set.of());
    }

    static Stream<Arguments> allPairs() {
        return Stream.of(PaymentStatus.values())
                .flatMap(from -> Stream.of(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void tableMatchesSpec(PaymentStatus from, PaymentStatus to) {
        boolean expected = EXPECTED.getOrDefault(from, Set.of()).contains(to);
        assertThat(PaymentStateMachine.isAllowed(from, to)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "terminal {0} has no moves")
    @org.junit.jupiter.params.provider.EnumSource(
            value = PaymentStatus.class, names = {"SUCCEEDED", "FAILED", "EXPIRED", "CANCELLED"})
    void terminalStatesAreTerminal(PaymentStatus terminal) {
        for (PaymentStatus to : PaymentStatus.values()) {
            assertThat(PaymentStateMachine.isAllowed(terminal, to)).isFalse();
        }
    }
}
