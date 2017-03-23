package com.nytimes.android.external.store2;

import com.nytimes.android.external.cache.Cache;
import com.nytimes.android.external.cache.CacheBuilder;
import com.nytimes.android.external.store2.base.Fetcher;
import com.nytimes.android.external.store2.base.Persister;
import com.nytimes.android.external.store2.base.impl.BarCode;
import com.nytimes.android.external.store2.base.impl.RealStore;
import com.nytimes.android.external.store2.base.impl.Store;
import com.nytimes.android.external.store2.base.impl.StoreBuilder;
import com.nytimes.android.external.store2.util.NoopPersister;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.annotations.NonNull;
import io.reactivex.functions.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StoreTest {

    private static final String DISK = "disk";
    private static final String NETWORK = "fetch";
    private static final String MEMORY = "memory";
    final AtomicInteger counter = new AtomicInteger(0);
    @Mock
    Fetcher<String, BarCode> fetcher;
    @Mock
    Persister<String, BarCode> persister;
    private final BarCode barCode = new BarCode("key", "value");

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testSimple() {

        Store<String, BarCode> simpleStore = StoreBuilder.<String>barcode()
                .persister(persister)
                .fetcher(fetcher)
                .open();


        when(fetcher.fetch(barCode))
                .thenReturn(Observable.just(NETWORK));

        when(persister.read(barCode))
                .thenReturn(Observable.<String>empty())
                .thenReturn(Observable.just(DISK));

        when(persister.write(barCode, NETWORK))
                .thenReturn(Observable.just(true));

        String value = simpleStore.get(barCode).blockingFirst();

        assertThat(value).isEqualTo(DISK);
        value = simpleStore.get(barCode).blockingFirst();
        assertThat(value).isEqualTo(DISK);
        verify(fetcher, times(1)).fetch(barCode);
    }


    @Test
    public void testDoubleTap() {

        Store<String, BarCode> simpleStore = StoreBuilder.<String>barcode()
                .persister(persister)
                .fetcher(fetcher)
                .open();

        Observable<String> networkObservable =
                Observable.create(new ObservableOnSubscribe<String>() {
                    @Override
                    public void subscribe(ObservableEmitter<String> emitter) {
                        if (counter.incrementAndGet() == 1) {
                            emitter.onNext(NETWORK);

                        } else {
                            emitter.onError(new RuntimeException("Yo Dawg your inflight is broken"));
                        }
                    }
                });


        when(fetcher.fetch(barCode))
                .thenReturn(networkObservable);

        when(persister.read(barCode))
                .thenReturn(Observable.<String>empty())
                .thenReturn(Observable.just(DISK));

        when(persister.write(barCode, NETWORK))
                .thenReturn(Observable.just(true));


        String response = simpleStore.get(barCode).zipWith(simpleStore.get(barCode),
                new BiFunction<String, String, String>() {
                    @Override
                    public String apply(@NonNull String s, @NonNull String s2) {
                        return "hello";
                    }
                })
                .blockingFirst();
        assertThat(response).isEqualTo("hello");
        verify(fetcher, times(1)).fetch(barCode);
    }


    @Test
    public void testSubclass() {

        RealStore<String, BarCode> simpleStore = new SampleStore(fetcher, persister);
        simpleStore.clear();

        when(fetcher.fetch(barCode))
                .thenReturn(Observable.just(NETWORK));

        when(persister.read(barCode))
                .thenReturn(Observable.<String>empty())
                .thenReturn(Observable.just(DISK));
        when(persister.write(barCode, NETWORK)).thenReturn(Observable.just(true));

        String value = simpleStore.get(barCode).blockingFirst();
        assertThat(value).isEqualTo(DISK);
        value = simpleStore.get(barCode).blockingFirst();
        assertThat(value).isEqualTo(DISK);
        verify(fetcher, times(1)).fetch(barCode);
    }


    @Test
    public void testNoopAndDefault() {

        Persister<String, BarCode> persister = spy(NoopPersister.<String, BarCode>create());
        RealStore<String, BarCode> simpleStore = new SampleStore(fetcher, persister);


        when(fetcher.fetch(barCode))
                .thenReturn(Observable.just(NETWORK));

        String value = simpleStore.get(barCode).blockingFirst();
        verify(fetcher, times(1)).fetch(barCode);
        verify(persister, times(1)).write(barCode, NETWORK);
        verify(persister, times(2)).read(barCode);
        assertThat(value).isEqualTo(NETWORK);


        value = simpleStore.get(barCode).blockingFirst();
        verify(persister, times(2)).read(barCode);
        verify(persister, times(1)).write(barCode, NETWORK);
        verify(fetcher, times(1)).fetch(barCode);

        assertThat(value).isEqualTo(NETWORK);
    }


    @Test
    public void testEquivalence() {
        Cache<BarCode, String> cache = CacheBuilder.newBuilder()
                .maximumSize(1)
                .expireAfterAccess(Long.MAX_VALUE, TimeUnit.SECONDS)
                .build();

        cache.put(barCode, MEMORY);
        String value = cache.getIfPresent(barCode);
        assertThat(value).isEqualTo(MEMORY);

        value = cache.getIfPresent(new BarCode(barCode.getType(), barCode.getKey()));
        assertThat(value).isEqualTo(MEMORY);
    }
}