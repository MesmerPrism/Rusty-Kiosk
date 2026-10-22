using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using QuestIonAbleFileManager.Core;

const long now = 1_900_000_000_000;
const string profileId = "profile.quest.interop.001";
const string deviceId = "device.quest.interop.001";
const string ownerEpoch = "OwnerEpoch_0000000000000001";
const string pairingCode = "0123-4567-89AB-CDEF-GHJK-MNPQ-RS";
if (args.Length is < 3 or > 4) throw new ArgumentException("Expected java executable, Kotlin classpath file, evidence directory.");
Directory.CreateDirectory(args[2]);
using var kotlin = new KotlinProducer(args[0], File.ReadAllText(args[1]).Trim());
// Complete JVM startup before the consumer's real bounded exchange begins.
await kotlin.Exchange("warmup", "GET", "/v2/contract", [], true);
var results = new List<object>();
foreach (var scenario in new[] {
    "label-control", "many-tags", "success", "open-replay", "request-replay", "signer-missing", "truncated",
    "absent", "revoke-open", "revoke-admission", "revoke-dispatch", "revoke-seal",
    "generation", "rotation", "owner-rotation", "restart", "stale-snapshot", "future-snapshot",
    "wrong-pairing", "wrong-device", "wrong-owner", "wrong-profile", "selected-profile-mismatch", "wrong-path", "wrong-method",
    "duplicate-json", "extra-json", "bad-base64", "bad-request-ciphertext", "bad-request-nonce",
    "bad-request-counter", "bad-request-expiry", "bad-response-ciphertext", "bad-response-counter",
    "bad-content-length"
}.Where(item => args.Length == 3 || item == args[3])) {
    var handler = new ProducerHandler(kotlin, scenario);
    using var client = new HttpClient(handler);
    var profiles = new MemoryProfile(scenario == "wrong-device" ? "different-device" : deviceId,
        scenario == "wrong-pairing" ? "1123-4567-89AB-CDEF-GHJK-MNPQ-RS" : pairingCode);
    using var provider = new RustyKioskV2CatalogProvider(profiles, client, () => now);
    const string otherProfile = "profile.quest.interop.002";
    using var validOtherIdentity = new RustyKioskV2ProviderProfile(otherProfile,
        new Uri("http://192.168.1.44:39873/"), pairingCode.ToCharArray(), deviceId);
    var request = Request(scenario == "wrong-owner" ? "DifferentOwner_0000000001" : ownerEpoch,
        scenario == "wrong-profile" ? otherProfile : profileId);
    request.Validate();
    if (now < request.IssuedAtMs || now >= request.ExpiresAtMs)
        throw new InvalidOperationException("Profile controls must have valid current freshness.");
    var expected = scenario is "label-control" or "many-tags" or "success" or "open-replay" or "request-replay" or "signer-missing" or "truncated";
    try {
        using var exchange = await provider.FetchAsync(scenario is "wrong-profile" or "selected-profile-mismatch" ? otherProfile : profileId, request);
        if (!expected) throw new InvalidOperationException($"Negative control accepted: {scenario}");
        using var catalog = JsonDocument.Parse(exchange.OwnerCatalogJson);
        var root = catalog.RootElement;
        if (root.GetProperty("fresh_until_ms").GetInt64() - root.GetProperty("observed_at_ms").GetInt64() != 300_000)
            throw new InvalidOperationException("Snapshot freshness drifted.");
        foreach (var entry in root.GetProperty("entries").EnumerateArray()) {
            if (entry.TryGetProperty("package", out _) || entry.TryGetProperty("signing_evidence", out _))
                throw new InvalidOperationException("Summary leaked detail.");
        }
        if (scenario == "signer-missing" && (root.GetProperty("complete").GetBoolean() ||
            root.GetProperty("permission_limited_count").GetInt32() != 1 ||
            root.GetProperty("entries").GetArrayLength() != 0)) throw new InvalidOperationException("Signer failure was concealed.");
        if (scenario == "truncated" && (!root.GetProperty("truncated").GetBoolean() ||
            root.GetProperty("entries").GetArrayLength() != 500)) throw new InvalidOperationException("Truncation was concealed.");
        if (scenario == "label-control" && root.GetProperty("entries")[0].GetProperty("label").GetString() != "One Two")
            throw new InvalidOperationException("Control label was not normalized.");
        if (scenario == "many-tags" && (root.GetProperty("complete").GetBoolean() ||
            root.GetProperty("permission_limited_count").GetInt32() != 0 ||
            root.GetProperty("total_candidate_count").GetInt32() != 1 || root.GetProperty("entries").GetArrayLength() != 0))
            throw new InvalidOperationException("Unrepresentable metadata omission was concealed or misclassified.");
        if (scenario == "success") {
            File.WriteAllBytes(Path.Combine(args[2], "actual-kotlin-owner-catalog.json"), exchange.OwnerCatalogJson.ToArray());
            File.WriteAllBytes(Path.Combine(args[2], "actual-qfm-verified-response.json"),
                RustyKioskV2CatalogProviderResponse.Verified(profileId, request.RequestId, exchange).ToUtf8Json());
        }
        results.Add(new { scenario, outcome = "verified", requests = handler.Count });
    } catch (RustyKioskV2ProviderException failure) {
        if (expected) throw new InvalidOperationException($"Actual Kotlin exchange failed: {scenario}: {failure.Code}", failure);
        if (scenario == "wrong-profile" && (profiles.Calls != 1 || profiles.LastId != otherProfile ||
            failure.Code != "provider_profile_unavailable" || handler.Count != 0))
            throw new InvalidOperationException("Wrong-profile control did not reach the exact valid profile lookup.");
        if (scenario == "selected-profile-mismatch" && (profiles.Calls != 0 || handler.Count != 0 ||
            failure.Code != "request_freshness_invalid" || request.ProfileId == otherProfile))
            throw new InvalidOperationException("Selected/request profile binding control was vacuous.");
        results.Add(new { scenario, outcome = "rejected", code = failure.Code, requests = handler.Count,
            profile_lookups = profiles.Calls, lookup_id = profiles.LastId });
    }
}

