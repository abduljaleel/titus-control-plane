/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.titus.master.clusteroperations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.netflix.spectator.api.BasicTag;
import com.netflix.spectator.api.Gauge;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.api.Tag;
import com.netflix.titus.api.agent.model.AgentInstance;
import com.netflix.titus.api.agent.model.AgentInstanceGroup;
import com.netflix.titus.api.agent.model.InstanceGroupLifecycleState;
import com.netflix.titus.api.agent.model.InstanceOverrideState;
import com.netflix.titus.api.agent.model.InstanceOverrideStatus;
import com.netflix.titus.api.agent.service.AgentManagementService;
import com.netflix.titus.api.jobmanager.model.job.ContainerResources;
import com.netflix.titus.api.jobmanager.model.job.Job;
import com.netflix.titus.api.jobmanager.model.job.Task;
import com.netflix.titus.api.jobmanager.model.job.TaskState;
import com.netflix.titus.api.jobmanager.model.job.TaskStatus;
import com.netflix.titus.api.jobmanager.service.V3JobOperations;
import com.netflix.titus.api.model.ResourceDimension;
import com.netflix.titus.api.model.Tier;
import com.netflix.titus.common.runtime.TitusRuntime;
import com.netflix.titus.common.util.guice.annotation.Activator;
import com.netflix.titus.common.util.limiter.ImmutableLimiters;
import com.netflix.titus.common.util.limiter.tokenbucket.ImmutableTokenBucket;
import com.netflix.titus.common.util.limiter.tokenbucket.ImmutableTokenBucket.ImmutableRefillStrategy;
import com.netflix.titus.common.util.rx.ObservableExt;
import com.netflix.titus.common.util.time.Clock;
import com.netflix.titus.common.util.tuple.Pair;
import com.netflix.titus.master.scheduler.SchedulingService;
import com.netflix.titus.master.scheduler.TaskPlacementFailure;
import com.netflix.titus.master.scheduler.TaskPlacementFailure.FailureKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Completable;
import rx.Scheduler;
import rx.Subscription;
import rx.schedulers.Schedulers;

import static com.netflix.titus.master.MetricConstants.METRIC_CLUSTER_OPERATIONS;
import static com.netflix.titus.master.clusteroperations.ClusterOperationFunctions.applyScalingFactor;
import static com.netflix.titus.master.clusteroperations.ClusterOperationFunctions.canFit;
import static com.netflix.titus.master.clusteroperations.ClusterOperationFunctions.getNumberOfTasksOnAgents;
import static com.netflix.titus.master.clusteroperations.ClusterOperationFunctions.hasTimeElapsed;
import static java.util.Collections.singletonList;

/**
 * This component is responsible for adding and removing agents.
 */
@Singleton
public class ClusterAgentAutoScaler {
    private static final Logger logger = LoggerFactory.getLogger(ClusterAgentAutoScaler.class);
    private static final String METRIC_ROOT = METRIC_CLUSTER_OPERATIONS + "clusterAgentAutoScaler.";
    private static final long TIME_TO_WAIT_AFTER_ACTIVATION = 300_000;
    private static final long AUTO_SCALER_ITERATION_INTERVAL_MS = 30_000;
    private static final long CLUSTER_AGENT_AUTO_SCALE_COMPLETABLE_TIMEOUT_MS = 300_000;
    private static final long TASK_IDS_PREVIOUSLY_SCALED_TTL_MS = 600_000;
    private static final long SCALE_UP_TOKEN_BUCKET_CAPACITY = 50;
    private static final long SCALE_UP_TOKEN_BUCKET_REFILL_AMOUNT = 2;
    private static final long SCALE_UP_TOKEN_BUCKET_REFILL_INTERVAL_MS = 1_000;
    private static final long SCALE_DOWN_TOKEN_BUCKET_CAPACITY = 50;
    private static final long SCALE_DOWN_TOKEN_BUCKET_REFILL_AMOUNT = 2;
    private static final long SCALE_DOWN_TOKEN_BUCKET_REFILL_INTERVAL_MS = 1_000;

