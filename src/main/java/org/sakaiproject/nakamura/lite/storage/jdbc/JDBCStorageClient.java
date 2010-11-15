package org.sakaiproject.nakamura.lite.storage.jdbc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.commons.lang.StringUtils;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.lite.content.StreamedContentHelper;
import org.sakaiproject.nakamura.lite.storage.ConnectionPoolException;
import org.sakaiproject.nakamura.lite.storage.RowHasher;
import org.sakaiproject.nakamura.lite.storage.StorageClient;
import org.sakaiproject.nakamura.lite.storage.StorageClientException;
import org.sakaiproject.nakamura.lite.storage.StorageClientUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Maps;

public class JDBCStorageClient implements StorageClient, RowHasher {

    private static final Logger LOGGER = LoggerFactory.getLogger(JDBCStorageClient.class);
    private static final String BASESQLPATH = "org/sakaiproject/nakamura/lite/storage/jdbc/config/client";
    private static final String SQL_VALIDATE = "validate";
    private static final String SQL_CHECKSCHEMA = "check-schema";
    private static final String SQL_COMMENT = "#";
    private static final String SQL_EOL = ";";
    private static final String SQL_DELETE_STRING_ROW = "delete-string-row";
    private static final String SQL_SELECT_STRING_ROW = "select-string-row";
    private static final String SQL_INSERT_STRING_COLUMN = "insert-string-column";
    private static final String SQL_UPDATE_STRING_COLUMN = "update-string-column";
    private static final String PROP_HASH_ALG = "rowid-hash";
    private static final String SQL_REMOVE_STRING_COLUMN = "remove-string-column";
    private Connection connection;
    private String[] clientSQLLocations;
    private Map<String, Object> sqlConfig;
    private boolean active;
    private PreparedStatement deleteStringRow;
    private PreparedStatement selectStringRow;
    private PreparedStatement insertStringColumn;
    private PreparedStatement updateStringColumn;
    private PreparedStatement removeStringColumn;
    private MessageDigest hasher;
    private boolean alive;
    private StreamedContentHelper streamedContentHelper;

