// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses only one explicit Activity's own window metadata; never view/text trees. */
final class ClientFocus {
    private static final Pattern HEADER_LINE=Pattern.compile("(?m)^[ \\t]*ACTIVITY(?:[ \\t]+[^\\r\\n]*)?\\r?$");
    private static final Pattern HEADER=Pattern.compile("ACTIVITY[ \\t]+(\\S+)[ \\t]+[0-9a-fA-F]{1,16}[ \\t]+pid=([0-9]{1,10}|\\(not running\\))"
        +"(?:[ \\t]+userId=([0-9]{1,10}))?(?:[ \\t]+uid=([0-9]{1,10}))?"
        +"(?:[ \\t]+displayId=([0-9]{1,10})(?:\\(type=[A-Z_]{1,24}\\))?)?[ \\t]*");
    enum Value { WAITING, FOCUSED, UNFOCUSED }
    static Value parse(String text,String pkg,String activity) {
        Matcher lines=HEADER_LINE.matcher(text);
        int count=0;
        boolean notRunning=false;
        while (lines.find()) {
            // Every Activity header must qualify, including suffixed and malformed headers.
            // Otherwise a second/foreign Activity could disappear from the identity count.
            Matcher headers=HEADER.matcher(lines.group().trim());
            if (!headers.matches()) {
                String header=lines.group().trim();
                throw new IllegalStateException("client-activity-header:"+header.substring(0,Math.min(header.length(),384)));
            }
            String[] component=headers.group(1).split("/",-1);
            String cls=component.length==2?component[1]:"";
            if (cls.startsWith(".")) cls=pkg+cls;
            boolean pending="(not running)".equals(headers.group(2));
            if (component.length!=2 || !pkg.equals(component[0]) || !activity.equals(cls)
                    || (!pending && !boundedNumber(headers.group(2),false))
                    || (headers.group(3)!=null && !"0".equals(headers.group(3)))
                    || (headers.group(4)!=null && !boundedNumber(headers.group(4),false))
                    || (headers.group(5)!=null && !boundedNumber(headers.group(5),true)))
                throw new IllegalStateException("client-activity-identity");
            notRunning|=pending;
            count++;
        }
        // A launch/replacement transition may contain matching live and not-running records.
        // Validate all identities first, then await an unambiguous live sample regardless of stale body fields.
        if (notRunning) return Value.WAITING;
        if (count>1) throw new IllegalStateException("multiple-client-activities");
        if (count==0 || !text.contains("mFinished=false")) return Value.WAITING;
        boolean yes=text.contains("mHasWindowFocus=true"), no=text.contains("mHasWindowFocus=false");
        if (yes&&no) throw new IllegalStateException("mixed-client-focus");
        if (!yes&&!no) return Value.WAITING;
        return yes&&text.contains("mResumed=true")?Value.FOCUSED:Value.UNFOCUSED;
    }
    private static boolean boundedNumber(String value,boolean allowZero) {
        long number=Long.parseLong(value);
        return number>=(allowZero?0:1) && number<=Integer.MAX_VALUE;
    }
}
