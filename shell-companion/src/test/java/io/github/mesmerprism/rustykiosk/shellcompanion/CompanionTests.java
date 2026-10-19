// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

public final class CompanionTests {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        EnabledReadState read = new EnabledReadState();
        check(!read.pending());
        check(!read.started()); check(!read.callbackReceived());
        read.begin();
        check(read.pending());
        check(read.observeDeadline());
        check(read.pending()); // A pending capability read must still block FINAL/exit after STOP.
        check(!read.observeDeadline());
        boolean duplicateRead = false;
        try { read.begin(); } catch (IllegalStateException expected) { duplicateRead = true; }
        check(duplicateRead);
        check(read.callback()); // Only the genuine late original callback retires the read.
        check(read.started() && read.callbackReceived());
        check(!read.pending());
        check(!read.callback());
        check(!read.observeDeadline());
        String good = "  123.456789  42  43 I Shell   : ShellClayApp: Navigator invocation event: 1";
        check(NativeEvent.parse(good, 42, 120000, 123457, 120000).open);
        check(NativeEvent.parse(good, 41, 120000, 123457, 120000) == null);
        check(NativeEvent.parse(good.replace("I Shell", "D Shell"), 42, 120000, 123457, 120000) == null);
        check(NativeEvent.parse(good, 42, 123456, 123457, 120000) == null);
        check(NativeEvent.parse(good, 42, 120000, 125457, 120000) == null);
        check(NativeEvent.parse(good, 42, 120000, 123455, 120000) == null);
        check(NativeEvent.parse(good, 42, 120000, 123457, 123456) == null);
        check(NativeEvent.parse(good + " trailing", 42, 120000, 123457, 120000) == null);
        CloseState state = new CloseState();
        long first = state.observe(true);
        check(state.claim("a", first));
        state.observe(false); // A false received during provider claim prevents unsent execution.
        check(!state.beforeExecute("a", first)); state.noExecute("a");
        long second = state.observe(true);
        check(state.claim("b", second)); check(state.beforeExecute("b", second));
        state.observe(false); long third = state.observe(true); // Retained while b is pending.
        check(!state.claim("c", third)); state.callback("b");
        check(state.claim("c", third)); check(state.beforeExecute("c", third));
        state.hold("c"); check(!state.claim("d", third)); check(state.pending());
        state.callback("c"); check(!state.pending()); check(!state.claim("d", third));
        CloseState stop = new CloseState(); long edge = stop.observe(true);
        check(stop.claim("e", edge)); stop.stop(); check(!stop.beforeExecute("e", edge));
        stop.noExecute("e"); check(!stop.pending());
        check(TargetIdentity.generation(Long.MIN_VALUE)); check(TargetIdentity.generation(-1));
        check(TargetIdentity.generation(1)); check(!TargetIdentity.generation(0));
        check(TargetIdentity.component("org.example.vr", "android.app.NativeActivity"));
        check(TargetIdentity.component("org.example.vr", "org.example.vr.MainActivity$Inner"));
        check(!TargetIdentity.component("org.example.vr;sh", "android.app.NativeActivity"));
        check(!TargetIdentity.component("org.example.vr", "android.app.NativeActivity;sh"));
        check(!TargetIdentity.component(CompanionIdentity.KIOSK_PACKAGE, "android.app.NativeActivity"));
        check(CompanionIdentity.PROVIDER_AUTHORITY.equals(CompanionIdentity.KIOSK_PACKAGE+".shell-companion"));
        check(CompanionIdentity.COMPONENT.equals(CompanionIdentity.KIOSK_PACKAGE+"/io.github.mesmerprism.rustykiosk.RustyKioskActivity"));
        CloseState escape = new CloseState(); long escaped = escape.observe(true);
        // App-authorized escape is guard scoped: no call to stop() and the next guard can use this adapter.
        escape.observe(false); long rearmed=escape.observe(true);
        check(rearmed>escaped); check(escape.claim("next-guard",rearmed));
        escape.noExecute("next-guard");
        check(ClientFocus.parse("", "org.example.vr", "org.example.vr.Main")==ClientFocus.Value.WAITING);
        String header="ACTIVITY org.example.vr/.Main abc pid=42\n";
        check(ClientFocus.parse(header, "org.example.vr", "org.example.vr.Main")==ClientFocus.Value.WAITING);
        check(ClientFocus.parse(header+"mFinished=false mResumed=true\n", "org.example.vr", "org.example.vr.Main")==ClientFocus.Value.WAITING);
        String body="mFinished=false mResumed=true\nmHasWindowFocus=true\n";
        check(ClientFocus.parse(header+body, "org.example.vr", "org.example.vr.Main")==ClientFocus.Value.FOCUSED);
        check(ClientFocus.parse(header+body.replace("true", "false"), "org.example.vr", "org.example.vr.Main")==ClientFocus.Value.UNFOCUSED);
        check(ClientFocus.parse("ACTIVITY org.example.vr/android.app.NativeActivity abc pid=42\n"+body,
            "org.example.vr", "android.app.NativeActivity")==ClientFocus.Value.FOCUSED);
        boolean rejected=false;
        try { ClientFocus.parse(header+body,"org.other.vr","org.example.vr.Main"); } catch(IllegalStateException e) { rejected=true; }
        check(rejected);
        rejected=false;
        try { ClientFocus.parse(header+body+"mHasWindowFocus=false\n","org.example.vr","org.example.vr.Main"); } catch(IllegalStateException e) { rejected=true; }
        check(rejected);
        String suffix=" userId=0 uid=10045 displayId=0(type=INTERNAL)";
        String modern="  ACTIVITY org.example.vr/.Main 36635f8 pid=28470"+suffix+"\n";
        check(ClientFocus.parse(modern+body,"org.example.vr","org.example.vr.Main")==ClientFocus.Value.FOCUSED);
        check(ClientFocus.parse(modern+body.replace("mHasWindowFocus=true","mHasWindowFocus=false"),
            "org.example.vr","org.example.vr.Main")==ClientFocus.Value.UNFOCUSED);
        check(ClientFocus.parse(modern.replace("/.Main","/org.example.vr.Main")+body,
            "org.example.vr","org.example.vr.Main")==ClientFocus.Value.FOCUSED);
        check(ClientFocus.parse(modern.replace("/.Main","/android.app.NativeActivity")+body,
            "org.example.vr","android.app.NativeActivity")==ClientFocus.Value.FOCUSED);
        check(ClientFocus.parse(modern.replace(" displayId=0(type=INTERNAL)","")+body,
            "org.example.vr","org.example.vr.Main")==ClientFocus.Value.FOCUSED);
        check(ClientFocus.parse(modern.replace("displayId=0(type=INTERNAL)","displayId=12(type=VIRTUAL)")+body,
            "org.example.vr","org.example.vr.Main")==ClientFocus.Value.FOCUSED);
        check(ClientFocus.parse((modern+body).replace("\n","\r\n"),
            "org.example.vr","org.example.vr.Main")==ClientFocus.Value.FOCUSED);
        for(String bad:new String[]{modern.replace("pid=28470","pid=0"),modern.replace("pid=28470","pid=2147483648"),
                modern.replace("userId=0","userId=10"),modern.replace("uid=10045","uid=0"),
                modern.replace("uid=10045","uid=2147483648"),modern.replace("displayId=0","displayId=-1"),
                modern.replace("(type=INTERNAL)","(type=INTERNAL) arbitrary=true"),
                modern.replace("pid=28470","pid=28470 pid=9"),modern.replace("org.example.vr/","org.other.vr/"),
                modern+modern,header+modern.replace("org.example.vr/","org.other.vr/")}) {
            rejected=false;
            try { ClientFocus.parse(bad+body,"org.example.vr","org.example.vr.Main"); }
            catch(IllegalStateException e) { rejected=true; }
            check(rejected);
        }
        String notRunning=modern.replace("pid=28470","pid=(not running)");
        check(ClientFocus.parse(notRunning,"org.example.vr","org.example.vr.Main")==ClientFocus.Value.WAITING);
        check(ClientFocus.parse(notRunning+body,"org.example.vr","org.example.vr.Main")==ClientFocus.Value.WAITING);
        check(ClientFocus.parse(modern+body+notRunning,"org.example.vr","org.example.vr.Main")==ClientFocus.Value.WAITING);
        check(ClientFocus.parse(notRunning+modern+body,"org.example.vr","org.example.vr.Main")==ClientFocus.Value.WAITING);
        check(ClientFocus.parse(notRunning.replace("/.Main","/android.app.NativeActivity")+body,
            "org.example.vr","android.app.NativeActivity")==ClientFocus.Value.WAITING);
        for(String bad:new String[]{notRunning.replace("org.example.vr/","org.other.vr/"),
                notRunning.replace("/.Main","/.Other"),notRunning.replace("userId=0","userId=10"),
                notRunning.replace("uid=10045","uid=0"),notRunning.replace("(type=INTERNAL)","(type=INTERNAL) extra=true"),
                notRunning.replace("(not running)","(unknown)"),
                modern+notRunning.replace("org.example.vr/","org.other.vr/"),
                notRunning+modern.replace("org.example.vr/","org.other.vr/")}) {
            rejected=false;
            try { ClientFocus.parse(bad+body,"org.example.vr","org.example.vr.Main"); }
            catch(IllegalStateException e) { rejected=true; }
            check(rejected);
        }
        System.out.println("PASS " + checks + " companion admission/parser checks");
    }
}
