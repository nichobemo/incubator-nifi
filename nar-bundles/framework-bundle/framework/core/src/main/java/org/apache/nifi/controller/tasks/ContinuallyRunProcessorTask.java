/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.controller.tasks;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.repository.BatchingSessionFactory;
import org.apache.nifi.controller.repository.ProcessContext;
import org.apache.nifi.controller.repository.StandardFlowFileEvent;
import org.apache.nifi.controller.repository.StandardProcessSession;
import org.apache.nifi.controller.repository.StandardProcessSessionFactory;
import org.apache.nifi.controller.scheduling.ProcessContextFactory;
import org.apache.nifi.controller.scheduling.ScheduleState;
import org.apache.nifi.controller.scheduling.SchedulingAgent;
import org.apache.nifi.encrypt.StringEncryptor;
import org.apache.nifi.logging.ProcessorLog;
import org.apache.nifi.nar.NarCloseable;
import org.apache.nifi.processor.ProcessSessionFactory;
import org.apache.nifi.processor.SimpleProcessLogger;
import org.apache.nifi.processor.StandardProcessContext;
import org.apache.nifi.processor.annotation.OnStopped;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.util.Connectables;
import org.apache.nifi.util.ReflectionUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContinuallyRunProcessorTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ContinuallyRunProcessorTask.class);

    private final SchedulingAgent schedulingAgent;
    private final ProcessorNode procNode;
    private final ProcessContext context;
    private final ScheduleState scheduleState;
    private final StandardProcessContext processContext;
    private final FlowController flowController;
    private final int numRelationships;

    public ContinuallyRunProcessorTask(final SchedulingAgent schedulingAgent, final ProcessorNode procNode,
            final FlowController flowController, final ProcessContextFactory contextFactory, final ScheduleState scheduleState, final StringEncryptor encryptor) {

        this.schedulingAgent = schedulingAgent;
        this.procNode = procNode;
        this.scheduleState = scheduleState;
        this.numRelationships = procNode.getRelationships().size();
        this.flowController = flowController;

        context = contextFactory.newProcessContext(procNode, new AtomicLong(0L));
        this.processContext = new StandardProcessContext(procNode, flowController, encryptor);
    }

    @Override
    public void run() {
        // make sure processor is not yielded
        boolean shouldRun = (procNode.getYieldExpiration() < System.currentTimeMillis());
        if (!shouldRun) {
            return;
        }

        // make sure that either we're not clustered or this processor runs on all nodes or that this is the primary node
        shouldRun = !procNode.isIsolated() || !flowController.isClustered() || flowController.isPrimary();
        if (!shouldRun) {
            return;
        }

        // make sure that either proc has incoming FlowFiles or has no incoming connections or is annotated with @TriggerWhenEmpty
        shouldRun = procNode.isTriggerWhenEmpty() || !procNode.hasIncomingConnection() || Connectables.flowFilesQueued(procNode);
        if (!shouldRun) {
            return;
        }

        if (numRelationships > 0) {
            final int requiredNumberOfAvailableRelationships = procNode.isTriggerWhenAnyDestinationAvailable() ? 1 : numRelationships;
            shouldRun = context.isRelationshipAvailabilitySatisfied(requiredNumberOfAvailableRelationships);
        }

        final long batchNanos = procNode.getRunDuration(TimeUnit.NANOSECONDS);
        final ProcessSessionFactory sessionFactory;
        final StandardProcessSession rawSession;
        final boolean batch;
        if (procNode.isHighThroughputSupported() && batchNanos > 0L) {
            rawSession = new StandardProcessSession(context);
            sessionFactory = new BatchingSessionFactory(rawSession);
            batch = true;
        } else {
            rawSession = null;
            sessionFactory = new StandardProcessSessionFactory(context);
            batch = false;
        }

        if (!shouldRun) {
            return;
        }

        scheduleState.incrementActiveThreadCount();

        final long startNanos = System.nanoTime();
        final long finishNanos = startNanos + batchNanos;
        int invocationCount = 0;
        try {
            try (final AutoCloseable ncl = NarCloseable.withNarLoader()) {
                while (shouldRun) {
                    procNode.onTrigger(processContext, sessionFactory);
                    invocationCount++;

                    if (!batch) {
                        return;
                    }

                    if (System.nanoTime() > finishNanos) {
                        return;
                    }

                    shouldRun = procNode.isTriggerWhenEmpty() || !procNode.hasIncomingConnection() || Connectables.flowFilesQueued(procNode);
                    shouldRun = shouldRun && (procNode.getYieldExpiration() < System.currentTimeMillis());

                    if (shouldRun && numRelationships > 0) {
                        final int requiredNumberOfAvailableRelationships = procNode.isTriggerWhenAnyDestinationAvailable() ? 1 : numRelationships;
                        shouldRun = context.isRelationshipAvailabilitySatisfied(requiredNumberOfAvailableRelationships);
                    }
                }
            } catch (final ProcessException pe) {
                final ProcessorLog procLog = new SimpleProcessLogger(procNode.getIdentifier(), procNode.getProcessor());
                procLog.error("Failed to process session due to {}", new Object[]{pe});
            } catch (final Throwable t) {
                // Use ProcessorLog to log the event so that a bulletin will be created for this processor
                final ProcessorLog procLog = new SimpleProcessLogger(procNode.getIdentifier(), procNode.getProcessor());
                procLog.error("{} failed to process session due to {}", new Object[]{procNode.getProcessor(), t});
                procLog.warn("Processor Administratively Yielded for {} due to processing failure", new Object[]{schedulingAgent.getAdministrativeYieldDuration()});
                logger.warn("Administratively Yielding {} due to uncaught Exception: {}", procNode.getProcessor(), t.toString());
                logger.warn("", t);

                procNode.yield(schedulingAgent.getAdministrativeYieldDuration(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
            }
        } finally {
            if (batch) {
                rawSession.commit();
            }

            final long processingNanos = System.nanoTime() - startNanos;

            // if the processor is no longer scheduled to run and this is the last thread,
            // invoke the OnStopped methods
            if (!scheduleState.isScheduled() && scheduleState.getActiveThreadCount() == 1 && scheduleState.mustCallOnStoppedMethods()) {
                try (final NarCloseable x = NarCloseable.withNarLoader()) {
                    ReflectionUtils.quietlyInvokeMethodsWithAnnotation(OnStopped.class, procNode.getProcessor(), processContext);
                    flowController.heartbeat();
                }
            }

            scheduleState.decrementActiveThreadCount();

            try {
                final StandardFlowFileEvent procEvent = new StandardFlowFileEvent(procNode.getIdentifier());
                procEvent.setProcessingNanos(processingNanos);
                procEvent.setInvocations(invocationCount);
                context.getFlowFileEventRepository().updateRepository(procEvent);
            } catch (final IOException e) {
                logger.error("Unable to update FlowFileEvent Repository for {}; statistics may be inaccurate. Reason for failure: {}", procNode.getProcessor(), e.toString());
                logger.error("", e);
            }
        }
    }

}
