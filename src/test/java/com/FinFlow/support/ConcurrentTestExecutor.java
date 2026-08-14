package com.FinFlow.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ConcurrentTestExecutor {

  public ConcurrentExecutionResult execute(int requestCount, ConcurrentOperation operation) throws Exception {
    List<ConcurrentOperation> operations = new ArrayList<>();
    for (int index = 0; index < requestCount; index++) {
      operations.add(operation);
    }
    return execute(operations);
  }

  public ConcurrentExecutionResult execute(List<ConcurrentOperation> operations) throws Exception {
    ExecutorService executorService = Executors.newFixedThreadPool(operations.size());
    StartGate startGate = new StartGate(operations.size());
    List<Future<Throwable>> futures = new ArrayList<>();

    try {
      for (ConcurrentOperation operation : operations) {
        futures.add(executorService.submit(() -> {
          try {
            operation.run(startGate);
            return null;
          } catch (Throwable throwable) {
            return throwable;
          }
        }));
      }

      startGate.awaitAllReady();
      long startedAt = System.nanoTime();
      startGate.start();

      int successCount = 0;
      List<Throwable> failures = new ArrayList<>();
      for (Future<Throwable> future : futures) {
        Throwable failure = future.get(10, TimeUnit.SECONDS);
        if (failure == null) {
          successCount++;
        } else {
          failures.add(failure);
        }
      }

      return new ConcurrentExecutionResult(
              successCount,
              List.copyOf(failures),
              TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
      );
    } finally {
      executorService.shutdownNow();
    }
  }

  @FunctionalInterface
  public interface ConcurrentOperation {
    void run(StartGate startGate) throws Exception;
  }

  public static class StartGate {
    private final CountDownLatch ready;
    private final CountDownLatch start = new CountDownLatch(1);

    private StartGate(int requestCount) {
      this.ready = new CountDownLatch(requestCount);
    }

    public void readyAndAwaitStart() {
      ready.countDown();
      try {
        if (!start.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("동시성 테스트 시작 대기 시간이 초과되었습니다.");
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("동시성 테스트가 중단되었습니다.", exception);
      }
    }

    private void awaitAllReady() throws InterruptedException {
      if (!ready.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("모든 작업이 동시 실행 준비 상태에 도달하지 못했습니다.");
      }
    }

    private void start() {
      start.countDown();
    }
  }

  public record ConcurrentExecutionResult(
          int successCount,
          List<Throwable> failures,
          long elapsedMilliseconds
  ) {
  }
}
