package cv.sitech.adapter.in.fspiop;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.logging.Logger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/** Runs FSPIOP request handling after the hub has been acknowledged. */
@ApplicationScoped
public class FspiopDispatcher {

  private static final Logger LOG = Logger.getLogger(FspiopDispatcher.class);

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public void dispatch(String description, Runnable task) {
    executor.execute(() -> {
      try {
        task.run();
      } catch (RuntimeException e) {
        LOG.errorf(e, "Handling %s failed", description);
      }
    });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }

}
