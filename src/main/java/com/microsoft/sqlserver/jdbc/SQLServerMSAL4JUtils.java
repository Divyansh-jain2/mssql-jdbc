/*
 * Microsoft JDBC Driver for SQL Server Copyright(c) Microsoft Corporation All rights reserved. This program is made
 * available under the terms of the MIT License. See the LICENSE file in the project root for more information.
 */

package com.microsoft.sqlserver.jdbc;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;

import java.text.MessageFormat;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

import javax.security.auth.kerberos.KerberosPrincipal;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IClientCredential;
import com.microsoft.aad.msal4j.IntegratedWindowsAuthenticationParameters;
import com.microsoft.aad.msal4j.InteractiveRequestParameters;
import com.microsoft.aad.msal4j.MsalInteractionRequiredException;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import com.microsoft.aad.msal4j.SystemBrowserOptions;
import com.microsoft.aad.msal4j.UserNamePasswordParameters;

import com.microsoft.sqlserver.jdbc.SQLServerConnection.ActiveDirectoryAuthentication;
import com.microsoft.sqlserver.jdbc.SQLServerConnection.SqlFedAuthInfo;

import static com.microsoft.sqlserver.jdbc.Util.getHashedSecret;


class SQLServerMSAL4JUtils {

    static final String REDIRECTURI = "http://localhost";
    static final String SLASH_DEFAULT = "/.default";
    static final String ACCESS_TOKEN_EXPIRE = "access token expires: ";
    static final long TOKEN_WAIT_DURATION_MS = 20000;

    // ===== .NET-inspired layered design (replaces Semaphore(1) + 5 s timer) =====
    //
    // Layer C: bound MSAL4J's DefaultHttpClient. MSAL4J defaults connectTimeout/readTimeout to 0
    // (= infinite). MSAL.NET's HttpClient defaults to 100 s. Without these the only thing saving
    // us from a hung AAD socket is the driver's own Future.get(20 s), which abandons the wait
    // but does NOT cancel the underlying socket. Set explicit bounds here.
    static final int MSAL_HTTP_CONNECT_TIMEOUT_MS = Integer.getInteger("mssql.msal.httpConnectTimeoutMs", 10_000);
    static final int MSAL_HTTP_READ_TIMEOUT_MS = Integer.getInteger("mssql.msal.httpReadTimeoutMs", 30_000);

    // Layer A: per-credential MSAL application cache. Mirrors MSAL.NET's s_pcaMap pattern so
    // the long-lived application object survives across connections and its internal in-memory
    // token cache (plus the Aspect's JSON cache) can serve subsequent hits cheaply.
    private static final ConcurrentMap<String, ConfidentialClientApplication> CCA_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, PublicClientApplication> PCA_CACHE = new ConcurrentHashMap<>();

    // Layer B: per-key single-flight rendezvous. Replaces the JVM-wide Semaphore(1). Cold-burst
    // followers wait on the leader's CompletableFuture; exactly 1 AAD call per credential per
    // cold window. The .NET stack does not need this because MSAL.NET coalesces internally;
    // MSAL4J does not document equivalent coalescing, so we provide it on the driver side.
    private static final ConcurrentMap<String, CompletableFuture<SqlAuthenticationToken>> IN_FLIGHT = new ConcurrentHashMap<>();

