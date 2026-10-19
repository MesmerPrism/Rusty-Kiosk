// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

/** Custody of one async capability read. A deadline is an observation, never retirement. */
final class EnabledReadState {
    private boolean started, pending, deadlineObserved, callbackReceived;
    synchronized void begin() {
        if (started) throw new IllegalStateException("capability-read-already-owned");
        started = true; pending = true;
    }
    synchronized boolean callback() {
        if (!pending) return false;
        pending = false;
        callbackReceived = true;
        return true;
    }
    synchronized boolean observeDeadline() {
        if (!pending || deadlineObserved) return false;
        deadlineObserved = true;
        return true;
    }
    synchronized boolean pending() { return pending; }
    synchronized boolean started() { return started; }
    synchronized boolean callbackReceived() { return callbackReceived; }
}
