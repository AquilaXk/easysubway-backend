package com.easysubway.admin.operations.application.port.out;

import com.easysubway.admin.operations.domain.AdminIncident;
import java.util.List;
import java.util.Optional;

public interface AdminIncidentRepository {

	List<AdminIncident> findRecent(int limit);

	Optional<AdminIncident> findById(String incidentId);

	AdminIncident save(AdminIncident incident);
}
