package org.majki.intellij.ldapbrowser.ldap;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.idea.IdeaLogger;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.xmlb.annotations.Transient;
import org.apache.directory.api.ldap.codec.api.DefaultConfigurableBinaryAttributeDetector;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.exception.LdapTlsHandshakeException;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.api.LdapConnectionConfig;
import org.apache.directory.ldap.client.api.LdapNetworkConnection;
import org.majki.intellij.ldapbrowser.TextBundle;

import java.io.IOException;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.concurrent.atomic.AtomicInteger;


public class LdapConnectionInfo implements Serializable {

    private static final Logger LOGGER = IdeaLogger.getInstance(LdapConnectionInfo.class);
    private String name = "<unnamed ldap connection>";
    private String host = "localhost";
    private int port = 636;
    private String baseDn;
    private boolean auth = false;
    private boolean ssl = true;
    private String username;
    private String password;
    private String trustedCertificateFingerprint;

    @Transient
    private UntrustedCertificate untrustedCertificate;

    @Transient
    private LdapConnection ldapConnection;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBaseDn() {
        return baseDn == null ? "" : baseDn;
    }

    public void setBaseDn(String baseDn) {
        this.baseDn = baseDn;
    }

    public boolean isAuth() {
        return auth;
    }

    public void setAuth(boolean auth) {
        this.auth = auth;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public boolean isOpened() {
        return ldapConnection != null && ldapConnection.isConnected();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return name + " (" + asUrl() + ")";
    }

    public LdapConnectionInfo getCopy() {
        LdapConnectionInfo info = new LdapConnectionInfo();
        info.name = name;
        info.host = host;
        info.port = port;
        info.baseDn = baseDn;
        info.auth = auth;
        info.ssl = ssl;
        info.username = username;
        info.password = password;
        info.trustedCertificateFingerprint = trustedCertificateFingerprint;
        return info;
    }

    @Transient
    LdapConnection getLdapConnection() {
        if (!isOpened()) {
            connect();
        }
        return ldapConnection;
    }

    public String asUrl() {
        return (ssl ? "ldaps" : "ldap") + "://" + (host == null ? LdapConnectionConfig.DEFAULT_LDAP_HOST : host) + ":" + port;
    }

    private LdapConnectionConfig createLdapConnectionConfiguration() {
        return createLdapConnectionConfiguration(host, port, ssl);
    }

    private LdapConnectionConfig createLdapConnectionConfiguration(String host, int port, boolean ssl) {
        LdapConnectionConfig config = new LdapConnectionConfig();
        config.setUseSsl(ssl);
        config.setLdapPort(port);
        config.setLdapHost(host);
        config.setUseSsl(ssl);
        config.setTimeout(3000L);
        config.setBinaryAttributeDetector(new DefaultConfigurableBinaryAttributeDetector());
        config.setTrustManagers(new LdapConnectionTrustManager(this, config.getTrustManagers()));
        return config;
    }

    LdapConnection openReferralConnection(String host, int port, boolean ssl) throws LdapException {
        LdapNetworkConnection connection = new LdapNetworkConnection(createLdapConnectionConfiguration(host, port, ssl));
        try {
            if (auth) {
                connection.bind(username, password);
            } else {
                connection.bind();
            }
            return connection;
        } catch (LdapException e) {
            connection.close();
            throw e;
        }
    }

    public boolean connect() {
        if (isOpened()) {
            return true;
        }
        closeQuietly();
        ldapConnection = new LdapNetworkConnection(createLdapConnectionConfiguration());
        try {
            if (auth) {
                ldapConnection.bind(username, password);
            } else {
                ldapConnection.bind();
            }
            return true;
        } catch (LdapException e) {
            closeQuietly();
            if (handleSslUntrustedCertificate(e)) {
                return connect();
            } else {
                showErrorDialog(detailedConnectionFailure(e), TextBundle.message("ldapbrowser.connection-failure"));
                return false;
            }
        }
    }

    private void closeQuietly() {
        if (ldapConnection != null) {
            try {
                ldapConnection.close();
            } catch (IOException ex) {
                LOGGER.warn("Could not close LDAP connection", ex);
            }
        }
        ldapConnection = null;
    }

    public void disconnect() {
        if (isOpened()) {
            try {
                ldapConnection.unBind();
                ldapConnection.close();
            } catch (LdapException e) {
                showErrorDialog(e.getMessage(), TextBundle.message("ldapbrowser.connection-unbind-failure"));
            } catch (IOException e) {
                showErrorDialog(e.getMessage(), TextBundle.message("ldapbrowser.connection-close-failure"));
            }
        }
        ldapConnection = null;
    }

    public boolean testConnection() {
        try (LdapConnection connection = new LdapNetworkConnection(createLdapConnectionConfiguration())) {
            if (auth) {
                connection.bind(username, password);
            } else {
                connection.bind();
            }

            connection.unBind();

            return true;
        } catch (LdapException | IOException e) {
            if (e instanceof LdapException && handleSslUntrustedCertificate((LdapException) e)) {
                return testConnection();
            } else {
                showErrorDialog(
                    detailedConnectionFailure(e),
                    TextBundle.message("ldapbrowser.connection-failure")
                );
                return false;
            }
        }
    }

    private boolean handleSslUntrustedCertificate(LdapException ldapException) {
        if (ldapException instanceof LdapTlsHandshakeException) {
            if (untrustedCertificate != null) {
                X509Certificate certificate = untrustedCertificate.certificate;
                String fingerprint = untrustedCertificate.fingerprint;
                untrustedCertificate = null;
                SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
                int result = showYesNoDialog(
                    "Certificate subject: " + certificate.getSubjectDN()
                        + ", issuer: " + certificate.getIssuerDN() + "\n"
                        + "Valid from " + dateFormatter.format(certificate.getNotBefore())
                        + " to " + dateFormatter.format(certificate.getNotAfter()),
                    "Trust Certificate?",
                    "Trust", "Do not trust",
                    Messages.getQuestionIcon());
                if (result != Messages.NO) {
                    trustedCertificateFingerprint = fingerprint;
                    return true;
                }
            }
        }
        return false;
    }

    private String detailedConnectionFailure(Exception exception) {
        Throwable rootCause = rootCause(exception);
        String details = rootCause.getMessage() == null ? exception.getMessage() : rootCause.getMessage();
        String category = connectionFailureCategory(exception, rootCause);

        return "Could not connect to " + asUrl() + "\n"
            + "Cause: " + category + "\n"
            + "Details: " + (details == null || details.trim().isEmpty() ? rootCause.getClass().getSimpleName() : details);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable result = throwable;
        while (result.getCause() != null && result.getCause() != result) {
            result = result.getCause();
        }
        return result;
    }

    private String connectionFailureCategory(Throwable exception, Throwable rootCause) {
        String message = (exception.getMessage() == null ? "" : exception.getMessage()).toLowerCase();
        String rootMessage = (rootCause.getMessage() == null ? "" : rootCause.getMessage()).toLowerCase();
        if (exception instanceof LdapTlsHandshakeException || message.contains("ssl") || message.contains("tls")) {
            return "TLS/SSL handshake or certificate problem";
        }
        if (rootCause instanceof UnknownHostException) {
            return "Unknown LDAP host";
        }
        if (rootCause instanceof SocketTimeoutException || message.contains("timeout") || rootMessage.contains("timeout")) {
            return "Connection timed out";
        }
        if (rootCause instanceof ConnectException || rootMessage.contains("connection refused")) {
            return "Connection refused by LDAP host/port";
        }
        if (message.contains("invalid credentials") || message.contains("49")) {
            return auth ? "Invalid username / Bind DN or password" : "LDAP server rejected anonymous bind";
        }
        if (message.contains("schema")) {
            return "LDAP schema loading failed";
        }
        return "LDAP bind or network error";
    }

    public String getTrustedCertificateFingerprint() {
        return trustedCertificateFingerprint;
    }

    public void setTrustedCertificateFingerprint(String trustedCertificateFingerprint) {
        this.trustedCertificateFingerprint = trustedCertificateFingerprint;
    }

    void setUntrustedCertificate(X509Certificate certificate, String fingerprint) {
        untrustedCertificate = new UntrustedCertificate();
        untrustedCertificate.fingerprint = fingerprint;
        untrustedCertificate.certificate = certificate;
    }

    private int showYesNoDialog(String message, String title, String yesText, String noText, javax.swing.Icon icon) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return Messages.showYesNoDialog(message, title, yesText, noText, icon);
        }
        AtomicInteger result = new AtomicInteger(Messages.NO);
        ApplicationManager.getApplication().invokeAndWait(() ->
            result.set(Messages.showYesNoDialog(message, title, yesText, noText, icon)));
        return result.get();
    }

    private void showErrorDialog(String message, String title) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            Messages.showErrorDialog(message, title);
        } else {
            ApplicationManager.getApplication().invokeLater(() -> Messages.showErrorDialog(message, title));
        }
    }

    private static class UntrustedCertificate {
        private String fingerprint;
        private X509Certificate certificate;
    }
}
