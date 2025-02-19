/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.common.utils.MockTime;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.KeyValueTimestamp;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Aggregator;
import org.apache.kafka.streams.kstream.Initializer;
import org.apache.kafka.streams.kstream.Merger;
import org.apache.kafka.streams.kstream.SessionWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.processor.StateStoreContext;
import org.apache.kafka.streams.processor.internals.metrics.TaskMetrics;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.processor.internals.ToInternal;
import org.apache.kafka.streams.processor.internals.testutil.LogCaptureAppender;
import org.apache.kafka.streams.processor.internals.testutil.LogCaptureAppender.Event;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.SessionStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.internals.ThreadCache;
import org.apache.kafka.test.InternalMockProcessorContext;
import org.apache.kafka.test.MockRecordCollector;
import org.apache.kafka.test.StreamsTestUtils;
import org.apache.kafka.test.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static java.time.Duration.ofMillis;
import static org.apache.kafka.common.utils.Utils.mkEntry;
import static org.apache.kafka.common.utils.Utils.mkMap;
import static org.apache.kafka.test.StreamsTestUtils.getMetricByName;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


@SuppressWarnings("deprecation") // Old PAPI. Needs to be migrated.
public class KStreamSessionWindowAggregateProcessorTest {

    private static final long GAP_MS = 5 * 60 * 1000L;
    private static final String STORE_NAME = "session-store";

    private final String threadId = Thread.currentThread().getName();
    private final ToInternal toInternal = new ToInternal();
    private final Initializer<Long> initializer = () -> 0L;
    private final Aggregator<String, String, Long> aggregator = (aggKey, value, aggregate) -> aggregate + 1;
    private final Merger<String, Long> sessionMerger = (aggKey, aggOne, aggTwo) -> aggOne + aggTwo;
    private final KStreamSessionWindowAggregate<String, String, Long> sessionAggregator =
        new KStreamSessionWindowAggregate<>(
            SessionWindows.with(ofMillis(GAP_MS)),
            STORE_NAME,
            initializer,
            aggregator,
            sessionMerger);

    private final List<KeyValueTimestamp<Windowed<String>, Change<Long>>> results = new ArrayList<>();
    private final org.apache.kafka.streams.processor.Processor<String, String> processor = sessionAggregator.get();
    private SessionStore<String, Long> sessionStore;
    private InternalMockProcessorContext context;
    private final Metrics metrics = new Metrics();

    @Before
    public void setup() {
        setup(true);
    }

    private void setup(final boolean enableCache) {
        final StreamsMetricsImpl streamsMetrics =
            new StreamsMetricsImpl(metrics, "test", StreamsConfig.METRICS_LATEST, new MockTime());
        context = new InternalMockProcessorContext<Object, Object>(
            TestUtils.tempDirectory(),
            Serdes.String(),
            Serdes.String(),
            streamsMetrics,
            new StreamsConfig(StreamsTestUtils.getStreamsConfig()),
            MockRecordCollector::new,
            new ThreadCache(new LogContext("testCache "), 100000, streamsMetrics),
            Time.SYSTEM
        ) {
            @SuppressWarnings("unchecked")
            @Override
            public void forward(final Object key, final Object value, final To to) {
                toInternal.update(to);
                results.add(new KeyValueTimestamp<>((Windowed<String>) key, (Change<Long>) value, toInternal.timestamp()));
            }
        };
        TaskMetrics.droppedRecordsSensor(threadId, context.taskId().toString(), streamsMetrics);

        initStore(enableCache);
        processor.init(context);
    }

    private void initStore(final boolean enableCaching) {
        final StoreBuilder<SessionStore<String, Long>> storeBuilder =
            Stores.sessionStoreBuilder(
                Stores.persistentSessionStore(STORE_NAME, ofMillis(GAP_MS * 3)),
                Serdes.String(),
                Serdes.Long())
            .withLoggingDisabled();

        if (enableCaching) {
            storeBuilder.withCachingEnabled();
        }

        if (sessionStore != null) {
            sessionStore.close();
        }
        sessionStore = storeBuilder.build();
        sessionStore.init((StateStoreContext) context, sessionStore);
    }

    @After
    public void closeStore() {
        sessionStore.close();
    }

