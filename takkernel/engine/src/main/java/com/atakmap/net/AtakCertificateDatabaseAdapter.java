package com.atakmap.net;

import android.util.Pair;
import android.util.Patterns;

import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.io.IOProviderFactory;
import com.atakmap.coremap.log.Log;
import com.atakmap.database.DatabaseIface;
import com.atakmap.database.QueryIface;
import com.atakmap.database.StatementIface;
import com.atakmap.database.impl.DatabaseImpl;
import com.atakmap.filesystem.HashingUtils;
import com.atakmap.annotations.ModifierApi;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Class responsible for the local calls similar to {@link AtakCertificateDatabaseAdapter}.
 *
 */
@ModifierApi(since = "4.3", target = "4.6", modifiers = {
        "final"
})
public final class AtakCertificateDatabaseAdapter implements AtakCertificateDatabaseIFace {

    /**
     * Tag for logging
     */
    private static final String TAG = "AtakCertificateDatabaseAdapter";
    /**
     * Holds the certificate database.
     */
    private DatabaseIface certDb;

    final Object lock = new Object();

    /**
     * The constructor
     */
    public AtakCertificateDatabaseAdapter() {
        this.certDb = null;
    }

    /**
     * Closes the certificate database
     */
    public void dispose() {
        synchronized (lock) {
            if (certDb != null) {
                try {
                    certDb.close();
                } catch (Exception e) { }
            }
        }
    }

    /**
     * Deletes the given file.
     *
     * @param databaseFile the file to delete.
     */
    public void clear(File databaseFile) {
        synchronized (lock) {
            FileSystemUtils.deleteFile(databaseFile);
            certDb = null;
        }
    }

    @Override
    public byte[] getCertificateForType(String type) {
        return getCertificateForType(type, true);
    }

    /**
     * Gets the certificate by the given type.
     *
     * @param type the certificate type.
     * @return Null if not exists, the certificate as a <code>byte[]</code> otherwise.
     */
    @Override
    public byte[] getCertificateForType(String type, boolean validateHash) {
        synchronized (lock) {
            byte[] certificate = getCertificate(type);
            if (certificate == null) {
                return null;
            }

            if(validateHash) {
                String hash = getCertificateHash(type);
                if (hash == null || hash.length() == 0) {
                    Log.w(TAG, "found certificate without hash: " + type);
                    return null;
                }
                String hashCheck = HashingUtils.sha256sum(certificate);
                if (!hash.equals(hashCheck)) {
                    Log.w(TAG, "certificate hash validation failed!");
                    return null;
                }
            }

            return certificate;
        }
    }

    /**
     * Retrieves the certificate for the specified type for the specified
     * server. The certificate is validated against the stored hash.
     *
     * @param type          The type
     * @param server        The server
     * @return
     */
    @Override
    public byte[] getCertificateForTypeAndServer(String type, String server) {
        return getCertificateForTypeAndServer(type, server, true);
    }

    /**
     * Gets the certificate based off of the given type and server.
     *
     * @param type   the certificate type
     * @param server the server
     * @return Null if not exists, the certificate as a <code>byte[]</code> otherwise.
     */
    @Override
    public byte[] getCertificateForTypeAndServer(String type, String server, boolean validateHash) {
        synchronized (lock) {

            byte[] certificate = getCertificateForServer(type, server);
            if (certificate == null) {
                return null;
            }

            if(validateHash) {
                String hash = getCertificateHashForServer(type, server);
                if (hash == null || hash.length() == 0) {
                    Log.w(TAG, "found certificate without hash: " + type);
                    return null;
                }
                String hashCheck = HashingUtils.sha256sum(certificate);
                if (!hash.equals(hashCheck)) {
                    Log.w(TAG, "certificate hash validation failed!");
                    return null;
                }
            }

            return certificate;
        }
    }

