package com.easysubway.collection.adapter.out.idgnr;

import com.easysubway.collection.application.port.out.GenerateCollectionRunIdPort;
import java.util.Set;
import javax.sql.DataSource;
import org.egovframe.rte.fdl.cmmn.exception.FdlException;
import org.egovframe.rte.fdl.idgnr.EgovIdGnrService;
import org.egovframe.rte.fdl.idgnr.impl.EgovTableIdGnrServiceImpl;
import org.egovframe.rte.fdl.property.EgovPropertyService;
import org.egovframe.rte.fdl.property.impl.EgovPropertyServiceImpl;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * 수집 run ID control-plane 어댑터 구성.
 *
 * <p>eGovFrame fdl-property({@link EgovPropertyService})로 run ID 접두어를 외부화하고,
 * fdl-idgnr Table 전략({@link EgovTableIdGnrServiceImpl})으로 운영성 순번을 발번한다.
 * eGov 타입은 이 파일에만 나타나며, application 계층은 {@link GenerateCollectionRunIdPort}만 안다.
 *
 * <p>발번 대상은 운영성 수집 run ID 한정이다. UUID·DB PK·receipt token·보안 식별자는 대상이 아니다.
 */
@Configuration
class CollectionControlPlaneEgovConfig {

	static final String RUN_ID_PREFIX_PROPERTY = "collection.run.id.prefix";
	static final String RUN_ID_GROUP = "COLLECTION_RUN_ID";
	private static final String CONTROL_PLANE_PROPERTIES =
		"classpath*:egovframe/collection-control-plane.properties";

	@Bean
	EgovPropertyService collectionControlPlanePropertyService() {
		var propertyService = new EgovPropertyServiceImpl();
		propertyService.setExtFileName(Set.of(CONTROL_PLANE_PROPERTIES));
		return propertyService;
	}

	@Bean
	EgovIdGnrService collectionRunIdGnrService(DataSource dataSource) {
		var idGnrService = new ControlPlaneTableIdGnrService();
		idGnrService.setDataSource(dataSource);
		idGnrService.setTable("ids");
		idGnrService.setTableName(RUN_ID_GROUP);
		idGnrService.setTableNameFieldName("table_name");
		idGnrService.setNextIdFieldName("next_id");
		idGnrService.setBlockSize(10);
		return idGnrService;
	}

	@Bean
	GenerateCollectionRunIdPort collectionRunIdGenerator(
		EgovPropertyService collectionControlPlanePropertyService,
		EgovIdGnrService collectionRunIdGnrService
	) {
		String prefix = collectionControlPlanePropertyService.getString(RUN_ID_PREFIX_PROPERTY);
		return () -> {
			try {
				return prefix + collectionRunIdGnrService.getNextLongId();
			} catch (FdlException exception) {
				throw new IllegalStateException("수집 run ID를 발번하지 못했습니다.", exception);
			}
		};
	}

	/**
	 * eGovFrame idgnr 서비스 전용 메시지 소스.
	 *
	 * <p>eGov 서비스는 자체 debug/error 메시지를 {@code MessageSource}로 조회하는데, 앱 전역
	 * {@code messageSource}는 code 기반 검증을 위해 누락 코드에 예외를 던진다. 앱 검증 동작을 건드리지
	 * 않도록, idgnr 서비스에는 eGov 번들을 로드하고 누락 코드는 code로 대체하는 관대한 소스를 준다.
	 */
	private static MessageSource egovIdgnrMessageSource() {
		var messageSource = new ResourceBundleMessageSource();
		messageSource.setBasenames(
			"org/egovframe/rte/fdl/idgnr/messages/idgnr",
			"messages/message-common"
		);
		messageSource.setDefaultEncoding("UTF-8");
		messageSource.setUseCodeAsDefaultMessage(true);
		return messageSource;
	}

	/**
	 * {@link EgovTableIdGnrServiceImpl}의 {@code ApplicationContextAware} 훅이 앱 전역
	 * {@code messageSource} 빈(strict)을 잡아 debug 메시지에서 예외를 던지는 것을 막고, eGov 전용
	 * 관대한 메시지 소스를 주입한다(어댑터 경계 격리).
	 */
	private static final class ControlPlaneTableIdGnrService extends EgovTableIdGnrServiceImpl {

		@Override
		public void setApplicationContext(ApplicationContext applicationContext) {
			this.messageSource = egovIdgnrMessageSource();
		}
	}
}