    @Test
    public void shouldCreateSingleSessionWhenWithinGap() {
        context.setTime(0);
        processor.process("john", "first");
        context.setTime(500);
        processor.process("john", "second");

        try (final KeyValueIterator<Windowed<String>, Long> values =
                 sessionStore.findSessions("john", 0, 2000)) {
            assertTrue(values.hasNext());
            assertEquals(Long.valueOf(2), values.next().value);
        }
    }

    @Test
    public void shouldMergeSessions() {
        context.setTime(0);
        final String sessionId = "mel";
        processor.process(sessionId, "first");
        assertTrue(sessionStore.findSessions(sessionId, 0, 0).hasNext());

        // move time beyond gap
        context.setTime(GAP_MS + 1);
        processor.process(sessionId, "second");
        assertTrue(sessionStore.findSessions(sessionId, GAP_MS + 1, GAP_MS + 1).hasNext());
        // should still exist as not within gap
        assertTrue(sessionStore.findSessions(sessionId, 0, 0).hasNext());
        // move time back
        context.setTime(GAP_MS / 2);
        processor.process(sessionId, "third");

        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                 sessionStore.findSessions(sessionId, 0, GAP_MS + 1)) {
            final KeyValue<Windowed<String>, Long> kv = iterator.next();

            assertEquals(Long.valueOf(3), kv.value);
            assertFalse(iterator.hasNext());
        }
    }

    @Test
    public void shouldUpdateSessionIfTheSameTime() {
        context.setTime(0);
        processor.process("mel", "first");
        processor.process("mel", "second");
        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                 sessionStore.findSessions("mel", 0, 0)) {
            assertEquals(Long.valueOf(2L), iterator.next().value);
            assertFalse(iterator.hasNext());
        }
    }

    @Test
    public void shouldHaveMultipleSessionsForSameIdWhenTimestampApartBySessionGap() {
        final String sessionId = "mel";
        long time = 0;
        context.setTime(time);
        processor.process(sessionId, "first");
        context.setTime(time += GAP_MS + 1);
        processor.process(sessionId, "second");
        processor.process(sessionId, "second");
        context.setTime(time += GAP_MS + 1);
        processor.process(sessionId, "third");
        processor.process(sessionId, "third");
        processor.process(sessionId, "third");

        sessionStore.flush();
        assertEquals(
            Arrays.asList(
                new KeyValueTimestamp<>(
                    new Windowed<>(sessionId, new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>(sessionId, new SessionWindow(GAP_MS + 1, GAP_MS + 1)),
                    new Change<>(2L, null),
                    GAP_MS + 1),
                new KeyValueTimestamp<>(
                    new Windowed<>(sessionId, new SessionWindow(time, time)),
                    new Change<>(3L, null),
                    time)
            ),
            results
        );

    }

    @Test
    public void shouldRemoveMergedSessionsFromStateStore() {
        context.setTime(0);
        processor.process("a", "1");

        // first ensure it is in the store
        try (final KeyValueIterator<Windowed<String>, Long> a1 =
                 sessionStore.findSessions("a", 0, 0)) {
            assertEquals(KeyValue.pair(new Windowed<>("a", new SessionWindow(0, 0)), 1L), a1.next());
        }


        context.setTime(100);
        processor.process("a", "2");
        // a1 from above should have been removed
        // should have merged session in store
        try (final KeyValueIterator<Windowed<String>, Long> a2 =
                 sessionStore.findSessions("a", 0, 100)) {
            assertEquals(KeyValue.pair(new Windowed<>("a", new SessionWindow(0, 100)), 2L), a2.next());
            assertFalse(a2.hasNext());
        }
    }

    @Test
    public void shouldHandleMultipleSessionsAndMerging() {
        context.setTime(0);
        processor.process("a", "1");
        processor.process("b", "1");
        processor.process("c", "1");
        processor.process("d", "1");
        context.setTime(GAP_MS / 2);
        processor.process("d", "2");
        context.setTime(GAP_MS + 1);
        processor.process("a", "2");
        processor.process("b", "2");
        context.setTime(GAP_MS + 1 + GAP_MS / 2);
        processor.process("a", "3");
        processor.process("c", "3");

        sessionStore.flush();

        assertEquals(
            Arrays.asList(
                new KeyValueTimestamp<>(
                    new Windowed<>("a", new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>("b", new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>("c", new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>("d", new SessionWindow(0, GAP_MS / 2)),
                    new Change<>(2L, null),
                    GAP_MS / 2),
                new KeyValueTimestamp<>(
                    new Windowed<>("b", new SessionWindow(GAP_MS + 1, GAP_MS + 1)),
                    new Change<>(1L, null),
                    GAP_MS + 1),
                new KeyValueTimestamp<>(
                    new Windowed<>("a", new SessionWindow(GAP_MS + 1, GAP_MS + 1 + GAP_MS / 2)),
                    new Change<>(2L, null),
                    GAP_MS + 1 + GAP_MS / 2),
                new KeyValueTimestamp<>(new Windowed<>(
                    "c",
                    new SessionWindow(GAP_MS + 1 + GAP_MS / 2, GAP_MS + 1 + GAP_MS / 2)), new Change<>(1L, null),
                    GAP_MS + 1 + GAP_MS / 2)
            ),
            results
        );
    }

    @Test
    public void shouldGetAggregatedValuesFromValueGetter() {
        final KTableValueGetter<Windowed<String>, Long> getter = sessionAggregator.view().get();
        getter.init(context);
        context.setTime(0);
        processor.process("a", "1");
        context.setTime(GAP_MS + 1);
        processor.process("a", "1");
        processor.process("a", "2");
        final long t0 = getter.get(new Windowed<>("a", new SessionWindow(0, 0))).value();
        final long t1 = getter.get(new Windowed<>("a", new SessionWindow(GAP_MS + 1, GAP_MS + 1))).value();
        assertEquals(1L, t0);
        assertEquals(2L, t1);
    }

    @Test
    public void shouldImmediatelyForwardNewSessionWhenNonCachedStore() {
        initStore(false);
        processor.init(context);

        context.setTime(0);
        processor.process("a", "1");
        processor.process("b", "1");
        processor.process("c", "1");

        assertEquals(
            Arrays.asList(
                new KeyValueTimestamp<>(
                    new Windowed<>("a", new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>("b", new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>("c", new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L)
            ),
            results
        );
    }

    @Test
    public void shouldImmediatelyForwardRemovedSessionsWhenMerging() {
        initStore(false);
        processor.init(context);

        context.setTime(0);
        processor.process("a", "1");
        context.setTime(5);
        processor.process("a", "1");
        assertEquals(
            Arrays.asList(
                new KeyValueTimestamp<>(
                    new Windowed<>("a", new SessionWindow(0, 0)),
                    new Change<>(1L, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>("a", new SessionWindow(0, 0)),
                    new Change<>(null, null),
                    0L),
                new KeyValueTimestamp<>(
                    new Windowed<>("a", new SessionWindow(0, 5)),
                    new Change<>(2L, null),
                    5L)
            ),
            results
        );

    }

    @Test
    public void shouldLogAndMeterWhenSkippingNullKeyWithBuiltInMetrics() {
        setup(false);
        context.setRecordContext(
            new ProcessorRecordContext(-1, -2, -3, "topic", new RecordHeaders())
        );

        try (final LogCaptureAppender appender =
                 LogCaptureAppender.createAndRegister(KStreamSessionWindowAggregate.class)) {

            processor.process(null, "1");

            assertThat(
                appender.getEvents().stream()
                    .filter(e -> e.getLevel().equals("WARN"))
                    .map(Event::getMessage)
                    .collect(Collectors.toList()),
                hasItem("Skipping record due to null key. value=[1] topic=[topic] partition=[-3] offset=[-2]")
            );
        }

        assertEquals(
            1.0,
            getMetricByName(context.metrics().metrics(), "dropped-records-total", "stream-task-metrics").metricValue()
        );
    }

    @Test
    public void shouldLogAndMeterWhenSkippingLateRecordWithZeroGrace() {
        setup(false);
        final org.apache.kafka.streams.processor.Processor<String, String> processor = new KStreamSessionWindowAggregate<>(
            SessionWindows.with(ofMillis(10L)).grace(ofMillis(0L)),
            STORE_NAME,
            initializer,
            aggregator,
            sessionMerger
        ).get();
        processor.init(context);

        // dummy record to establish stream time = 0
        context.setRecordContext(new ProcessorRecordContext(0, -2, -3, "topic", new RecordHeaders()));
        processor.process("dummy", "dummy");

        // record arrives on time, should not be skipped
        context.setRecordContext(new ProcessorRecordContext(0, -2, -3, "topic", new RecordHeaders()));
        processor.process("OnTime1", "1");

        // dummy record to advance stream time = 11, 10 for gap time plus 1 to place outside window
        context.setRecordContext(new ProcessorRecordContext(11, -2, -3, "topic", new RecordHeaders()));
        processor.process("dummy", "dummy");

        try (final LogCaptureAppender appender =
                 LogCaptureAppender.createAndRegister(KStreamSessionWindowAggregate.class)) {

            // record is late
            context.setRecordContext(new ProcessorRecordContext(0, -2, -3, "topic", new RecordHeaders()));
            processor.process("Late1", "1");

            assertThat(
                appender.getMessages(),
                hasItem("Skipping record for expired window." +
                    " key=[Late1] topic=[topic] partition=[-3] offset=[-2] timestamp=[0] window=[0,0] expiration=[1] streamTime=[11]")
            );
        }

        final MetricName dropTotal;
        final MetricName dropRate;
        dropTotal = new MetricName(
            "dropped-records-total",
            "stream-task-metrics",
            "The total number of dropped records",
            mkMap(
                mkEntry("thread-id", threadId),
                mkEntry("task-id", "0_0")
            )
        );
        dropRate = new MetricName(
            "dropped-records-rate",
            "stream-task-metrics",
            "The average number of dropped records per second",
            mkMap(
                mkEntry("thread-id", threadId),
                mkEntry("task-id", "0_0")
            )
        );
        assertThat(metrics.metrics().get(dropTotal).metricValue(), is(1.0));
        assertThat(
            (Double) metrics.metrics().get(dropRate).metricValue(),
            greaterThan(0.0)
        );
    }

    @Test
    public void shouldLogAndMeterWhenSkippingLateRecordWithNonzeroGrace() {
        setup(false);
        final org.apache.kafka.streams.processor.Processor<String, String> processor = new KStreamSessionWindowAggregate<>(
            SessionWindows.with(ofMillis(10L)).grace(ofMillis(1L)),
            STORE_NAME,
            initializer,
            aggregator,
            sessionMerger
        ).get();
        processor.init(context);

        try (final LogCaptureAppender appender =
                 LogCaptureAppender.createAndRegister(KStreamSessionWindowAggregate.class)) {

            // dummy record to establish stream time = 0
            context.setRecordContext(new ProcessorRecordContext(0, -2, -3, "topic", new RecordHeaders()));
            processor.process("dummy", "dummy");

            // record arrives on time, should not be skipped
            context.setRecordContext(new ProcessorRecordContext(0, -2, -3, "topic", new RecordHeaders()));
            processor.process("OnTime1", "1");

            // dummy record to advance stream time = 11, 10 for gap time plus 1 to place at edge of window
            context.setRecordContext(new ProcessorRecordContext(11, -2, -3, "topic", new RecordHeaders()));
            processor.process("dummy", "dummy");

            // delayed record arrives on time, should not be skipped
            context.setRecordContext(new ProcessorRecordContext(0, -2, -3, "topic", new RecordHeaders()));
            processor.process("OnTime2", "1");

            // dummy record to advance stream time = 12, 10 for gap time plus 2 to place outside window
            context.setRecordContext(new ProcessorRecordContext(12, -2, -3, "topic", new RecordHeaders()));
            processor.process("dummy", "dummy");

            // delayed record arrives late
            context.setRecordContext(new ProcessorRecordContext(0, -2, -3, "topic", new RecordHeaders()));
            processor.process("Late1", "1");

            assertThat(
                appender.getMessages(),
                hasItem("Skipping record for expired window." +
                    " key=[Late1] topic=[topic] partition=[-3] offset=[-2] timestamp=[0] window=[0,0] expiration=[1] streamTime=[12]")
            );
        }

        final MetricName dropTotal;
        final MetricName dropRate;
        dropTotal = new MetricName(
            "dropped-records-total",
            "stream-task-metrics",
            "The total number of dropped records",
            mkMap(
                mkEntry("thread-id", threadId),
                mkEntry("task-id", "0_0")
            )
        );
        dropRate = new MetricName(
            "dropped-records-rate",
            "stream-task-metrics",
            "The average number of dropped records per second",
            mkMap(
                mkEntry("thread-id", threadId),
                mkEntry("task-id", "0_0")
            )
        );

        assertThat(metrics.metrics().get(dropTotal).metricValue(), is(1.0));
        assertThat(
            (Double) metrics.metrics().get(dropRate).metricValue(),
            greaterThan(0.0));
    }
}
