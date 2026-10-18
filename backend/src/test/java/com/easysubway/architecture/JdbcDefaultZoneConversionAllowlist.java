package com.easysubway.architecture;

import java.util.Set;

/**
 * 기본 시간대 JDBC 변환({@code Timestamp.valueOf}·{@code toLocalDateTime} 등)을 이미 쓰는 곳.
 * 키는 {@code 클래스#메서드}이며 모두 JDBC 어댑터의 행 매핑·바인딩이다. 어댑터를
 * {@code setObject(LocalDateTime)}·{@code getObject(LocalDateTime.class)}로 옮기면 해당 항목을 지운다.
 */
final class JdbcDefaultZoneConversionAllowlist {

	static final Set<String> SITES = Set.of(
		"com.easysubway.admin.audit.adapter.out.persistence.JdbcAdminAuditEventRepository#mapEvent",
		"com.easysubway.admin.code.adapter.out.persistence.JdbcAdminCommonCodeRepository#mapCode",
		"com.easysubway.admin.code.adapter.out.persistence.JdbcAdminCommonCodeRepository#mapGroup",
		"com.easysubway.admin.identity.adapter.out.persistence.JdbcAdminIdentityRepository#mapIdentity",
		"com.easysubway.admin.identity.adapter.out.persistence.JdbcAdminIdentityRepository#toLocalDateTime",
		"com.easysubway.admin.operations.adapter.out.persistence.JdbcAdminIncidentRepository#mapIncident",
		"com.easysubway.admin.operations.adapter.out.persistence.JdbcAdminIncidentRepository#mapTransition",
		"com.easysubway.admin.savedview.adapter.out.persistence.JdbcAdminSavedViewRepository#mapSavedView",
		"com.easysubway.ads.adapter.out.persistence.JdbcAdRepository#mapCreative",
		"com.easysubway.collection.adapter.out.persistence.JdbcDataCollectionRunRepository#mapRun",
		"com.easysubway.datapack.adapter.out.persistence.JdbcAliasQuarantineQueueRepository#toLocalDateTime",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository#mapEvent",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDataSourceSnapshotRepository#mapSnapshot",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackCandidateRepository#toLocalDateTime",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseBlockerSummaryRepository#countSourceFreshnessBlockers",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseBlockerSummaryRepository#mapCandidate",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository#listObservations",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository#mapChannel",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseChannelRepository#mapEvent",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseDeliveryRepository#ldt",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseDeliveryRepository#ts",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseRequestRepository#toLdt",
		"com.easysubway.datapack.adapter.out.persistence.JdbcDatapackReleaseRequestRepository#toTs",
		"com.easysubway.datapack.adapter.out.persistence.JdbcFacilityEvidenceMatrixRepository#toLocalDateTime",
		"com.easysubway.datapack.adapter.out.persistence.JdbcManualOverrideLedgerRepository#toLocalDateTime",
		"com.easysubway.datapack.adapter.out.persistence.JdbcRouteGateMatrixRepository#toLocalDateTime",
		"com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteFacilityRepository#mapFavoriteFacility",
		"com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteRouteRepository#mapFavoriteRoute",
		"com.easysubway.favorite.adapter.out.persistence.JdbcFavoriteStationRepository#mapFavoriteStation",
		"com.easysubway.field.adapter.out.persistence.JdbcFieldVerificationChangeHistoryRepository#mapHistory",
		"com.easysubway.field.adapter.out.persistence.JdbcFieldVerificationSessionRepository#mapSession",
		"com.easysubway.journey.adapter.out.persistence.JdbcJourneySearchRecordStore#aggregate",
		"com.easysubway.journey.adapter.out.persistence.JdbcJourneySearchRecordStore#purgeRecordedBefore",
		"com.easysubway.journey.adapter.out.persistence.JdbcJourneySearchRecordStore#save",
		"com.easysubway.notice.adapter.out.persistence.JdbcServiceNoticeRepository#findActiveAt",
		"com.easysubway.notice.adapter.out.persistence.JdbcServiceNoticeRepository#save",
		"com.easysubway.notice.adapter.out.persistence.JdbcServiceNoticeRepository#toLdt",
		"com.easysubway.notice.adapter.out.persistence.JdbcServiceNoticeRepository#toTimestamp",
		"com.easysubway.notice.adapter.out.persistence.JdbcServiceNoticeRepository#unpublish",
		"com.easysubway.notification.adapter.out.persistence.JdbcNotificationPreferenceRepository#mapNotificationSettings",
		"com.easysubway.notification.adapter.out.persistence.JdbcNotificationPreferenceRepository#mapRegisteredDevice",
		"com.easysubway.notification.adapter.out.persistence.JdbcPushNotificationOutboxRepository#mapPushNotification",
		"com.easysubway.profile.adapter.out.persistence.JdbcMobilityProfileRepository#mapProfile",
		"com.easysubway.report.adapter.out.persistence.JdbcFacilityReportRepository#loadReportProcessingTimeSummary",
		"com.easysubway.report.adapter.out.persistence.JdbcFacilityReportRepository#mapFacilityReport",
		"com.easysubway.report.adapter.out.persistence.JdbcFacilityReportRepository#mapFacilityReportSummary",
		"com.easysubway.report.adapter.out.persistence.JdbcFacilityReportRepository#timestampOrNull",
		"com.easysubway.report.adapter.out.persistence.JdbcFacilityReportReviewAuditRepository#mapAudit",
		"com.easysubway.route.adapter.out.persistence.JdbcRouteSearchRepository#loadRecentBlockedFeedbacks",
		"com.easysubway.route.adapter.out.persistence.JdbcRouteSearchRepository#mapRouteSearchResult",
		"com.easysubway.transit.adapter.out.persistence.JdbcTransitMasterOverrideRepository#listMasterDataOverrideAudits",
		"com.easysubway.usage.adapter.out.persistence.JdbcUserActivityRepository#activeUsersByDate",
		"com.easysubway.usage.adapter.out.persistence.JdbcUserActivityRepository#apiTrafficByDate");

	private JdbcDefaultZoneConversionAllowlist() {
	}
}
