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
package org.apache.nifi.controller;

import org.apache.nifi.connectable.Connectable;
import org.apache.nifi.connectable.Funnel;
import org.apache.nifi.connectable.Port;
import org.apache.nifi.processor.annotation.OnScheduled;
import org.apache.nifi.processor.annotation.OnUnscheduled;
import org.apache.nifi.scheduling.SchedulingStrategy;

public interface ProcessScheduler {

    /**
     * Shuts down the scheduler, stopping all components
     */
    void shutdown();

    /**
     * Starts scheduling the given processor to run after invoking all methods
     * on the underlying {@link nifi.processor.Processor FlowFileProcessor} that
     * are annotated with the {@link OnScheduled} annotation. If the Processor
     * is already scheduled to run, does nothing.
     *
     * @param procNode
     * @throws IllegalStateException if the Processor is disabled
     */
    void startProcessor(ProcessorNode procNode);

    /**
     * Stops scheduling the given processor to run and invokes all methods on
     * the underlying {@link nifi.processor.Processor FlowFileProcessor} that
     * are annotated with the {@link OnUnscheduled} annotation. This does not
     * interrupt any threads that are currently running within the given
     * Processor. If the Processor is not scheduled to run, does nothing.
     * @param procNode
     */
    void stopProcessor(ProcessorNode procNode);

    /**
     * Starts scheduling the given Port to run. If the Port is already scheduled
     * to run, does nothing.
     *
     * @param port
     *
     * @throws IllegalStateException if the Port is disabled
     */
    void startPort(Port port);

    /**
     * Stops scheduling the given Port to run. This does not interrupt any
     * threads that are currently running within the given port. This does not
     * interrupt any threads that are currently running within the given Port.
     * If the Port is not scheduled to run, does nothing.
     *
     * @param port
     */
    void stopPort(Port port);

    /**
     * Starts scheduling the given Funnel to run. If the funnel is already
     * scheduled to run, does nothing.
     *
     * @param funnel
     *
     * @throws IllegalStateException if the Funnel is disabled
     */
    void startFunnel(Funnel funnel);

    /**
     * Stops scheduling the given Funnel to run. This does not interrupt any
     * threads that are currently running within the given funnel. If the funnel
     * is not scheduled to run, does nothing.
     *
     * @param funnel
     */
    void stopFunnel(Funnel funnel);

    void enableFunnel(Funnel funnel);

    void enablePort(Port port);

    void enableProcessor(ProcessorNode procNode);

    void disableFunnel(Funnel funnel);

    void disablePort(Port port);

    void disableProcessor(ProcessorNode procNode);

    /**
     * Returns the number of threads currently active for the given
     * <code>Connectable</code>.
     *
     * @param scheduled
     * @return
     */
    int getActiveThreadCount(Object scheduled);

    /**
     * Returns a boolean indicating whether or not the given object is scheduled
     * to run
     *
     * @param scheduled
     * @return
     */
    boolean isScheduled(Object scheduled);

    /**
     * Registers a relevant event for an Event-Driven worker
     *
     * @param worker
     */
    void registerEvent(Connectable worker);

    /**
     * Notifies the ProcessScheduler of how many threads are available to use
     * for the given {@link SchedulingStrategy}
     *
     * @param strategy
     * @param maxThreadCount
     */
    void setMaxThreadCount(SchedulingStrategy strategy, int maxThreadCount);

    /**
     * Notifies the Scheduler that it should stop scheduling the given component
     * until its yield duration has expired
     *
     * @param procNode
     */
    void yield(ProcessorNode procNode);
}
