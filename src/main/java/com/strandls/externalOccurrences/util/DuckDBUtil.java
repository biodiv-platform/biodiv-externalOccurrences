package com.strandls.externalOccurrences.util;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.strandls.externalOccurrences.ExternalOccurrencesConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Singleton manager for a shared DuckDB database instance with connection pooling.
 *
 * This ensures:
 * - Single DuckDB database shared across all requests
 * - Memory limit enforced globally (default 50MB)
 * - Spatial extension loaded once at initialization
 * - Efficient connection reuse via pooling
 */
public class DuckDBUtil {

	private static final Logger logger = LoggerFactory.getLogger(DuckDBUtil.class);

	// Singleton instance
	private static volatile DuckDBUtil instance;
	private static final Object LOCK = new Object();

	// Connection pool
	private final HikariDataSource dataSource;
	private final String databasePath;

	/**
	 * Private constructor - initializes the DuckDB database and connection pool.
	 */
	private DuckDBUtil() throws SQLException {
		logger.info("Initializing shared DuckDB instance...");

		// Load configuration
		String dbPath = ExternalOccurrencesConfig.getProperty("duckdb_database_path");
		String memoryLimit = ExternalOccurrencesConfig.getProperty("duckdb_memory_limit");
		String tempDir = ExternalOccurrencesConfig.getProperty("duckdb_temp_directory");

		// Use defaults if not configured
		if (dbPath == null || dbPath.isEmpty()) {
			dbPath = "/tmp/externalOccurrences_duckdb.db";
			logger.warn("duckdb_database_path not configured, using default: {}", dbPath);
		}
		if (memoryLimit == null || memoryLimit.isEmpty()) {
			memoryLimit = "50MB";
			logger.info("duckdb_memory_limit not configured, using default: {}", memoryLimit);
		}
		if (tempDir == null || tempDir.isEmpty()) {
			tempDir = "/tmp/externalOccurrences_duckdb_temp";
			logger.info("duckdb_temp_directory not configured, using default: {}", tempDir);
		}

		this.databasePath = dbPath;

		// Ensure DuckDB JDBC driver is loaded
		try {
			Class.forName("org.duckdb.DuckDBDriver");
			logger.info("DuckDB JDBC driver loaded successfully");
		} catch (ClassNotFoundException e) {
			throw new SQLException("Failed to load DuckDB JDBC driver", e);
		}

		// Initialize database with spatial extension and memory settings
		initializeDatabase(dbPath, memoryLimit, tempDir);

		// Setup connection pool
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl("jdbc:duckdb:" + dbPath);
		config.setMaximumPoolSize(10); // Max 10 concurrent connections
		config.setMinimumIdle(2);      // Keep 2 connections ready
		config.setConnectionTimeout(30000); // 30 seconds
		config.setIdleTimeout(600000);      // 10 minutes
		config.setMaxLifetime(1800000);     // 30 minutes
		config.setPoolName("DuckDB-ExternalOccurrences-Pool");

		// Enable auto-commit for read-only queries
		config.setAutoCommit(true);

		// CRITICAL: Load spatial extension for each connection from the pool
		// INSTALL persists to the database, but LOAD is per-connection
		config.setConnectionInitSql("LOAD spatial");

		this.dataSource = new HikariDataSource(config);

		logger.info("DuckDB connection pool initialized successfully. Database: {}, Memory Limit: {}",
				dbPath, memoryLimit);
	}

	/**
	 * search_area(geojson, buffer_m): the GeoJSON geometry grown by buffer_m
	 * metres, as a one-row table with the area a, its bounding box x0, x1, y0, y1,
	 * and polygons: the input's own polygons without the buffer (empty when it has
	 * none). Points become circles, lines corridors, and polygons grow outward
	 * keeping their shape with rounded corners, so the edge of the area is
	 * buffer_m from the geometry everywhere; overlapping parts merge. ST_Buffer is planar, so the geometry is buffered in an azimuthal
	 * equidistant projection centred on it, where units are metres, and
	 * transformed back to lon/lat. A zero buffer uses the geometry as-is so
	 * polygons stay exact.
	 */
	private static final String SEARCH_AREA_MACRO = "CREATE OR REPLACE MACRO search_area(geojson, buffer_m) AS TABLE"
			+ " WITH input AS (SELECT ST_MakeValid(ST_GeomFromGeoJSON(geojson)) AS g),"
			+ " local AS ("
			+ "   SELECT g, printf('+proj=aeqd +lat_0=%f +lon_0=%f +datum=WGS84 +units=m',"
			+ "     ST_Y(ST_Centroid(g)), ST_X(ST_Centroid(g))) AS crs"
			+ "   FROM input"
			+ " ),"
			+ " buffered AS ("
			+ "   SELECT CASE WHEN buffer_m = 0 THEN g"
			+ "     ELSE ST_Transform("
			+ "       ST_Buffer(ST_Transform(g, 'EPSG:4326', crs, always_xy := true), buffer_m, 16),"
			+ "       crs, 'EPSG:4326', always_xy := true)"
			+ "   END AS a, ST_CollectionExtract(g, 3) AS polygons"
			+ "   FROM local"
			+ " )"
			+ " SELECT a, ST_XMin(a) AS x0, ST_XMax(a) AS x1, ST_YMin(a) AS y0, ST_YMax(a) AS y1, polygons FROM buffered";

