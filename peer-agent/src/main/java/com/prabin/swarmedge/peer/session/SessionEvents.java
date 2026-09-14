package com.prabin.swarmedge.peer.session;

/**
 * What one session tells its owner while it runs.
 *
 * <p>A single transfer has no use for any of this. A swarm needs all three: two peers'
 * inventories are what rarest-first is computed from, and a chunk that has just verified
 * has to be announced to the other peers before they can ask us for it (blueprint P5-01).
 *
 * <p>Called on the session's event loop, so an implementation shared by several sessions
 * must be thread-safe and must not block.
 */
public interface SessionEvents {

    SessionEvents NONE = new SessionEvents() {

        @Override
        public void remoteInventory(byte[] bitfield) {
        }

        @Override
        public void remoteGained(int chunkIndex) {
        }

        @Override
        public void chunkStored(int chunkIndex) {
        }
    };

    /** The far side sent its BITFIELD. The array belongs to the callee. */
    void remoteInventory(byte[] bitfield);

    /** The far side announced a chunk it did not have at handshake time. */
    void remoteGained(int chunkIndex);

    /** This session verified a chunk into the store, so it is ours to serve now. */
    void chunkStored(int chunkIndex);
}
