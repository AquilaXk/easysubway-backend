package com.easysubway.admin.code.application.port.out;

import com.easysubway.admin.code.domain.AdminCommonCode;
import com.easysubway.admin.code.domain.AdminCommonCodeGroup;
import java.util.List;
import java.util.Optional;

public interface AdminCommonCodeRepository {

	List<AdminCommonCodeGroup> findGroups();

	Optional<AdminCommonCodeGroup> findGroup(String groupCode);

	List<AdminCommonCode> findCodes(String groupCode);

	Optional<AdminCommonCode> findCode(String groupCode, String code);

	AdminCommonCode saveCode(AdminCommonCode code);
}
