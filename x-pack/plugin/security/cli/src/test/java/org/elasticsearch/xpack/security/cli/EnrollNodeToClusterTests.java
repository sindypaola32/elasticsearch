/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.security.cli;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import org.elasticsearch.Version;
import org.elasticsearch.cli.Command;
import org.elasticsearch.cli.CommandTestCase;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.CheckedSupplier;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.ssl.PemUtils;
import org.elasticsearch.core.CheckedFunction;
import org.elasticsearch.core.PathUtilsForTesting;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.core.security.CommandLineHttpClient;
import org.elasticsearch.xpack.core.security.EnrollmentToken;
import org.elasticsearch.xpack.core.security.HttpResponse;
import org.elasticsearch.xpack.core.ssl.CertParsingUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.security.cli.EnrollNodeToCluster.HTTP_AUTOGENERATED_CA_NAME;
import static org.elasticsearch.xpack.security.cli.EnrollNodeToCluster.HTTP_AUTOGENERATED_KEYSTORE_NAME;
import static org.elasticsearch.xpack.security.cli.EnrollNodeToCluster.TLS_CONFIG_DIR_NAME_PREFIX;
import static org.elasticsearch.xpack.security.cli.EnrollNodeToCluster.TRANSPORT_AUTOGENERATED_CERT_ALIAS;
import static org.elasticsearch.xpack.security.cli.EnrollNodeToCluster.TRANSPORT_AUTOGENERATED_KEYSTORE_NAME;
import static org.elasticsearch.xpack.security.cli.EnrollNodeToCluster.TRANSPORT_AUTOGENERATED_KEY_ALIAS;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EnrollNodeToClusterTests extends CommandTestCase {

    static FileSystem jimfs;
    private Path confDir;
    private CommandLineHttpClient client;
    static String keystoresPassword;
    Settings settings;

    @Override
    protected Command newCommand() {
        return new EnrollNodeToCluster(((environment, pinnedCaCertFingerprint) -> client)) {
            @Override
            protected Environment createEnv(Map<String, String> settings) {
                return new Environment(EnrollNodeToClusterTests.this.settings, confDir);
            }

            @Override
            SecureString newKeystorePassword() {
                return new SecureString(keystoresPassword.toCharArray());
            }
        };
    }

    @BeforeClass
    public static void setupJimfs() {
        Configuration conf = Configuration.unix().toBuilder().setAttributeViews("posix", "owner").build();
        jimfs = Jimfs.newFileSystem(conf);
        PathUtilsForTesting.installMock(jimfs);
    }

    @Before
    @SuppressWarnings("unchecked")
    @SuppressForbidden(reason = "Cannot use getDataPath() as Paths.get() throws UnsupportedOperationException for jimfs")
    public void setup() throws Exception {
        Path homeDir = jimfs.getPath("eshome");
        IOUtils.rm(homeDir);
        confDir = homeDir.resolve("config");
        Files.createDirectories(confDir);
        settings = Settings.builder().put("path.home", homeDir).build();
        Files.createFile(confDir.resolve("elasticsearch.yml"));
        String httpCaCertPemString = Files.readAllLines(
            Paths.get(getClass().getResource("http_ca.crt").toURI()).toAbsolutePath().normalize()
        ).stream().filter(l -> l.contains("-----") == false).collect(Collectors.joining());
        String httpCaKeyPemString = Files.readAllLines(
            Paths.get(getClass().getResource("http_ca.key").toURI()).toAbsolutePath().normalize()
        ).stream().filter(l -> l.contains("-----") == false).collect(Collectors.joining());
        String transportKeyPemString = Files.readAllLines(
            Paths.get(getClass().getResource("transport.key").toURI()).toAbsolutePath().normalize()
        ).stream().filter(l -> l.contains("-----") == false).collect(Collectors.joining());
        String transportCertPemString = Files.readAllLines(
            Paths.get(getClass().getResource("transport.crt").toURI()).toAbsolutePath().normalize()
        ).stream().filter(l -> l.contains("-----") == false).collect(Collectors.joining());

        HttpResponse nodeEnrollResponse = new HttpResponse(
            HttpURLConnection.HTTP_OK,
            Map.of(
                "http_ca_key",
                httpCaKeyPemString,
                "http_ca_cert",
                httpCaCertPemString,
                "transport_key",
                transportKeyPemString,
                "transport_cert",
                transportCertPemString,
                "nodes_addresses",
                List.of("127.0.0.1:9300", "192.168.1.10:9301")
            )
        );
        this.client = mock(CommandLineHttpClient.class);
        when(client.execute(anyString(), any(URL.class), any(SecureString.class), any(CheckedSupplier.class), any(CheckedFunction.class)))
            .thenReturn(nodeEnrollResponse);
        keystoresPassword = randomAlphaOfLengthBetween(14, 18);
    }

    @AfterClass
    public static void closeJimfs() throws IOException {
        if (jimfs != null) {
            jimfs.close();
            jimfs = null;
        }
    }

    @SuppressForbidden(reason = "Cannot use getDataPath() as Paths.get() throws UnsupportedOperationException for jimfs")
    public void testEnrollmentSuccess() throws Exception {
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );
        execute("--enrollment-token", enrollmentToken.getEncoded());
        final Path autoConfigDir = assertAutoConfigurationFilesCreated();
        assertTransportKeystore(
            autoConfigDir.resolve(TRANSPORT_AUTOGENERATED_KEYSTORE_NAME + ".p12"),
            Paths.get(getClass().getResource("transport.key").toURI()).toAbsolutePath().normalize(),
            Paths.get(getClass().getResource("transport.crt").toURI()).toAbsolutePath().normalize()
        );
        assertHttpKeystore(
            autoConfigDir.resolve(HTTP_AUTOGENERATED_KEYSTORE_NAME + ".p12"),
            Paths.get(getClass().getResource("http_ca.key").toURI()).toAbsolutePath().normalize(),
            Paths.get(getClass().getResource("http_ca.crt").toURI()).toAbsolutePath().normalize()
        );
    }

    public void testEnrollmentExitsOnAlreadyConfiguredNode() throws Exception {
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );
        Path dataDir = Files.createDirectory(jimfs.getPath("eshome").resolve("data"));
        Files.createFile(dataDir.resolve("foo"));
        settings = Settings.builder().put(settings).put("path.data", dataDir).put("xpack.security.enrollment.enabled", true).build();
        UserException e = expectThrows(UserException.class, () -> execute("--enrollment-token", enrollmentToken.getEncoded()));
        assertThat(e.getMessage(), equalTo("Aborting enrolling to cluster. It appears that this is not the first time this node starts."));
        assertAutoConfigurationFilesNotCreated();
    }

    public void testEnrollmentDoesNotExitOnAlreadyConfiguredNodeIfForced() throws Exception {
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );
        Path dataDir = Files.createDirectory(jimfs.getPath("eshome").resolve("data"));
        Files.createFile(dataDir.resolve("foo"));
        settings = Settings.builder().put(settings).put("path.data", dataDir).put("xpack.security.enrollment.enabled", true).build();
        execute("--enrollment-token", enrollmentToken.getEncoded(), randomFrom("-f", "--force"));
        assertAutoConfigurationFilesCreated();
    }

    public void testEnrollmentExitsOnInvalidEnrollmentToken() throws Exception {
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );

        UserException e = expectThrows(
            UserException.class,
            () -> execute(
                "--enrollment-token",
                enrollmentToken.getEncoded().substring(0, enrollmentToken.getEncoded().length() - randomIntBetween(6, 12))
            )
        );
        assertThat(e.getMessage(), equalTo("Aborting enrolling to cluster. Invalid enrollment token"));
        assertAutoConfigurationFilesNotCreated();
    }

    @SuppressWarnings("unchecked")
    public void testEnrollmentExitsOnUnexpectedResponse() throws Exception {
        when(client.execute(anyString(), any(URL.class), any(SecureString.class), any(CheckedSupplier.class), any(CheckedFunction.class)))
            .thenReturn(new HttpResponse(randomFrom(401, 403, 500), Map.of()));
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );
        UserException e = expectThrows(UserException.class, () -> execute("--enrollment-token", enrollmentToken.getEncoded()));
        assertThat(
            e.getMessage(),
            equalTo(
                "Aborting enrolling to cluster. "
                    + "Could not communicate with the initial node in any of the addresses from the enrollment token. All of "
                    + enrollmentToken.getBoundAddress()
                    + "where attempted."
            )
        );
        assertAutoConfigurationFilesNotCreated();
    }

    public void testEnrollmentExitsOnExistingSecurityConfiguration() throws Exception {
        settings = Settings.builder().put(settings).put("xpack.security.enabled", true).build();
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );
        UserException e = expectThrows(UserException.class, () -> execute("--enrollment-token", enrollmentToken.getEncoded()));
        assertThat(e.getMessage(), equalTo("Aborting enrolling to cluster. It appears that security is already configured."));
        assertAutoConfigurationFilesNotCreated();
    }

    @SuppressForbidden(reason = "Cannot use getDataPath() as Paths.get() throws UnsupportedOperationException for jimfs")
    public void testEnrollmentDoesNotExitOnExistingSecurityConfigurationIfForced() throws Exception {
        settings = Settings.builder().put(settings).put("xpack.security.enabled", true).build();
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );
        execute("--enrollment-token", enrollmentToken.getEncoded(), randomFrom("-f", "--force"));
        final Path autoConfigDir = assertAutoConfigurationFilesCreated();
        assertTransportKeystore(
            autoConfigDir.resolve(TRANSPORT_AUTOGENERATED_KEYSTORE_NAME + ".p12"),
            Paths.get(getClass().getResource("transport.key").toURI()).toAbsolutePath().normalize(),
            Paths.get(getClass().getResource("transport.crt").toURI()).toAbsolutePath().normalize()
        );
        assertHttpKeystore(
            autoConfigDir.resolve(HTTP_AUTOGENERATED_KEYSTORE_NAME + ".p12"),
            Paths.get(getClass().getResource("http_ca.key").toURI()).toAbsolutePath().normalize(),
            Paths.get(getClass().getResource("http_ca.crt").toURI()).toAbsolutePath().normalize()
        );
    }

    public void testEnrollmentExitsOnExistingTlsConfiguration() throws Exception {
        settings = Settings.builder()
            .put(settings)
            .put("xpack.security.transport.ssl.enabled", true)
            .put("xpack.security.http.ssl.enabled", true)
            .build();
        final EnrollmentToken enrollmentToken = new EnrollmentToken(
            randomAlphaOfLength(12),
            randomAlphaOfLength(12),
            Version.CURRENT.toString(),
            List.of("127.0.0.1:9200")
        );
        UserException e = expectThrows(UserException.class, () -> execute("--enrollment-token", enrollmentToken.getEncoded()));
        assertThat(e.getMessage(), equalTo("Aborting enrolling to cluster. It appears that TLS is already configured."));
        assertAutoConfigurationFilesNotCreated();
    }

    private Path assertAutoConfigurationFilesCreated() throws Exception {
        List<Path> f = Files.find(
            confDir,
            2,
            ((path, basicFileAttributes) -> Files.isDirectory(path) && path.getFileName().toString().startsWith(TLS_CONFIG_DIR_NAME_PREFIX))
        ).collect(Collectors.toList());
        assertThat(f.size(), equalTo(1));
        final Path autoConfigDir = f.get(0);
        assertThat(Files.isRegularFile(autoConfigDir.resolve(HTTP_AUTOGENERATED_CA_NAME + ".crt")), is(true));
        assertThat(Files.isRegularFile(autoConfigDir.resolve(HTTP_AUTOGENERATED_KEYSTORE_NAME + ".p12")), is(true));
        assertThat(Files.isRegularFile(autoConfigDir.resolve(TRANSPORT_AUTOGENERATED_KEYSTORE_NAME + ".p12")), is(true));

        return autoConfigDir;
    }

    private void assertAutoConfigurationFilesNotCreated() throws Exception {
        List<Path> f = Files.find(
            confDir,
            2,
            ((path, basicFileAttributes) -> Files.isDirectory(path) && path.getFileName().toString().startsWith(TLS_CONFIG_DIR_NAME_PREFIX))
        ).collect(Collectors.toList());
        assertThat(f.size(), equalTo(0));
    }

    private void assertTransportKeystore(Path keystorePath, Path keyPath, Path certPath) throws Exception {
        try (InputStream in = Files.newInputStream(keystorePath)) {
            final KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, keystoresPassword.toCharArray());
            assertThat(keyStore.size(), equalTo(2));
            assertThat(keyStore.isKeyEntry(TRANSPORT_AUTOGENERATED_KEY_ALIAS), is(true));
            assertThat(keyStore.isCertificateEntry(TRANSPORT_AUTOGENERATED_CERT_ALIAS), is(true));
            assertThat(
                keyStore.getKey(TRANSPORT_AUTOGENERATED_KEY_ALIAS, keystoresPassword.toCharArray()),
                equalTo(PemUtils.readPrivateKey(keyPath, () -> null))
            );
            assertThat(
                keyStore.getCertificate(TRANSPORT_AUTOGENERATED_CERT_ALIAS),
                equalTo(CertParsingUtils.readX509Certificate(certPath))
            );
            assertThat(keyStore.getCertificate(TRANSPORT_AUTOGENERATED_KEY_ALIAS), equalTo(CertParsingUtils.readX509Certificate(certPath)));
        }
    }

    private void assertHttpKeystore(Path keystorePath, Path keyPath, Path certPath) throws Exception {
        try (InputStream in = Files.newInputStream(keystorePath)) {
            final KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, keystoresPassword.toCharArray());
            assertThat(keyStore.size(), equalTo(2));
            assertThat(keyStore.isKeyEntry(HTTP_AUTOGENERATED_KEYSTORE_NAME + "_ca"), is(true));
            assertThat(keyStore.isKeyEntry(HTTP_AUTOGENERATED_KEYSTORE_NAME), is(true));
            assertThat(
                keyStore.getCertificate(HTTP_AUTOGENERATED_KEYSTORE_NAME + "_ca"),
                equalTo(CertParsingUtils.readX509Certificate(certPath))
            );
            assertThat(
                keyStore.getKey(HTTP_AUTOGENERATED_KEYSTORE_NAME + "_ca", keystoresPassword.toCharArray()),
                equalTo(PemUtils.readPrivateKey(keyPath, () -> null))
            );
            keyStore.getCertificate(HTTP_AUTOGENERATED_KEYSTORE_NAME).verify(CertParsingUtils.readX509Certificate(certPath).getPublicKey());
            // Certificate#verify didn't throw
        }
    }

}
