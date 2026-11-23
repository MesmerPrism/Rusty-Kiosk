package io.github.mesmerprism.queststatusaudioexample;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Bounded local HTTP adapter. The serial owner authorizes all operations.
 * Based on Rusty Quest's ExperimentSessionGatewayHttpServer at
 * c3d2df89b644189efcf08279783e01d97a83f819.
 */
final class CompanionHttpServer implements AutoCloseable {
    static final int PORT = 47876;
    static final String PATH = "/v1/gateway";
    private static final int MAX_BODY = 8192;
    private static final Set<String> ORIGINS =
            Set.of("http://127.0.0.1:8765", "https://mesmerprism.github.io");

    interface Owner {
        boolean acceptsLocalAddress(InetAddress address);
        JSONObject dispatch(JSONObject request) throws Exception;
    }

    private final Owner owner;
    private final Set<Socket> active = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(0, 6, 30,
            TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "quest-companion-request");
                thread.setDaemon(true);
                return thread;
            });
    private ServerSocket listener;

    CompanionHttpServer(Owner owner) {
        this.owner = owner;
    }

    void start() throws IOException {
        listener = new ServerSocket(PORT, 8);
        ServerSocket admitted = listener;
        Thread acceptor = new Thread(() -> accept(admitted), "quest-companion-lan");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void accept(ServerSocket admitted) {
        while (!admitted.isClosed()) {
            Socket socket;
            try {
                socket = admitted.accept();
            } catch (IOException ended) {
                return;
            }
            if (!isPrivateIpv4(socket.getInetAddress()) || !isPrivateIpv4(socket.getLocalAddress())) {
                closeSocket(socket);
                continue;
            }
            active.add(socket);
            try {
                if (admitted.isClosed()) throw new RejectedExecutionException();
                workers.execute(() -> serve(socket));
            } catch (RejectedExecutionException full) {
                active.remove(socket);
                closeSocket(socket);
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            if (!owner.acceptsLocalAddress(socket.getLocalAddress())) return;
            socket.setSoTimeout(4000);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            String first = line(input, 1024, deadline);
            String[] parts = first.split(" ", -1);
            if (parts.length != 3 || !PATH.equals(parts[1]) || !"HTTP/1.1".equals(parts[2])) {
                reply(socket, 404, "", "{}");
                return;
            }

            Map<String, String> headers = new HashMap<>();
            int size = first.length();
            while (true) {
                String line = line(input, 2048, deadline);
                size += line.length() + 2;
                if (size > 8192) throw new IOException("Request header limit");
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon <= 0) throw new IOException("Malformed header");
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9-]+")
                        || headers.put(name, line.substring(colon + 1).trim()) != null) {
                    throw new IOException("Ambiguous header");
                }
            }

            String origin = headers.get("origin");
            String expectedHost = socket.getLocalAddress().getHostAddress() + ":" + PORT;
            if (origin == null || !ORIGINS.contains(origin) || !expectedHost.equals(headers.get("host"))) {
                reply(socket, 403, "", "{}");
                return;
            }
            String cors = "Access-Control-Allow-Origin: " + origin + "\r\nVary: Origin\r\n";
            if ("OPTIONS".equals(parts[0])) {
                String method = headers.get("access-control-request-method");
                String requestedHeaders = headers.get("access-control-request-headers");
                if (!("GET".equals(method) || "POST".equals(method))
                        || (requestedHeaders != null && !"content-type".equalsIgnoreCase(requestedHeaders))) {
                    reply(socket, 403, "", "{}");
                    return;
                }
                reply(socket, 200, cors + "Access-Control-Allow-Methods: GET, POST\r\n"
                        + "Access-Control-Allow-Headers: Content-Type\r\n"
                        + "Access-Control-Allow-Private-Network: true\r\n", "{}");
                return;
            }

            JSONObject request = null;
            if ("POST".equals(parts[0])) {
                String bodySize = headers.get("content-length");
                if (headers.containsKey("transfer-encoding")
                        || !"application/json".equals(headers.get("content-type"))
                        || bodySize == null || !bodySize.matches("[1-9][0-9]{0,3}")) {
                    reply(socket, 400, cors, "{}");
                    return;
                }
                int length = Integer.parseInt(bodySize);
                if (length > MAX_BODY) throw new IOException("Request body limit");
                byte[] body = new byte[length];
                for (int offset = 0; offset < length; ) {
                    checkDeadline(deadline);
                    int read = input.read(body, offset, length - offset);
                    if (read <= 0) throw new IOException("Incomplete request body");
                    offset += read;
                }
                request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            } else if (!"GET".equals(parts[0])) {
                reply(socket, 400, cors, "{}");
                return;
            }

            try {
                reply(socket, 200, cors, owner.dispatch(request).toString());
            } catch (Exception denied) {
                reply(socket, 403, cors, "{\"error\":\"gateway-request-rejected\"}");
            }
        } catch (Exception invalid) {
            // No raw request, route, identity, or credentials enter device logs.
        } finally {
            active.remove(socket);
        }
    }

    static boolean isPrivateIpv4(InetAddress address) {
        return address instanceof Inet4Address && address.isSiteLocalAddress();
    }

    private static String line(InputStream input, int max, long deadline) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int previous = -1;
        while (result.size() < max) {
            checkDeadline(deadline);
            int value = input.read();
            if (value < 0) throw new IOException("Incomplete HTTP request");
            if (previous == '\r' && value == '\n') {
                byte[] bytes = result.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            if (value < 32 && value != '\r' && value != '\t' || value > 126
                    || previous == '\r') throw new IOException("Invalid HTTP header byte");
            result.write(value);
            previous = value;
        }
        throw new IOException("Request line limit");
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (System.nanoTime() - deadline >= 0) throw new IOException("Request timed out");
    }

    private static void reply(Socket socket, int status, String cors, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Rejected")
                + "\r\nContent-Type: application/json\r\nCache-Control: no-store\r\n"
                + "Connection: close\r\n" + cors + "Content-Length: " + bytes.length
                + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    @Override public void close() {
        ServerSocket current = listener;
        listener = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
        workers.shutdownNow();
        for (Socket socket : active) closeSocket(socket);
    }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