    // Shared daemon executor for cached MSAL applications. A per-call ExecutorService cannot be
    // shutdown() after each call when the CCA/PCA is cached for reuse, so we share one process-
    // wide daemon pool. Threads are daemons so they never block JVM shutdown.
    private static final ThreadFactory MSAL_THREAD_FACTORY = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "mssql-jdbc-msal");
            t.setDaemon(true);
            return t;
        }
    };
    private static final ExecutorService SHARED_MSAL_EXECUTOR = Executors.newCachedThreadPool(MSAL_THREAD_FACTORY);

    private static final TokenCacheMap TOKEN_CACHE_MAP = new TokenCacheMap();

    private final static String LOGCONTEXT = "MSAL version "
            + com.microsoft.aad.msal4j.PublicClientApplication.class.getPackage().getImplementationVersion() + ": ";

    private static final java.util.logging.Logger logger = java.util.logging.Logger
            .getLogger("com.microsoft.sqlserver.jdbc.SQLServerMSAL4JUtils");

    private SQLServerMSAL4JUtils() {
        throw new UnsupportedOperationException(SQLServerException.getErrString("R_notSupported"));
    }

    static SqlAuthenticationToken getSqlFedAuthToken(SqlFedAuthInfo fedAuthInfo, String user, String password,
            String authenticationString, int millisecondsRemaining) throws SQLServerException {

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token for user: " + user);
        }

        final String hashedSecret = getHashedSecret(new String[] {fedAuthInfo.stsurl, user, password});
        PersistentTokenCacheAccessAspect aspect = TOKEN_CACHE_MAP.getEntry(user, hashedSecret);
        if (null == aspect) {
            aspect = new PersistentTokenCacheAccessAspect();
            TOKEN_CACHE_MAP.addEntry(hashedSecret, aspect);
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + ": cache token for user: " + user);
            }
        } else if (logger.isLoggable(Level.FINER)) {
            logger.finer(LOGCONTEXT + ": retrieved cached token for user: " + user);
        }

        // Layer A: get-or-build per-credential PCA (cached for reuse, HTTP timeouts applied).
        final String cacheKey = "pwd:" + hashedSecret;
        final PersistentTokenCacheAccessAspect aspectRef = aspect;
        final PublicClientApplication pca;
        try {
            pca = PCA_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return PublicClientApplication
                            .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID)
                            .executorService(SHARED_MSAL_EXECUTOR)
                            .setTokenCacheAccessAspect(aspectRef)
                            .authority(fedAuthInfo.stsurl)
                            .connectTimeoutForDefaultHttpClient(MSAL_HTTP_CONNECT_TIMEOUT_MS)
                            .readTimeoutForDefaultHttpClient(MSAL_HTTP_READ_TIMEOUT_MS)
                            .build();
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            Throwable c = re.getCause();
            if (c instanceof MalformedURLException) {
                throw getCorrectedException((Exception) c, user, authenticationString);
            }
            throw re;
        }

        // Layer B: single-flight. Followers wait on the leader's future; only the leader hits AAD.
        CompletableFuture<SqlAuthenticationToken> mine = new CompletableFuture<>();
        CompletableFuture<SqlAuthenticationToken> winner = IN_FLIGHT.putIfAbsent(cacheKey, mine);
        if (winner != null) {
            try {
                return winner.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLServerException(e.getMessage(), e);
            } catch (ExecutionException e) {
                throw getCorrectedException(e, user, authenticationString);
            } catch (TimeoutException e) {
                throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), user, authenticationString);
            }
        }

        // We are the leader: do the real AAD call exactly once for this cold-burst.
        try {
            CompletableFuture<IAuthenticationResult> future = pca.acquireToken(UserNamePasswordParameters
                    .builder(Collections.singleton(fedAuthInfo.spn + SLASH_DEFAULT), user, password.toCharArray())
                    .build());
            IAuthenticationResult ar = future.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + (ar.account() != null ? ar.account().username() + ": "
                        : "" + ACCESS_TOKEN_EXPIRE + ar.expiresOnDate()));
            }
            SqlAuthenticationToken tok = new SqlAuthenticationToken(ar.accessToken(), ar.expiresOnDate());
            mine.complete(tok);
            return tok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mine.completeExceptionally(e);
            throw new SQLServerException(e.getMessage(), e);
        } catch (ExecutionException e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(e, user, authenticationString);
        } catch (TimeoutException e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), user, authenticationString);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            IN_FLIGHT.remove(cacheKey, mine);
        }
    }

    static SqlAuthenticationToken getSqlFedAuthTokenPrincipal(SqlFedAuthInfo fedAuthInfo, String aadPrincipalID,
            String aadPrincipalSecret, String authenticationString, int millisecondsRemaining) throws SQLServerException {

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token for principal: " + aadPrincipalID);
        }

        String defaultScopeSuffix = SLASH_DEFAULT;
        String scope = fedAuthInfo.spn.endsWith(defaultScopeSuffix) ? fedAuthInfo.spn
                                                                    : fedAuthInfo.spn + defaultScopeSuffix;
        final Set<String> scopes = new HashSet<>();
        scopes.add(scope);

        final String hashedSecret = getHashedSecret(
                new String[] {fedAuthInfo.stsurl, aadPrincipalID, aadPrincipalSecret});
        PersistentTokenCacheAccessAspect aspect = TOKEN_CACHE_MAP.getEntry(aadPrincipalID, hashedSecret);
        if (null == aspect) {
            aspect = new PersistentTokenCacheAccessAspect();
            TOKEN_CACHE_MAP.addEntry(hashedSecret, aspect);
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + ": cache token for principal id: " + aadPrincipalID);
            }
        } else if (logger.isLoggable(Level.FINER)) {
            logger.finer(LOGCONTEXT + ": retrieved cached token for principal id: " + aadPrincipalID);
        }

        // Layer A: get-or-build per-credential CCA, cached for reuse across connections.
        final String cacheKey = "sp:" + hashedSecret;
        final PersistentTokenCacheAccessAspect aspectRef = aspect;
        final ConfidentialClientApplication cca;
        try {
            cca = CCA_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    IClientCredential credential = ClientCredentialFactory.createFromSecret(aadPrincipalSecret);
                    return buildSpCca(SHARED_MSAL_EXECUTOR, aadPrincipalID, credential, aspectRef,
                            fedAuthInfo.stsurl);
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            Throwable c = re.getCause();
            if (c instanceof MalformedURLException) {
                throw getCorrectedException((Exception) c, aadPrincipalID, authenticationString);
            }
            throw re;
        }

        // Layer B: single-flight. Followers wait on the leader's future; only the leader hits AAD.
        CompletableFuture<SqlAuthenticationToken> mine = new CompletableFuture<>();
        CompletableFuture<SqlAuthenticationToken> winner = IN_FLIGHT.putIfAbsent(cacheKey, mine);
        if (winner != null) {
            // Follower path: wait on the leader's future instead of issuing a redundant AAD call.
            try {
                return winner.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLServerException(e.getMessage(), e);
            } catch (ExecutionException e) {
                throw getCorrectedException(e, aadPrincipalID, authenticationString);
            } catch (TimeoutException e) {
                throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), aadPrincipalID, authenticationString);
            }
        }

        // Leader path: real AAD call exactly once per cold-burst per credential.
        try {
            CompletableFuture<IAuthenticationResult> future = cca
                    .acquireToken(ClientCredentialParameters.builder(scopes).build());
            IAuthenticationResult ar = future.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + (ar.account() != null ? ar.account().username() + ": "
                        : "" + ACCESS_TOKEN_EXPIRE + ar.expiresOnDate()));
            }
            SqlAuthenticationToken tok = new SqlAuthenticationToken(ar.accessToken(), ar.expiresOnDate());
            mine.complete(tok);
            return tok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mine.completeExceptionally(e);
            throw new SQLServerException(e.getMessage(), e);
        } catch (ExecutionException e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(e, aadPrincipalID, authenticationString);
        } catch (TimeoutException e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), aadPrincipalID, authenticationString);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            IN_FLIGHT.remove(cacheKey, mine);
        }
    }

    /**
     * Builds a ConfidentialClientApplication for the SP (client_credentials) path with explicit
     * HTTP connect/read timeouts so a hung AAD socket cannot outlive the driver's own deadline.
     */
    private static ConfidentialClientApplication buildSpCca(ExecutorService executorService, String clientId,
            IClientCredential credential, PersistentTokenCacheAccessAspect aspect, String authority)
            throws MalformedURLException {
        return ConfidentialClientApplication.builder(clientId, credential).executorService(executorService)
                .setTokenCacheAccessAspect(aspect).authority(authority)
                .connectTimeoutForDefaultHttpClient(MSAL_HTTP_CONNECT_TIMEOUT_MS)
                .readTimeoutForDefaultHttpClient(MSAL_HTTP_READ_TIMEOUT_MS).build();
    }

    static SqlAuthenticationToken getSqlFedAuthTokenPrincipalCertificate(SqlFedAuthInfo fedAuthInfo,
            String aadPrincipalID, String certFile, String certPassword, String certKey, String certKeyPassword,
            String authenticationString, int millisecondsRemaining) throws SQLServerException {

        if (logger.isLoggable(Level.FINEST)) {
            logger.finest(LOGCONTEXT + authenticationString + ": get FedAuth token for principal certificate: "
                    + aadPrincipalID);
        }

        String defaultScopeSuffix = SLASH_DEFAULT;
        String scope = fedAuthInfo.spn.endsWith(defaultScopeSuffix) ? fedAuthInfo.spn
                                                                    : fedAuthInfo.spn + defaultScopeSuffix;
        final Set<String> scopes = new HashSet<>();
        scopes.add(scope);

        final String hashedSecret = getHashedSecret(new String[] {fedAuthInfo.stsurl, aadPrincipalID, certFile,
                certPassword, certKey, certKeyPassword});
        PersistentTokenCacheAccessAspect aspect = TOKEN_CACHE_MAP.getEntry(aadPrincipalID, hashedSecret);
        if (null == aspect) {
            aspect = new PersistentTokenCacheAccessAspect();
            TOKEN_CACHE_MAP.addEntry(hashedSecret, aspect);
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + ": cache token for principal id: " + aadPrincipalID);
            }
        } else if (logger.isLoggable(Level.FINER)) {
            logger.finer(LOGCONTEXT + ": retrieved cached token for principal id: " + aadPrincipalID);
        }

        // Layer A: get-or-build per-credential CCA (loads cert PKCS12-first, X509-fallback once).
        final String cacheKey = "cert:" + hashedSecret;
        final PersistentTokenCacheAccessAspect aspectRef = aspect;
        final ConfidentialClientApplication cca;
        try {
            cca = CCA_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return buildCertCca(aadPrincipalID, certFile, certPassword, certKey, certKeyPassword,
                            aspectRef, fedAuthInfo.stsurl);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            Throwable c = re.getCause();
            if (c instanceof FileNotFoundException) {
                throw new SQLServerException(SQLServerException.getErrString("R_readCertError") + c.getMessage(), null,
                        0, null);
            }
            if (c instanceof GeneralSecurityException) {
                throw new SQLServerException(SQLServerException.getErrString("R_readCertError") + c.getMessage(), null,
                        0, null);
            }
            if (c instanceof Exception) {
                throw getCorrectedException((Exception) c, aadPrincipalID, authenticationString);
            }
            throw re;
        }

        // Layer B: single-flight
        CompletableFuture<SqlAuthenticationToken> mine = new CompletableFuture<>();
        CompletableFuture<SqlAuthenticationToken> winner = IN_FLIGHT.putIfAbsent(cacheKey, mine);
        if (winner != null) {
            try {
                return winner.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLServerException(e.getMessage(), e);
            } catch (ExecutionException e) {
                throw getCorrectedException(e, aadPrincipalID, authenticationString);
            } catch (TimeoutException e) {
                throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), aadPrincipalID, authenticationString);
            }
        }

        try {
            final CompletableFuture<IAuthenticationResult> future = cca
                    .acquireToken(ClientCredentialParameters.builder(scopes).build());
            final IAuthenticationResult ar = future.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + (ar.account() != null ? ar.account().username() + ": "
                        : "" + ACCESS_TOKEN_EXPIRE + ar.expiresOnDate()));
            }
            SqlAuthenticationToken tok = new SqlAuthenticationToken(ar.accessToken(), ar.expiresOnDate());
            mine.complete(tok);
            return tok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mine.completeExceptionally(e);
            throw new SQLServerException(e.getMessage(), e);
        } catch (TimeoutException e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), aadPrincipalID, authenticationString);
        } catch (Exception e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(e, aadPrincipalID, authenticationString);
        } finally {
            IN_FLIGHT.remove(cacheKey, mine);
        }
    }

    /**
     * Loads the credential (PKCS12 first, then X509 fallback) and builds a ConfidentialClientApplication
     * suitable for caching. Throws checked exceptions so the caller can map them to the right
     * SQLServerException surface.
     */
    private static ConfidentialClientApplication buildCertCca(String aadPrincipalID, String certFile,
            String certPassword, String certKey, String certKeyPassword, PersistentTokenCacheAccessAspect aspect,
            String authority) throws GeneralSecurityException, IOException, SQLServerException {
        ConfidentialClientApplication clientApplication = null;

        // check if cert is PKCS12 first
        try (InputStream is = new FileInputStream(certFile)) {
            KeyStore keyStore = SQLServerCertificateUtils.loadPKCS12KeyStore(certFile, certPassword);

            if (logger.isLoggable(Level.FINEST)) {
                logger.finest(LOGCONTEXT + "certificate type: " + keyStore.getType());

                // we don't need to do this unless logging enabled since MSAL will fail if cert is not valid
                Enumeration<String> enumeration = keyStore.aliases();
                while (enumeration.hasMoreElements()) {
                    String alias = enumeration.nextElement();
                    X509Certificate cert = (X509Certificate) keyStore.getCertificate(alias);
                    cert.checkValidity();
                    logger.finest(LOGCONTEXT + "certificate: " + cert.toString());
                }
            }

            IClientCredential credential = ClientCredentialFactory.createFromCertificate(is, certPassword);
            clientApplication = ConfidentialClientApplication.builder(aadPrincipalID, credential)
                    .executorService(SHARED_MSAL_EXECUTOR).setTokenCacheAccessAspect(aspect)
                    .authority(authority)
                    .connectTimeoutForDefaultHttpClient(MSAL_HTTP_CONNECT_TIMEOUT_MS)
                    .readTimeoutForDefaultHttpClient(MSAL_HTTP_READ_TIMEOUT_MS)
                    .build();
        } catch (FileNotFoundException e) {
            // re-throw if file not there no point to try another format
            throw e;
        } catch (CertificateException | NoSuchAlgorithmException | IOException e) {
            // ignore not PKCS12 cert error, will try another format after this
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + "Error loading PKCS12 certificate: " + e.getMessage());
            }
        }

        if (clientApplication == null) {
            // try loading X509 cert
            X509Certificate cert = (X509Certificate) SQLServerCertificateUtils.loadCertificate(certFile);

            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + "certificate type: " + cert.getType());

                // we don't really need to do this, MSAL will fail if cert is not valid, but good to check here and throw with proper error message
                cert.checkValidity();
                logger.finer(LOGCONTEXT + "certificate: " + cert.toString());
            }

            PrivateKey privateKey = SQLServerCertificateUtils.loadPrivateKey(certKey, certKeyPassword);

            IClientCredential credential = ClientCredentialFactory.createFromCertificate(privateKey, cert);
            clientApplication = ConfidentialClientApplication.builder(aadPrincipalID, credential)
                    .executorService(SHARED_MSAL_EXECUTOR).setTokenCacheAccessAspect(aspect)
                    .authority(authority)
                    .connectTimeoutForDefaultHttpClient(MSAL_HTTP_CONNECT_TIMEOUT_MS)
                    .readTimeoutForDefaultHttpClient(MSAL_HTTP_READ_TIMEOUT_MS)
                    .build();
        }

        return clientApplication;
    }

    static SqlAuthenticationToken getSqlFedAuthTokenIntegrated(SqlFedAuthInfo fedAuthInfo,
            String authenticationString, int millisecondsRemaining) throws SQLServerException {

        /*
         * principal name does not matter, what matters is the realm name it gets the username in
         * principal_name@realm_name format
         */
        KerberosPrincipal kerberosPrincipal = new KerberosPrincipal("username");
        final String user = kerberosPrincipal.getName();

        if (logger.isLoggable(Level.FINER)) {
            logger.finer(LOGCONTEXT + authenticationString + ": get FedAuth token integrated, user: " + user
                    + "realm name:" + kerberosPrincipal.getRealm());
        }

        // Layer A: per-authority PublicClientApplication cache (no per-credential secret material).
        final String cacheKey = "int:" + fedAuthInfo.stsurl;
        final PublicClientApplication pca;
        try {
            pca = PCA_CACHE.computeIfAbsent(cacheKey, k -> {
                try {
                    return PublicClientApplication
                            .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID)
                            .executorService(SHARED_MSAL_EXECUTOR)
                            .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                            .authority(fedAuthInfo.stsurl)
                            .connectTimeoutForDefaultHttpClient(MSAL_HTTP_CONNECT_TIMEOUT_MS)
                            .readTimeoutForDefaultHttpClient(MSAL_HTTP_READ_TIMEOUT_MS)
                            .build();
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException re) {
            Throwable c = re.getCause();
            if (c instanceof MalformedURLException) {
                throw getCorrectedException((Exception) c, user, authenticationString);
            }
            throw re;
        }

        // Layer B: single-flight on (stsurl + integrated). Followers wait on leader's future.
        CompletableFuture<SqlAuthenticationToken> mine = new CompletableFuture<>();
        CompletableFuture<SqlAuthenticationToken> winner = IN_FLIGHT.putIfAbsent(cacheKey, mine);
        if (winner != null) {
            try {
                return winner.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLServerException(e.getMessage(), e);
            } catch (ExecutionException e) {
                throw getCorrectedException(e, user, authenticationString);
            } catch (TimeoutException e) {
                throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), user, authenticationString);
            }
        }

        try {
            final CompletableFuture<IAuthenticationResult> future = pca
                    .acquireToken(IntegratedWindowsAuthenticationParameters
                            .builder(Collections.singleton(fedAuthInfo.spn + SLASH_DEFAULT), user).build());

            final IAuthenticationResult ar = future.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);

            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + (ar.account() != null ? ar.account().username() + ": "
                        : "" + ACCESS_TOKEN_EXPIRE + ar.expiresOnDate()));
            }

            SqlAuthenticationToken tok = new SqlAuthenticationToken(ar.accessToken(), ar.expiresOnDate());
            mine.complete(tok);
            return tok;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mine.completeExceptionally(e);
            throw new SQLServerException(e.getMessage(), e);
        } catch (ExecutionException e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(e, user, authenticationString);
        } catch (TimeoutException e) {
            mine.completeExceptionally(e);
            throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), user, authenticationString);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            IN_FLIGHT.remove(cacheKey, mine);
        }
    }

    static SqlAuthenticationToken getSqlFedAuthTokenInteractive(SqlFedAuthInfo fedAuthInfo, String user,
            String authenticationString, int millisecondsRemaining) throws SQLServerException {
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        if (logger.isLoggable(Level.FINER)) {
            logger.finer(LOGCONTEXT + authenticationString + ": get FedAuth token interactive for user: " + user);
        }

        try {
            // Interactive flows hold a system-browser handle per attempt and cannot share an open
            // AuthorizationCodeRequest across callers, so we intentionally do NOT cache the PCA
            // and do NOT single-flight here. We still bound MSAL's HTTP socket waits.
            PublicClientApplication pca = PublicClientApplication
                    .builder(ActiveDirectoryAuthentication.JDBC_FEDAUTH_CLIENT_ID).executorService(executorService)
                    .setTokenCacheAccessAspect(PersistentTokenCacheAccessAspect.getInstance())
                    .authority(fedAuthInfo.stsurl)
                    .connectTimeoutForDefaultHttpClient(MSAL_HTTP_CONNECT_TIMEOUT_MS)
                    .readTimeoutForDefaultHttpClient(MSAL_HTTP_READ_TIMEOUT_MS)
                    .build();

            CompletableFuture<IAuthenticationResult> future = null;
            IAuthenticationResult authenticationResult = null;

            // try to acquire token silently if user account found in cache
            try {
                Set<IAccount> accountsInCache = pca.getAccounts().join();
                if (logger.isLoggable(Level.FINEST)) {
                    StringBuilder acc = new StringBuilder();
                    if (accountsInCache != null) {
                        for (IAccount account : accountsInCache) {
                            if (acc.length() != 0) {
                                acc.append(", ");
                            }
                            acc.append(account.username());
                        }
                    }
                    if (logger.isLoggable(Level.FINEST)) {
                        logger.finest(LOGCONTEXT + "Accounts in cache = " + acc + ", size = "
                                + (accountsInCache == null ? null : accountsInCache.size()) + ", user = " + user);
                    }
                }
                if (null != accountsInCache && !accountsInCache.isEmpty() && null != user && !user.isEmpty()) {
                    IAccount account = getAccountByUsername(accountsInCache, user);
                    if (null != account) {
                        if (logger.isLoggable(Level.FINEST)) {
                            logger.finest(LOGCONTEXT + "Silent authentication for user:" + user);
                        }
                        SilentParameters silentParameters = SilentParameters
                                .builder(Collections.singleton(fedAuthInfo.spn + SLASH_DEFAULT), account).build();

                        future = pca.acquireTokenSilently(silentParameters);
                    }
                }
            } catch (MsalInteractionRequiredException e) {
                // not an error, need to get token interactively
                if (logger.isLoggable(Level.FINEST)) {
                    logger.log(Level.FINEST, e,
                            () -> LOGCONTEXT + "Need to get token interactively: " + e.reason().toString());
                }
            }

            if (null != future) {
                authenticationResult = future.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            } else {
                // acquire token interactively with system browser
                if (logger.isLoggable(Level.FINEST)) {
                    logger.finest(LOGCONTEXT + "Interactive authentication");
                }
                InteractiveRequestParameters parameters = buildInteractiveRequestParameters(
                        new URI(REDIRECTURI), user, fedAuthInfo.spn);

                future = pca.acquireToken(parameters);
                authenticationResult = future.get(Math.min(millisecondsRemaining, TOKEN_WAIT_DURATION_MS), TimeUnit.MILLISECONDS);
            }

            if (logger.isLoggable(Level.FINER)) {
                logger.finer(
                        LOGCONTEXT + (authenticationResult.account() != null ? authenticationResult.account().username()
                                + ": " : "" + ACCESS_TOKEN_EXPIRE + authenticationResult.expiresOnDate()));
            }

            return new SqlAuthenticationToken(authenticationResult.accessToken(), authenticationResult.expiresOnDate());
        } catch (InterruptedException e) {
            // re-interrupt thread
            Thread.currentThread().interrupt();

            throw new SQLServerException(e.getMessage(), e);
        } catch (MalformedURLException | URISyntaxException | ExecutionException e) {
            throw getCorrectedException(e, user, authenticationString);
        } catch (TimeoutException e) {
            throw getCorrectedException(new SQLServerException(SQLServerException.getErrString("R_connectionTimedOut"), e), user, authenticationString);
        } finally {
            executorService.shutdown();
        }
    }

    // Helper function to return account containing user name from set of accounts, or null if no match
    private static IAccount getAccountByUsername(Set<IAccount> accounts, String username) {
        if (!accounts.isEmpty()) {
            for (IAccount account : accounts) {
                if (account.username().equalsIgnoreCase(username)) {
                    return account;
                }
            }
        }
        return null;
    }

    /**
     * Builds the MSAL4J InteractiveRequestParameters used by the ActiveDirectoryInteractive
     * authentication flow. Extracted from {@link #getSqlFedAuthTokenInteractive} so the
     * configuration (notably {@code response_mode=form_post}, which keeps the AAD
     * authorization response out of the redirect URL) can be unit-tested without driving
     * a real interactive sign-in.
     */
    static InteractiveRequestParameters buildInteractiveRequestParameters(URI redirectUri, String user, String spn) {
        return InteractiveRequestParameters.builder(redirectUri)
                .systemBrowserOptions(SystemBrowserOptions.builder()
                        .htmlMessageSuccess(SQLServerResource.getResource("R_MSALAuthComplete")).build())
                .loginHint(user)
                .extraQueryParameters(Collections.singletonMap("response_mode", "form_post"))
                .scopes(Collections.singleton(spn + SLASH_DEFAULT))
                .build();
    }

    private static SQLServerException getCorrectedException(Exception e, String user, String authenticationString) {
        Object[] msgArgs = {user, authenticationString};

        if (null == e.getCause() || null == e.getCause().getMessage()) {
            MessageFormat form = new MessageFormat(
                    SQLServerException.getErrString("R_MSALExecution") + " " + e.getMessage());

            // The case when Future's outcome has no AuthenticationResult but Exception.
            return new SQLServerException(form.format(msgArgs), null);
        } else {
            /*
             * the cause error message uses \\n\\r which does not give correct format change it to \r\n to provide
             * correct format. Also replace {} which confuses MessageFormat
             */
            String correctedErrorMessage = e.getCause().getMessage().replaceAll("\\\\r\\\\n", "\r\n")
                    .replaceAll("\\{", "\"").replaceAll("\\}", "\"");

            RuntimeException correctedAuthenticationException = new RuntimeException(correctedErrorMessage);
            MessageFormat form = new MessageFormat(
                    SQLServerException.getErrString("R_MSALExecution") + " " + correctedErrorMessage);

            /*
             * SQLServerException is caused by ExecutionException, which is caused by AuthenticationException to match
             * the exception tree before error message correction
             */
            ExecutionException correctedExecutionException = new ExecutionException(correctedAuthenticationException);

            return new SQLServerException(form.format(msgArgs), null, 0, correctedExecutionException);
        }
    }

    private static class TokenCacheMap {
        private ConcurrentHashMap<String, PersistentTokenCacheAccessAspect> tokenCacheMap = new ConcurrentHashMap<>();

        PersistentTokenCacheAccessAspect getEntry(String value, String key) {
            PersistentTokenCacheAccessAspect persistentTokenCacheAccessAspect = tokenCacheMap.get(key);

            if (null != persistentTokenCacheAccessAspect) {
                long currentTime = System.currentTimeMillis();

                if (currentTime > persistentTokenCacheAccessAspect.getExpiryTime()) {
                    tokenCacheMap.remove(key);

                    persistentTokenCacheAccessAspect = new PersistentTokenCacheAccessAspect();
                    persistentTokenCacheAccessAspect
                            .setExpiryTime(currentTime + PersistentTokenCacheAccessAspect.TIME_TO_LIVE);

                    tokenCacheMap.put(key, persistentTokenCacheAccessAspect);

                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer(LOGCONTEXT + ": entry expired for: " + value + " new entry will expire in: "
                                + TimeUnit.MILLISECONDS.toSeconds(PersistentTokenCacheAccessAspect.TIME_TO_LIVE) + "s");
                    }
                }
            }

            return persistentTokenCacheAccessAspect;
        }

        void addEntry(String key, PersistentTokenCacheAccessAspect value) {
            value.setExpiryTime(System.currentTimeMillis() + PersistentTokenCacheAccessAspect.TIME_TO_LIVE);
            tokenCacheMap.put(key, value);
            if (logger.isLoggable(Level.FINER)) {
                logger.finer(LOGCONTEXT + ": add entry for: " + value + ", will expire in: "
                        + TimeUnit.MILLISECONDS.toSeconds(PersistentTokenCacheAccessAspect.TIME_TO_LIVE) + "s");
            }
        }
    }
}
