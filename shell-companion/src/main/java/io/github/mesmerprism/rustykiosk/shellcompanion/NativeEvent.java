// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict logcat monotonic-time parser. No wall-clock conversion or presentation inference. */
final class NativeEvent {
    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\.(\\d{3,9})\\s+(\\d+)\\s+\\d+\\s+I\\s+Shell\\s*:\\s+ShellClayApp: Navigator invocation event: ([01])\\s*$");
    final long uptimeMs;
    final boolean open;
    NativeEvent(long time, boolean value) { uptimeMs = time; open = value; }
    static NativeEvent parse(String line, int producerPid, long anchor, long observed, long previous) {
        Matcher m = LINE.matcher(line);
        if (!m.matches()) return null;
        try {
            long time = Math.addExact(Math.multiplyExact(Long.parseLong(m.group(1)), 1000),
                    Long.parseLong(m.group(2).substring(0, 3)));
            if (Integer.parseInt(m.group(3)) != producerPid || time <= anchor || time <= previous
                    || time > observed || observed - time > 2000) return null;
            return new NativeEvent(time, m.group(4).equals("1"));
        } catch (ArithmeticException | NumberFormatException e) { return null; }
    }
}
