// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

/** Local custody only. Target, mode, Home counting and command admission belong to the app. */
final class CloseState {
    long edge;
    boolean nativeOpen, stopping;
    String original;
    boolean attempted, unknown;
    synchronized long observe(boolean open) {
        if (nativeOpen != open) edge++;
        nativeOpen = open;
        return edge;
    }
    synchronized boolean eligible(long expectedEdge) {
        return !stopping && nativeOpen && edge == expectedEdge && original == null;
    }
    synchronized boolean claim(String id, long expectedEdge) {
        if (!eligible(expectedEdge)) return false;
        original = id; attempted = false; unknown = false;
        return true;
    }
    synchronized boolean beforeExecute(String id, long expectedEdge) {
        if (!id.equals(original) || stopping || !nativeOpen || edge != expectedEdge || attempted) return false;
        attempted = true;
        return true;
    }
    synchronized void noExecute(String id) {
        if (!id.equals(original) || attempted) throw new IllegalStateException("not-unsent-original");
        original = null;
    }
    synchronized void callback(String id) {
        if (!id.equals(original) || !attempted) throw new IllegalStateException("not-original-callback");
        original = null; unknown = false;
    }
    synchronized void hold(String id) {
        if (!id.equals(original) || !attempted) throw new IllegalStateException("not-attempted-original");
        unknown = true; stopping = true;
    }
    synchronized void stop() { stopping = true; }
    synchronized boolean pending() { return original != null; }
}
