package com.apollographql.apollo;

import com.apollographql.apollo.api.Operation;
import com.apollographql.apollo.api.ResponseFieldMapper;
import com.apollographql.apollo.api.ScalarType;
import com.apollographql.apollo.api.internal.Optional;
import com.apollographql.apollo.cache.http.EvictionStrategy;
import com.apollographql.apollo.cache.http.HttpCacheControl;
import com.apollographql.apollo.cache.http.ResponseCacheStore;
import com.apollographql.apollo.cache.normalized.CacheControl;
import com.apollographql.apollo.cache.normalized.CacheKeyResolver;
import com.apollographql.apollo.cache.normalized.CacheStore;
import com.apollographql.apollo.internal.RealApolloCall;
import com.apollographql.apollo.internal.RealApolloPrefetch;
import com.apollographql.apollo.internal.cache.http.HttpCache;
import com.apollographql.apollo.internal.cache.normalized.Cache;
import com.apollographql.apollo.internal.cache.normalized.RealCache;
import com.apollographql.apollo.internal.util.ApolloLogger;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import static com.apollographql.apollo.api.internal.Utils.checkNotNull;


/**
 * ApolloClient class represents the abstraction for the graphQL client that will be used to execute queries and read
 * the responses back.
 *
 * <h3>ApolloClient should be shared</h3>
 *
 * Since each ApolloClient holds its own connection pool and thread pool, it is recommended to only create a single
 * ApolloClient and use that for execution of all the queries, as this would reduce latency and would also save memory.
 * Conversely, creating a client for each query execution would result in resource wastage on idle pools.
 *
 *
 * <p>See the {@link ApolloClient.Builder} class for configuring the ApolloClient.
 */
public final class ApolloClient implements ApolloCall.Factory, ApolloPrefetch.Factory {

  public static Builder builder() {
    return new Builder();
  }

  private final HttpUrl serverUrl;
  private final Call.Factory httpCallFactory;
  private final HttpCache httpCache;
  private final Cache cache;
  private final Map<ScalarType, CustomTypeAdapter> customTypeAdapters;
  private final Moshi moshi;
  private final Map<Class, ResponseFieldMapper> responseFieldMapperPool = new LinkedHashMap<>();
  private final ExecutorService dispatcher;
  private final HttpCacheControl defaultHttpCacheControl;
  private final CacheControl defaultCacheControl;
  private final ApolloLogger logger;

  private ApolloClient(Builder builder) {
    this.serverUrl = builder.serverUrl;
    this.httpCallFactory = builder.okHttpClient;
    this.httpCache = builder.httpCache;
    this.cache = builder.cache;
    this.customTypeAdapters = builder.customTypeAdapters;
    this.moshi = builder.moshiBuilder.build();
    this.dispatcher = builder.dispatcher;
    this.defaultHttpCacheControl = builder.defaultHttpCacheControl;
    this.defaultCacheControl = builder.defaultCacheControl;
    this.logger = builder.apolloLogger;
  }

  /**
   * Prepares the {@link ApolloCall} request which will be executed at some point in the future.
   */
  @Override
  public <D extends Operation.Data, T, V extends Operation.Variables> ApolloCall<T> newCall(
      @Nonnull Operation<D, T, V> operation) {
    ResponseFieldMapper responseFieldMapper;
    synchronized (responseFieldMapperPool) {
      responseFieldMapper = responseFieldMapperPool.get(operation.getClass());
      if (responseFieldMapper == null) {
        responseFieldMapper = operation.responseFieldMapper();
        responseFieldMapperPool.put(operation.getClass(), responseFieldMapper);
      }
    }
    return new RealApolloCall<T>(operation, serverUrl, httpCallFactory, httpCache, defaultHttpCacheControl, moshi,
        responseFieldMapper, customTypeAdapters, cache, defaultCacheControl, dispatcher, logger)
        .httpCacheControl(defaultHttpCacheControl)
        .cacheControl(defaultCacheControl);
  }

  /**
   * Prepares the {@link ApolloPrefetch} which will be executed at some point in the future.
   */
  @Override
  public <D extends Operation.Data, T, V extends Operation.Variables> ApolloPrefetch prefetch(
      @Nonnull Operation<D, T, V> operation) {
    return new RealApolloPrefetch(operation, serverUrl, httpCallFactory, httpCache, moshi, dispatcher, logger);
  }

  void clearCache() {
    if (httpCache != null) {
      httpCache.clear();
    }
  }

  Response cachedHttpResponse(String cacheKey) throws IOException {
    if (httpCache != null) {
      return httpCache.read(cacheKey);
    } else {
      return null;
    }
  }

  @SuppressWarnings("WeakerAccess") public static class Builder {
    OkHttpClient okHttpClient;
    HttpUrl serverUrl;
    ResponseCacheStore httpCacheStore;
    EvictionStrategy httpEvictionStrategy;
    Cache cache = Cache.NO_CACHE;
    HttpCacheControl defaultHttpCacheControl = HttpCacheControl.CACHE_FIRST;
    CacheControl defaultCacheControl = CacheControl.CACHE_FIRST;
    final Map<ScalarType, CustomTypeAdapter> customTypeAdapters = new LinkedHashMap<>();
    final Moshi.Builder moshiBuilder = new Moshi.Builder();
    ExecutorService dispatcher;
    Optional<Logger> logger = Optional.absent();
    HttpCache httpCache;
    ApolloLogger apolloLogger;

