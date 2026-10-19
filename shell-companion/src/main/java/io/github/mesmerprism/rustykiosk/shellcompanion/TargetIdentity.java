// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

/** The catalogue owns the explicit package/class pair; Java classes may use another namespace. */
final class TargetIdentity {
    static boolean generation(long value) { return value != 0; }
    static boolean component(String pkg,String activity) {
        return pkg!=null && activity!=null && !CompanionIdentity.KIOSK_PACKAGE.equals(pkg)
            && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
            && activity.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }
}