    /**
     * Gets the certificate based off of the given type and server and port.
     *
     * @param type   the certificate type
     * @param server the server
     * @param port   the port
     * @return Null if not exists, the certificate as a <code>byte[]</code> otherwise.
     */
    @Override
    public byte[] getCertificateForTypeAndServerAndPort(String type, String server, int port) {
        byte[] certificate = getCertificateForTypeAndServerAndPort(type, server, port, true);
        if (certificate == null) {
            certificate = getCertificateForTypeAndServer(type, server, true);
        }
        return certificate;
    }

    /**
     * Gets the certificate based off of the given type and server and port and optionally
     * validate the hash of the certificate retrieved from the database
     *
     * @param type   the certificate type
     * @param server the server
     * @param port   the port
     * @param validateHash   true to validate the certificates hash
     * @return Null if not exists, the certificate as a <code>byte[]</code> otherwise.
     */
    public byte[] getCertificateForTypeAndServerAndPort(String type, String server, int port, boolean validateHash) {
        synchronized (lock) {

            byte[] certificate = getCertificateForServerAndPort(type, server, port);
            if (certificate == null) {
                return null;
            }

            if(validateHash) {
                String hash = getCertificateHashForServerAndPort(type, server, port);
                if (hash == null || hash.length() == 0) {
                    Log.w(TAG, "found certificate without hash: " + type);
                    return null;
                }
                String hashCheck = HashingUtils.sha256sum(certificate);
                if (!hash.equals(hashCheck)) {
                    Log.w(TAG, "certificate hash validation failed!");
                    return null;
                }
            }

            return certificate;
        }
    }

