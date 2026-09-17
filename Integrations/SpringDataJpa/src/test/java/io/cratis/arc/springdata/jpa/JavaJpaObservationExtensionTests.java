// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.coroutines.flow.SharedFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Java conformance tests for {@link JpaObservations} static helpers.
 * Proves that the default-query helpers reach the same underlying snapshot
 * behavior as the corresponding direct calls on {@link JpaObservableQuery}
 * and that the Kotlin and Java paths agree.
 *
 * JPA observation is in-process only; there is no cross-process notification
 * mechanism. Callers needing cross-process notifications must supply a custom
 * {@link DatabaseChangeNotifier} bean.
 */
final class JavaJpaObservationExtensionTests {

    @Test
    @SuppressWarnings("unchecked")
    void defaultQueryObservePublisherWaitsForDemandAndEmitsSnapshot() throws Exception {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        EntityManager em = mock(EntityManager.class);
        Metamodel metamodel = mock(Metamodel.class);
        EntityType<JpaTaskReadModel> entityMeta = mock(EntityType.class);
        TypedQuery<JpaTaskReadModel> typedQuery = mock(TypedQuery.class);

        when(emf.createEntityManager()).thenReturn(em);
        when(em.getMetamodel()).thenReturn(metamodel);
        doReturn(entityMeta).when(metamodel).entity(JpaTaskReadModel.class);
        when(entityMeta.getName()).thenReturn("JpaTaskReadModel");
        doReturn(typedQuery).when(em).createQuery(any(String.class), eq(JpaTaskReadModel.class));
        when(typedQuery.getResultList()).thenReturn(List.of(new JpaTaskReadModel("one", "Observed")));

        DatabaseChangeNotifier notifier = (type, tenantId) -> FlowKt.emptyFlow();
        JpaObservableQuery queries = new JpaObservableQuery(emf, notifier);

        RecordingSubscriber<JpaTaskReadModel> subscriber = new RecordingSubscriber<>();
        JpaObservations.observePublisher(queries, JpaTaskReadModel.class).subscribe(subscriber);

        assertFalse(subscriber.received.await(100, TimeUnit.MILLISECONDS), "Publisher must not emit before demand");
        subscriber.subscription.get().request(1);
        assertTrue(subscriber.received.await(2, TimeUnit.SECONDS), "Publisher must emit after demand");
        assertTrue(subscriber.completed.await(2, TimeUnit.SECONDS));
        assertEquals("one", subscriber.values.get(0).get(0).getId());
        assertNull(subscriber.failure.get());
        queries.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultQueryObservePublisherAgreesWithDirectObservePublisher() throws Exception {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        EntityManager em = mock(EntityManager.class);
        Metamodel metamodel = mock(Metamodel.class);
        EntityType<JpaTaskReadModel> entityMeta = mock(EntityType.class);
        TypedQuery<JpaTaskReadModel> typedQuery = mock(TypedQuery.class);

        when(emf.createEntityManager()).thenReturn(em);
        when(em.getMetamodel()).thenReturn(metamodel);
        doReturn(entityMeta).when(metamodel).entity(JpaTaskReadModel.class);
        when(entityMeta.getName()).thenReturn("JpaTaskReadModel");
        doReturn(typedQuery).when(em).createQuery(any(String.class), eq(JpaTaskReadModel.class));
        when(typedQuery.getResultList()).thenReturn(List.of(new JpaTaskReadModel("two", "Direct")));

        DatabaseChangeNotifier notifier = (type, tenantId) -> FlowKt.emptyFlow();
        JpaObservableQuery queries = new JpaObservableQuery(emf, notifier);

        // Via JpaObservations — no explicit query argument needed
        RecordingSubscriber<JpaTaskReadModel> viaHelper = new RecordingSubscriber<>();
        JpaObservations.observePublisher(queries, JpaTaskReadModel.class).subscribe(viaHelper);
        viaHelper.subscription.get().request(1);
        assertTrue(viaHelper.received.await(2, TimeUnit.SECONDS));

        // Via direct call on JpaObservableQuery with equivalent explicit query
        JpaSnapshotQuery<JpaTaskReadModel> explicitQuery = em2 ->
            em2.createQuery("select entity from JpaTaskReadModel entity", JpaTaskReadModel.class).getResultList();
        RecordingSubscriber<JpaTaskReadModel> viaDirect = new RecordingSubscriber<>();
        queries.observePublisher(JpaTaskReadModel.class, explicitQuery, null).subscribe(viaDirect);
        viaDirect.subscription.get().request(1);
        assertTrue(viaDirect.received.await(2, TimeUnit.SECONDS));

        assertEquals(
            viaHelper.values.get(0).get(0).getId(),
            viaDirect.values.get(0).get(0).getId(),
            "Helper and direct call must return the same snapshot"
        );
        queries.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultQueryObserveSharedIsCallableFromJavaAndReturnsNonNullSharedFlow() {
        EntityManagerFactory emf = mock(EntityManagerFactory.class);
        EntityManager em = mock(EntityManager.class);
        Metamodel metamodel = mock(Metamodel.class);
        EntityType<JpaTaskReadModel> entityMeta = mock(EntityType.class);
        TypedQuery<JpaTaskReadModel> typedQuery = mock(TypedQuery.class);

        when(emf.createEntityManager()).thenReturn(em);
        when(em.getMetamodel()).thenReturn(metamodel);
        doReturn(entityMeta).when(metamodel).entity(JpaTaskReadModel.class);
        when(entityMeta.getName()).thenReturn("JpaTaskReadModel");
        doReturn(typedQuery).when(em).createQuery(any(String.class), eq(JpaTaskReadModel.class));
        when(typedQuery.getResultList()).thenReturn(List.of());

        DatabaseChangeNotifier notifier = (type, tenantId) -> FlowKt.emptyFlow();
        JpaObservableQuery queries = new JpaObservableQuery(emf, notifier);

        // CoroutineScope via anonymous class — WhileSubscribed does not launch a coroutine
        // before any subscriber subscribes, so no cleanup is needed in this test.
        CoroutineScope scope = new CoroutineScope() {
            @Override
            public CoroutineContext getCoroutineContext() {
                return Dispatchers.getIO();
            }
        };
        SharedFlow<List<JpaTaskReadModel>> shared =
            JpaObservations.observeShared(scope, queries, JpaTaskReadModel.class);

        assertNotNull(shared, "observeShared must return a non-null SharedFlow");
        queries.close();
    }

    private static final class RecordingSubscriber<T> implements Flow.Subscriber<List<T>> {
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        private final CopyOnWriteArrayList<List<T>> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription value) { subscription.set(value); }

        @Override
        public void onNext(List<T> value) { values.add(value); received.countDown(); }

        @Override
        public void onError(Throwable throwable) { failure.set(throwable); completed.countDown(); }

        @Override
        public void onComplete() { completed.countDown(); }
    }
}
