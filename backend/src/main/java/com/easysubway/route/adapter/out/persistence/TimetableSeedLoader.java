package com.easysubway.route.adapter.out.persistence;

import com.easysubway.route.application.port.out.LoadRouteTimetablePort;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.zip.GZIPInputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #1843: KRIC 4호선 시각표 seed를 prod DB에 startup(Flyway 이후)에 적재한다.
 *
 * <p>이중 게이트: {@code @Profile("prod|...")}로 non-prod 테스트에서 미로드(오염 없음),
 * {@code @ConditionalOnProperty}로 배포 flag를 켜야만 활성(출시게이트 스위치). 빈 DB일 때만 실행(멱등),
 * 전체를 단일 트랜잭션 + JDBC 배치로 all-or-nothing 적재한다(중간 실패 시 반파 방지). 리소스는 gzip(.gz)도 지원.
 */
@Component
@Profile("prod | staging | release | prod-like")
@ConditionalOnProperty(name = "easysubway.timetable.seed.enabled", havingValue = "true")
public class TimetableSeedLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TimetableSeedLoader.class);

	private final LoadRouteTimetablePort routeTimetablePort;
	private final DataSource dataSource;
	private final TransactionTemplate transactionTemplate;
	private final Resource seedResource;

	public TimetableSeedLoader(
		LoadRouteTimetablePort routeTimetablePort,
		DataSource dataSource,
		PlatformTransactionManager transactionManager,
		@Value("${easysubway.timetable.seed.resource:classpath:timetable/line4-timetable-seed.sql.gz}") Resource seedResource
	) {
		this.routeTimetablePort = routeTimetablePort;
		this.dataSource = dataSource;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.seedResource = seedResource;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (routeTimetablePort.hasRouteTimetable()) {
			log.info("transit timetable already present; skipping seed load");
			return;
		}
		List<String> statements = readStatements(seedResource);
		try {
			transactionTemplate.executeWithoutResult(status -> executeBatch(statements));
		} catch (RuntimeException exception) {
			// 다중 replica 동시 배포 경쟁: 다른 인스턴스가 먼저 적재하면 이 배치는 PK/싱글턴 충돌로 실패한다.
			// 실패 후 이미 적재됐으면(경쟁 loser) 관용 처리한다(부팅 crash loop 방지). 아니면 실제 오류로 재던진다.
			if (routeTimetablePort.hasRouteTimetable()) {
				log.info("transit timetable was seeded concurrently by another instance; batch failure is benign");
				return;
			}
			throw exception;
		}
		log.info("transit timetable seeded from {} ({} statements)", seedResource, statements.size());
	}

	private void executeBatch(List<String> statements) {
		Connection connection = DataSourceUtils.getConnection(dataSource);
		try (Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.addBatch(sql);
			}
			statement.executeBatch();
		} catch (SQLException exception) {
			throw new IllegalStateException("transit timetable seed failed", exception);
		} finally {
			DataSourceUtils.releaseConnection(connection, dataSource);
		}
	}

	// 도구가 한 줄당 한 statement(;로 종료)로 방출하므로 라인 단위 파싱으로 충분하다. .gz면 gunzip.
	private static List<String> readStatements(Resource resource) {
		String filename = resource.getFilename();
		boolean gzip = filename != null && filename.endsWith(".gz");
		try (InputStream raw = resource.getInputStream();
				InputStream data = gzip ? new GZIPInputStream(raw) : raw) {
			String sql = new String(data.readAllBytes(), StandardCharsets.UTF_8);
			return sql.lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("--"))
				.map(line -> line.endsWith(";") ? line.substring(0, line.length() - 1) : line)
				.toList();
		} catch (IOException exception) {
			throw new IllegalStateException("cannot read timetable seed resource: " + resource, exception);
		}
	}
}
