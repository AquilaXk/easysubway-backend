package com.easysubway.common.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class LegacySchemaFingerprint {

	private static final List<CatalogQuery> CATALOG_QUERIES = List.of(
		new CatalogQuery("RELATION", """
			SELECT c.relkind::text,
			       c.relname,
			       c.relpersistence::text,
			       c.relrowsecurity::text,
			       c.relforcerowsecurity::text,
			       CASE WHEN c.relkind IN ('v', 'm') THEN pg_get_viewdef(c.oid, true) ELSE '' END
			FROM pg_catalog.pg_class c
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = ?
			  AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S', 'c')
			  AND c.relname <> 'flyway_schema_history'
			"""),
		new CatalogQuery("COLUMN", """
			SELECT c.relname,
			       a.attnum::text,
			       a.attname,
			       pg_catalog.format_type(a.atttypid, a.atttypmod),
			       a.attnotnull::text,
			       a.attidentity::text,
			       a.attgenerated::text,
			       COALESCE(pg_get_expr(d.adbin, d.adrelid), ''),
			       COALESCE(coll.collname, '')
			FROM pg_catalog.pg_attribute a
			JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
			LEFT JOIN pg_catalog.pg_collation coll ON coll.oid = a.attcollation
			WHERE n.nspname = ?
			  AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'c')
			  AND c.relname <> 'flyway_schema_history'
			  AND a.attnum > 0
			  AND NOT a.attisdropped
			"""),
		new CatalogQuery("CONSTRAINT", """
			SELECT COALESCE(c.relname, ''),
			       con.conname,
			       con.contype::text,
			       con.condeferrable::text,
			       con.condeferred::text,
			       con.convalidated::text,
			       pg_get_constraintdef(con.oid, true)
			FROM pg_catalog.pg_constraint con
			JOIN pg_catalog.pg_namespace n ON n.oid = con.connamespace
			LEFT JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
			WHERE n.nspname = ?
			  AND COALESCE(c.relname, '') <> 'flyway_schema_history'
			"""),
		new CatalogQuery("INDEX", """
			SELECT table_class.relname,
			       index_class.relname,
			       index_data.indisprimary::text,
			       index_data.indisunique::text,
			       index_data.indisvalid::text,
			       index_data.indisready::text,
			       pg_get_indexdef(index_data.indexrelid)
			FROM pg_catalog.pg_index index_data
			JOIN pg_catalog.pg_class table_class ON table_class.oid = index_data.indrelid
			JOIN pg_catalog.pg_class index_class ON index_class.oid = index_data.indexrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = table_class.relnamespace
			WHERE n.nspname = ?
			  AND table_class.relname <> 'flyway_schema_history'
			"""),
		new CatalogQuery("SEQUENCE", """
			SELECT c.relname,
			       pg_catalog.format_type(s.seqtypid, NULL),
			       s.seqstart::text,
			       s.seqincrement::text,
			       s.seqmax::text,
			       s.seqmin::text,
			       s.seqcache::text,
			       s.seqcycle::text
			FROM pg_catalog.pg_sequence s
			JOIN pg_catalog.pg_class c ON c.oid = s.seqrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = ?
			"""),
		new CatalogQuery("TYPE", """
			SELECT t.typname,
			       t.typtype::text,
			       pg_catalog.format_type(t.typbasetype, t.typtypmod),
			       t.typnotnull::text,
			       COALESCE(t.typdefault, ''),
			       COALESCE((
			         SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder)::text
			         FROM pg_catalog.pg_enum e
			         WHERE e.enumtypid = t.oid
			       ), '[]'),
			       COALESCE(pg_catalog.format_type(r.rngsubtype, NULL), '')
			FROM pg_catalog.pg_type t
			JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
			LEFT JOIN pg_catalog.pg_range r ON r.rngtypid = t.oid
			LEFT JOIN pg_catalog.pg_class type_class ON type_class.oid = t.typrelid
			WHERE n.nspname = ?
			  AND (
			    (t.typtype IN ('d', 'e', 'r', 'm') AND t.typrelid = 0)
			    OR (t.typtype = 'c' AND type_class.relkind = 'c')
			  )
			"""),
		new CatalogQuery("ROUTINE", """
			SELECT p.proname,
			       p.prokind::text,
			       pg_get_function_identity_arguments(p.oid),
			       pg_get_functiondef(p.oid)
			FROM pg_catalog.pg_proc p
			JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
			WHERE n.nspname = ?
			"""),
		new CatalogQuery("TRIGGER", """
			SELECT c.relname,
			       t.tgname,
			       t.tgenabled::text,
			       pg_get_triggerdef(t.oid, true)
			FROM pg_catalog.pg_trigger t
			JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = ?
			  AND NOT t.tgisinternal
			  AND c.relname <> 'flyway_schema_history'
			"""),
		new CatalogQuery("RULE", """
			SELECT c.relname,
			       r.rulename,
			       r.ev_type::text,
			       r.ev_enabled::text,
			       r.is_instead::text,
			       pg_get_ruledef(r.oid, true)
			FROM pg_catalog.pg_rewrite r
			JOIN pg_catalog.pg_class c ON c.oid = r.ev_class
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = ?
			  AND c.relname <> 'flyway_schema_history'
			  AND r.rulename <> '_RETURN'
			"""),
		new CatalogQuery("POLICY", """
			SELECT c.relname,
			       p.polname,
			       p.polcmd::text,
			       p.polpermissive::text,
			       COALESCE((
			         SELECT jsonb_agg(COALESCE(role_name.rolname, 'PUBLIC') ORDER BY COALESCE(role_name.rolname, 'PUBLIC'))::text
			         FROM unnest(p.polroles) AS policy_role(oid)
			         LEFT JOIN pg_catalog.pg_roles role_name ON role_name.oid = policy_role.oid
			       ), '[]'),
			       COALESCE(pg_get_expr(p.polqual, p.polrelid), ''),
			       COALESCE(pg_get_expr(p.polwithcheck, p.polrelid), '')
			FROM pg_catalog.pg_policy p
			JOIN pg_catalog.pg_class c ON c.oid = p.polrelid
			JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
			WHERE n.nspname = ?
			  AND c.relname <> 'flyway_schema_history'
			""")
	);

	private LegacySchemaFingerprint() {
	}

	static String calculate(Connection connection, String schema) throws SQLException {
		requireIdentifier(schema);
		List<String> records = new ArrayList<>();
		records.add(canonicalRecord("SCHEMA", List.of(schema)));
		for (CatalogQuery catalogQuery : CATALOG_QUERIES) {
			readRecords(connection, schema, catalogQuery, records);
		}
		records.sort(Comparator.naturalOrder());
		MessageDigest digest = sha256Digest();
		for (String record : records) {
			digest.update(record.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	static DatabaseIdentity databaseIdentity(Connection connection, String expectedSchema) throws SQLException {
		requireIdentifier(expectedSchema);
		try (var statement = connection.prepareStatement("""
			SELECT current_setting('server_version_num')::integer / 10000,
			       current_database(),
			       current_schema(),
			       current_user,
			       COALESCE(inet_server_addr()::text, ''),
			       COALESCE(inet_server_port(), 0)::text
			"""); var result = statement.executeQuery()) {
			if (!result.next()) throw new SQLException("database identity unavailable");
			int postgresMajor = result.getInt(1);
			String currentSchema = result.getString(3);
			if (!expectedSchema.equals(currentSchema)) throw new SQLException("database schema identity mismatch");
			String canonical = canonicalRecord("POSTGRES_TARGET_V1", List.of(
				Integer.toString(postgresMajor),
				result.getString(2),
				currentSchema,
				result.getString(4),
				result.getString(5),
				result.getString(6)
			));
			return new DatabaseIdentity(postgresMajor, sha256(canonical.getBytes(StandardCharsets.UTF_8)));
		}
	}

	private static void readRecords(
		Connection connection,
		String schema,
		CatalogQuery catalogQuery,
		List<String> records
	) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(catalogQuery.sql())) {
			statement.setString(1, schema);
			try (ResultSet result = statement.executeQuery()) {
				ResultSetMetaData metadata = result.getMetaData();
				while (result.next()) {
					List<String> fields = new ArrayList<>(metadata.getColumnCount());
					for (int column = 1; column <= metadata.getColumnCount(); column++) {
						fields.add(normalize(result.getString(column)));
					}
					records.add(canonicalRecord(catalogQuery.kind(), fields));
				}
			}
		}
	}

	private static String canonicalRecord(String kind, List<String> fields) {
		StringBuilder canonical = new StringBuilder();
		appendField(canonical, kind);
		for (String field : fields) appendField(canonical, field);
		return canonical.toString();
	}

	private static void appendField(StringBuilder canonical, String value) {
		String safeValue = value == null ? "" : value;
		canonical.append(safeValue.length()).append(':').append(safeValue).append(';');
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	private static void requireIdentifier(String value) {
		if (value == null || !value.matches("[a-z][a-z0-9_]{0,62}")) {
			throw new IllegalArgumentException("invalid schema identifier");
		}
	}

	private static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(sha256Digest().digest(bytes));
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	record DatabaseIdentity(int postgresMajor, String sha256) {
	}

	private record CatalogQuery(String kind, String sql) {
	}
}
