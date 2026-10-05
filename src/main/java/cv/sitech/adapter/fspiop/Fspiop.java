package cv.sitech.adapter.fspiop;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;

import cv.sitech.application.service.IlpService;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;

/** FSPIOP v1.1 wire model (the subset used for P2P) and its conversions. */
public final class Fspiop {

  public static final String SOURCE = "FSPIOP-Source";
  public static final String DESTINATION = "FSPIOP-Destination";

  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private Fspiop() {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PartyIdInfo(String partyIdType, String partyIdentifier, String fspId) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Party(PartyIdInfo partyIdInfo, String name) {
  }

  public record PartiesPut(Party party) {
  }

  public record Money(String amount, String currency) {
  }

  public record TransactionType(String scenario, String initiator, String initiatorType) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QuotesPost(String quoteId, String transactionId, Party payee, Party payer, String amountType,
      Money amount, TransactionType transactionType, String note) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record QuotesPut(Money transferAmount, Money payeeReceiveAmount, Money payeeFspFee, String expiration,
      String ilpPacket, String condition) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TransfersPost(String transferId, String payerFsp, String payeeFsp, Money amount, String ilpPacket,
      String condition, String expiration) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TransfersPut(String fulfilment, String completedTimestamp, String transferState) {
  }

  public record ErrorInformation(String errorCode, String errorDescription) {
  }

  public record ErrorBody(ErrorInformation errorInformation) {
  }

  public record ParticipantsPost(String fspId, String currency) {
  }

  // --- conversions ---

  public static String timestamp(Instant instant) {
    return TIMESTAMP.format(instant);
  }

  public static Instant instant(String timestamp) {
    return timestamp == null ? null : OffsetDateTime.parse(timestamp).toInstant();
  }

  public static Money money(BigDecimal amount, CurrencyEnum currency) {
    return amount == null ? null : new Money(IlpService.amountString(amount), currency.name());
  }

  public static BigDecimal amount(Money money) {
    return money == null ? null : new BigDecimal(money.amount());
  }

  public static Party party(PartyInfo info) {
    return new Party(new PartyIdInfo(info.idType().name(), info.idValue(), info.fspId()), info.name());
  }

  public static PartyInfo partyInfo(Party party) {
    PartyIdInfo id = party.partyIdInfo();
    return new PartyInfo(IdTypeEnum.valueOf(id.partyIdType()), id.partyIdentifier(), id.fspId(), party.name());
  }

  public static ErrorBody errorBody(FspiopError error) {
    return new ErrorBody(new ErrorInformation(error.code(), error.description()));
  }

  public static FspiopError error(ErrorBody body) {
    ErrorInformation info = body == null ? null : body.errorInformation();
    return info == null ? new FspiopError("1000", "Unknown error")
        : new FspiopError(info.errorCode(), info.errorDescription());
  }

  /** e.g. {@code application/vnd.interoperability.parties+json;version=1.1} */
  public static String contentType(String resource) {
    return "application/vnd.interoperability." + resource + "+json;version=1.1";
  }

  public static String accept(String resource) {
    return "application/vnd.interoperability." + resource + "+json;version=1";
  }

}
