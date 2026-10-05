package cv.sitech.adapter.out.hub;

import static cv.sitech.adapter.fspiop.Fspiop.money;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import cv.sitech.adapter.fspiop.Fspiop;
import cv.sitech.application.port.out.FspiopGateway;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class FspiopHubClient implements FspiopGateway {

  private final HubHttp http;
  private final SimulatorConfig.Hub hub;

  public FspiopHubClient(HubHttp http, SimulatorConfig config) {
    this.http = http;
    this.hub = config.hub();
  }

  @Override
  public void getParty(String sourceFspId, IdTypeEnum idType, String idValue) {
    http.fspiop("GET", partiesUrl(idType, idValue), "parties", sourceFspId, null, null);
  }

  @Override
  public void postQuote(Transfer t) {
    var body = new Fspiop.QuotesPost(t.getQuoteId(), t.getTransactionId(),
        new Fspiop.Party(new Fspiop.PartyIdInfo(t.getPayeeIdType().name(), t.getPayeeIdValue(), t.getPayeeFspId()),
            null),
        new Fspiop.Party(new Fspiop.PartyIdInfo(t.getPayerIdType().name(), t.getPayerIdValue(), t.getPayerFspId()),
            null),
        "SEND", money(t.getAmount(), t.getCurrency()),
        new Fspiop.TransactionType("TRANSFER", "PAYER", "CONSUMER"), t.getNote());
    http.fspiop("POST", hub.quotesUrl() + "/quotes", "quotes", t.getPayerFspId(), t.getPayeeFspId(), body);
  }

  @Override
  public void postTransfer(Transfer t) {
    var body = new Fspiop.TransfersPost(t.getTransferId(), t.getPayerFspId(), t.getPayeeFspId(),
        money(t.getTransferAmount(), t.getCurrency()), t.getIlpPacket(), t.getIlpCondition(),
        Fspiop.timestamp(t.getExpiration()));
    http.fspiop("POST", hub.transfersUrl() + "/transfers", "transfers", t.getPayerFspId(), t.getPayeeFspId(), body);
  }

  @Override
  public void putParty(String sourceFspId, String destinationFspId, PartyInfo party) {
    http.fspiop("PUT", partiesUrl(party.idType(), party.idValue()), "parties", sourceFspId, destinationFspId,
        new Fspiop.PartiesPut(Fspiop.party(party)));
  }

  @Override
  public void putPartyError(String sourceFspId, String destinationFspId, IdTypeEnum idType, String idValue,
      FspiopError error) {
    http.fspiop("PUT", partiesUrl(idType, idValue) + "/error", "parties", sourceFspId, destinationFspId,
        Fspiop.errorBody(error));
  }

  @Override
  public void putQuote(Transfer t) {
    var body = new Fspiop.QuotesPut(money(t.getTransferAmount(), t.getCurrency()),
        money(t.getPayeeReceiveAmount(), t.getCurrency()),
        t.getPayeeFee() != null && t.getPayeeFee().signum() > 0 ? money(t.getPayeeFee(), t.getCurrency()) : null,
        Fspiop.timestamp(t.getExpiration()), t.getIlpPacket(), t.getIlpCondition());
    http.fspiop("PUT", hub.quotesUrl() + "/quotes/" + t.getQuoteId(), "quotes", t.getPayeeFspId(),
        t.getPayerFspId(), body);
  }

  @Override
  public void putQuoteError(String sourceFspId, String destinationFspId, String quoteId, FspiopError error) {
    http.fspiop("PUT", hub.quotesUrl() + "/quotes/" + quoteId + "/error", "quotes", sourceFspId, destinationFspId,
        Fspiop.errorBody(error));
  }

  @Override
  public void putTransfer(Transfer t, String transferState) {
    var body = new Fspiop.TransfersPut(t.getFulfilment(), Fspiop.timestamp(Instant.now()), transferState);
    http.fspiop("PUT", hub.transfersUrl() + "/transfers/" + t.getTransferId(), "transfers", t.getPayeeFspId(),
        t.getPayerFspId(), body);
  }

  @Override
  public void putTransferError(String sourceFspId, String destinationFspId, String transferId, FspiopError error) {
    http.fspiop("PUT", hub.transfersUrl() + "/transfers/" + transferId + "/error", "transfers", sourceFspId,
        destinationFspId, Fspiop.errorBody(error));
  }

  private String partiesUrl(IdTypeEnum idType, String idValue) {
    return hub.alsUrl() + "/parties/" + idType.name() + "/" + URLEncoder.encode(idValue, StandardCharsets.UTF_8);
  }

}