	/**
	 * Initialize the DuckDB database with required extensions and settings.
	 */
	private void initializeDatabase(String dbPath, String memoryLimit, String tempDir) throws SQLException {
		String jdbcUrl = "jdbc:duckdb:" + dbPath;

		logger.info("Initializing DuckDB database at: {}", dbPath);

		try (Connection conn = java.sql.DriverManager.getConnection(jdbcUrl)) {
			try (Statement stmt = conn.createStatement()) {
				// Set memory limit (applies to all connections to this database)
				logger.info("Setting DuckDB memory_limit to: {}", memoryLimit);
				stmt.execute("SET memory_limit = " + literal(memoryLimit));

				// Set temp directory for disk spilling
				logger.info("Setting DuckDB temp_directory to: {}", tempDir);
				stmt.execute("SET temp_directory = " + literal(tempDir));

				// Install spatial extension (persists in the database file)
				// Note: INSTALL persists, but LOAD is per-connection (handled by HikariCP connectionInitSql)
				logger.info("Installing spatial extension...");
				stmt.execute("INSTALL spatial");
				stmt.execute("LOAD spatial"); // Load for this initialization connection

				// Search area used by the GBIF queries; persists in the database file
				logger.info("Creating search_area macro...");
				stmt.execute(SEARCH_AREA_MACRO);

				logger.info("DuckDB database initialized successfully with spatial extension");
			}
		} catch (SQLException e) {
			logger.error("Failed to initialize DuckDB database", e);
			throw e;
		}
	}

	/**
	 * Get the singleton DuckDBUtil instance.
	 */
	public static DuckDBUtil getInstance() {
		if (instance == null) {
			synchronized (LOCK) {
				if (instance == null) {
					try {
						instance = new DuckDBUtil();
					} catch (SQLException e) {
						logger.error("Failed to initialize DuckDBUtil", e);
						throw new RuntimeException("Failed to initialize DuckDB", e);
					}
				}
			}
		}
		return instance;
	}

	/**
	 * Get a connection from the pool.
	 *
	 * IMPORTANT: Caller MUST close the connection (use try-with-resources).
	 * The spatial extension is already loaded, no need to load it again.
	 *
	 * @return Connection from the pool
	 * @throws SQLException if unable to get connection
	 */
	public static Connection getConnection() throws SQLException {
		return getInstance().dataSource.getConnection();
	}

	/**
	 * Get the database file path.
	 *
	 * @return Path to the DuckDB database file
	 */
	public String getDatabasePath() {
		return databasePath;
	}

	/**
	 * Interface for executing operations with a DuckDB connection.
	 * Ensures proper resource cleanup.
	 *
	 * @param <T> Return type of the operation
	 */
	@FunctionalInterface
	public interface DuckDBOperation<T> {
		T execute(Connection conn) throws SQLException;
	}

	/**
	 * Execute an operation with a connection from the pool.
	 * Automatically handles connection cleanup.
	 *
	 * @param <T> Return type
	 * @param operation Operation to execute
	 * @return Result of the operation
	 * @throws SQLException if operation fails
	 */
	public static <T> T withConnection(DuckDBOperation<T> operation) throws SQLException {
		try (Connection conn = getConnection()) {
			return operation.execute(conn);
		}
	}

	/** Quote a string as a SQL literal. */
	public static String literal(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	/** Quote a name as a SQL identifier. */
	public static String identifier(String name) {
		return "\"" + name.replace("\"", "\"\"") + "\"";
	}

	/**
	 * Shutdown the connection pool gracefully.
	 * Call this on application shutdown.
	 */
	public static void shutdown() {
		if (instance != null && instance.dataSource != null) {
			logger.info("Shutting down DuckDB connection pool...");
			instance.dataSource.close();
			instance = null;
			logger.info("DuckDB connection pool closed successfully");
		}
	}
}