    public JDBCStorageClient(Connection connection, Map<String, Object> properties)
            throws SQLException, NoSuchAlgorithmException, StorageClientException {
        this.connection = connection;
        streamedContentHelper = new FileStreamContentHelper(this, properties);
        String dbProductName = connection.getMetaData().getDatabaseProductName()
                .replaceAll(" ", "");
        int dbProductMajorVersion = connection.getMetaData().getDatabaseMajorVersion();
        int dbProductMinorVersion = connection.getMetaData().getDatabaseMinorVersion();

        clientSQLLocations = new String[] {
                BASESQLPATH + "." + dbProductName + "." + dbProductMajorVersion + "."
                        + dbProductMinorVersion,
                BASESQLPATH + "." + dbProductName + "." + dbProductMajorVersion,
                BASESQLPATH + "." + dbProductName, BASESQLPATH };

        for (String clientSQLLocation : clientSQLLocations) {
            String clientConfig = clientSQLLocation + ".sql";
            InputStream in = this.getClass().getClassLoader().getResourceAsStream(clientConfig);
            if (in != null) {
                try {
                    Properties p = new Properties();
                    p.load(in);
                    in.close();
                    Builder<String, Object> b = ImmutableMap.builder();
                    for (Entry<Object, Object> e : p.entrySet()) {
                        b.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    sqlConfig = b.build();
                    LOGGER.info("Using SQL configuation from {} ", clientConfig);
                    break;
                } catch (IOException e) {
                    LOGGER.info("Failed to read {} ", clientConfig, e);
                }
            } else {
                LOGGER.info("No SQL configuation at {} ", clientConfig);
            }
        }
        if (sqlConfig == null) {
            LOGGER.info("Tried all the following locations and failed for SQL confiuration {} ",
                    Arrays.toString(clientSQLLocations));
            throw new StorageClientException("No SQL Configuration for client ");
        }

        String rowidHash = getSql(PROP_HASH_ALG);
        if (rowidHash == null) {
            rowidHash = "MD5";
        }
        hasher = MessageDigest.getInstance(rowidHash);

    }

    public Map<String, Object> get(String keySpace, String columnFamily, String key)
            throws StorageClientException {
        ResultSet strings = null;
        ResultSet blobs = null;
        Map<String, Object> result = Maps.newHashMap();
        String rid = rowHash(keySpace, columnFamily, key);
        try {
            startUpConnection();
            selectStringRow.clearWarnings();
            selectStringRow.clearParameters();
            selectStringRow.setString(1, rid);
            strings = selectStringRow.executeQuery();
            while (strings.next()) {
                result.put(strings.getString(1), strings.getString(2));
            }
        } catch (SQLException e) {
            LOGGER.warn("Failed to perform get operation on {}:{}:{} ", new Object[] { keySpace,
                    columnFamily, key }, e);
            throw new StorageClientException(e.getMessage(), e);
        } finally {
            try {
                if (strings != null) {
                    strings.close();
                }
            } catch (Throwable e) {
                LOGGER.debug("Failed to close result set, ok to ignore this message ", e);
            }
            try {
                if (blobs != null) {
                    blobs.close();
                }
            } catch (Throwable e) {
                LOGGER.debug("Failed to close result set, ok to ignore this message ", e);
            }
        }
        return result;
    }

    public String rowHash(String keySpace, String columnFamily, String key) {
        hasher.reset();
        String keystring = keySpace + ":" + columnFamily + ":" + key;
        byte[] ridkey;
        try {
            ridkey = keystring.getBytes("UTF8");
        } catch (UnsupportedEncodingException e) {
            ridkey = keystring.getBytes();
        }
        return StorageClientUtils.encode(hasher.digest(ridkey),
                StorageClientUtils.URL_SAFE_ENCODING);
    }

    public void insert(String keySpace, String columnFamily, String key, Map<String, Object> values)
            throws StorageClientException {
        try {
            startUpConnection();
            String rid = rowHash(keySpace, columnFamily, key);
            for (Entry<String, Object> e : values.entrySet()) {
                String k = e.getKey();
                Object o = e.getValue();
                if (o instanceof byte[]) {
                    throw new RuntimeException("Invalid content in "+k+", storing byte[] rather than streaming it");
                }
            }
            for (Entry<String, Object> e : values.entrySet()) {
                String k = e.getKey();
                Object o = e.getValue();
                if (o instanceof String) {
                    updateStringColumn.clearWarnings();
                    updateStringColumn.clearParameters();
                    updateStringColumn.setString(1, (String) o);
                    updateStringColumn.setString(2, rid);
                    updateStringColumn.setString(3, k);
                    if (updateStringColumn.executeUpdate() == 0) {
                        insertStringColumn.clearWarnings();
                        insertStringColumn.clearParameters();
                        insertStringColumn.setString(1, (String) o);
                        insertStringColumn.setString(2, rid);
                        insertStringColumn.setString(3, k);
                        if (insertStringColumn.executeUpdate() == 0) {
                            throw new StorageClientException("Failed to save "
                                    + getRowId(keySpace, columnFamily, key) + "  column:[" + k
                                    + "] ");
                        } else {
                            LOGGER.info("Inserted {} {} [{}]", new Object[]{getRowId(keySpace, columnFamily, key), k, o});
                        }
                    } else {
                        LOGGER.info("Updated {} {} [{}]", new Object[]{getRowId(keySpace, columnFamily, key), k, o});
                    }
                } else if (o == null) {
                    removeStringColumn.clearWarnings();
                    removeStringColumn.clearParameters();
                    removeStringColumn.setString(1, rid);
                    removeStringColumn.setString(2, k);
                    if (removeStringColumn.executeUpdate() == 0) {
                        Map<String, Object> m = get(keySpace, columnFamily, key);
                        LOGGER.info("Column Not present did not remove {} {} Current Column:{} ", new Object[]{getRowId(keySpace, columnFamily, key), k, m}); 
                    } else {
                        LOGGER.info("Removed {} {} ", getRowId(keySpace, columnFamily, key), k); 
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Failed to perform insert/update operation on {}:{}:{} ", new Object[] {
                    keySpace, columnFamily, key }, e);
            throw new StorageClientException(e.getMessage(), e);
        }
    }

    private String getRowId(String keySpace, String columnFamily, String key) {
        return keySpace + ":" + columnFamily + ":" + key;
    }

    public void remove(String keySpace, String columnFamily, String key)
            throws StorageClientException {
        String rid = rowHash(keySpace, columnFamily, key);
        try {
            startUpConnection();
            deleteStringRow.clearWarnings();
            deleteStringRow.clearParameters();
            deleteStringRow.setString(1, rid);
            deleteStringRow.executeUpdate();

            deleteStringRow.clearWarnings();
            deleteStringRow.clearParameters();
            deleteStringRow.setString(1, rid);
            deleteStringRow.executeUpdate();
        } catch (SQLException e) {
            LOGGER.warn("Failed to perform delete operation on {}:{}:{} ", new Object[] { keySpace,
                    columnFamily, key }, e);
            throw new StorageClientException(e.getMessage(), e);
        }
    }

    public void close() {
        try {
            shutdownConnection();
            connection.close();
        } catch (Throwable t) {
            LOGGER.debug("Failed to close connection ", t);
        }
    }

    public void startUpConnection() throws SQLException {
        if (!active && alive) {
            LOGGER.info("Activating {}", this);
            deleteStringRow = openPreparedStatement(SQL_DELETE_STRING_ROW);
            selectStringRow = openPreparedStatement(SQL_SELECT_STRING_ROW);
            insertStringColumn = openPreparedStatement(SQL_INSERT_STRING_COLUMN);
            updateStringColumn = openPreparedStatement(SQL_UPDATE_STRING_COLUMN);
            removeStringColumn = openPreparedStatement(SQL_REMOVE_STRING_COLUMN);

            active = true;
        }
    }

    private PreparedStatement openPreparedStatement(String statementKey) throws SQLException {
        return connection.prepareStatement(getSql(statementKey));
    }

    public void shutdownConnection() {
        if (active) {
            LOGGER.info("Passivating {}", this);
            deleteStringRow = closePreparedStatement(deleteStringRow);
            selectStringRow = closePreparedStatement(selectStringRow);
            insertStringColumn = closePreparedStatement(insertStringColumn);
            updateStringColumn = closePreparedStatement(updateStringColumn);
            removeStringColumn = closePreparedStatement(removeStringColumn);
            active = false;
        }
    }

    private PreparedStatement closePreparedStatement(PreparedStatement pst) {
        try {
            pst.close();
        } catch (Throwable t) {

        }
        return null;
    }

    public boolean validate() {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.execute(getSql(SQL_VALIDATE));
            return true;
        } catch (SQLException e) {
            LOGGER.warn("Failed to validate connection ", e);
            return false;
        } finally {
            try {
                statement.close();
            } catch (Throwable e) {
                LOGGER.debug("Failed to close statement in validate ", e);
            }
        }
    }

    private String getSql(String statementName) {
        return (String) sqlConfig.get(statementName);
    }

    public void checkSchema() throws ConnectionPoolException {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            try {
                if (statement.execute(getSql(SQL_CHECKSCHEMA))) {
                    alive = true;
                    return;
                }
            } catch (SQLException e) {
                LOGGER.debug("Schema does not exist ", e);
            }

            for (String clientSQLLocation : clientSQLLocations) {
                String clientDDL = clientSQLLocation + ".ddl";
                InputStream in = this.getClass().getClassLoader().getResourceAsStream(clientDDL);
                if (in != null) {
                    try {
                        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF8"));
                        int lineNo = 1;
                        String line = br.readLine();
                        StringBuilder sqlStatement = new StringBuilder();
                        while (line != null) {
                            line = StringUtils.stripEnd(line, null);
                            if (!line.isEmpty()) {
                                if (line.startsWith(SQL_COMMENT)) {
                                    LOGGER.info("Comment {} ", line);
                                } else if (line.endsWith(SQL_EOL)) {
                                    sqlStatement.append(line.substring(0, line.length() - 1));
                                    String ddl = sqlStatement.toString();
                                    try {
                                        statement.executeUpdate(ddl);
                                        LOGGER.info("SQL OK    {}:{} {} ", new Object[] {
                                                clientDDL, lineNo, ddl });
                                    } catch (SQLException e) {
                                        LOGGER.warn("SQL ERROR {}:{} {} {} ", new Object[] {
                                                clientDDL, lineNo, ddl, e.getMessage() });
                                    }
                                    sqlStatement = new StringBuilder();
                                } else {
                                    sqlStatement.append(line);
                                }
                            }
                            line = br.readLine();
                            lineNo++;
                        }
                        br.close();
                        alive = true;
                        break;
                    } catch (Throwable e) {
                        LOGGER.error("Failed to load Schema from {}", clientDDL, e);
                    } finally {
                        try {
                            in.close();
                        } catch (IOException e) {
                            LOGGER.error("Failed to close stream from {}", clientDDL, e);
                        }
                    }
                }

            }

        } catch (SQLException e) {
            throw new ConnectionPoolException("Failed to create schema ", e);
        } finally {
            try {
                statement.close();
            } catch (Throwable e) {
                LOGGER.debug("Failed to close statement in validate ", e);
            }
        }
    }

    public void activate() {
    }

    public void passivate() {
    }

    @Override
    public Map<String, Object> streamBodyIn(String keySpace, String columnFamily, String contentId,
            String contentBlockId, InputStream in) throws StorageClientException,
            AccessDeniedException, IOException {
       return streamedContentHelper.writeBody(keySpace, columnFamily, contentId, contentBlockId, in);
    }

    @Override
    public InputStream streamBodyOut(String keySpace, String columnFamily, String contentId,
            String contentBlockId, Map<String, Object> content) throws StorageClientException,
            AccessDeniedException, IOException {
        return streamedContentHelper.readBody(keySpace, columnFamily, contentBlockId);
    }
}