    private final TitusRuntime titusRuntime;
    private final ClusterOperationsConfiguration configuration;
    private final AgentManagementService agentManagementService;
    private final V3JobOperations v3JobOperations;
    private final SchedulingService schedulingService;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Cache<String, String> taskIdsForPreviousScaleUps;
    private final Map<Tier, TierAutoScalerExecution> tierTierAutoScalerExecutions;

    private Subscription agentAutoScalerSubscription;

    @Inject
    public ClusterAgentAutoScaler(TitusRuntime titusRuntime,
                                  ClusterOperationsConfiguration configuration,
                                  AgentManagementService agentManagementService,
                                  V3JobOperations v3JobOperations,
                                  SchedulingService schedulingService) {
        this(titusRuntime, configuration, agentManagementService, v3JobOperations, schedulingService, Schedulers.newThread());
    }

    public ClusterAgentAutoScaler(TitusRuntime titusRuntime,
                                  ClusterOperationsConfiguration configuration,
                                  AgentManagementService agentManagementService,
                                  V3JobOperations v3JobOperations,
                                  SchedulingService schedulingService,
                                  Scheduler scheduler) {
        this.titusRuntime = titusRuntime;
        this.configuration = configuration;
        this.agentManagementService = agentManagementService;
        this.v3JobOperations = v3JobOperations;
        this.schedulingService = schedulingService;
        this.scheduler = scheduler;
        this.clock = titusRuntime.getClock();
        this.taskIdsForPreviousScaleUps = CacheBuilder.newBuilder()
                .expireAfterWrite(TASK_IDS_PREVIOUSLY_SCALED_TTL_MS, TimeUnit.MILLISECONDS)
                .build();
        this.tierTierAutoScalerExecutions = new HashMap<>();
    }

    @Activator
    public void enterActiveMode() {
        agentAutoScalerSubscription = ObservableExt.schedule(
                METRIC_ROOT, titusRuntime.getRegistry(),
                "doAgentScaling", doAgentScaling(),
                TIME_TO_WAIT_AFTER_ACTIVATION, AUTO_SCALER_ITERATION_INTERVAL_MS, TimeUnit.MILLISECONDS, scheduler
        ).subscribe(next -> next.ifPresent(e -> logger.warn("doAgentScaling error:", e)));
    }

    @PreDestroy
    public void shutdown() {
        ObservableExt.safeUnsubscribe(agentAutoScalerSubscription);
    }

