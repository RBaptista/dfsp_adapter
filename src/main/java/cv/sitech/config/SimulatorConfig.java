package cv.sitech.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "simulator")
public interface SimulatorConfig {

  /** base URL the hub uses to reach this app */
  String publicBaseUrl();

  /** how long a synchronous API call waits for a hub callback */
  Duration callbackTimeout();

  /** lifetime of quotes and transfers before they expire */
  Duration transferExpiry();

  /** HMAC secret the payee uses to derive ILP fulfilments from packets */
  String ilpSecret();

  Payee payee();

  Hub hub();

  Settlement settlement();

  interface Payee {
    /** state the payee answers a transfer prepare with: COMMITTED or RESERVED */
    @WithDefault("COMMITTED")
    String transferState();

    /** fee taken by the payee DFSP, in percent of the quoted amount */
    @WithDefault("0")
    BigDecimal feePercent();
  }

  interface Hub {
    String alsUrl();

    String quotesUrl();

    String transfersUrl();

    String adminUrl();

    String settlementUrl();

    Optional<String> bearerToken();
  }

  interface Settlement {
    /** scheduler interval of the reconciliation job, or "off" */
    @WithDefault("5m")
    String reconcileEvery();
  }

  default String callbackBaseUrl(String fspId) {
    return publicBaseUrl() + "/fspiop/" + fspId;
  }
}
