/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.search.profile.query;

import org.apache.lucene.sandbox.search.ProfilerCollector;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.CollectorManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A {@link CollectorManager} that takes another CollectorManager as input and wraps all Collectors generated by it
 * in an {@link InternalProfileCollector}. It delegates all the profiling to the generated collectors via {@link #getCollectorTree()}
 * and joins the different collector trees together when its {@link #reduce} method is called.
 * Supports optionally providing sub-collector managers for top docs as well as aggs collection, so that each
 * {@link InternalProfileCollector} created is provided with the corresponding sub-collectors that are children of the top-level collector.
 * @param <T> the return type of the wrapped collector manager, which the reduce method returns.
 */
public final class ProfileCollectorManager<T> implements CollectorManager<InternalProfileCollector, T> {

    private final CollectorManager<? extends Collector, T> collectorManager;
    private final String reason;
    private final ProfileCollectorManager<?> topDocsSubCollectorManager;
    private final ProfileCollectorManager<?> aggsSubCollectorManager;
    // this is a bit of a hack: it allows us to retrieve the last collector that newCollector has returned for sub-collector managers,
    // so that we can provide them to InternalProfileCollector's constructor as children. This is fine as newCollector does not get called
    // concurrently, but rather in advance before parallelizing the collection
    private InternalProfileCollector profileCollector;

    private CollectorResult collectorTree;

    public ProfileCollectorManager(CollectorManager<? extends Collector, T> collectorManager, String reason) {
        this(collectorManager, reason, null, null);
    }

    public ProfileCollectorManager(
        CollectorManager<? extends Collector, T> collectorManager,
        String reason,
        ProfileCollectorManager<?> topDocsSubCollectorManager,
        ProfileCollectorManager<?> aggsSubCollectorManager
    ) {
        this.collectorManager = collectorManager;
        this.reason = reason;
        assert assertSubCollectorManagers() : "top docs manager is null while aggs manager isn't";
        this.topDocsSubCollectorManager = topDocsSubCollectorManager;
        this.aggsSubCollectorManager = aggsSubCollectorManager;
    }

    private boolean assertSubCollectorManagers() {
        if (aggsSubCollectorManager != null) {
            return topDocsSubCollectorManager != null;
        }
        return true;
    }

    @Override
    public InternalProfileCollector newCollector() throws IOException {
        Collector collector = collectorManager.newCollector();
        if (aggsSubCollectorManager == null && topDocsSubCollectorManager == null) {
            profileCollector = new InternalProfileCollector(collector, reason);
        } else if (aggsSubCollectorManager == null) {
            assert topDocsSubCollectorManager.profileCollector != null;
            profileCollector = new InternalProfileCollector(collector, reason, topDocsSubCollectorManager.profileCollector);
        } else {
            assert topDocsSubCollectorManager.profileCollector != null && aggsSubCollectorManager.profileCollector != null;
            profileCollector = new InternalProfileCollector(
                collector,
                reason,
                topDocsSubCollectorManager.profileCollector,
                aggsSubCollectorManager.profileCollector
            );
        }
        return profileCollector;
    }

    @Override
    public T reduce(Collection<InternalProfileCollector> profileCollectors) throws IOException {
        assert profileCollectors.size() > 0 : "at least one collector expected";
        List<Collector> unwrapped = profileCollectors.stream().map(InternalProfileCollector::getWrappedCollector).toList();
        @SuppressWarnings("unchecked")
        CollectorManager<Collector, T> cm = (CollectorManager<Collector, T>) collectorManager;
        T returnValue = cm.reduce(unwrapped);

        List<CollectorResult> resultsPerProfiler = profileCollectors.stream().map(InternalProfileCollector::getCollectorTree).toList();
        long totalTime = resultsPerProfiler.stream().map(CollectorResult::getTime).reduce(0L, Long::sum);
        String collectorName = resultsPerProfiler.get(0).getName();
        assert profileCollectors.stream().map(ProfilerCollector::getReason).allMatch(reason::equals);
        assert profileCollectors.stream().map(ProfilerCollector::getName).allMatch(collectorName::equals);
        assert assertChildrenSize(resultsPerProfiler);

        List<CollectorResult> childrenResults = new ArrayList<>();
        // for the children collector managers, we rely on the chain on reduce calls to make their collector results available
        if (topDocsSubCollectorManager != null) {
            childrenResults.add(topDocsSubCollectorManager.getCollectorTree());
        }
        if (aggsSubCollectorManager != null) {
            childrenResults.add(aggsSubCollectorManager.getCollectorTree());
        }
        this.collectorTree = new CollectorResult(collectorName, reason, totalTime, childrenResults);

        return returnValue;
    }

    private boolean assertChildrenSize(List<CollectorResult> resultsPerProfiler) {
        int expectedSize = 0;
        if (topDocsSubCollectorManager != null) {
            expectedSize++;
        }
        if (aggsSubCollectorManager != null) {
            expectedSize++;
        }
        final int expectedChildrenSize = expectedSize;
        return resultsPerProfiler.stream()
            .map(collectorResult -> collectorResult.getChildrenResults().size())
            .allMatch(integer -> integer == expectedChildrenSize);
    }

    public CollectorResult getCollectorTree() {
        if (this.collectorTree == null) {
            throw new IllegalStateException("A collectorTree hasn't been set yet. Call reduce() before attempting to retrieve it");
        }
        return this.collectorTree;
    }
}