    @VisibleForTesting
    Completable doAgentScaling() {
        return Completable.defer(() -> {
            if (!configuration.isAutoScalingAgentsEnabled()) {
                logger.debug("Auto scaling agents is not enabled");
                return Completable.complete();
            }

            List<Completable> actions = new ArrayList<>();

            Map<String, Job> allJobs = getAllJobs();
            Map<String, Task> allTasks = getAllTasks();
            List<AgentInstanceGroup> activeInstanceGroups = getActiveInstanceGroups();
            Map<AgentInstanceGroup, List<AgentInstance>> instancesForActiveInstanceGroups = getInstancesForInstanceGroups(activeInstanceGroups);

            Map<String, Long> numberOfTasksOnAgents = getNumberOfTasksOnAgents(allTasks.values());
            Map<FailureKind, List<TaskPlacementFailure>> lastTaskPlacementFailures = schedulingService.getLastTaskPlacementFailures();
            Map<String, TaskPlacementFailure> launchGuardFailuresByTaskId = getLaunchGuardFailuresByTaskId(lastTaskPlacementFailures);
            Map<Tier, Set<String>> failedTaskIdsByTier = getFailedTaskIds(lastTaskPlacementFailures);

            long now = clock.wallTime();

            for (Tier tier : Tier.values()) {
                logger.debug("Starting scaling for tier: {}", tier);
                TierAutoScalingConfiguration tierConfiguration = ClusterOperationFunctions.getTierConfiguration(tier, configuration);
                TierAutoScalerExecution tierAutoScalerExecution = tierTierAutoScalerExecutions.computeIfAbsent(
                        tier, k -> new TierAutoScalerExecution(tier, titusRuntime.getRegistry())
                );

                List<AgentInstanceGroup> activeInstanceGroupsForTier = activeInstanceGroups.stream()
                        .filter(ig -> ig.getTier() == tier)
                        .collect(Collectors.toList());
                logger.debug("activeInstanceGroupsForTier: {}", activeInstanceGroupsForTier);
                List<AgentInstance> idleInstancesForTier = getIdleInstancesForTier(tier, tierConfiguration.getPrimaryInstanceType(),
                        instancesForActiveInstanceGroups, numberOfTasksOnAgents);
                tierAutoScalerExecution.getTotalIdleInstancesGauge().set(idleInstancesForTier.size());
                logger.debug("idleInstancesForTier: {}", idleInstancesForTier);

                int agentCountToScaleUp = 0;
                Set<String> potentialTaskIdsForScaleUp = new HashSet<>();

                if (hasTimeElapsed(tierAutoScalerExecution.getLastScaleUp().get(), now, tierConfiguration.getScaleUpCoolDownMs())) {
                    int minIdleForTier = tierConfiguration.getMinIdle();
                    if (idleInstancesForTier.size() < minIdleForTier) {
                        int instancesNeededForMinIdle = minIdleForTier - idleInstancesForTier.size();
                        logger.debug("instancesNeededForMinIdle: {}", instancesNeededForMinIdle);
                        agentCountToScaleUp += instancesNeededForMinIdle;
                    }

                    // This will throw an exception if not properly configured
                    ResourceDimension resourceDimension = agentManagementService.getResourceLimits(tierConfiguration.getPrimaryInstanceType());
                    Set<String> taskIdsForScaleUp = getTaskIdsForScaleUp(tier, resourceDimension,
                            lastTaskPlacementFailures, launchGuardFailuresByTaskId, allJobs, allTasks);
                    logger.debug("taskIdsForScaleUp: {}", taskIdsForScaleUp);
                    potentialTaskIdsForScaleUp.addAll(taskIdsForScaleUp);

                    if (agentCountToScaleUp > 0) {
                        tierAutoScalerExecution.getLastScaleUp().set(clock.wallTime());
                    }
                }

                Set<String> failedTaskIds = failedTaskIdsByTier.getOrDefault(tier, Collections.emptySet());
                tierAutoScalerExecution.getTotalFailedTasksGauge().set(failedTaskIds.size());
                logger.debug("failedTaskIds: {}", failedTaskIds);

                Set<String> tasksPastSlo = getTasksPastSlo(failedTaskIds, allTasks, now, tierConfiguration.getTaskSloMs());
                tierAutoScalerExecution.getTotalTasksPastSloGauge().set(tasksPastSlo.size());
                logger.debug("tasksPastSlo: {}", tasksPastSlo);

                potentialTaskIdsForScaleUp.addAll(tasksPastSlo);

                Set<String> taskIdsForScaleUp = new HashSet<>();
                for (String taskId : potentialTaskIdsForScaleUp) {
                    boolean previouslyScaledFor = taskIdsForPreviousScaleUps.getIfPresent(taskId) != null;
                    if (!previouslyScaledFor) {
                        taskIdsForScaleUp.add(taskId);
                        taskIdsForPreviousScaleUps.put(taskId, taskId);
                    }
                }
                tierAutoScalerExecution.getTotalTasksForScaleUpGauge().set(taskIdsForScaleUp.size());
                logger.debug("taskIdsForScaleUp: {}", taskIdsForScaleUp);
                agentCountToScaleUp += applyScalingFactor(tierConfiguration.getScaleUpAdjustingFactor(), taskIdsForScaleUp.size());
                tierAutoScalerExecution.getTotalAgentsToScaleUpGauge().set(agentCountToScaleUp);
                boolean scalingUp = false;

                if (agentCountToScaleUp > 0) {
                    long maxTokensToTake = Math.min(SCALE_DOWN_TOKEN_BUCKET_CAPACITY, agentCountToScaleUp);
                    Optional<Pair<Long, ImmutableTokenBucket>> takeOpt = tierAutoScalerExecution.getLastScaleUpTokenBucket().tryTake(1, maxTokensToTake);
                    if (takeOpt.isPresent()) {
                        Pair<Long, ImmutableTokenBucket> takePair = takeOpt.get();
                        tierAutoScalerExecution.setLastScaleUpTokenBucket(takePair.getRight());
                        long tokensAvailable = takePair.getLeft();
                        Pair<Integer, Completable> scaleUpPair = createScaleUpCompletable(activeInstanceGroupsForTier, (int) tokensAvailable);
                        actions.add(scaleUpPair.getRight());
                        Integer agentCountBeingScaled = scaleUpPair.getLeft();
                        tierAutoScalerExecution.getTotalAgentsBeingScaledUpGauge().set(agentCountBeingScaled);
                        logger.info("Attempting to scale up {} tier by {} agent instances", tier, agentCountBeingScaled);
                        scalingUp = true;
                    }
                }

                if (!scalingUp && hasTimeElapsed(tierAutoScalerExecution.getLastScaleDown().get(), now, tierConfiguration.getScaleDownCoolDownMs())) {
                    int agentCountToScaleDown = 0;
                    int maxIdleForTier = tierConfiguration.getMaxIdle();
                    if (idleInstancesForTier.size() > maxIdleForTier) {
                        int instancesNotNeededForMaxIdle = idleInstancesForTier.size() - maxIdleForTier;
                        logger.debug("instancesNotNeededForMaxIdle: {}", instancesNotNeededForMaxIdle);
                        agentCountToScaleDown += instancesNotNeededForMaxIdle;
                    }

                    tierAutoScalerExecution.getTotalAgentsToScaleDownGauge().set(agentCountToScaleDown);

                    if (agentCountToScaleDown > 0) {
                        long maxTokensToTake = Math.min(SCALE_DOWN_TOKEN_BUCKET_CAPACITY, agentCountToScaleDown);
                        Optional<Pair<Long, ImmutableTokenBucket>> takeOpt = tierAutoScalerExecution.getLastScaleDownTokenBucket().tryTake(1, maxTokensToTake);
                        if (takeOpt.isPresent()) {
                            Pair<Long, ImmutableTokenBucket> takePair = takeOpt.get();
                            tierAutoScalerExecution.setLastScaleDownTokenBucket(takePair.getRight());
                            long tokensAvailable = takePair.getLeft();
                            Pair<Integer, Completable> scaleDownPair = createSetRemovableOverrideStatusesCompletable(idleInstancesForTier, (int) tokensAvailable);
                            actions.add(scaleDownPair.getRight());
                            Integer agentCountBeingScaledDown = scaleDownPair.getLeft();
                            tierAutoScalerExecution.getTotalAgentsBeingScaledDownGauge().set(agentCountBeingScaledDown);
                            logger.info("Attempting to scale down {} tier by {} agent instances", tier, agentCountBeingScaledDown);
                            tierAutoScalerExecution.getLastScaleDown().set(clock.wallTime());
                        }
                    }
                }
                logger.debug("Finishing scaling for tier: {}", tier);
            }

            List<AgentInstance> removableInstancesPastElapsedTime = getRemovableInstancesPastElapsedTime(instancesForActiveInstanceGroups,
                    now, configuration.getAgentInstanceRemovableTimeoutMs());
            logger.debug("removableInstancesPastElapsedTime: {}", removableInstancesPastElapsedTime);

            if (!removableInstancesPastElapsedTime.isEmpty()) {
                actions.add(createResetOverrideStatusesCompletable(removableInstancesPastElapsedTime));
                logger.info("Resetting {} agent instances", removableInstancesPastElapsedTime.size());
            }

            return Completable.concat(actions);
        }).doOnCompleted(() -> logger.debug("Completed scaling agents"))
                .timeout(CLUSTER_AGENT_AUTO_SCALE_COMPLETABLE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private List<AgentInstanceGroup> getActiveInstanceGroups() {
        return agentManagementService.getInstanceGroups().stream()
                .filter(ig -> ig.getLifecycleStatus().getState() == InstanceGroupLifecycleState.Active)
                .collect(Collectors.toList());
    }

    private Map<AgentInstanceGroup, List<AgentInstance>> getInstancesForInstanceGroups(List<AgentInstanceGroup> instanceGroups) {
        Map<AgentInstanceGroup, List<AgentInstance>> instancesForActiveInstanceGroups = new HashMap<>();
        for (AgentInstanceGroup instanceGroup : instanceGroups) {
            List<AgentInstance> instances = instancesForActiveInstanceGroups.computeIfAbsent(instanceGroup, k -> new ArrayList<>());
            instances.addAll(agentManagementService.getAgentInstances(instanceGroup.getId()));
        }
        return instancesForActiveInstanceGroups;
    }

    private List<AgentInstance> getRemovableInstancesPastElapsedTime(Map<AgentInstanceGroup, List<AgentInstance>> instancesForActiveInstanceGroups,
                                                                     long finish,
                                                                     long elapsed) {
        return instancesForActiveInstanceGroups.entrySet().stream()
                .flatMap(e -> e.getValue().stream().filter(i -> {
                    InstanceOverrideStatus overrideStatus = i.getOverrideStatus();
                    return overrideStatus.getState() == InstanceOverrideState.Removable && hasTimeElapsed(overrideStatus.getTimestamp(), finish, elapsed);
                }))
                .collect(Collectors.toList());
    }

    private List<AgentInstance> getIdleInstancesForTier(Tier tier,
                                                        String primaryInstanceType,
                                                        Map<AgentInstanceGroup, List<AgentInstance>> instancesForActiveInstanceGroups,
                                                        Map<String, Long> numberOfTasksOnAgent) {
        return instancesForActiveInstanceGroups.entrySet().stream()
                .filter(e -> {
                    AgentInstanceGroup instanceGroup = e.getKey();
                    return instanceGroup.getTier() == tier && instanceGroup.getInstanceType().equals(primaryInstanceType);
                })
                .flatMap(e -> e.getValue().stream().filter(i -> numberOfTasksOnAgent.getOrDefault(i.getId(), 0L) <= 0))
                .collect(Collectors.toList());
    }

    private Set<String> getTaskIdsForScaleUp(Tier tier,
                                             ResourceDimension resourceDimension,
                                             Map<FailureKind, List<TaskPlacementFailure>> lastTaskPlacementFailures,
                                             Map<String, TaskPlacementFailure> launchGuardFailuresByTaskId,
                                             Map<String, Job> allJobs,
                                             Map<String, Task> allTasks) {
        Set<String> taskIdsForScaleUp = new HashSet<>();
        for (List<TaskPlacementFailure> failures : lastTaskPlacementFailures.values()) {
            for (TaskPlacementFailure failure : failures) {
                String taskId = failure.getTaskId();
                if (failure.getTier() == tier && !launchGuardFailuresByTaskId.containsKey(failure.getTaskId())) {
                    Task task = allTasks.get(taskId);
                    if (task == null) {
                        continue;
                    }
                    Job job = allJobs.get(task.getJobId());
                    if (job == null) {
                        continue;
                    }
                    ContainerResources containerResources = job.getJobDescriptor().getContainer().getContainerResources();
                    if (canFit(containerResources, resourceDimension)) {
                        taskIdsForScaleUp.add(failure.getTaskId());
                    }
                }
            }
        }
        return taskIdsForScaleUp;
    }


    private Pair<Integer, Completable> createScaleUpCompletable(List<AgentInstanceGroup> scalableInstanceGroups, int scaleUpCount) {
        int count = 0;
        List<Completable> scaleUpActions = new ArrayList<>();
        for (AgentInstanceGroup instanceGroup : scalableInstanceGroups) {
            int totalAgentsNeeded = scaleUpCount - count;
            if (totalAgentsNeeded <= 0) {
                break;
            }
            int agentsAvailableInInstanceGroup = instanceGroup.getMax() - instanceGroup.getDesired();
            int agentsToScaleInInstanceGroup = Math.min(totalAgentsNeeded, agentsAvailableInInstanceGroup);
            scaleUpActions.add(agentManagementService.scaleUp(instanceGroup.getId(), agentsToScaleInInstanceGroup));
            count += agentsToScaleInInstanceGroup;
        }
        return Pair.of(count, Completable.concat(scaleUpActions));
    }

    private Pair<Integer, Completable> createSetRemovableOverrideStatusesCompletable(List<AgentInstance> idleInstances, int scaleDownCount) {
        List<Completable> actions = new ArrayList<>();

        int count = 0;
        for (AgentInstance agentInstance : idleInstances) {
            if (count >= scaleDownCount) {
                break;
            }
            InstanceOverrideStatus removableOverrideStatus = InstanceOverrideStatus.newBuilder()
                    .withState(InstanceOverrideState.Removable)
                    .withTimestamp(clock.wallTime())
                    .withDetail("ClusterAgentAutoScaler setting to Removable to scale down the instance")
                    .build();
            Completable completable = agentManagementService.updateInstanceOverride(agentInstance.getId(), removableOverrideStatus);
            actions.add(completable);
            count++;
        }
        return Pair.of(count, Completable.concat(actions));
    }

    private Completable createResetOverrideStatusesCompletable(List<AgentInstance> removableInstances) {
        List<Completable> actions = new ArrayList<>();
        for (AgentInstance agentInstance : removableInstances) {
            Completable completable = agentManagementService.removeInstanceOverride(agentInstance.getId());
            actions.add(completable);
        }
        return Completable.concat(actions);
    }


    private Map<String, Job> getAllJobs() {
        return v3JobOperations.getJobs().stream().collect(Collectors.toMap(Job::getId, Function.identity()));
    }

    private Map<String, Task> getAllTasks() {
        return v3JobOperations.getTasks().stream().collect(Collectors.toMap(Task::getId, Function.identity()));
    }

    private Map<Tier, Set<String>> getFailedTaskIds(Map<FailureKind, List<TaskPlacementFailure>> taskPlacementFailures) {
        Map<Tier, Set<String>> failedTaskIdsByTier = new HashMap<>();
        for (List<TaskPlacementFailure> failures : taskPlacementFailures.values()) {
            for (TaskPlacementFailure failure : failures) {
                Set<String> failedTaskIds = failedTaskIdsByTier.computeIfAbsent(failure.getTier(), k -> new HashSet<>());
                failedTaskIds.add(failure.getTaskId());
            }
        }
        return failedTaskIdsByTier;
    }

    private Set<String> getTasksPastSlo(Set<String> failedTaskIds, Map<String, Task> allTasks, long finish, long elapsed) {
        Set<String> taskIdsPastSlo = new HashSet<>();
        for (String taskId : failedTaskIds) {
            Task task = allTasks.get(taskId);
            if (task != null) {
                TaskStatus status = task.getStatus();
                if (status.getState() == TaskState.Accepted && hasTimeElapsed(status.getTimestamp(), finish, elapsed)) {
                    taskIdsPastSlo.add(taskId);
                }
            }
        }
        return taskIdsPastSlo;
    }

    private Map<String, TaskPlacementFailure> getLaunchGuardFailuresByTaskId(Map<FailureKind, List<TaskPlacementFailure>> lastTaskPlacementFailures) {
        return lastTaskPlacementFailures.getOrDefault(FailureKind.LaunchGuard, Collections.emptyList()).stream()
                .collect(Collectors.toMap(TaskPlacementFailure::getTaskId, Function.identity()));
    }

    private static class TierAutoScalerExecution {
        private final AtomicLong lastScaleUp = new AtomicLong();
        private final AtomicLong lastScaleDown = new AtomicLong();

        private final Gauge totalIdleInstancesGauge;
        private final Gauge totalFailedTasksGauge;
        private final Gauge totalTasksPastSloGauge;
        private final Gauge totalTasksForScaleUpGauge;
        private final Gauge totalAgentsToScaleUpGauge;
        private final Gauge totalAgentsBeingScaledUpGauge;
        private final Gauge totalAgentsToScaleDownGauge;
        private final Gauge totalAgentsBeingScaledDownGauge;

        private ImmutableTokenBucket lastScaleUpTokenBucket;
        private ImmutableTokenBucket lastScaleDownTokenBucket;

        TierAutoScalerExecution(Tier tier, Registry registry) {
            List<Tag> commonTags = singletonList(new BasicTag("tier", tier.name()));
            totalIdleInstancesGauge = registry.gauge(METRIC_ROOT + "totalIdleInstances", commonTags);
            totalFailedTasksGauge = registry.gauge(METRIC_ROOT + "totalFailedTasks", commonTags);
            totalTasksPastSloGauge = registry.gauge(METRIC_ROOT + "totalTasksPastSlo", commonTags);
            totalTasksForScaleUpGauge = registry.gauge(METRIC_ROOT + "totalTasksForScaleUp", commonTags);
            totalAgentsToScaleUpGauge = registry.gauge(METRIC_ROOT + "totalAgentsToScaleUp", commonTags);
            totalAgentsBeingScaledUpGauge = registry.gauge(METRIC_ROOT + "totalAgentsBeingScaledUp", commonTags);
            totalAgentsToScaleDownGauge = registry.gauge(METRIC_ROOT + "totalAgentsToScaleDown", commonTags);
            totalAgentsBeingScaledDownGauge = registry.gauge(METRIC_ROOT + "totalAgentsBeingScaledDown", commonTags);

            ImmutableRefillStrategy scaleUpRefillStrategy = ImmutableLimiters.refillAtFixedInterval(SCALE_UP_TOKEN_BUCKET_REFILL_AMOUNT,
                    SCALE_UP_TOKEN_BUCKET_REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            lastScaleUpTokenBucket = ImmutableLimiters.tokenBucket(SCALE_UP_TOKEN_BUCKET_CAPACITY, scaleUpRefillStrategy);

            ImmutableRefillStrategy scaleDownRefillStrategy = ImmutableLimiters.refillAtFixedInterval(SCALE_DOWN_TOKEN_BUCKET_REFILL_AMOUNT,
                    SCALE_DOWN_TOKEN_BUCKET_REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            lastScaleDownTokenBucket = ImmutableLimiters.tokenBucket(SCALE_DOWN_TOKEN_BUCKET_CAPACITY, scaleDownRefillStrategy);
        }

        AtomicLong getLastScaleUp() {
            return lastScaleUp;
        }

        AtomicLong getLastScaleDown() {
            return lastScaleDown;
        }

        Gauge getTotalIdleInstancesGauge() {
            return totalIdleInstancesGauge;
        }

        Gauge getTotalFailedTasksGauge() {
            return totalFailedTasksGauge;
        }

        Gauge getTotalTasksPastSloGauge() {
            return totalTasksPastSloGauge;
        }

        Gauge getTotalTasksForScaleUpGauge() {
            return totalTasksForScaleUpGauge;
        }

        Gauge getTotalAgentsToScaleUpGauge() {
            return totalAgentsToScaleUpGauge;
        }

        Gauge getTotalAgentsBeingScaledUpGauge() {
            return totalAgentsBeingScaledUpGauge;
        }

        Gauge getTotalAgentsToScaleDownGauge() {
            return totalAgentsToScaleDownGauge;
        }

        Gauge getTotalAgentsBeingScaledDownGauge() {
            return totalAgentsBeingScaledDownGauge;
        }

        ImmutableTokenBucket getLastScaleUpTokenBucket() {
            return lastScaleUpTokenBucket;
        }

        ImmutableTokenBucket getLastScaleDownTokenBucket() {
            return lastScaleDownTokenBucket;
        }

        void setLastScaleUpTokenBucket(ImmutableTokenBucket lastScaleUpTokenBucket) {
            this.lastScaleUpTokenBucket = lastScaleUpTokenBucket;
        }

        void setLastScaleDownTokenBucket(ImmutableTokenBucket lastScaleDownTokenBucket) {
            this.lastScaleDownTokenBucket = lastScaleDownTokenBucket;
        }
    }
}