    private String getByNameHelper(String server) {
        try {
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] results = new String[1];
            results[0] = null;
            Thread t = new Thread(TAG + "-GetHostAddress") {
                @Override
                public void run() {
                    try {
                        InetAddress inetAddress = InetAddress.getByName(server);
                        if (inetAddress == null) {
                            Log.w(TAG, "getByName failed for " + server);
                        } else {
                            results[0] = inetAddress.getHostAddress();
                        }
                    } catch (UnknownHostException uhe) {
                        Log.w(TAG, "UnknownHostException for " + server);
                    }

                    latch.countDown();
                }
            };
            t.start();
            latch.await();

            return results[0];
        } catch (InterruptedException e) {
            Log.e(TAG, "getByNameHelper interrupted!", e);
            return null;
        }
    }

    /**
     * Gets the certificate based on the given IP Address
     *
     * @param type the type
     * @param IP   the IP address
     * @return the Pair of certificate and server name.
     */
    public Pair<byte[], String> getCertificateForIPaddress(final String type, final String IP) {
        synchronized (lock) {
            try {

                // bail if we dont have a proper IP address
                if (IP == null || !Patterns.IP_ADDRESS.matcher(IP).matches()) {
                    return null;
                }

                String[] servers = getServers(type);
                if (servers == null) {
                    return null;
                }

                for (final String server : servers) {

                    // ignore any certs stored via IP address
                    if (Patterns.IP_ADDRESS.matcher(server).matches()) {
                        continue;
                    }

                    String ipForCert = getByNameHelper(server);

                    if (ipForCert == null) {
                        Log.w(TAG, "getByName failed for " + server);
                    } else if (ipForCert.compareTo(IP) == 0) {
                        byte[] cert = getCertificateForServer(type, server);
                        Pair<byte[], String> pair = new Pair<byte[], String>(cert, server);
                        return pair;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "exception in getCertificateForIPaddress: " + e.getMessage(), e);
            }

            return null;
        }
    }

    /**
     * Gets the certificate based on the given IP Address and port
     *
     * @param type the type
     * @param IP   the IP address
     * @param port the port
     * @return the Pair of certificate and server name.
     */
    public Pair<byte[], String> getCertificateForIPaddressAndPort(
            final String type, final String IP, final int port) {
        synchronized (lock) {
            try {

                // bail if we dont have a proper IP address
                if (IP == null || !Patterns.IP_ADDRESS.matcher(IP).matches()) {
                    return null;
                }

                NetConnectString[] netConnectStrings = getServerConnectStrings(type);
                if (netConnectStrings == null) {
                    return null;
                }

                for (final NetConnectString netConnectString : netConnectStrings) {

                    String host = netConnectString.getHost();

                    // ignore any certs stored via IP address
                    if (Patterns.IP_ADDRESS.matcher(host).matches()) {
                        continue;
                    }

                    String ipForCert = getByNameHelper(host);

                    if (ipForCert == null) {
                        Log.w(TAG, "getByName failed for " + host);
                    } else if (ipForCert.compareTo(IP) == 0) {
                        byte[] cert = getCertificateForServerAndPort(
                                type, netConnectString.getHost(), netConnectString.getPort());
                        Pair<byte[], String> pair = new Pair<byte[], String>(cert, host);
                        return pair;
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "exception in getCertificateForIPaddressAndPort: " + e.getMessage(), e);
            }

            return null;
        }
    }

    /**
     * Saves the certificate with the given type.
     *
     * @param type        the type of the certificate
     * @param certificate the certificate as a <code>byte[]</code>
     */
    @Override
    public void saveCertificateForType(String type, byte[] certificate) {

        synchronized (lock) {
            String hash = HashingUtils.sha256sum(certificate);
            saveCertificate(type, certificate, hash);
        }
    }

    /**
     * Saves the certificate with the given type and server.
     *
     * @param type        the type of the certificate
     * @param server      the server name.
     * @param certificate the certificate as a <code>byte[]</code>
     */
    @Override
    public void saveCertificateForTypeAndServer(String type, String server, byte[] certificate) {

        synchronized (lock) {
            String hash = HashingUtils.sha256sum(certificate);
            saveCertificateForServer(type, server, certificate, hash);
        }
    }

    /**
     * Saves the certificate with the given type and server and port
     *
     * @param type        the type of the certificate
     * @param server      the server name.
     * @param port        the port.
     * @param certificate the certificate as a <code>byte[]</code>
     */
    @Override
    public void saveCertificateForTypeAndServerAndPort(String type, String server, int port, byte[] certificate) {

        synchronized (lock) {
            String hash = HashingUtils.sha256sum(certificate);
            saveCertificateForServerAndPort(type, server, port, certificate, hash);
        }
    }

    /**
     * Deletes all certificates of the given type within the certificates table only.
     *
     * @param type the type to be removed.
     */
    @Override
    public boolean deleteCertificateForType(String type) {
        synchronized (lock) {
            return deleteCertificate(type);
        }
    }

    /**
     * Deletes all certificates of the given type and server within the server_certificates table only.
     *
     * @param type   the type to be removed.
     * @param server the server to be removed.
     */
    @Override
    public boolean deleteCertificateForTypeAndServer(String type, String server) {
        synchronized (lock) {
            return deleteCertificateForServer(type, server);
        }
    }

    /**
     * Deletes all certificates of the given type and server and port within the server_certificates table only.
     *
     * @param type   the type to be removed.
     * @param server the server to be removed.
     * @param port the port server to be removed.
     */
    @Override
    public boolean deleteCertificateForTypeAndServerAndPort(String type, String server, int port) {
        synchronized (lock) {
            return deleteCertificateForServerAndPort(type, server, port);
        }
    }

    /**
     * Gets a CA Certificate.
     *
     * @return a list of {@link X509Certificate}
     */
    @Override
    public List<X509Certificate> getCACerts() {
        return AtakCertificateDatabase.getCACerts();
    }

    /**
     * Checks the validity of the given keystore and password.
     *
     * @param keystore the keystore
     * @param password the password
     * @return true if valid, false otherwise.
     */
    @Override
    public boolean checkValidity(byte[] keystore, String password) {
        AtakCertificateDatabase.CertificateValidity validity = AtakCertificateDatabase.checkValidity(keystore, password);
        return validity != null && validity.isValid();
    }

    /**
     * Gets the certificate of the given type.
     *
     * @param type the certificate type.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private byte[] getCertificate(String type) {
        if(this.certDb == null)
            return null;
        String sql = "SELECT certificate FROM certificates WHERE type = ?;";
        QueryIface query = null;
        try {
            query = this.certDb.compileQuery(sql);
            query.bind(1, type);
            return getCertificateHelperImpl(query, "getCertificateLocal");
        } finally {
            if (query != null)
                query.close();
        }
    }

    /**
     * Gets the certificate of the given type.
     *
     * @param type the certificate type.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private String getCertificateHash(String type) {
        if(this.certDb == null)
            return null;
        String sql = "SELECT hash FROM certificates WHERE type = ?;";
        QueryIface query = null;
        try {
            query = this.certDb.compileQuery(sql);
            query.bind(1, type);
            return getCertificateHashHelperImpl(query, "getCertificateHashLocal");
        } finally {
            if(query != null)
                query.close();
        }
    }

    /**
     * Saves the certificate to the DB.
     *
     * @param type the type of certificate
     * @param cert the certificate represented as a <code>byte[]</code>
     * @param hash the hash of the certificate.
     * @return true if successful, false otherwise.
     */
    private boolean saveCertificate(String type, byte[] cert, String hash) {
        if(this.certDb == null)
            return false;

        byte[] existing = getCertificate(type);
        if (existing == null || existing.length == 0) {
            StatementIface stmt = null;
            try {
                stmt = this.certDb.compileStatement(
                        "insert into certificates ( type, certificate, hash ) values ( ?, ?, ? );");
                stmt.bind(1, type);
                stmt.bind(2, cert);
                stmt.bind(3, hash);

                try {
                    stmt.execute();
                    return true;
                } catch(Throwable t) {
                    Log.e(TAG, "saveCertificate: failed to execute SQL",  t);
                    return false;
                }
            } finally {
                if(stmt != null)
                    stmt.close();
            }
        } else {
            StatementIface stmt = null;
            try {
                stmt = this.certDb.compileStatement(
                        "update certificates set certificate = ?, hash = ? where type = ?;");
                stmt.bind(1, cert);
                stmt.bind(2, hash);
                stmt.bind(3, type);

                try {
                    stmt.execute();
                    return true;
                } catch (Throwable t) {
                    Log.e(TAG, "saveCertificate: failed to execute SQL",  t);
                    return false;
                }
            } finally {
                if(stmt != null)
                    stmt.close();
            }
        }
    }

    /**
     * Deletes a certificate of the given type.
     *
     * @param type the type of certificate to delete.
     * @return True if successful, false otherwise.
     */
    private boolean deleteCertificate(String type) {
        if (this.certDb == null)
            return false;

        String sql = "DELETE FROM certificates WHERE type = ?;";
        StatementIface sqlStatement = null;
        try {
            sqlStatement = certDb.compileStatement(sql);

            sqlStatement.bind(1, type);

            try {
                sqlStatement.execute();
            } catch (Exception e) {
                Log.e(TAG, "deleteCertificate: failed to execute SQL", e);
                return false;
            }
            return true;
        } finally {
            if (sqlStatement != null)
                sqlStatement.close();
        }
    }

    /**
     * Gets a collection of servers in the DB.
     *
     * @param type the type of certificate.
     * @return a collection of server names
     */
    @Override
    public String[] getServers(String type) {
        if(this.certDb == null)
            return null;

        List<String> returnList = new ArrayList<>();
        String sql = "SELECT server FROM server_certificates WHERE type = ? AND" +
                " (server IS NOT NULL AND length(server) != 0);";
        QueryIface sqlQuery = null;
        try {
            sqlQuery = certDb.compileQuery(sql);

            sqlQuery.bind(1, type);
            try {
                while (sqlQuery.moveToNext()) {
                    returnList.add(sqlQuery.getString(0));
                }
            } catch (Exception e) {
                Log.e(TAG, "getServers: failed to query.", e);
                return new String[0];
            }
            return returnList.toArray(new String[0]);
        } finally {
            if (sqlQuery != null)
                sqlQuery.close();
        }
    }

    /**
     * Gets a collection of connectionStrings in the DB.
     *
     * @param type the type of certificate.
     * @return a collection of server names
     */
    @Override
    public NetConnectString[] getServerConnectStrings(String type) {
        if(this.certDb == null)
            return null;

        List<NetConnectString> returnList = new ArrayList<>();
        String sql = "SELECT server, port FROM server_certificates WHERE " +
                " port IS NOT NULL and type = ? AND" +
                " (server IS NOT NULL AND length(server) != 0);";
        QueryIface sqlQuery = null;
        try {
            sqlQuery = certDb.compileQuery(sql);

            sqlQuery.bind(1, type);
            try {
                while (sqlQuery.moveToNext()) {
                    returnList.add(NetConnectString.fromString(
                            sqlQuery.getString(0) + ":" +
                            sqlQuery.getString(1) + ":ssl"));
                }
            } catch (Exception e) {
                Log.e(TAG, "getServerConnectStrings: failed to query.", e);
                return new NetConnectString[0];
            }
            return returnList.toArray(new NetConnectString[0]);
        } finally {
            if (sqlQuery != null)
                sqlQuery.close();
        }
    }

    /**
     * Gets the certificate of the given type and server.
     *
     * @param type   the certificate type.
     * @param server the server.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private byte[] getCertificateForServer(String type, String server) {
        if(this.certDb == null)
            return null;

        String sql = "SELECT certificate FROM server_certificates WHERE type = ? AND server = ?;";
        QueryIface query = null;
        try {
            query = this.certDb.compileQuery(sql);
            query.bind(1, type);
            query.bind(2, server);
            return getCertificateHelperImpl(query, "getCertificateForServerLocal");
        } finally {
            if(query != null)
                query.close();
        }
    }

    /**
     * Gets the certificate of the given type and server and port
     *
     * @param type   the certificate type.
     * @param server the server.
     * @param port the server.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private byte[] getCertificateForServerAndPort(String type, String server, int port) {
        if(this.certDb == null)
            return null;

        String sql = "SELECT certificate FROM server_certificates WHERE type = ? AND server = ? and port = ?;";
        QueryIface query = null;
        try {
            query = this.certDb.compileQuery(sql);
            query.bind(1, type);
            query.bind(2, server);
            query.bind(3, port);
            return getCertificateHelperImpl(query, "getCertificateForServerLocal");
        } finally {
            if(query != null)
                query.close();
        }
    }


    /**
     * Gets the certificate of the given type and server.
     *
     * @param type   the certificate type.
     * @param server the server.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private String getCertificateHashForServer(String type, String server) {
        if(this.certDb == null)
            return null;
        String sql = "SELECT hash FROM server_certificates WHERE type = ? AND server = ?;";
        QueryIface query = null;
        try {
            query = this.certDb.compileQuery(sql);
            query.bind(1, type);
            query.bind(2, server);
            return getCertificateHashHelperImpl(query, "getCertificateHashForServerLocal");
        } finally {
            if(query != null)
                query.close();
        }
    }

    /**
     * Gets the certificate of the given type and server.
     *
     * @param type   the certificate type.
     * @param server the server.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private String getCertificateHashForServerAndPort(String type, String server, int port) {
        if(this.certDb == null)
            return null;
        String sql = "SELECT hash FROM server_certificates WHERE type = ? AND server = ? " +
                "AND port = ?;";
        QueryIface query = null;
        try {
            query = this.certDb.compileQuery(sql);
            query.bind(1, type);
            query.bind(2, server);
            query.bind(3, port);
            return getCertificateHashHelperImpl(query, "getCertificateHashForServerAndPort");
        } finally {
            if(query != null)
                query.close();
        }
    }

    /**
     * Saves the server certificate to the DB.
     *
     * @param type   the type of certificate
     * @param cert   the certificate represented as a <code>byte[]</code>
     * @param hash   the hash of the certificate.
     * @param server the server
     * @return true if successful, false otherwise.
     */
    private boolean saveCertificateForServer(String type, String server, byte[] cert,
                                               String hash) {
        if (this.certDb == null)
            return false;
        byte[] existing = getCertificateForServer(type, server);
        if (existing == null || existing.length == 0) {
            StatementIface stmt = null;
            try {
                stmt = this.certDb.compileStatement(
                        "insert into server_certificates ( type, certificate, hash, server ) values ( ?, ?, ?, ? );");

                stmt.bind(1, type);
                stmt.bind(2, cert);
                stmt.bind(3, hash);
                stmt.bind(4, server);

                try {
                    stmt.execute();
                    return true;
                } catch(Throwable t) {
                    Log.w(TAG, "saveCertificateForServer: failed to execute SQL", t);
                    return false;
                }
            } finally {
                if(stmt != null)
                    stmt.close();
            }
        } else {
            StatementIface stmt = null;
            try {
                stmt = this.certDb.compileStatement(
                        "update server_certificates set certificate = ?, hash = ? where type = ? and server = ?;");

                stmt.bind(1, cert);
                stmt.bind(2, hash);
                stmt.bind(3, type);
                stmt.bind(4, server);

                try {
                    stmt.execute();
                    return true;
                } catch(Throwable t) {
                    Log.w(TAG, "saveCertificateForServer: failed to execute SQL", t);
                    return false;
                }
            } finally {
                if(stmt != null)
                    stmt.close();
            }
        }
    }

    private boolean saveCertificateForServerAndPort(String type, String server, int port, byte[] cert,
                                             String hash) {
        if (this.certDb == null)
            return false;
        byte[] existing = getCertificateForServerAndPort(type, server, port);
        if (existing == null || existing.length == 0) {
            StatementIface stmt = null;
            try {
                stmt = this.certDb.compileStatement(
                        "insert into server_certificates ( type, certificate, hash, server, port ) values ( ?, ?, ?, ?, ? );");

                stmt.bind(1, type);
                stmt.bind(2, cert);
                stmt.bind(3, hash);
                stmt.bind(4, server);
                stmt.bind(5, port);

                try {
                    stmt.execute();
                    return true;
                } catch(Throwable t) {
                    Log.w(TAG, "saveCertificateForServerAndPort: failed to execute SQL", t);
                    return false;
                }
            } finally {
                if(stmt != null)
                    stmt.close();
            }
        } else {
            StatementIface stmt = null;
            try {
                stmt = this.certDb.compileStatement(
                        "update server_certificates set certificate = ?, hash = ? where type = ? and server = ? and port = ?;");

                stmt.bind(1, cert);
                stmt.bind(2, hash);
                stmt.bind(3, type);
                stmt.bind(4, server);
                stmt.bind(5, port);

                try {
                    stmt.execute();
                    return true;
                } catch(Throwable t) {
                    Log.w(TAG, "saveCertificateForServer: failed to execute SQL", t);
                    return false;
                }
            } finally {
                if(stmt != null)
                    stmt.close();
            }
        }
    }

    /**
     * Deletes a certificate for server of the given type.
     *
     * @param type   the type of certificate to delete.
     * @param server the server for the certificate
     * @return True if successful, false otherwise.
     */
    private boolean deleteCertificateForServer(String type, String server) {
        if(this.certDb == null)
            return false;
        String sql = "DELETE FROM server_certificates WHERE type = ? AND server = ?;";
        StatementIface sqlStatement = null;
        try {
            sqlStatement = certDb.compileStatement(sql);

            sqlStatement.bind(1, type);
            sqlStatement.bind(2, server);

            try {
                sqlStatement.execute();
            } catch (Exception e) {
                Log.e(TAG, "deleteCertificateForServer: failed to execute SQL", e);
                return false;
            }
            return true;
        } finally {
            if(sqlStatement != null)
                sqlStatement.close();
        }
    }

    /**
     * Deletes a certificate for server of the given type.
     *
     * @param type   the type of certificate to delete.
     * @param server the server for the certificate
     * @return True if successful, false otherwise.
     */
    private boolean deleteCertificateForServerAndPort(String type, String server, int port) {
        if(this.certDb == null)
            return false;
        String sql = "DELETE FROM server_certificates WHERE type = ? AND server = ? " +
                " AND port = ?;";
        StatementIface sqlStatement = null;
        try {
            sqlStatement = certDb.compileStatement(sql);

            sqlStatement.bind(1, type);
            sqlStatement.bind(2, server);
            sqlStatement.bind(3, port);

            try {
                sqlStatement.execute();
            } catch (Exception e) {
                Log.e(TAG, "deleteCertificateForServer: failed to execute SQL", e);
                return false;
            }
            return true;
        } finally {
            if(sqlStatement != null)
                sqlStatement.close();
        }
    }

    /**
     * Helper method for returning a single <code>byte[]</code> representing a certificate.
     *
     * @param sqlQuery   The SQL query.
     * @param methodName the name of the calling method.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private byte[] getCertificateHelperImpl(QueryIface sqlQuery, String methodName) {
        List<byte[]> result = new ArrayList<>();
        //Iterate through cursor and add all rows returned
        try {
            while (sqlQuery.moveToNext()) {
                result.add(sqlQuery.getBlob(0));
            }
        } catch (Exception e) {
            Log.e(TAG, methodName + ": failed to query.", e);
        }

        if (result.isEmpty()) {
            // Return null, and log error if no rows are returned.
            return null;
        } else if (result.size() > 1) {
            //This should only return 1 row, log a warning if more than 1 is returned.
            Log.d(TAG, methodName + ": more than 1 row returned! Returning first row.");
        }

        return result.get(0);
    }

    /**
     * Helper method for returning a single <code>String</code> representing a certificate's hash.
     *
     * @param sqlQuery   The SQL statement.
     * @param methodName the name of the calling method.
     * @return The <code>byte[]</code> of the certificate of the given type, null if doesn't exist.
     */
    private String getCertificateHashHelperImpl(QueryIface sqlQuery, String methodName) {
        List<String> result = new ArrayList<>();
        //Iterate through cursor and add all rows returned
        try {
            while (sqlQuery.moveToNext()) {
                result.add(sqlQuery.getString(0));
            }
        } catch (Exception e) {
            Log.e(TAG, methodName + ": failed to query.\n" + e.toString());
        }

        if (result.isEmpty()) {
            // Return null, and log error if no rows are returned.
            return null;
        } else if (result.size() > 1) {
            //This should only return 1 row, log a warning if more than 1 is returned.
            Log.d(TAG, methodName + ": more than 1 row returned! Returning first row.");
        }

        return result.get(0);
    }

    public boolean openOrCreateDatabase(String path) {
        return openOrCreateDatabase(path, null) == 0;
    }
    int openOrCreateDatabase(String absolutePath, String pwd) {
        synchronized(lock) {
            if(this.certDb != null) {
                this.certDb.close();
                this.certDb = null;
            }
            try {
                if (pwd != null)
                    this.certDb = DatabaseImpl.open(absolutePath, pwd, 0x02000000 | DatabaseImpl.OPEN_CREATE);
                else if(absolutePath != null)
                    this.certDb = IOProviderFactory.createDatabase(new File(absolutePath));
                else
                    this.certDb = IOProviderFactory.createDatabase((File)null);
                if (certDb != null) {
                    this.certDb.execute("create table if not exists certificates(type TEXT, certificate BLOB, hash TEXT);", null);
                    this.certDb.execute("create table if not exists server_certificates (type TEXT, server TEXT, certificate BLOB, hash TEXT);", null);
                    try {
                        this.certDb.execute("alter table server_certificates add column port INTEGER;", null);
                    } catch (Throwable t) { }
                }
                return (this.certDb != null) ? 0 : -1;
            } catch(Throwable t) {
                return -1;
            }
        }
    }
}
