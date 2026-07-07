package com.easysubway.datapack.application.service;

import com.easysubway.datapack.application.service.DatapackReleaseChannelCommandService.ReleaseChannelCommand;
import com.easysubway.datapack.application.service.DatapackReleaseChannelCommandService.ReleaseChannelOperationResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 콜백 수신 트랜잭션(REQUIRED)과 격리된 promote 실행 위임 빈.
 * REQUIRES_NEW 로 자체 트랜잭션을 열어 promote 게이트 거부 예외가 outer tx 를
 * rollback-only 로 오염시키지 않도록 한다.
 * self-invocation 프록시 우회 방지를 위해 별도 Spring 빈으로 분리한다.
 */
@Service
public class DatapackReleasePromoteDelegate {

    private final DatapackReleaseChannelCommandService channelCommandService;

    public DatapackReleasePromoteDelegate(DatapackReleaseChannelCommandService channelCommandService) {
        this.channelCommandService = channelCommandService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReleaseChannelOperationResult promote(ReleaseChannelCommand command) {
        return channelCommandService.promote(command);
    }
}