// Run the actual production subprocess argument/input parser and response serializer too.
if (args.Length == 3)
using (var handler = new ProducerHandler(kotlin, "success"))
using (var client = new HttpClient(handler)) {
    var host = new RustyKioskV2CatalogSubprocessHost(() => new RustyKioskV2CatalogProvider(
        new MemoryProfile(deviceId, pairingCode), client, () => now));
    await using var input = new MemoryStream(RequestBytes());
    await using var output = new MemoryStream();
    var exit = await host.RunAsync(["integration", "kiosk-v2-catalog", "--json"], input, output);
    if (exit != 0) throw new InvalidOperationException(Encoding.UTF8.GetString(output.ToArray()));
    File.WriteAllBytes(Path.Combine(args[2], "actual-qfm-subprocess-response.json"), output.ToArray());
    File.WriteAllBytes(Path.Combine(args[2], "actual-qfm-request.json"), RequestBytes());
    results.Add(new { scenario = "production-subprocess-host", outcome = "verified", requests = handler.Count });
}
File.WriteAllText(Path.Combine(args[2], "interop-results.json"), JsonSerializer.Serialize(results, new JsonSerializerOptions { WriteIndented = true }));
Console.WriteLine($"Actual Kotlin -> production QFM: {results.Count} controls passed.");

static RustyKioskV2CatalogProviderRequest Request(string owner = ownerEpoch, string profile = profileId) => RustyKioskV2CatalogProviderRequest.Parse(RequestBytes(owner, profile));
static byte[] RequestBytes(string owner = ownerEpoch, string profile = profileId) => JsonSerializer.SerializeToUtf8Bytes(new {
    schema = RustyKioskV2ProviderContract.RequestSchema, profile_id = profile,
    request_id = "catalog-request-interop-0001", device_id = deviceId, identity_revision = 7,
    capability_id = RustyKioskV2ProviderContract.CapabilityId, capability_evidence_revision = 11,
    route_id = RustyKioskV2ProviderContract.RouteId, expected_owner_epoch = owner,
    scopes = new[] { RustyKioskV2ProviderContract.CatalogSummaryScope }, issued_at_ms = now - 1000, expires_at_ms = now + 25000
});

sealed class MemoryProfile(string device, string code) : IRustyKioskV2ProviderProfileStore {
    public int Calls { get; private set; }
    public string? LastId { get; private set; }
    public RustyKioskV2ProviderProfile Open(string id) {
        Calls++;
        LastId = id;
        if (id != "profile.quest.interop.001")
            throw RustyKioskV2ProviderException.Unavailable("provider_profile_unavailable");
        return new(id, new Uri("http://192.168.1.44:39873/"), code.ToCharArray(), device);
    }
}

