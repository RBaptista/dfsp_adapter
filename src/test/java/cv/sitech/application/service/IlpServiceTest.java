package cv.sitech.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.Transfer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
class IlpServiceTest {

  @Inject
  IlpService ilp;

  @Test
  void packetIsAnIlpPaymentWithScaledAmountAndAddress() {
    byte[] packet = Base64.getUrlDecoder().decode(ilp.buildPacket(quote("12.5")));
    assertEquals(1, packet[0], "ILP Payment type");
    // after the type byte and a long-form length prefix comes the uint64 amount in minor units
    int offset = (packet[1] & 0x80) != 0 ? 2 + (packet[1] & 0x7f) : 2;
    assertEquals(1250L, ByteBuffer.wrap(packet, offset, 8).getLong());
    String address = new String(packet, offset + 9, packet[offset + 8]);
    assertEquals("g.payeefsp.msisdn.2389911111", address);
  }

  @Test
  void conditionIsTheHashOfTheFulfilment() {
    String packet = ilp.buildPacket(quote("10"));
    String fulfilment = ilp.fulfilment(packet);
    String condition = IlpService.condition(fulfilment);
    assertEquals(43, fulfilment.length());
    assertEquals(43, condition.length());
    assertTrue(IlpService.matches(fulfilment, condition));
    assertFalse(IlpService.matches(ilp.fulfilment(ilp.buildPacket(quote("11"))), condition));
    assertNotEquals(packet, ilp.buildPacket(quote("11")));
  }

  @Test
  void amountsUseTheFspiopFormat() {
    assertEquals("10", IlpService.amountString(new BigDecimal("10.00")));
    assertEquals("10.5", IlpService.amountString(new BigDecimal("10.50")));
    assertEquals("0", IlpService.amountString(new BigDecimal("0.00")));
    assertEquals("100", IlpService.amountString(new BigDecimal("1E+2")));
  }

  private static Transfer quote(String amount) {
    Transfer t = new Transfer();
    t.setQuoteId("q-1");
    t.setTransactionId("tx-1");
    t.setPayerFspId("payerfsp");
    t.setPayerIdType(IdTypeEnum.MSISDN);
    t.setPayerIdValue("2389900000");
    t.setPayeeFspId("payeefsp");
    t.setPayeeIdType(IdTypeEnum.MSISDN);
    t.setPayeeIdValue("2389911111");
    t.setTransferAmount(new BigDecimal(amount));
    t.setCurrency(CurrencyEnum.CVE);
    return t;
  }

}
