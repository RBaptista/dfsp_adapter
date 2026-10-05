package cv.sitech.application.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import cv.sitech.domain.exception.BusinessException;
import jakarta.enterprise.context.ApplicationScoped;

/** Lets a synchronous API call wait for the FSPIOP callback that answers it. */
@ApplicationScoped
public class CallbackRegistry {

  private final Map<String, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();

  /** must be called before the request that triggers the callback is sent */
  public void expect(String key) {
    pending.computeIfAbsent(key, k -> new CompletableFuture<>());
  }

  @SuppressWarnings("unchecked")
  public <T> T await(String key, Duration timeout) {
    CompletableFuture<Object> future = pending.computeIfAbsent(key, k -> new CompletableFuture<>());
    try {
      return (T) future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      throw new BusinessException("2004", "No callback from the hub within " + timeout.toSeconds() + "s");
    } catch (ExecutionException e) {
      throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } finally {
      pending.remove(key, future);
    }
  }

  /** completes a waiting call, if any */
  public void complete(String key, Object value) {
    CompletableFuture<Object> future = pending.get(key);
    if (future != null) {
      future.complete(value);
    }
  }

  public void fail(String key, RuntimeException error) {
    CompletableFuture<Object> future = pending.get(key);
    if (future != null) {
      future.completeExceptionally(error);
    }
  }

}
