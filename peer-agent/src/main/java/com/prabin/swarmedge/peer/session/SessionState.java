package com.prabin.swarmedge.peer.session;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of one peer-to-peer connection (blueprint §7.5).
 *
 * <p>The machine exists so that a frame arriving out of order is a state error rather
 * than something a handler has to guess about: a BLOCK before the handshake finished,
 * or a second HELLO on a live session, both land on {@link #CLOSED}.
 *
 * <p>Every state can go straight to {@link #CLOSED}, which is the fail-closed rule.
 * Any authentication, signature, or protocol failure drops the connection instead of
 * degrading to a weaker mode.
 */
public enum SessionState {

    /** No socket yet. */
    DISCONNECTED,

    /** TCP is up, nothing has been said. Only a handshake frame is legal here. */
    TCP_CONNECTED,

    /** We dialled out and sent HELLO; we are waiting for HELLO_ACK. */
    HELLO_SENT,

    /** We accepted a connection and read the remote HELLO; we owe a HELLO_ACK. */
    HELLO_RECEIVED,

    /** Asset, peer identity, and token were accepted. Inventory may now be exchanged. */
    AUTHENTICATED,

    /** Both sides have sent BITFIELD, so each knows what the other can serve. */
    BITFIELD_EXCHANGED,

    /** REQUEST, BLOCK, HAVE, and CANCEL are legal. This is the only transfer state. */
    ACTIVE,

    /** Closing politely: in-flight requests may finish, new ones are refused. */
    DRAINING,

    /** Terminal. The channel is closed and late frames are discarded. */
    CLOSED;

    private static final Map<SessionState, Set<SessionState>> LEGAL = legalTransitions();

    /**
     * True when the session may issue or serve new REQUESTs. Draining deliberately
     * returns false: work already in flight can land, but nothing new is admitted.
     */
    public boolean transfersData() {
        return this == ACTIVE;
    }

    public boolean isTerminal() {
        return this == CLOSED;
    }

    public boolean canTransitionTo(SessionState next) {
        return LEGAL.get(this).contains(next);
    }

    /** Legal successors, for tests and diagnostics. */
    public Set<SessionState> successors() {
        return LEGAL.get(this);
    }

    private static Map<SessionState, Set<SessionState>> legalTransitions() {
        Map<SessionState, Set<SessionState>> map = new EnumMap<>(SessionState.class);
        map.put(DISCONNECTED, EnumSet.of(TCP_CONNECTED));
        // The first frame decides the role: we either sent HELLO or we read one.
        map.put(TCP_CONNECTED, EnumSet.of(HELLO_SENT, HELLO_RECEIVED));
        map.put(HELLO_SENT, EnumSet.of(AUTHENTICATED));
        map.put(HELLO_RECEIVED, EnumSet.of(AUTHENTICATED));
        map.put(AUTHENTICATED, EnumSet.of(BITFIELD_EXCHANGED));
        map.put(BITFIELD_EXCHANGED, EnumSet.of(ACTIVE));
        map.put(ACTIVE, EnumSet.of(DRAINING));
        map.put(DRAINING, EnumSet.noneOf(SessionState.class));
        map.put(CLOSED, EnumSet.noneOf(SessionState.class));

        // Fail closed from anywhere, and let close() be idempotent.
        for (SessionState state : values()) {
            Set<SessionState> next = EnumSet.copyOf(map.get(state));
            next.add(CLOSED);
            map.put(state, Set.copyOf(next));
        }
        return Map.copyOf(map);
    }
}
