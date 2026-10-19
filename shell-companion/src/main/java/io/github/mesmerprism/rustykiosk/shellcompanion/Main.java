// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.mesmerprism.rustykiosk.shellcompanion;

import android.app.Application;
import android.app.appsearch.GenericDocument;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OutcomeReceiver;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import io.github.mesmerprism.rustyquest.shellrecenter.ShellApplicationContext;

/** Optional, real-UID-2000 adapter. No input hooks, settings, identity writes or generic RPC. */
public final class Main {
    static final String BASE = "/data/local/tmp/rusty-kiosk-shell-companion";
    static final String META = "com.oculus.vrshell";
    static final String FUNCTION = "com.oculus.vrshell.appfunctions.ShellAppFunctions#openNavigator";
    static final long SESSION_MS = 8 * 60 * 60 * 1000L;
    static final int MAX_CALLS = 600;
    final String session, boot;
    final File directory, active;
    final CloseState state = new CloseState();
    final EnabledReadState enabledRead = new EnabledReadState();
    final ExecutorService ipc = Executors.newSingleThreadExecutor(r -> new Thread(r, "KioskCompanionProtocol"));
    final ExecutorService samples = Executors.newSingleThreadExecutor(r -> new Thread(r, "KioskCompanionFocus"));
    final AtomicInteger protocolJobs=new AtomicInteger(), sampleJobs=new AtomicInteger();
    Handler main;
    Application app;
    ExternalProvider provider;
    Object manager, request;
    Method execute, resultDocument;
    java.lang.Process reader, escape;
    Thread readerThread;
    int producerPid, readerPid, calls;
    String producerBirth, readerBirth;
    long anchor, sequence, previousEvent = -1, generation, qualifiedGeneration, expires;
    long lastPoll, lastIdentity, lastSample;
    long capabilityDispatch;
    Boolean lastFocused;
    boolean ready, registered, stopped, finished, outputHeld, readerEof, sampling, escapeKnown = true;
    boolean pollBusy, registrationBusy, withdrawalStarted, withdrawn;
    int retirements;
    boolean claimBusy;
    Candidate candidate;
    Original original;
    String targetPackage, targetActivity, mode;
    static final class Candidate {
        final String id; final long generation, edge, due, event;
        Candidate(String i, long g, long e, long d, long t) { id=i; generation=g; edge=e; due=d; event=t; }
    }
    static final class Original {
        final Candidate command; long dispatch; boolean callback, reportedUnknown;
        Original(Candidate c) { command=c; }
    }
    Main(String id) throws Exception {
        canonicalUuid(id); session=id; boot=read("/proc/sys/kernel/random/boot_id").trim(); canonicalUuid(boot);
        directory = new File(BASE, session); active = new File(BASE, "ACTIVE");
        if (!directory.isDirectory() || !new File(directory, "state").mkdir()) throw new IllegalStateException("fresh-own-session-required");
        if (!active.mkdir()) throw new IllegalStateException("companion-already-owned");
        writeNew(new File(active, "owner.json"), json("session",session).put("boot",boot)
                .put("pid",android.os.Process.myPid()).put("start_ticks",birth(android.os.Process.myPid()))
                .put("source_id",CompanionIdentity.SOURCE_ID).put("product_channel",CompanionIdentity.PRODUCT_CHANNEL));
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !"--session".equals(args[0])) throw new IllegalArgumentException("only-session-uuid");
        canonicalUuid(args[1]);
        Main own;
        try { own=new Main(args[1]); }
        catch(Throwable e) {
            File stage=new File(BASE,args[1]);
            if (stage.isDirectory()) writeNew(new File(stage,"FAIL.json"),json("session",args[1])
                .put("pid",android.os.Process.myPid()).put("error",e.getClass().getName()).put("api_attempted",false));
            throw e;
        }
        if (Looper.getMainLooper()==null) Looper.prepareMainLooper();
        own.main=new Handler(Looper.getMainLooper());
        try { JSONObject identity=new JSONObject(); own.app=ShellApplicationContext.create(identity); own.initialize(identity); }
        catch(Throwable e) { own.fail("startup",e); }
        while (!own.finished) { try { Looper.loop(); } catch(Throwable e) { own.fail("main-callback",e); } }
    }
    void initialize(JSONObject identity) throws Exception {
        expires=SystemClock.uptimeMillis()+SESSION_MS;
        event("IDENTITY",identity);
        provider=new ExternalProvider(app.getAttributionSource());
        if (app.checkSelfPermission("android.permission.EXECUTE_APP_FUNCTIONS") != 0) throw new SecurityException("execute-permission-unavailable");
        Class<?> mt=Class.forName("android.app.appfunctions.AppFunctionManager");
        Class<?> rt=Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest");
        Class<?> bt=Class.forName("android.app.appfunctions.ExecuteAppFunctionRequest$Builder");
        Class<?> response=Class.forName("android.app.appfunctions.ExecuteAppFunctionResponse");
        manager=app.getSystemService(mt);
        if (manager==null) throw new IllegalStateException("optional-manager-unavailable");
        execute=mt.getMethod("executeAppFunction",rt,Executor.class,CancellationSignal.class,OutcomeReceiver.class);
        resultDocument=response.getMethod("getResultDocument");
        request=bt.getMethod("build").invoke(bt.getConstructor(String.class,String.class).newInstance(META,FUNCTION));
        Method enabled=mt.getMethod("isAppFunctionEnabled",String.class,String.class,Executor.class,OutcomeReceiver.class);
        tick();
        capabilityDispatch=SystemClock.uptimeMillis();
        writeNew(new File(directory,"state/CAPABILITY-INTENT.json"),capabilityRecord()
            .put("dispatch_uptime_ms",capabilityDispatch));
        enabledRead.begin();
        main.postDelayed(() -> {
            if (enabledRead.observeDeadline()) event("CAPABILITY_READ_PENDING",json("original_owned",true));
        },15000);
        enabled.invoke(manager,FUNCTION,META,(Executor)(r -> main.post(r)),new OutcomeReceiver<Object,Throwable>() {
                public void onResult(Object value) { try {
                    if (!enabledRead.callback()) return;
                    if (!recordEnabledCallback(value,null)) return;
                    if (stopped) { finishIfKnown(); return; }
                    if (!Boolean.TRUE.equals(value)) throw new IllegalStateException("fixed-function-not-enabled");
                    startNativeReader(); ready=true; registrationBusy=true;
                    protocol(() -> { try { Bundle reply=call("register", extras().putBooleanValue("ready",true));
                        if (!"registered".equals(reply.getString("status"))) throw new IllegalStateException("provider-register-rejected");
                        main.post(() -> { registrationBusy=false; registered=true;
                            if (stopped) { stop("stopped-during-register"); return; }
                            try {
                                if (!reader.isAlive()) throw new IllegalStateException("reader-not-alive-at-ready");
                                writeNew(new File(directory,"READY.json"),json("session",session).put("boot",boot)
                                    .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid())
                                    .put("start_ticks",birth(android.os.Process.myPid())).put("uptime_ms",SystemClock.uptimeMillis())
                                    .put("api_enabled",true).put("provider_registered",true).put("native_reader_alive",true)
                                    .put("product_channel",CompanionIdentity.PRODUCT_CHANNEL).put("source_id",CompanionIdentity.SOURCE_ID)
                                    .put("reader_pid",readerPid).put("reader_start_ticks",readerBirth)
                                    .put("producer_pid",producerPid).put("producer_start_ticks",producerBirth));
                            } catch(Throwable e) { fail("ready-publication",e); return; }
                            event("READY",json("producer_pid",producerPid)
                                .put("producer_start_ticks",producerBirth).put("function",FUNCTION)); });
                    } catch(Throwable e) { main.post(() -> { registrationBusy=false; fail("register",e); }); } });
                } catch(Throwable e) { fail("capability",e); } }
                public void onError(Throwable error) {
                    if (enabledRead.callback() && recordEnabledCallback(null,error)) fail("capability",error);
                }
            });
    }
    Json capabilityRecord() {
        return json("schema","rusty.kiosk.shell_companion.capability.v1").put("session",session)
            .put("boot",boot).put("pid",android.os.Process.myPid()).put("function",FUNCTION).put("query","is-enabled");
    }
    boolean recordEnabledCallback(Object value,Throwable error) {
        try {
            writeNew(new File(directory,"state/CAPABILITY-RESULT.json"),capabilityRecord()
                .put("dispatch_uptime_ms",capabilityDispatch).put("callback_uptime_ms",SystemClock.uptimeMillis())
                .put("enabled",value instanceof Boolean?value:JSONObject.NULL)
                .put("error",error!=null?error.getClass().getName():(value instanceof Boolean?JSONObject.NULL:"local-enabled-result-decode")));
            return true;
        } catch(Throwable failure) { outputHeld=true; fail("capability-callback-publication",failure); return false; }
    }
    void startNativeReader() throws Exception {
        producerPid=Integer.parseInt(command("/system/bin/pidof",META).trim());
        producerBirth=birth(producerPid); verifyProducer(); anchor=SystemClock.uptimeMillis(); lastIdentity=anchor;
        reader=new ProcessBuilder("/system/bin/logcat","-v","threadtime","-v","monotonic","-T","1",
                "--pid",Integer.toString(producerPid),"Shell:I","*:S").redirectErrorStream(true).start();
        readerThread=new Thread(() -> {
            try (BufferedReader lines=new BufferedReader(new InputStreamReader(reader.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while ((line=lines.readLine())!=null) {
                    if (line.length()>2048) continue;
                    long observed=SystemClock.uptimeMillis();
                    NativeEvent value=NativeEvent.parse(line,producerPid,anchor,observed,previousEvent);
                    if (value==null) continue;
                    previousEvent=value.uptimeMs; long edge=state.observe(value.open); long seq=++sequence;
                    main.post(() -> nativeEvent(value,observed,seq,edge));
                }
            } catch(Throwable e) { main.post(() -> fail("native-reader",e)); }
            finally { try { int exit=reader.waitFor(); main.post(() -> { readerEof=true; event("READER_RETIRED",json("exit",exit));
                if (!stopped) stop("native-reader-ended"); finishIfKnown(); });
            } catch(InterruptedException e) { main.post(() -> { outputHeld=true; fail("reader-retirement",e); }); } }
        },"KioskCompanionNative"); readerThread.start();
        identifyReader();
    }
    void identifyReader() throws Exception {
        String children=read("/proc/self/task/"+android.os.Process.myPid()+"/children").trim();
        if (!children.matches("[1-9][0-9]{0,9}")) throw new IllegalStateException("unique-owned-reader-required");
        readerPid=Integer.parseInt(children); readerBirth=birth(readerPid);
        String[] expected={"/system/bin/logcat","-v","threadtime","-v","monotonic","-T","1","--pid",Integer.toString(producerPid),"Shell:I","*:S"};
        if (!java.util.Arrays.equals(expected,read("/proc/"+readerPid+"/cmdline").split("\\u0000")) ||
            !java.util.regex.Pattern.compile("(?m)^PPid:\\s+"+android.os.Process.myPid()+"\\s*$").matcher(read("/proc/"+readerPid+"/status")).find())
            throw new IllegalStateException("owned-reader-identity-mismatch");
    }
    void nativeEvent(NativeEvent value,long observed,long seq,long edge) {
        event(value.open?"NATIVE_OPEN":"NATIVE_CLOSED",json("event_uptime_ms",value.uptimeMs)
                .put("observed_uptime_ms",observed).put("sequence",seq).put("edge",edge));
        if (!value.open) candidate=null;
        if (stopped || !TargetIdentity.generation(generation)) return;
        long g=generation;
        protocol(() -> { try {
            Values input=extras().putLongValue("generation",g).putLongValue("sequence",seq)
                .putLongValue("event_uptime_ms",value.uptimeMs).putLongValue("observed_uptime_ms",observed);
            Bundle reply=call(value.open?"native-open":"native-closed",input);
            main.post(() -> {
                event("NATIVE_DECISION",replyJson(reply).put("event_uptime_ms",value.uptimeMs));
                String action=reply.getString("action");
                if ("escape".equals(action)) {
                    generation=0; candidate=null; qualifiedGeneration=0; lastFocused=null;
                    event("CURRENT_GUARD_ESCAPED",json("generation",g)); returnToKiosk(); return;
                }
                if (!stopped && g==generation && "close".equals(action) && state.eligible(edge)) {
                    String id=reply.getString("command_id"); canonicalUuid(id);
                    long due=reply.getLong("due_uptime_ms",-1);
                    if (due!=value.uptimeMs+80) { stop("invalid-fixed-due"); return; }
                    candidate=new Candidate(id,g,edge,due,value.uptimeMs); schedule(candidate);
                } else if (!stopped && g==generation && "close".equals(action)) {
                    // A new opening remains queued while the old original callback is unresolved.
                    String id=reply.getString("command_id"); canonicalUuid(id);
                    candidate=new Candidate(id,g,edge,reply.getLong("due_uptime_ms",-1),value.uptimeMs);
                }
            });
        } catch(Throwable e) { main.post(() -> fail("native-protocol",e)); } });
    }
    void schedule(Candidate c) { main.postAtTime(() -> claim(c),Math.max(c.due,SystemClock.uptimeMillis())); }
    void claim(Candidate c) {
        if (stopped || c!=candidate || claimBusy || original!=null || !state.eligible(c.edge)) return;
        if (generation!=c.generation || SystemClock.uptimeMillis()-lastPoll>2000
                || SystemClock.uptimeMillis()-lastIdentity>15000 || SystemClock.uptimeMillis()-c.event>2000) {
            stop("stale-command-authority"); return;
        }
        claimBusy=true;
        protocol(() -> { try {
            Bundle reply=call("claim-close",extras().putLongValue("generation",c.generation).putStringValue("command_id",c.id));
            main.post(() -> {
                claimBusy=false;
                if ("execute".equals(reply.getString("action"))) dispatch(c);
                else if ("wait".equals(reply.getString("action")) && c==candidate && !stopped)
                    main.postDelayed(() -> claim(c),10);
                else if (c==candidate) candidate=null;
            });
        } catch(Throwable e) { main.post(() -> { claimBusy=false; stop("claim-transport-unknown"); event("CLAIM_UNKNOWN_HELD",json("command_id",c.id)); outputHeld=true; }); } });
    }
    void dispatch(Candidate c) {
        // claim-close is one-shot. If local state changed, explicitly retire it without an API call.
        if (stopped || c.generation!=generation || !"shell".equals(mode) || c!=candidate) { reportNoExecute(c); return; }
        if (!state.claim(c.id,c.edge)) { reportNoExecute(c); return; }
        Original own=new Original(c); original=own; candidate=null;
        try {
            writeNew(new File(directory,"state/INTENT-"+c.id+".json"),json("command_id",c.id)
                .put("generation",c.generation).put("source_uptime_ms",c.event).put("due_uptime_ms",c.due)
                .put("function",FUNCTION).put("parameters","empty"));
            own.dispatch=SystemClock.uptimeMillis();
            event("API_DISPATCH_INTENT",json("command_id",c.id).put("generation",c.generation)
                .put("source_uptime_ms",c.event).put("dispatch_uptime_ms",own.dispatch));
            if (!state.beforeExecute(c.id,c.edge)) { state.noExecute(c.id); original=null; reportNoExecute(c); return; }
            calls++;
            main.postDelayed(() -> { if (original==own && !own.callback) unknown(own,"callback-timeout"); },30000);
            execute.invoke(manager,request,(Executor)(r -> main.post(r)),new CancellationSignal(),new OutcomeReceiver<Object,Throwable>() {
                public void onResult(Object response) { complete(own,response,null); }
                public void onError(Throwable error) { complete(own,null,error); }
            });
        } catch(Throwable e) {
            if (state.attempted) unknown(own,"execute-threw");
            else { state.noExecute(c.id); original=null; reportNoExecute(c); stop("intent-publication-failed"); }
        }
    }
    void reportNoExecute(Candidate c) {
        retirements++;
        protocol(() -> { try { Bundle reply=call("close-result",extras().putLongValue("generation",c.generation)
            .putStringValue("command_id",c.id).putStringValue("outcome","not-executed")
            .putBooleanValue("success",false).putLongValue("completion_uptime_ms",SystemClock.uptimeMillis()));
            if (!"known_not_executed".equals(reply.getString("status"))) throw new IllegalStateException("no-execute-retirement-unconfirmed");
            main.post(() -> { retirements--; event("KNOWN_NOT_EXECUTED",json("command_id",c.id)); finishIfKnown(); });
        } catch(Throwable e) { main.post(() -> { outputHeld=true; fail("no-execute-report",e); }); } });
    }
    void complete(Original own,Object response,Throwable error) {
        if (original!=own || own.callback) return;
        own.callback=true; long callback=SystemClock.uptimeMillis(); boolean success=false;
        try {
            if (error==null) {
                Object raw=resultDocument.invoke(response);
                if (!(raw instanceof GenericDocument)) throw new IllegalStateException("typed-result-document");
                GenericDocument result=(GenericDocument)raw;
                if (!"com.oculus.vrshell.appfunctions.model.ShellActionResult".equals(result.getSchemaType()))
                    throw new IllegalStateException("typed-shell-action-result");
                boolean[] values=result.getPropertyBooleanArray("success");
                if (values==null || values.length!=1) throw new IllegalStateException("typed-success-missing");
                success=values[0];
            }
            writeNew(new File(directory,"state/RESULT-"+own.command.id+".json"),json("command_id",own.command.id)
                .put("outcome","callback").put("success",success).put("dispatch_uptime_ms",own.dispatch)
                .put("callback_uptime_ms",callback).put("callback_error",error==null?JSONObject.NULL:error.getClass().getName())
                .put("effect_verified",false));
            final boolean resultSuccess=success;
            protocol(() -> { try {
                Bundle reply=call("close-result",extras().putLongValue("generation",own.command.generation)
                    .putStringValue("command_id",own.command.id).putStringValue("outcome","callback")
                    .putBooleanValue("success",resultSuccess).putLongValue("dispatch_uptime_ms",own.dispatch)
                    .putLongValue("callback_uptime_ms",callback));
                main.post(() -> {
                    if (!"callback_known".equals(reply.getString("status")) && !"callback_failed".equals(reply.getString("status"))) {
                        outputHeld=true; stop("result-retirement-unconfirmed"); return;
                    }
                    state.callback(own.command.id); original=null;
                    event("CALLBACK_KNOWN",json("command_id",own.command.id).put("success",resultSuccess)
                        .put("dispatch_uptime_ms",own.dispatch).put("callback_uptime_ms",callback).put("effect_verified",false));
                    if (!resultSuccess) stop("api-callback-failed");
                    if (calls>=MAX_CALLS) stop("call-budget");
                    if (candidate!=null) schedule(candidate); finishIfKnown();
                });
            } catch(Throwable e) { main.post(() -> { outputHeld=true; fail("callback-report",e); }); } });
        } catch(Throwable e) { outputHeld=true; stop("callback-record-or-decode-failed"); event("CALLBACK_HELD",json("command_id",own.command.id)); }
    }
    void unknown(Original own,String why) {
        if (own.reportedUnknown || own.callback) return;
        own.reportedUnknown=true; state.hold(own.command.id); stop(why);
        event("ORIGINAL_UNKNOWN_HELD",json("command_id",own.command.id).put("dispatch_uptime_ms",own.dispatch));
        protocol(() -> { try { call("close-result",extras().putLongValue("generation",own.command.generation)
            .putStringValue("command_id",own.command.id).putStringValue("outcome","unknown")
            .putBooleanValue("success",false).putLongValue("dispatch_uptime_ms",own.dispatch));
        } catch(Throwable e) { main.post(() -> event("UNKNOWN_REPORT_FAILED",json("error",e.getClass().getName()))); } });
        // Never cancellation, process kill, repeat execute or assume transport return reverses an action.
    }
    void tick() {
        if (finished) return;
        if (new File(directory,"STOP").exists() || SystemClock.uptimeMillis()>=expires) stop("stop-or-session-expiry");
        if (!stopped && registered && !pollBusy) { pollBusy=true; protocol(() -> { try {
            long now=SystemClock.uptimeMillis();
            if (now-lastIdentity>=10000) { verifyProducer(); lastIdentity=now; }
            Bundle reply=call("poll",extras());
            main.post(() -> {
                pollBusy=false;
                if (stopped) { finishIfKnown(); return; }
                lastPoll=SystemClock.uptimeMillis();
                // ready is for NEW guard arming; healthy remains true during a known pending original.
                if (!reply.getBoolean("healthy")) { stop("provider-not-healthy"); return; }
                if (!reply.getBoolean("armed")) { generation=0; candidate=null; return; }
                long next=reply.getLong("generation",0);
                if (next!=generation) { generation=next; candidate=null; qualifiedGeneration=0; lastFocused=null; }
                mode=reply.getString("mode"); targetPackage=reply.getString("target_package"); targetActivity=reply.getString("target_activity");
                if (reply.getLong("delay_ms",-1)!=80 || !TargetIdentity.generation(next) || !("shell".equals(mode)||"accessibility".equals(mode)||"visibility".equals(mode))) { stop("protocol-target-invalid"); return; }
                if (!sampling && SystemClock.uptimeMillis()-lastSample>=(qualifiedGeneration!=generation?250:1000))
                    sampleTarget(generation,targetPackage,targetActivity);
            });
        } catch(Throwable e) { main.post(() -> { pollBusy=false; fail("heartbeat-or-identity",e); }); } }); }
        main.postDelayed(this::tick,100); finishIfKnown();
    }
    void sampleTarget(long g,String pkg,String activity) {
        if (!TargetIdentity.component(pkg,activity)) { stop("invalid-catalogue-target"); return; }
        ComponentName component=new ComponentName(pkg,activity);
        sampling=true; lastSample=SystemClock.uptimeMillis(); long sampledAt=lastSample; long edge=state.edge;
        sample(() -> { try {
            boolean focused=ownFocus(command("/system/bin/dumpsys","activity",component.flattenToString()),component);
            main.post(() -> {
                sampling=false;
                if (stopped || generation!=g || state.edge!=edge || SystemClock.uptimeMillis()-sampledAt>2000
                        || (lastFocused!=null&&lastFocused==focused)) return;
                protocol(() -> { try { Bundle reply=call("target-state",extras().putLongValue("generation",g)
                    .putBooleanValue("own_focused",focused).putLongValue("observed_uptime_ms",sampledAt));
                    main.post(() -> {
                        if (generation!=g) return;
                        String status=reply.getString("status");
                        if ("target_qualified".equals(status) || "target_observed".equals(status) || "target_focus_unchanged".equals(status)
                                || "awaiting_target_focus".equals(status)) { lastFocused=focused;
                            if (focused) qualifiedGeneration=g;
                        }
                    });
                } catch(Throwable e) { main.post(() -> fail("target-state",e)); } });
            });
        } catch(Throwable e) { main.post(() -> { sampling=false;
            if (!stopped && generation==g && !(e instanceof AwaitingFocus && qualifiedGeneration!=g)) fail("target-focus",e);
            else finishIfKnown();
        }); } });
    }
    static final class AwaitingFocus extends IllegalStateException {
        AwaitingFocus() { super("target-window-not-attached-yet"); }
    }
    static boolean ownFocus(String text,ComponentName expected) {
        ClientFocus.Value value=ClientFocus.parse(text,expected.getPackageName(),expected.getClassName());
        if (value==ClientFocus.Value.WAITING) throw new AwaitingFocus();
        return value==ClientFocus.Value.FOCUSED;
    }
    void verifyProducer() throws Exception {
        if (!boot.equals(read("/proc/sys/kernel/random/boot_id").trim()) || !producerBirth.equals(birth(producerPid))
                || !META.equals(read("/proc/"+producerPid+"/cmdline").split("\u0000",2)[0])) throw new IllegalStateException("native-producer-changed");
    }
    void returnToKiosk() {
        escapeKnown=false;
        sample(() -> { try {
            String answer=command("/system/bin/am","start","-a","io.github.mesmerprism.rustykiosk.action.FOREGROUND_GUARD_RETURN",
                "-c","android.intent.category.LAUNCHER","-n",CompanionIdentity.COMPONENT,"-f","0x34000000");
            main.post(() -> { escapeKnown=true; event("FIXED_KIOSK_RETURN_RETIRED",json("transport_returned",true)); finishIfKnown(); });
        } catch(Throwable e) { main.post(() -> { outputHeld=true; fail("fixed-kiosk-return",e); }); } });
    }
    void stop(String why) {
        if (!stopped) { stopped=true; state.stop(); candidate=null; event("ADMISSION_STOPPED",json("reason",why)); }
        if (reader!=null && reader.isAlive()) reader.destroy(); // Own log reader only; never an original UI caller.
        if (!withdrawalStarted && registered) {
            withdrawalStarted=true;
            protocol(() -> { try { call("register",extras().putBooleanValue("ready",false));
                main.post(() -> { withdrawn=true; finishIfKnown(); });
            } catch(Throwable e) { main.post(() -> { outputHeld=true; event("WITHDRAWAL_UNKNOWN_HELD",json("error",e.getClass().getName())); }); } });
        }
        finishIfKnown();
    }
    void fail(String where,Throwable error) {
        String reason=error.getMessage();
        event("FAIL_CLOSED",json("where",where).put("error",error.getClass().getName())
                .put("reason",reason==null?"":reason.substring(0,Math.min(reason.length(),512)))); stop(where);
    }
    void finishIfKnown() {
        if (!stopped || finished || state.pending() || enabledRead.pending() || claimBusy || registrationBusy || original!=null || outputHeld || !escapeKnown || retirements!=0
                || (registered&&!withdrawn) || sampling || pollBusy || protocolJobs.get()!=0 || sampleJobs.get()!=0
                || (reader!=null && !readerEof)) return;
        finished=true;
        try {
            if (provider!=null) provider.release();
            writeNew(new File(directory,"FINAL.json"),json("all_original_calls_resolved",true).put("calls",calls)
                .put("session",session).put("boot",boot).put("pid",android.os.Process.myPid())
                .put("start_ticks",birth(android.os.Process.myPid())).put("source_id",CompanionIdentity.SOURCE_ID)
                .put("product_channel",CompanionIdentity.PRODUCT_CHANNEL)
                .put("capability_read_attempted",enabledRead.started()).put("capability_callback_received",enabledRead.callbackReceived())
                .put("reader_pid",readerPid).put("reader_start_ticks",readerBirth==null?JSONObject.NULL:readerBirth)
                .put("reader_eof",reader==null||readerEof).put("external_provider_reference_released",provider!=null).put("effect_verified",false));
            File owner=new File(active,"owner.json");
            JSONObject admitted=new JSONObject(read(owner.getAbsolutePath()));
            if (!session.equals(admitted.getString("session")) || admitted.getInt("pid")!=android.os.Process.myPid()
                    || !boot.equals(admitted.getString("boot"))
                    || !birth(android.os.Process.myPid()).equals(admitted.getString("start_ticks")))
                throw new IllegalStateException("active-owner-changed");
            if (!owner.delete() || !active.delete()) throw new IllegalStateException("own-active-retirement-failed");
            ipc.shutdown(); samples.shutdown(); System.exit(0);
        } catch(Throwable e) { finished=false; outputHeld=true; event("FINAL_OUTPUT_HELD",json("error",e.getClass().getName())); }
    }
    Bundle call(String method,Values values) throws Exception {
        Bundle reply=provider.call(method,values.bundle);
        if (reply==null || reply.getInt("protocol",-1)!=1 || reply.getString("status")==null || reply.getString("action")==null) throw new IllegalStateException("typed-provider-response");
        return reply;
    }
    void protocol(Runnable operation) {
        protocolJobs.incrementAndGet();
        ipc.execute(() -> { try { operation.run(); } finally { main.post(() -> { protocolJobs.decrementAndGet(); finishIfKnown(); }); } });
    }
    void sample(Runnable operation) {
        sampleJobs.incrementAndGet();
        samples.execute(() -> { try { operation.run(); } finally { main.post(() -> { sampleJobs.decrementAndGet(); finishIfKnown(); }); } });
    }
    Values extras() { return new Values().putIntValue("protocol",1).putStringValue("session",session)
        .putStringValue("boot",boot).putIntValue("pid",android.os.Process.myPid()); }
    static final class Values {
        final Bundle bundle=new Bundle();
        Values putIntValue(String k,int v) { bundle.putInt(k,v); return this; }
        Values putLongValue(String k,long v) { bundle.putLong(k,v); return this; }
        Values putBooleanValue(String k,boolean v) { bundle.putBoolean(k,v); return this; }
        Values putStringValue(String k,String v) { bundle.putString(k,v); return this; }
    }
    synchronized void event(String name,JSONObject value) {
        try {
            value.put("schema","rusty-kiosk.shell-companion.v1").put("event",name).put("session",session)
                .put("uptime_ms",SystemClock.uptimeMillis()).put("pid",android.os.Process.myPid());
            File journal=new File(directory,"events.jsonl"), previous=new File(directory,"events.previous.jsonl");
            if (journal.length()>1048576) { if (previous.exists()&&!previous.delete()) throw new IllegalStateException("journal-retirement");
                if (!journal.renameTo(previous)) throw new IllegalStateException("journal-rotation"); }
            try (FileOutputStream out=new FileOutputStream(journal,true)) { out.write((value+"\n").getBytes(StandardCharsets.UTF_8)); }
            System.out.println(value); System.out.flush();
        } catch(Throwable e) { outputHeld=true; state.stop(); stopped=true; }
    }
    static Json json(String key,Object value) { return new Json().put(key,value); }
    static final class Json extends JSONObject {
        @Override public Json put(String k,Object v) { try { super.put(k,v); return this; } catch(Exception e) { throw new IllegalStateException(e); } }
        @Override public Json put(String k,long v) { return put(k,Long.valueOf(v)); }
        @Override public Json put(String k,int v) { return put(k,Integer.valueOf(v)); }
        @Override public Json put(String k,boolean v) { return put(k,Boolean.valueOf(v)); }
    }
    static Json replyJson(Bundle reply) { Json result=new Json(); for(String k:reply.keySet()) result.put(k,reply.get(k)); return result; }
    static void writeNew(File file,JSONObject value) throws Exception {
        if (!file.createNewFile()) throw new IllegalStateException("create-new-receipt");
        try (FileOutputStream out=new FileOutputStream(file)) { out.write((value+"\n").getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    }
    static void canonicalUuid(String value) { if (value==null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("canonical-uuid"); }
    static String birth(int pid) throws Exception {
        String stat=read("/proc/"+pid+"/stat"); return stat.substring(stat.lastIndexOf(')')+2).split(" ")[19];
    }
    static String read(String path) throws Exception {
        try (FileInputStream in=new FileInputStream(path)) { byte[] bytes=new byte[65536]; int size=in.read(bytes);
            if (size<0) return ""; if (in.read()!=-1) throw new IllegalStateException("bounded-file"); return new String(bytes,0,size,StandardCharsets.UTF_8); }
    }
    static String command(String... argv) throws Exception {
        java.lang.Process child=new ProcessBuilder(argv).redirectErrorStream(true).start();
        StringBuilder value=new StringBuilder(); boolean overflow=false;
        try (BufferedReader lines=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))) {
            String line; while((line=lines.readLine())!=null) { if (value.length()+line.length()>262144) overflow=true;
                if (!overflow) value.append(line).append('\n'); }
        }
        int exit=child.waitFor(); if (overflow) throw new IllegalStateException("bounded-command-output");
        if (exit!=0) throw new IllegalStateException("fixed-command-exit-"+exit); return value.toString();
    }
}
