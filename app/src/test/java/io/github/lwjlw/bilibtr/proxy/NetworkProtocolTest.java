package io.github.lwjlw.bilibtr.proxy;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.*;

/** Real loopback HTTP/TLS tests: no internet, Android device, or relaxed certificate verification. */
public class NetworkProtocolTest {
    private static SSLContext previousDefault;
    private static SSLContext trustedServer;
    private static SSLContext untrustedServer;
    private static Path certificates;
    private static final byte[] BODY = new byte[]{1, 2, 3, 4};

    @BeforeClass public static void certificates() throws Exception {
        certificates = Files.createTempDirectory("bilibtr-tls-test-");
        KeyStore trusted = certificate("trusted");
        KeyStore untrusted = certificate("untrusted");
        trustedServer = serverContext(trusted);
        untrustedServer = serverContext(untrusted);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trust.getTrustManagers(), null);
        previousDefault = SSLContext.getDefault();
        SSLContext.setDefault(client);
    }

    @AfterClass public static void restoreTrust() throws Exception {
        SSLContext.setDefault(previousDefault);
        try (java.util.stream.Stream<Path> files = Files.walk(certificates)) {
            for (Path path : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(path);
            }
        }
    }

    @After public void clearPool() { UpstreamPool.clearAll(); }

    private static KeyStore certificate(String name) throws Exception {
        Path store = certificates.resolve(name + ".p12");
        Process p = new ProcessBuilder(System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "test-password", "-keypass", "test-password", "-noprompt")
                .redirectErrorStream(true).start();
        byte[] output = p.getInputStream().readAllBytes();
        assertEquals(new String(output, StandardCharsets.UTF_8), 0, p.waitFor());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(store)) {
            ks.load(in, "test-password".toCharArray());
        }
        return ks;
    }

    private static SSLContext serverContext(KeyStore store) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "test-password".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    @Test public void httpsRangesUseVerifiedTlsSniAndReuseConnection() throws Exception {
        AtomicReference<String> sni = new AtomicReference<>();
        try (Server server = new Server(trustedServer, socket -> {
            SSLSocket tls = (SSLSocket) socket;
            tls.startHandshake();
            sni.set(((SNIHostName) ((ExtendedSSLSession) tls.getSession())
                    .getRequestedServerNames().get(0)).getAsciiName());
            for (int i = 0; i < 2; i++) {
                String request = readRequest(socket);
                assertTrue(request, request.contains("Host: localhost:" + socket.getLocalPort() + "\r\n"));
                assertTrue(request, request.contains("Range: bytes=0-3\r\n"));
                socket.getOutputStream().write(response());
                socket.getOutputStream().flush();
            }
        })) {
            for (int i = 0; i < 2; i++) {
                RangeFetcher.Result result = RangeFetcher.fetch(server.url("https", "localhost"),
                        "GET", Collections.emptyMap(), 0, 3, 1);
                assertTrue(result.error, result.ok);
                assertArrayEquals(BODY, result.body);
            }
            server.await();
            assertEquals("localhost", sni.get());
        }
    }

    @Test public void httpsRejectsTrustedCertificateForWrongHostname() throws Exception {
        assertTlsRejected(trustedServer, "127.0.0.1");
    }

    @Test public void httpsRejectsUntrustedCertificate() throws Exception {
        assertTlsRejected(untrustedServer, "localhost");
    }

    private static void assertTlsRejected(SSLContext context, String host) throws Exception {
        try (Server server = new Server(context, socket -> {
            try { ((SSLSocket) socket).startHandshake(); } catch (SSLException expected) { }
        })) {
            try {
                UpstreamPool.Conn c = UpstreamPool.acquire(new URL(server.url("https", host)), 2000, 2000);
                UpstreamPool.discard(c);
                fail("Invalid HTTPS identity must fail without a plaintext fallback");
            } catch (SSLException expected) { }
        }
    }

    @Test public void httpConnectionCannotSatisfyHttpsRequestForSameHostAndPort() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            listener.setSoTimeout(3000);
            URL http = new URL("http://localhost:" + listener.getLocalPort() + "/media");
            UpstreamPool.Conn plain = UpstreamPool.acquire(http, 2000, 2000);
            try (Socket first = listener.accept()) {
                plain.reusable = true;
                assertTrue(UpstreamPool.release(plain));
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    Future<?> tlsAttempt = executor.submit(() -> {
                        try {
                            UpstreamPool.Conn unexpected = UpstreamPool.acquire(
                                    new URL("https://localhost:" + listener.getLocalPort() + "/media"), 2000, 2000);
                            UpstreamPool.discard(unexpected);
                            throw new AssertionError("HTTPS must not borrow the HTTP connection");
                        } catch (IOException expected) { }
                    });
                    try (Socket second = listener.accept()) {
                        second.setSoTimeout(2000);
                        assertEquals("New connection must begin with a TLS handshake", 22, second.getInputStream().read());
                    }
                    tlsAttempt.get(4, TimeUnit.SECONDS);
                } finally { executor.shutdownNow(); }
                UpstreamPool.Conn again = UpstreamPool.acquire(http, 2000, 2000);
                assertSame(plain, again);
                UpstreamPool.discard(again);
            }
        }
    }

    @Test public void concurrentBorrowersNeverShareALiveConnection() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
            ExecutorService acceptor = Executors.newSingleThreadExecutor();
            Future<?> accepting = acceptor.submit(() -> {
                try { while (!listener.isClosed()) accepted.add(listener.accept()); }
                catch (IOException closed) { }
            });
            ExecutorService workers = Executors.newFixedThreadPool(8);
            Set<UpstreamPool.Conn> borrowed = ConcurrentHashMap.newKeySet();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<>();
            URL url = new URL("http://localhost:" + listener.getLocalPort() + "/media");
            try {
                for (int i = 0; i < 8; i++) results.add(workers.submit(() -> {
                    start.await();
                    for (int n = 0; n < 200; n++) {
                        UpstreamPool.Conn connection = UpstreamPool.acquire(url, 2000, 2000);
                        assertTrue("Connection borrowed twice", borrowed.add(connection));
                        Thread.yield();
                        assertTrue(borrowed.remove(connection));
                        connection.reusable = true;
                        UpstreamPool.release(connection);
                    }
                    return null;
                }));
                start.countDown();
                for (Future<?> result : results) result.get(15, TimeUnit.SECONDS);
            } finally {
                workers.shutdownNow();
                listener.close();
                accepting.get(2, TimeUnit.SECONDS);
                acceptor.shutdownNow();
                for (Socket socket : accepted) socket.close();
            }
        }
    }

    @Test public void resetAfterHeadersCannotFallBackToASecondHttpResponse() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void flush() { committed.countDown(); }
        };
        try (Server server = new Server(null, socket -> {
            readRequest(socket);
            socket.getOutputStream().write(headers().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            assertTrue(committed.await(3, TimeUnit.SECONDS));
            socket.setSoLinger(true, 0); // RST while RangeFetcher waits for the first body byte.
        })) {
            int result = RangeFetcher.streamRange(server.url("http", "localhost"),
                    Collections.emptyMap(), 0, 3, 1, 0, output);
            assertEquals(RangeFetcher.STREAM_ABORTED, result);
            assertEquals(headers(), output.toString(StandardCharsets.ISO_8859_1));
            server.await();
        }
    }

    @Test public void partialResponseHeaderWriteCannotFallBack() throws Exception {
        try (Server server = new Server(null, socket -> {
            readRequest(socket);
            socket.getOutputStream().write(response());
            socket.getOutputStream().flush();
        })) {
            OutputStream failing = new OutputStream() {
                int count;
                @Override public void write(int value) throws IOException {
                    if (++count > 12) throw new IOException("client disconnected during headers");
                }
            };
            assertEquals(RangeFetcher.STREAM_ABORTED, RangeFetcher.streamRange(
                    server.url("http", "localhost"), Collections.emptyMap(), 0, 3, 1, 0, failing));
            server.await();
        }
    }

    private static String headers() {
        return "HTTP/1.1 206 Partial Content\r\nContent-Length: 4\r\n"
                + "Content-Range: bytes 0-3/4\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n";
    }

    private static byte[] response() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("HTTP/1.1 206 Partial Content\r\nContent-Length: 4\r\n"
                + "Content-Range: bytes 0-3/4\r\nConnection: keep-alive\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(BODY);
        return out.toByteArray();
    }

    private static String readRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
        StringBuilder request = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) request.append(line).append("\r\n");
        return request.toString();
    }

    private interface Handler { void handle(Socket socket) throws Exception; }

    private static class Server implements AutoCloseable {
        final ServerSocket listener;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> handled;
        Server(SSLContext tls, Handler handler) throws IOException {
            listener = tls == null ? new ServerSocket(0) : tls.getServerSocketFactory().createServerSocket(0);
            listener.setSoTimeout(3000);
            handled = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    handler.handle(socket);
                }
                return null;
            });
        }
        String url(String scheme, String host) { return scheme + "://" + host + ":" + listener.getLocalPort() + "/media"; }
        void await() throws Exception { handled.get(5, TimeUnit.SECONDS); }
        @Override public void close() throws IOException { listener.close(); executor.shutdownNow(); }
    }
}
