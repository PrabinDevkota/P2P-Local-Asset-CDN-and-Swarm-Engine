package com.prabin.swarmedge.peer.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionStateTest {

    @Test
    void theDiallingSideWalksHelloSentToActive() {
        assertLegalPath(List.of(
                SessionState.DISCONNECTED,
                SessionState.TCP_CONNECTED,
                SessionState.HELLO_SENT,
                SessionState.AUTHENTICATED,
                SessionState.BITFIELD_EXCHANGED,
                SessionState.ACTIVE,
                SessionState.DRAINING,
                SessionState.CLOSED));
    }

    @Test
    void theAcceptingSideWalksHelloReceivedToActive() {
        assertLegalPath(List.of(
                SessionState.DISCONNECTED,
                SessionState.TCP_CONNECTED,
                SessionState.HELLO_RECEIVED,
                SessionState.AUTHENTICATED,
                SessionState.BITFIELD_EXCHANGED,
                SessionState.ACTIVE,
                SessionState.DRAINING,
                SessionState.CLOSED));
    }

    @Test
    void everyStateCanFailClosed() {
        for (SessionState state : SessionState.values()) {
            assertThat(state.canTransitionTo(SessionState.CLOSED))
                    .as("%s must be able to fail closed", state)
                    .isTrue();
        }
    }

    @Test
    void closedIsTerminalExceptForRepeatedClose() {
        for (SessionState state : SessionState.values()) {
            boolean legal = SessionState.CLOSED.canTransitionTo(state);
            assertThat(legal)
                    .as("CLOSED -> %s", state)
                    .isEqualTo(state == SessionState.CLOSED);
        }
        assertThat(SessionState.CLOSED.isTerminal()).isTrue();
    }

    @Test
    void aPeerCannotSkipTheHandshakeAndStartTransferring() {
        assertThat(SessionState.TCP_CONNECTED.canTransitionTo(SessionState.ACTIVE)).isFalse();
        assertThat(SessionState.TCP_CONNECTED.canTransitionTo(SessionState.AUTHENTICATED)).isFalse();
        assertThat(SessionState.HELLO_SENT.canTransitionTo(SessionState.ACTIVE)).isFalse();
        assertThat(SessionState.AUTHENTICATED.canTransitionTo(SessionState.ACTIVE)).isFalse();
    }

    @Test
    void aLiveSessionCannotBeRehandshaked() {
        assertThat(SessionState.ACTIVE.canTransitionTo(SessionState.HELLO_RECEIVED)).isFalse();
        assertThat(SessionState.ACTIVE.canTransitionTo(SessionState.AUTHENTICATED)).isFalse();
        assertThat(SessionState.BITFIELD_EXCHANGED.canTransitionTo(SessionState.BITFIELD_EXCHANGED)).isFalse();
    }

    @Test
    void aSideCannotBeBothDiallerAndAcceptor() {
        assertThat(SessionState.HELLO_SENT.canTransitionTo(SessionState.HELLO_RECEIVED)).isFalse();
        assertThat(SessionState.HELLO_RECEIVED.canTransitionTo(SessionState.HELLO_SENT)).isFalse();
    }

    @Test
    void drainingStopsAdmittingWorkAndOnlyActiveTransfers() {
        for (SessionState state : SessionState.values()) {
            assertThat(state.transfersData())
                    .as("%s transfersData", state)
                    .isEqualTo(state == SessionState.ACTIVE);
        }
        assertThat(SessionState.DRAINING.successors()).containsExactly(SessionState.CLOSED);
    }

    @Test
    void theTransitionTableCannotBeEditedByCallers() {
        assertThatThrownBy(() -> SessionState.ACTIVE.successors().add(SessionState.HELLO_SENT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static void assertLegalPath(List<SessionState> path) {
        for (int i = 0; i < path.size() - 1; i++) {
            SessionState from = path.get(i);
            SessionState to = path.get(i + 1);
            assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isTrue();
        }
    }
}
