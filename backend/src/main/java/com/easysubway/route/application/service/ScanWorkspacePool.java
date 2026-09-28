package com.easysubway.route.application.service;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lock-free, bounded workspace pool for {@link RouteTimetableRaptorPlanner.ScanWorkspace}.
 *
 * <p>Under Virtual Threads, {@link ThreadLocal} storage leads to per-request workspace allocations
 * (~28MB per request for Seoul Metro topology) and severe GC churn because virtual threads are
 * ephemeral and discarded after task completion. This pool decouples workspace lifecycle from
 * thread lifecycle, allowing virtual threads to borrow pre-allocated workspaces, reuse memory,
 * and return them safely.</p>
 */
public final class ScanWorkspacePool {

	private static final int DEFAULT_MAX_IDLE_WORKSPACES = Math.max(16, Runtime.getRuntime().availableProcessors() * 2);

	private static final class SharedHolder {
		private static final ScanWorkspacePool INSTANCE = new ScanWorkspacePool(DEFAULT_MAX_IDLE_WORKSPACES);
	}

	private final int maxIdleWorkspaces;
	private final ConcurrentLinkedQueue<RouteTimetableRaptorPlanner.ScanWorkspace> idleWorkspaces = new ConcurrentLinkedQueue<>();
	private final AtomicInteger idleCount = new AtomicInteger();
	private final AtomicInteger totalAllocated = new AtomicInteger();

	public static ScanWorkspacePool shared() {
		return SharedHolder.INSTANCE;
	}

	public ScanWorkspacePool() {
		this(DEFAULT_MAX_IDLE_WORKSPACES);
	}

	public ScanWorkspacePool(int maxIdleWorkspaces) {
		if (maxIdleWorkspaces <= 0) {
			throw new IllegalArgumentException("maxIdleWorkspaces must be positive: " + maxIdleWorkspaces);
		}
		this.maxIdleWorkspaces = maxIdleWorkspaces;
	}

	RouteTimetableRaptorPlanner.ScanWorkspace acquire() {
		RouteTimetableRaptorPlanner.ScanWorkspace workspace = idleWorkspaces.poll();
		if (workspace != null) {
			idleCount.decrementAndGet();
			return workspace;
		}
		totalAllocated.incrementAndGet();
		return new RouteTimetableRaptorPlanner.ScanWorkspace();
	}

	void release(RouteTimetableRaptorPlanner.ScanWorkspace workspace) {
		if (workspace == null) {
			return;
		}
		while (true) {
			int current = idleCount.get();
			if (current >= maxIdleWorkspaces) {
				return;
			}
			if (idleCount.compareAndSet(current, current + 1)) {
				idleWorkspaces.offer(workspace);
				return;
			}
		}
	}

	public int idleCount() {
		return Math.max(0, idleCount.get());
	}

	public int maxIdleWorkspaces() {
		return maxIdleWorkspaces;
	}

	public int totalAllocated() {
		return totalAllocated.get();
	}
}
