/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.http.server;

import com.google.common.collect.ImmutableSet;
import io.airlift.http.server.HttpServer.ClientCertificate;
import io.airlift.node.NodeInfo;
import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static com.google.common.io.Resources.getResource;
import static java.nio.file.Files.createTempDirectory;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

@TestInstance(PER_CLASS)
@Execution(SAME_THREAD)
public class TestHttpServerCipher
{
    private static final String KEY_STORE_PATH = constructKeyStorePath();
    private static final String KEY_STORE_PASSWORD = "airlift";
    public static final String CIPHER_1 = "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256";
    public static final String CIPHER_2 = "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256";
    public static final String CIPHER_3 = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";

    private File tempDir;

    private static String constructKeyStorePath()
    {
        try {
            return new File(getResource("test.keystore").toURI()).getAbsolutePath();
        }
        catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    public void setup()
            throws IOException
    {
        tempDir = createTempDirectory(null).toFile();
    }

    @AfterEach
    public void teardown()
            throws Exception
    {
        deleteRecursively(tempDir.toPath(), ALLOW_INSECURE);
    }

    @Test
    public void testIncludeCipherEmpty()
            throws Exception
    {
        HttpServerConfig config = createHttpServerConfig();
        HttpsConfig httpsConfig = createHttpsConfig()
                .setHttpsExcludedCipherSuites("")
                .setHttpsIncludedCipherSuites(" ,   ");
        NodeInfo nodeInfo = new NodeInfo("test");
        HttpServerInfo httpServerInfo = new HttpServerInfo(config, Optional.of(httpsConfig), nodeInfo);
        HttpServer server = createServer(nodeInfo, httpServerInfo, config, httpsConfig);
        try {
            server.start();
            URI httpsUri = httpServerInfo.getHttpsUri();

            HttpClient httpClient = createClientIncludeCiphers(CIPHER_1);
            httpClient.GET(httpsUri);

            httpClient = createClientIncludeCiphers(CIPHER_2);
            httpClient.GET(httpsUri);

            httpClient = createClientIncludeCiphers(CIPHER_3);
            httpClient.GET(httpsUri);
        }
        finally {
            server.stop();
        }
    }

    @Test
    public void testIncludedCipher()
            throws Exception
    {
        HttpServerConfig config = createHttpServerConfig();
        HttpsConfig httpsConfig = createHttpsConfig()
                .setHttpsExcludedCipherSuites("")
                .setHttpsIncludedCipherSuites(CIPHER_1 + "," + CIPHER_2);
        NodeInfo nodeInfo = new NodeInfo("test");
        HttpServerInfo httpServerInfo = new HttpServerInfo(config, Optional.of(httpsConfig), nodeInfo);
        HttpServer server = createServer(nodeInfo, httpServerInfo, config, httpsConfig);
        try {
            server.start();
            URI httpsUri = httpServerInfo.getHttpsUri();

            // should succeed because only one of the two allowed certificate is excluded
            HttpClient httpClient = createClientIncludeCiphers(CIPHER_1);
            httpClient.GET(httpsUri);

            // should succeed because only one of the two allowed certificate is excluded
            httpClient = createClientIncludeCiphers(CIPHER_2);
            httpClient.GET(httpsUri);

            httpClient = createClientIncludeCiphers(CIPHER_3);
            try {
                httpClient.GET(httpsUri);
                fail("SSL handshake should fail because client included only ciphers the server didn't include");
            }
            catch (ExecutionException e) {
                // expected
            }
        }
        finally {
            server.stop();
        }
    }

    @Test
    public void testExcludedCipher()
            throws Exception
    {
        HttpServerConfig config = createHttpServerConfig();
        HttpsConfig httpsConfig = createHttpsConfig()
                .setHttpsExcludedCipherSuites(CIPHER_1 + "," + CIPHER_2);
        NodeInfo nodeInfo = new NodeInfo("test");
        HttpServerInfo httpServerInfo = new HttpServerInfo(config, Optional.of(httpsConfig), nodeInfo);
        HttpServer server = createServer(nodeInfo, httpServerInfo, config, httpsConfig);

        try {
            server.start();
            URI httpsUri = httpServerInfo.getHttpsUri();

            // should succeed because all ciphers accepted
            HttpClient httpClient = createClientIncludeCiphers();
            httpClient.GET(httpsUri);

            httpClient = createClientIncludeCiphers(CIPHER_1, CIPHER_2);
            try {
                httpClient.GET(httpsUri);
                fail("SSL handshake should fail because client included only ciphers the server excluded");
            }
            catch (ExecutionException e) {
                // expected
            }
        }
        finally {
            server.stop();
        }
    }

    private HttpServerConfig createHttpServerConfig()
    {
        return new HttpServerConfig()
                .setHttpEnabled(false)
                .setHttpsEnabled(true)
                .setLogPath(new File(tempDir, "http-request.log").getAbsolutePath());
    }

    private static HttpsConfig createHttpsConfig()
    {
        return new HttpsConfig()
                .setHttpsPort(0)
                .setKeystorePath(KEY_STORE_PATH)
                .setKeystorePassword(KEY_STORE_PASSWORD);
    }

    private static HttpClient createClientIncludeCiphers(String... includedCipherSuites)
            throws Exception
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setIncludeCipherSuites(includedCipherSuites);
        sslContextFactory.setKeyStorePath(KEY_STORE_PATH);
        sslContextFactory.setKeyStorePassword(KEY_STORE_PASSWORD);

        ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(sslContextFactory);

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(connector));
        httpClient.start();
        return httpClient;
    }

    private static HttpServer createServer(NodeInfo nodeInfo, HttpServerInfo httpServerInfo, HttpServerConfig config, HttpsConfig httpsConfig)
    {
        return createServer(new DummyServlet(), nodeInfo, httpServerInfo, config, httpsConfig);
    }

    private static HttpServer createServer(HttpServlet servlet, NodeInfo nodeInfo, HttpServerInfo httpServerInfo, HttpServerConfig config, HttpsConfig httpsConfig)
    {
        return new HttpServerProvider(
                httpServerInfo,
                nodeInfo,
                config,
                Optional.of(httpsConfig),
                servlet,
                ImmutableSet.of(new DummyFilter()),
                ImmutableSet.of(),
                false,
                false,
                false,
                ClientCertificate.NONE,
                Optional.empty()).get();
    }
}
