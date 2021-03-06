/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except inColumn compliance with the License.
 * You may obtain a copy singleOf the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to inColumn writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codekaizen.test.db.paramin;

import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Semaphore;

import static org.codekaizen.test.db.paramin.Preconditions.checkArgument;
import static org.codekaizen.test.db.paramin.Preconditions.checkNotEmpty;
import static org.codekaizen.test.db.paramin.Preconditions.checkNotNull;

/**
 * Retrieves a valid set of database input parameter tuples.
 *
 * @author kbrockhoff
 */
public class DefaultFindParametersTask implements FindParametersTask {

    private static final int TRYS_MULTIPLE = 4;
    private static final int RECS_MULTIPLE = 16;
    private static final int MIN_RECS = 10;

    private final Logger logger = LoggerFactory.getLogger(DefaultFindParametersTask.class);
    private final String componentId;
    private final ParamSpecs paramSpecs;
    private final Set<Tuple> results;
    private final Semaphore semaphore;
    private Database database = Database.DEFAULT;
    private Connection connection;
    private EventBus eventBus;
    private LinkedList<SqlQueryProcessor> processors = new LinkedList<>();
    private boolean initialized = false;
    private int totalRequests = 0;
    private Subscription subscription;
    private boolean cancelled = false;
    private Throwable onErrorCause;

    /**
     * Constructs a retriever.
     *
     * @param paramSpecs the specifications on what to retrieve
     */
    public DefaultFindParametersTask(ParamSpecs paramSpecs) {
        checkNotNull(paramSpecs, "paramSpecs is required");
        this.componentId = UUID.randomUUID().toString();
        this.paramSpecs = paramSpecs;
        this.results = new LinkedHashSet<>(paramSpecs.getDesiredTuplesSetSize());
        this.semaphore = new Semaphore(1);
    }

    @Override
    public String getComponentId() {
        return componentId;
    }

    @Override
    public ParamSpecs getParamSpecs() {
        return paramSpecs;
    }

    @Override
    public void setDatabase(Database database) {
        checkNotNull(database, "database cannot be null");
        this.database = database;
    }

    @Override
    public void initialize(Connection connection, EventBus eventBus) throws IllegalStateException {
        logger.trace("initialize({})", connection);
        checkNotEmpty(connection, "valid connection must be provided");
        checkNotNull(eventBus, "eventBus must be provided");
        close();
        this.connection = connection;
        this.eventBus = eventBus;
        this.eventBus.registerReceiver(this);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        logger.trace("onSubscribe({})", subscription);
        checkNotNull(subscription);
        if (this.subscription != null || cancelled) {
            logger.warn("duplicate subscription received, per reactive streams rule 2.5 calling cancel");
            subscription.cancel();
            return;
        }
        this.subscription = subscription;
        doRequest();
    }

    @Override
    public void onNext(Tuple item) {
        logger.trace("onNext({})", item);
        checkNotNull(item, "reactive streams rule 2.13 requires throwing of null pointer");
        results.add(item);
        logger.debug("added {} resulting in results.size={}", item, results.size());
        if (results.size() >= paramSpecs.getDesiredTuplesSetSize()) {
            subscription.cancel();
            cleanupFlow();
        } else if (totalRequests > paramSpecs.getDesiredTuplesSetSize() * TRYS_MULTIPLE) {
            subscription.cancel();
            logger.warn("only able to retrieve results.size={} before exhausting the possiblities", results.size());
        } else {
            doRequest();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        logger.trace("onError({})", throwable);
        logger.info("retrieval failed: {}", throwable.getMessage());
        onErrorCause = throwable;
        cleanupFlow();
    }

    @Override
    public void onComplete() {
        logger.trace("onComplete()");
        cleanupFlow();
    }

    @Override
    public Set<Tuple> call() throws InterruptedException {
        logger.trace("call()");
        if (cancelled) {
            logger.info("subscription already cancelled so returning existing results");
            return results;
        }
        initiateProcessorsAndSubscriptionsIfNeeded();
        checkArgument(initialized, "retriever must be initialized before call");
        semaphore.acquire();
        checkIfDesiredSizeMet();
        logger.trace("returning results");
        try {
            if (onErrorCause == null) {
                return results;
            } else {
                if (onErrorCause instanceof Error) {
                    throw (Error) onErrorCause;
                } else if (onErrorCause instanceof RuntimeException) {
                    throw (RuntimeException) onErrorCause;
                } else {
                    throw new IllegalStateException(onErrorCause);
                }
            }
        } finally {
            semaphore.release();
        }
    }

    @Override
    public void close() {
        logger.trace("close()");
        processors.forEach(this::closeQuietly);
        if (eventBus != null) {
            eventBus.unregisterReceiver(this);
        }
        closeQuietly(connection);
    }

    @Override
    public String toString() {
        return FindParametersTask.class.getSimpleName() + " for " + paramSpecs;
    }

    private void initiateProcessorsAndSubscriptionsIfNeeded() {
        if (!initialized) {
            processors = configureProcessingFlow(paramSpecs);
            processors.getLast().subscribe(this);
            initialized = true;
            try {
                semaphore.acquire();
            } catch (InterruptedException interrupted) {
                notify();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    private LinkedList<SqlQueryProcessor> configureProcessingFlow(ParamSpecs specs) {
        LinkedList<SqlQueryProcessor> processors = new LinkedList<>();
        try {
            SqlQueryProcessor previous = null;
            Connection conn = getConnection();
            for (ParamSpec spec : specs.getParamSpecs()) {
                String sql = specs.getSqlStatement(spec);
                if (previous == null) {
                    sql = addRowsReturnedLimit(specs, sql);
                    logger.debug("restricted row count query: {}", sql);
                }
                SqlQueryProcessor proc = new SqlQueryProcessor(spec, specs.getDesiredTuplesSetSize(),
                        conn.prepareStatement(sql), eventBus);
                processors.add(proc);
                if (previous != null) {
                    previous.subscribe(proc);
                }
                previous = proc;
            }
        } catch (SQLException cause) {
            close();
            throw new IllegalStateException(cause);
        }
        return processors;
    }

    private String addRowsReturnedLimit(ParamSpecs specs, String sql) {
        StringBuilder builder = new StringBuilder(sql);
        int rowCount = specs.getDesiredTuplesSetSize() * RECS_MULTIPLE;
        rowCount = rowCount < MIN_RECS ? MIN_RECS : rowCount;
        Object[] args = new Object[2];
        args[0] = rowCount;
        args[1] = sql.contains(" WHERE ") ? "AND" : "WHERE";
        Formatter formatter = new Formatter(builder);
        formatter.format(database.getLimitClause(), args);
        return builder.toString();
    }

    private Connection getConnection() {
        return connection;
    }

    private void doRequest() {
        totalRequests++;
        subscription.request(1L);
    }

    private void cleanupFlow() {
        cancelled = true;
        semaphore.release();
        close();
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignore) {
                logger.info("exception on close: {}", ignore.getMessage());
            }
        }
    }

    private void checkIfDesiredSizeMet() {
        if (paramSpecs.isThrowingExceptionIfAvailableSizeLessThanDesiredSize() &&
                results.size() < paramSpecs.getDesiredTuplesSetSize()) {
            onErrorCause =
                    new IllegalStateException("unable to retrieve enough valid parameters before hitting request limit");
        }
    }

}