    private Builder() {
    }

    /**
     * Set the {@link OkHttpClient} to use for making network requests.
     *
     * @param okHttpClient the client to use.
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder okHttpClient(@Nonnull OkHttpClient okHttpClient) {
      this.okHttpClient = checkNotNull(okHttpClient, "okHttpClient is null");
      return this;
    }

    /**
     * <p>Set the API server's base url.</p>
     *
     * @param serverUrl the url to set.
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder serverUrl(@Nonnull HttpUrl serverUrl) {
      this.serverUrl = checkNotNull(serverUrl, "serverUrl is null");
      return this;
    }

    /**
     * <p>Set the API server's base url.</p>
     *
     * @param serverUrl the url to set.
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder serverUrl(@Nonnull String serverUrl) {
      this.serverUrl = HttpUrl.parse(checkNotNull(serverUrl, "serverUrl == null"));
      return this;
    }

    /**
     * Set the configuration to be used for request/response http cache.
     *
     * @param cacheStore       The store to use for reading and writing cached response.
     * @param evictionStrategy EvictionStrategy decides when the data in the cacheStore becomes stale and is no longer
     *                         valid
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder httpCache(@Nonnull ResponseCacheStore cacheStore, @Nonnull EvictionStrategy evictionStrategy) {
      this.httpCacheStore = checkNotNull(cacheStore, "cacheStore == null");
      this.httpEvictionStrategy = checkNotNull(evictionStrategy, "evictionStrategy == null");
      return this;
    }

    /**
     * Set the configuration to be used for normalized cache.
     *
     * @param cacheStore       the cache store to use
     * @param cacheKeyResolver the cache key resolver
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder normalizedCache(@Nonnull CacheStore cacheStore,
        @Nonnull CacheKeyResolver<Map<String, Object>> cacheKeyResolver) {
      this.cache = new RealCache(checkNotNull(cacheStore, "cacheStore == null"),
          checkNotNull(cacheKeyResolver, "cacheKeyResolver == null"));
      return this;
    }

    /**
     * Set the type adapter to use for serializing and de-serializing custom GraphQL scalar types.
     *
     * @param scalarType        the scalar type to serialize/deserialize
     * @param customTypeAdapter the type adapter to use
     * @param <T>               the value type
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public <T> Builder withCustomTypeAdapter(@Nonnull ScalarType scalarType,
        @Nonnull final CustomTypeAdapter<T> customTypeAdapter) {
      customTypeAdapters.put(scalarType, customTypeAdapter);
      moshiBuilder.add(scalarType.javaType(), new JsonAdapter<T>() {
        @Override
        public T fromJson(com.squareup.moshi.JsonReader reader) throws IOException {
          return customTypeAdapter.decode(reader.nextString());
        }

        @Override
        public void toJson(JsonWriter writer, T value) throws IOException {
          //noinspection unchecked
          writer.value(customTypeAdapter.encode(value));
        }
      });
      return this;
    }

    /**
     * The #{@link ExecutorService} to use for dispatching the requests.
     *
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder dispatcher(@Nonnull ExecutorService dispatcher) {
      this.dispatcher = checkNotNull(dispatcher, "dispatcher == null");
      return this;
    }

    /**
     * Set the default {@link HttpCacheControl} strategy.
     *
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder defaultHttpCacheControl(@Nonnull HttpCacheControl cacheControl) {
      this.defaultHttpCacheControl = checkNotNull(cacheControl, "cacheControl == null");
      return this;
    }

    /**
     * Set the default {@link CacheControl} strategy.
     *
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder defaultCacheControl(@Nonnull CacheControl cacheControl) {
      this.defaultCacheControl = checkNotNull(cacheControl, "cacheControl == null");
      return this;
    }

    /**
     * The {@link Logger} to use for logging purposes.
     *
     * @return The {@link Builder} object to be used for chaining method calls
     */
    public Builder logger(@Nullable Logger logger) {
      this.logger = Optional.fromNullable(logger);
      return this;
    }

    /**
     * Builds the {@link ApolloClient} instance using the configured values.
     *
     * Note that if the {@link #dispatcher} is not called, then a default {@link ExecutorService} is used.
     *
     * @return The configured {@link ApolloClient}
     */
    public ApolloClient build() {
      checkNotNull(okHttpClient, "okHttpClient is null");
      checkNotNull(serverUrl, "serverUrl is null");

      apolloLogger = new ApolloLogger(logger);

      if (httpCacheStore != null && httpEvictionStrategy != null) {
        httpCache = new HttpCache(httpCacheStore, httpEvictionStrategy, apolloLogger);
        okHttpClient = okHttpClient.newBuilder().addInterceptor(httpCache.interceptor()).build();
      }

      if (dispatcher == null) {
        dispatcher = defaultDispatcher();
      }

      return new ApolloClient(this);
    }

    private ExecutorService defaultDispatcher() {
      return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
          new SynchronousQueue<Runnable>(), new ThreadFactory() {
        @Override public Thread newThread(Runnable runnable) {
          return new Thread(runnable, "Apollo Dispatcher");
        }
      });
    }
  }
}
