package io.github.mesmerprism.queststatusaudioexample;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** One wearer-started owner for pairing, enrolled LAN requests and their retirement. */
final class CompanionGateway implements AutoCloseable {
    private static final int MAX_MEMBERS = 8;
    private static final int MAX_ROUTES = 1;
    private static final String STATE = "state_v1";
    private static final String ZERO_REQUEST_ID = "00000000000000000000000000000000";

    interface Effect {
        JSONObject exchange(String action, JSONObject payload) throws Exception;
    }

    private final Handler main;
    private final BooleanSupplier live;
    private final Effect effect;
    private final SharedPreferences preferences;
    private final ConnectivityManager connectivity;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, CompanionRequestAuth> authenticated = new HashMap<>();
    private final String epoch = randomEpoch();
    private Map<String, String> members = Map.of();
    private CompanionHttpServer http;
    private ExperimentPhonePairing pairing;
    private String device;
    private boolean started;
    private boolean storageHealthy = true;

    CompanionGateway(Context context, Handler main, BooleanSupplier live, Effect effect) {
        this.main = main;
        this.live = live;
        this.effect = effect;
        preferences = context.getSharedPreferences("companion_enrollment", Context.MODE_PRIVATE);
        connectivity = context.getSystemService(ConnectivityManager.class);
        checkOwner();
    }

    boolean start() {
        checkOwner();
        if (started) return true;
        try {
            restore();
            pairing = newPairing();
            http = new CompanionHttpServer(new CompanionHttpServer.Owner() {
                @Override public boolean acceptsLocalAddress(InetAddress address) {
                    return wifiAddresses().contains(address);
                }

                @Override public JSONObject dispatch(JSONObject request) throws Exception {
                    FutureTask<JSONObject> task = new FutureTask<>(() -> exchange(request));
                    if (!main.post(task)) throw new IllegalStateException("Gateway owner unavailable");
                    try {
                        return task.get(3500, TimeUnit.MILLISECONDS);
                    } finally {
                        task.cancel(false);
                    }
                }
            });
            http.start();
            started = true;
            return true;
        } catch (Exception unavailable) {
            close();
            return false;
        }
    }

    byte[] discovery() {
        checkOwner();
        if (!started || !storageHealthy) return null;
        try {
            byte[] metadata = publicIdentity().toString().getBytes(StandardCharsets.UTF_8);
            return metadata.length <= 512 ? metadata : null;
        } catch (JSONException invalid) {
            return null;
        }
    }

    JSONObject pendingPrompt() {
        checkOwner();
        return started && storageHealthy ? pairing.pendingPrompt() : null;
    }

    void decide(String id, boolean allow) {
        requireLive();
        pairing.decide(id, allow);
    }

    boolean hasEnrollment() {
        checkOwner();
        return !members.isEmpty();
    }

    void revokeAll() {
        checkOwner();
        if (!started) throw new IllegalStateException("Gateway unavailable");
        pairing.retire();
        authenticated.clear();
        try {
            persist(Map.of());
            pairing = newPairing();
        } catch (JSONException invalid) {
            storageHealthy = false;
            throw new IllegalStateException("Could not revoke enrollment", invalid);
        }
    }

    @Override public void close() {
        checkOwner();
        started = false;
        if (http != null) http.close();
        if (pairing != null) pairing.retire();
        http = null;
        pairing = null;
        authenticated.clear();
        members = Map.of();
    }

    private ExperimentPhonePairing newPairing() {
        return new ExperimentPhonePairing(new ExperimentPhonePairing.Host() {
            @Override public JSONObject identity() {
                try {
                    return identityFields();
                } catch (JSONException invalid) {
                    throw new IllegalStateException("Gateway identity unavailable", invalid);
                }
            }

            @Override public boolean canPair() {
                return started && storageHealthy && members.size() < MAX_MEMBERS;
            }

            @Override public JSONObject addTrustedPhone(String phoneName) throws Exception {
                if (!canPair()) throw new SecurityException("Enrollment unavailable");
                String controller;
                do {
                    controller = token(16);
                } while (members.containsKey(controller));
                String key = token(32);
                Map<String, String> next = new LinkedHashMap<>(members);
                next.put(controller, key);
                persist(next);
                return new JSONObject().put("controller", controller).put("key", key)
                        .put("device", device);
            }

            @Override public void discardTrustedPhone(String controller) throws Exception {
                Map<String, String> next = new LinkedHashMap<>(members);
                next.remove(controller);
                persist(next);
                authenticated.remove(controller);
            }

            @Override public long clockElapsedMs() {
                return SystemClock.elapsedRealtime();
            }
        });
    }