sealed class ProducerHandler(KotlinProducer producer, string scenario) : HttpMessageHandler {
    public int Count { get; private set; }
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
        var method = request.Method.Method;
        var path = request.RequestUri!.AbsolutePath;
        var bytes = request.Content is null ? [] : await request.Content.ReadAsByteArrayAsync(token);
        if (path == "/v2/catalog/summary") {
            if (scenario == "wrong-path") path = "/v2/catalog/detail";
            if (scenario == "wrong-method") method = "PUT";
            if (scenario == "duplicate-json") bytes = Encoding.UTF8.GetBytes(Encoding.UTF8.GetString(bytes).Replace("{", "{\"schema\":\"duplicate\",", StringComparison.Ordinal));
            if (scenario is "extra-json" or "bad-base64" or "bad-request-ciphertext" or "bad-request-nonce" or "bad-request-counter" or "bad-request-expiry") {
                var json = JsonNode.Parse(bytes)!.AsObject();
                if (scenario == "extra-json") json["extra"] = true;
                if (scenario == "bad-base64") json["nonce"] = json["nonce"]!.GetValue<string>() + "=";
                if (scenario == "bad-request-ciphertext") json["ciphertext"] = Flip(json["ciphertext"]!.GetValue<string>());
                if (scenario == "bad-request-nonce") json["nonce"] = Flip(json["nonce"]!.GetValue<string>());
                if (scenario == "bad-request-counter") json["counter"] = 2;
                if (scenario == "bad-request-expiry") json["expires_at_ms"] = 1;
                bytes = Encoding.UTF8.GetBytes(json.ToJsonString());
            }
        }
        var result = await producer.Exchange(scenario, method, path, bytes, Count++ == 0);
        if ((scenario == "open-replay" && path == "/v2/session/open") ||
            (scenario == "request-replay" && path == "/v2/catalog/summary")) {
            var replay = await producer.Exchange(scenario, method, path, bytes, false);
            if (replay.Status == 200) throw new InvalidOperationException("Production Kotlin accepted replay.");
        }
        var body = result.Body;
        if (path == "/v2/catalog/summary" && scenario is "bad-response-ciphertext" or "bad-response-counter") {
            var json = JsonNode.Parse(body)!.AsObject();
            if (scenario == "bad-response-ciphertext") json["ciphertext"] = Flip(json["ciphertext"]!.GetValue<string>());
            else json["response_counter"] = 2;
            body = Encoding.UTF8.GetBytes(json.ToJsonString());
        }
        var content = new ByteArrayContent(body);
        content.Headers.ContentType = new("application/json");
        content.Headers.ContentLength = body.Length + (scenario == "bad-content-length" ? 1 : 0);
        return new HttpResponseMessage((HttpStatusCode)result.Status) { Content = content };
    }
    private static string Flip(string value) => (value[0] == 'A' ? "B" : "A") + value[1..];
}

sealed class KotlinProducer : IDisposable {
    private readonly Process process;
    public KotlinProducer(string java, string classpath) {
        var info = new ProcessStartInfo(java) { UseShellExecute = false, RedirectStandardInput = true,
            RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        info.ArgumentList.Add("-cp"); info.ArgumentList.Add(classpath);
        info.ArgumentList.Add("io.github.mesmerprism.rustykiosk.DirectOperatorV2InteropHost");
        process = Process.Start(info) ?? throw new InvalidOperationException("Kotlin process did not start.");
        process.ErrorDataReceived += (_, item) => { if (item.Data is not null) Console.Error.WriteLine(item.Data); };
        process.BeginErrorReadLine();
    }
    public async Task<(int Status, byte[] Body)> Exchange(string scenario, string method, string path, byte[] body, bool reset) {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(new { scenario, method, path, body = Convert.ToBase64String(body), reset });
        var prefix = new byte[4]; BinaryPrimitives.WriteInt32BigEndian(prefix, bytes.Length);
        await process.StandardInput.BaseStream.WriteAsync(prefix);
        await process.StandardInput.BaseStream.WriteAsync(bytes);
        await process.StandardInput.BaseStream.FlushAsync();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        await process.StandardOutput.BaseStream.ReadExactlyAsync(prefix, timeout.Token);
        var size = BinaryPrimitives.ReadInt32BigEndian(prefix);
        if (size is < 1 or > 3_000_000) throw new InvalidOperationException("Invalid producer frame.");
        var response = new byte[size]; await process.StandardOutput.BaseStream.ReadExactlyAsync(response, timeout.Token);
        using var json = JsonDocument.Parse(response);
        var payload = Convert.FromBase64String(json.RootElement.GetProperty("body").GetString()!);
        if (payload.Length != json.RootElement.GetProperty("length").GetInt32()) throw new InvalidOperationException("Producer length mismatch.");
        return (json.RootElement.GetProperty("status").GetInt32(), payload);
    }
    public void Dispose() {
        process.StandardInput.Close();
        if (!process.WaitForExit(5000)) { process.Kill(true); process.WaitForExit(); }
        process.Dispose();
    }
}
