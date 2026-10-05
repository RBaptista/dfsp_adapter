package cv.sitech.application.service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.transfer.Transfer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * ILP as used by FSPIOP v1.1: an OER-encoded ILP Payment packet whose data is the base64url
 * transaction object. The payee derives the fulfilment as HMAC-SHA256(secret, ilpPacket) and the
 * condition is SHA-256(fulfilment), so a transfer can be validated from its packet alone.
 */
@ApplicationScoped
public class IlpService {

  private static final int ILP_PAYMENT_TYPE = 1;
  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64URL_DECODER = Base64.getUrlDecoder();

  private final SimulatorConfig config;
  private final ObjectMapper objectMapper;

  public IlpService(SimulatorConfig config, ObjectMapper objectMapper) {
    this.config = config;
    this.objectMapper = objectMapper;
  }

  /** the base64url ILP packet for the quote held by an INBOUND transfer */
  public String buildPacket(Transfer quote) {
    byte[] data = B64URL.encode(transactionObject(quote));
    String address = "g.%s.%s.%s".formatted(quote.getPayeeFspId(),
        quote.getPayeeIdType().name().toLowerCase(Locale.ROOT), quote.getPayeeIdValue().toLowerCase(Locale.ROOT));
    long amount = quote.getTransferAmount().movePointRight(quote.getCurrency().scale()).longValueExact();

    ByteArrayOutputStream content = new ByteArrayOutputStream();
    content.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(amount).array());
    writeVarOctetString(content, address.getBytes(StandardCharsets.US_ASCII));
    writeVarOctetString(content, data);

    ByteArrayOutputStream packet = new ByteArrayOutputStream();
    packet.write(ILP_PAYMENT_TYPE);
    writeVarOctetString(packet, content.toByteArray());
    return B64URL.encodeToString(packet.toByteArray());
  }

  public String fulfilment(String ilpPacket) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(config.ilpSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return B64URL.encodeToString(mac.doFinal(ilpPacket.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String condition(String fulfilment) {
    try {
      return B64URL.encodeToString(MessageDigest.getInstance("SHA-256").digest(B64URL_DECODER.decode(fulfilment)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static boolean matches(String fulfilment, String condition) {
    try {
      return fulfilment != null && condition != null && condition.equals(condition(fulfilment));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private byte[] transactionObject(Transfer quote) {
    Map<String, Object> tx = new LinkedHashMap<>();
    tx.put("transactionId", quote.getTransactionId());
    tx.put("quoteId", quote.getQuoteId());
    tx.put("payee", Map.of("partyIdInfo", Map.of("partyIdType", quote.getPayeeIdType().name(),
        "partyIdentifier", quote.getPayeeIdValue(), "fspId", quote.getPayeeFspId())));
    tx.put("payer", Map.of("partyIdInfo", Map.of("partyIdType", quote.getPayerIdType().name(),
        "partyIdentifier", quote.getPayerIdValue(), "fspId", quote.getPayerFspId())));
    tx.put("amount", Map.of("amount", amountString(quote.getTransferAmount()), "currency",
        quote.getCurrency().name()));
    tx.put("transactionType", Map.of("scenario", "TRANSFER", "initiator", "PAYER", "initiatorType", "CONSUMER"));
    if (quote.getNote() != null) {
      tx.put("note", quote.getNote());
    }
    try {
      return objectMapper.writeValueAsBytes(tx);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** FSPIOP amount format: no exponent, no trailing zeros */
  public static String amountString(BigDecimal amount) {
    BigDecimal stripped = amount.stripTrailingZeros();
    return stripped.signum() == 0 ? "0" : stripped.toPlainString();
  }

  private static void writeVarOctetString(ByteArrayOutputStream out, byte[] bytes) {
    int length = bytes.length;
    if (length <= 127) {
      out.write(length);
    } else {
      byte[] lengthBytes = BigInteger.valueOf(length).toByteArray();
      int offset = lengthBytes[0] == 0 ? 1 : 0;
      out.write(0x80 | (lengthBytes.length - offset));
      out.write(lengthBytes, offset, lengthBytes.length - offset);
    }
    out.writeBytes(bytes);
  }

}
