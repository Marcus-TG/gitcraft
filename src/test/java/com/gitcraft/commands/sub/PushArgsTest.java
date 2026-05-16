package com.gitcraft.commands.sub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PushArgsTest {

    @Test
    void noArgs() {
        PushSubcommand.PushArgs a = PushSubcommand.PushArgs.parse(new String[]{});
        assertEquals("origin", a.remoteName());
        assertFalse(a.force());
    }

    @Test
    void remoteOnly() {
        PushSubcommand.PushArgs a = PushSubcommand.PushArgs.parse(new String[]{"upstream"});
        assertEquals("upstream", a.remoteName());
        assertFalse(a.force());
    }

    @Test
    void forceOnly() {
        PushSubcommand.PushArgs a = PushSubcommand.PushArgs.parse(new String[]{"--force"});
        assertEquals("origin", a.remoteName());
        assertTrue(a.force());
    }

    @Test
    void remoteBeforeForce() {
        PushSubcommand.PushArgs a = PushSubcommand.PushArgs.parse(new String[]{"upstream", "--force"});
        assertEquals("upstream", a.remoteName());
        assertTrue(a.force());
    }

    @Test
    void forceBeforeRemote() {
        PushSubcommand.PushArgs a = PushSubcommand.PushArgs.parse(new String[]{"--force", "upstream"});
        assertEquals("upstream", a.remoteName());
        assertTrue(a.force());
    }

    @Test
    void twoRemotes() {
        assertNull(PushSubcommand.PushArgs.parse(new String[]{"a", "b"}));
    }

    @Test
    void duplicateForce() {
        assertNull(PushSubcommand.PushArgs.parse(new String[]{"--force", "--force"}));
    }
}
