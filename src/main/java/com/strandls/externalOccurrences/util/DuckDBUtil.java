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
	 * Macros that build the search area from any GeoJSON geometry, created in this
	 * order (each uses the ones before it):
	 * <ul>
	 * <li>search_input(geojson): the parsed, valid geometry.</li>
	 * <li>search_grow(part, whole, buffer_m): part grown outward by buffer_m
	 * metres; unchanged when the buffer is 0 or part is empty. ST_Buffer is
	 * planar, so it buffers in an azimuthal equidistant projection centred on the
	 * whole input, where units are metres, and transforms back to lon/lat.</li>
	 * <li>search_geom(geojson, point_buffer_m, buffer_m): the search area. Points
	 * grow by point_buffer_m into circles; lines (corridors) and polygons (the same
	 * shape with rounded corners) grow by buffer_m, so the edge of the area is
	 * that far from the geometry everywhere; overlapping parts merge. A zero buffer
	 * keeps that part as-is so polygons stay exact.</li>
	 * <li>search_polygons(geojson): the input's own polygons without a buffer
	 * (empty when it has none).</li>
	 * <li>search_area(geojson, point_buffer_m, buffer_m): a one-row table with the
	 * area a, its bounding box x0, x1, y0, y1, and polygons.</li>
	 * </ul>
	 * Macros are expanded inline, so list_transform([value], lambda v: ...)[1]
	 * names a value that is used more than once; otherwise the input would be
	 * parsed and buffered again for every use. search_geom and search_polygons are
	 * scalar so that, given literal arguments, DuckDB folds them into constant
	 * geometries while planning.
	 */
	private static final String[] SEARCH_MACROS = {
			"CREATE OR REPLACE MACRO search_input(geojson) AS ST_MakeValid(ST_GeomFromGeoJSON(geojson))",
			"CREATE OR REPLACE MACRO search_grow(part, whole, buffer_m) AS"
					+ " list_transform([printf('+proj=aeqd +lat_0=%f +lon_0=%f +datum=WGS84 +units=m',"
					+ "     ST_Y(ST_Centroid(whole)), ST_X(ST_Centroid(whole)))], lambda crs:"
					+ "   CASE WHEN ST_IsEmpty(part) OR buffer_m = 0 THEN part"
					+ "     ELSE ST_Transform(ST_Buffer(ST_Transform(part, 'EPSG:4326', crs, always_xy := true), buffer_m, 16),"
					+ "       crs, 'EPSG:4326', always_xy := true)"
					+ "   END)[1]",
			"CREATE OR REPLACE MACRO search_geom(geojson, point_buffer_m, buffer_m) AS"
					+ " list_transform([search_input(geojson)], lambda g:"
					+ "   list_transform([{'points': search_grow(ST_CollectionExtract(g, 1), g, point_buffer_m),"
					+ "       'others': search_grow(ST_Collect([ST_CollectionExtract(g, 2), ST_CollectionExtract(g, 3)]), g, buffer_m)}],"
					+ "     lambda grown:"
					+ "       CASE WHEN ST_IsEmpty(grown.points) THEN grown.others"
					+ "         WHEN ST_IsEmpty(grown.others) THEN grown.points"
					+ "         ELSE ST_Union(grown.points, grown.others)"
					+ "       END)[1])[1]",
			"CREATE OR REPLACE MACRO search_polygons(geojson) AS ST_CollectionExtract(search_input(geojson), 3)",
			"CREATE OR REPLACE MACRO search_area(geojson, point_buffer_m, buffer_m) AS TABLE"
					+ " SELECT a, ST_XMin(a) AS x0, ST_XMax(a) AS x1, ST_YMin(a) AS y0, ST_YMax(a) AS y1,"
					+ "   search_polygons(geojson) AS polygons"
					+ " FROM (SELECT search_geom(geojson, point_buffer_m, buffer_m) AS a)" };

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
				logger.info("Creating search area macros...");
				for (String macro : SEARCH_MACROS) {
					stmt.execute(macro);
				}

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
