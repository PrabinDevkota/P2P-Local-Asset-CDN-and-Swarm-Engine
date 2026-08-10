package com.prabin.swarm_node;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SwarmNodeTest {

    @Test
    void mainStartsWithoutError() {
        assertDoesNotThrow(() -> SwarmNode.main(new String[]{}));
    }
}
