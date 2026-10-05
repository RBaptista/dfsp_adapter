package cv.sitech.adapter.out.hub;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import cv.sitech.adapter.fspiop.Fspiop;
import cv.sitech.application.port.out.HubAdminGateway;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.IdTypeEnum;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CentralLedgerAdminClient implements HubAdminGateway {

  private static final Logger LOG = Logger.getLogger(CentralLedgerAdminClient.class);

  private final HubHttp http;
  private final SimulatorConfig config;

  public CentralLedgerAdminClient(HubHttp http, SimulatorConfig config) {
    this.http = http;
    this.config = config;
  }

  /** callback templates the hub fills in; every DFSP points at its own /fspiop/{fspId} base */
  static Map<String, String> endpoints(String base) {
    Map<String, String> endpoints = new LinkedHashMap<>();
    endpoints.put("FSPIOP_CALLBACK_URL_PARTIES_GET", base + "/parties/{{partyIdType}}/{{partyIdentifier}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTIES_PUT", base + "/parties/{{partyIdType}}/{{partyIdentifier}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTIES_PUT_ERROR",
        base + "/parties/{{partyIdType}}/{{partyIdentifier}}/error");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_PUT", base + "/participants/{{partyIdType}}/{{partyIdentifier}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_PUT_ERROR",
        base + "/participants/{{partyIdType}}/{{partyIdentifier}}/error");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT", base + "/participants/{{requestId}}");
    endpoints.put("FSPIOP_CALLBACK_URL_PARTICIPANT_BATCH_PUT_ERROR", base + "/participants/{{requestId}}/error");
    endpoints.put("FSPIOP_CALLBACK_URL_QUOTES", base);
    endpoints.put("FSPIOP_CALLBACK_URL_TRANSFER_POST", base + "/transfers");
    endpoints.put("FSPIOP_CALLBACK_URL_TRANSFER_PUT", base + "/transfers/{{transferId}}");
    endpoints.put("FSPIOP_CALLBACK_URL_TRANSFER_ERROR", base + "/transfers/{{transferId}}/error");
    return endpoints;
  }

  @Override
  public void onboard(Dfsp dfsp) {
    String admin = config.hub().adminUrl();
    String name = dfsp.getFspId();
    String currency = dfsp.getCurrency().name();
    ignoringExisting(() -> http.json("POST", admin + "/participants", Map.of("name", name, "currency", currency)));
    ignoringExisting(() -> http.json("POST", admin + "/participants/" + name + "/initialPositionAndLimits",
        Map.of("currency", currency,
            "limit", Map.of("type", "NET_DEBIT_CAP", "value", dfsp.getNetDebitCap()),
            "initialPosition", dfsp.getInitialPosition())));
    endpoints(config.callbackBaseUrl(name)).forEach((type, value) -> http.json("POST",
        admin + "/participants/" + name + "/endpoints", Map.of("type", type, "value", value)));
  }

  @Override
  public void recordFundsIn(String fspId, BigDecimal amount, String currency) {
    String admin = config.hub().adminUrl();
    JsonNode accounts = http.json("GET", admin + "/participants/" + fspId + "/accounts", null);
    String settlementAccountId = null;
    if (accounts != null) {
      for (JsonNode account : accounts) {
        if ("SETTLEMENT".equals(account.path("ledgerAccountType").asText())
            && currency.equals(account.path("currency").asText())) {
          settlementAccountId = account.path("id").asText();
        }
      }
    }
    if (settlementAccountId == null) {
      throw new HubCallException(404, "No " + currency + " settlement account for " + fspId + " in the hub");
    }
    http.json("POST", admin + "/participants/" + fspId + "/accounts/" + settlementAccountId, Map.of(
        "transferId", UUID.randomUUID().toString(),
        "externalReference", "dfsp-simulator",
        "action", "recordFundsIn",
        "reason", "initial funds",
        "amount", new Fspiop.Money(amount.stripTrailingZeros().toPlainString(), currency)));
  }

  @Override
  public void registerParty(String fspId, IdTypeEnum idType, String idValue, String currency) {
    http.fspiop("POST", participantsUrl(idType, idValue), "participants", fspId, null,
        new Fspiop.ParticipantsPost(fspId, currency));
  }

  @Override
  public void deregisterParty(String fspId, IdTypeEnum idType, String idValue) {
    try {
      http.fspiop("DELETE", participantsUrl(idType, idValue), "participants", fspId, null, null);
    } catch (HubCallException e) {
      LOG.warnf("Could not remove %s/%s from the ALS: %s", idType, idValue, e.getMessage());
    }
  }

  private String participantsUrl(IdTypeEnum idType, String idValue) {
    return config.hub().alsUrl() + "/participants/" + idType.name() + "/"
        + URLEncoder.encode(idValue, StandardCharsets.UTF_8);
  }

  // central-ledger answers 400 with "already" in the message when the resource exists
  private static void ignoringExisting(Runnable call) {
    try {
      call.run();
    } catch (HubCallException e) {
      if (e.getStatus() != 400 || !e.getMessage().toLowerCase().contains("already")) {
        throw e;
      }
    }
  }

}