    private JSONObject exchange(JSONObject request) throws Exception {
        requireLive();
        if (request == null) return publicIdentity();
        String action = string(request, "action");
        if (ExperimentPhonePairing.handlesAction(action)) return pairing.exchange(request);

        if (request.length() != 7 || !Integer.valueOf(1).equals(request.opt("v"))
                || !epoch.equals(request.opt("epoch"))) {
            throw new SecurityException("Gateway version or epoch unavailable");
        }
        String controller = string(request, "controller");
        String key = CompanionRequestAuth.isHex(controller, 32) ? members.get(controller) : null;
        if (key == null) throw new SecurityException("Controller is not enrolled");
        String nonce = string(request, "nonce");
        String rawPayload = string(request, "payload");
        CompanionRequestAuth auth = authenticated.computeIfAbsent(controller,
                id -> new CompanionRequestAuth(id, key, device, epoch));
        auth.accept(nonce, action, rawPayload, string(request, "mac"));
        JSONObject payload = new JSONObject(rawPayload);
        checkPayload(action, payload);
        String result = effect.exchange(action, payload).toString();
        return new JSONObject().put("v", 1).put("device", device).put("epoch", epoch)
                .put("nonce", nonce).put("action", action).put("payload", result)
                .put("mac", auth.signReply(nonce, action, result));
    }

    private static void checkPayload(String action, JSONObject payload) throws JSONException {
        String id = string(payload, "id");
        boolean command = "command".equals(action);
        Object opcode = payload.opt("opcode");
        if (!CompanionRequestAuth.isHex(id, 32) || id.equals(ZERO_REQUEST_ID)
                || payload.length() != (command ? 2 : 1)
                || command && !(Integer.valueOf(2).equals(opcode) || Integer.valueOf(3).equals(opcode))) {
            throw new SecurityException("Unsupported companion effect");
        }
    }

    private void restore() throws JSONException {
        String saved = preferences.getString(STATE, null);
        if (saved == null) {
            device = token(16);
            persist(Map.of());
            return;
        }
        JSONObject state = new JSONObject(saved);
        String savedDevice = string(state, "device");
        JSONArray records = state.getJSONArray("members");
        if (state.length() != 2 || !CompanionRequestAuth.isHex(savedDevice, 32)
                || records.length() > MAX_MEMBERS) {
            throw new SecurityException("Invalid enrollment store");
        }
        Map<String, String> loaded = new LinkedHashMap<>();
        for (int index = 0; index < records.length(); index++) {
            JSONObject record = records.getJSONObject(index);
            String controller = string(record, "controller");
            String key = string(record, "key");
            if (record.length() != 2 || !CompanionRequestAuth.isHex(controller, 32)
                    || !CompanionRequestAuth.isHex(key, 64) || loaded.put(controller, key) != null) {
                throw new SecurityException("Invalid enrollment member");
            }
        }
        device = savedDevice;
        members = Map.copyOf(loaded);
    }

    private void persist(Map<String, String> next) throws JSONException {
        JSONArray records = new JSONArray();
        for (Map.Entry<String, String> entry : next.entrySet()) {
            records.put(new JSONObject().put("controller", entry.getKey()).put("key", entry.getValue()));
        }
        String value = new JSONObject().put("device", device).put("members", records).toString();
        if (!preferences.edit().putString(STATE, value).commit()) {
            storageHealthy = false;
            authenticated.clear();
            throw new IllegalStateException("Could not store enrollment");
        }
        storageHealthy = true;
        members = Map.copyOf(next);
    }

    private JSONObject identityFields() throws JSONException {
        return new JSONObject().put("v", 2).put("device", device).put("serial", "")
                .put("identity_kind", "app-installation").put("epoch", epoch)
                .put("native_epoch", epoch);
    }

    private JSONObject publicIdentity() throws JSONException {
        JSONArray routes = new JSONArray();
        for (InetAddress address : wifiAddresses()) {
            routes.put("http://" + address.getHostAddress() + ":" + CompanionHttpServer.PORT
                    + CompanionHttpServer.PATH);
        }
        return identityFields().put("mode", "remote").put("gateways", routes).put("pairing", 1)
                .put("enabled", true).put("remote_available", true);
    }

    @SuppressWarnings("deprecation") // Needed when the private Wi-Fi network is not the default.
    private Set<InetAddress> wifiAddresses() {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        if (connectivity == null) return addresses;
        try {
            addWifiAddresses(addresses, connectivity.getActiveNetwork());
            for (Network network : connectivity.getAllNetworks()) addWifiAddresses(addresses, network);
        } catch (SecurityException unavailable) {
            addresses.clear();
        }
        return addresses;
    }

    private void addWifiAddresses(Set<InetAddress> addresses, Network network) {
        if (network == null || addresses.size() >= MAX_ROUTES) return;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties == null) return;
        for (LinkAddress link : properties.getLinkAddresses()) {
            if (CompanionHttpServer.isPrivateIpv4(link.getAddress())) addresses.add(link.getAddress());
            if (addresses.size() >= MAX_ROUTES) return;
        }
    }

    private static String string(JSONObject value, String field) throws JSONException {
        Object item = value.get(field);
        if (!(item instanceof String)) throw new SecurityException("Invalid gateway field");
        return (String) item;
    }

    private String token(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return CompanionRequestAuth.hex(bytes);
    }

    private String randomEpoch() {
        long value;
        do {
            value = random.nextLong() & Long.MAX_VALUE;
        } while (value == 0);
        return Long.toString(value);
    }

    private void checkOwner() {
        if (Looper.myLooper() != main.getLooper()) {
            throw new IllegalStateException("Companion owner must run on its Android handler");
        }
    }

    private void requireLive() {
        checkOwner();
        if (!started || !storageHealthy || !live.getAsBoolean() || !started) {
            throw new SecurityException("Companion session unavailable");
        }
    }
}
