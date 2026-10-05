package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;

import cv.sitech.application.port.in.PayeeUseCase;
import cv.sitech.application.port.in.QuoteRequest;
import cv.sitech.application.port.in.TransferPrepare;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.FspiopGateway;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.Customer;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryType;
import cv.sitech.domain.ledger.Posting;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import cv.sitech.domain.transfer.TransferState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

/**
 * Payee side of a P2P transfer: answers discovery and quotes for hosted parties and fulfils
 * transfers. The quote is stored as an INBOUND transfer row that the later prepare is matched to
 * by its ILP condition.
 */
@ApplicationScoped
public class PayeeService implements PayeeUseCase {

  private static final Logger LOG = Logger.getLogger(PayeeService.class);
  private static final FspiopError PARTY_NOT_FOUND = new FspiopError("3204", "Party not found");

  private final TransferRepository transferRepository;
  private final PartyRepository partyRepository;
  private final AccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final IlpService ilpService;
  private final FspiopGateway fspiop;
  private final SimulatorConfig config;

  public PayeeService(TransferRepository transferRepository, PartyRepository partyRepository,
      AccountRepository accountRepository, LedgerService ledgerService, IlpService ilpService, FspiopGateway fspiop,
      SimulatorConfig config) {
    this.transferRepository = transferRepository;
    this.partyRepository = partyRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.ilpService = ilpService;
    this.fspiop = fspiop;
    this.config = config;
  }

  static String ledgerReference(Transfer transfer) {
    return "IN:" + transfer.getTransferId();
  }

  @Override
  @ActivateRequestContext
  public void partyLookup(String fspId, String requesterFspId, IdTypeEnum idType, String idValue) {
    Optional<Party> party = QuarkusTransaction.joiningExisting().call(() -> hostedParty(fspId, idType, idValue));
    if (party.isPresent()) {
      fspiop.putParty(fspId, requesterFspId, new PartyInfo(idType, idValue, fspId, displayName(party.get())));
    } else {
      fspiop.putPartyError(fspId, requesterFspId, idType, idValue, PARTY_NOT_FOUND);
    }
  }

  @Override
  @ActivateRequestContext
  public void quoteRequested(String fspId, String requesterFspId, QuoteRequest request) {
    Transfer quote;
    try {
      quote = QuarkusTransaction.joiningExisting().call(() -> {
        var existing = transferRepository.findByQuoteId(TransferDirection.INBOUND, request.quoteId());
        if (existing.isPresent()) {
          // a resent quote request gets the same answer
          return existing.get();
        }
        return transferRepository.save(newQuote(fspId, request));
      });
    } catch (BusinessException e) {
      fspiop.putQuoteError(fspId, requesterFspId, request.quoteId(), new FspiopError(e.getCode(), e.getMessage()));
      return;
    }
    fspiop.putQuote(quote);
  }

  @Override
  @ActivateRequestContext
  public void transferPrepared(String fspId, String requesterFspId, TransferPrepare prepare) {
    Transfer transfer;
    try {
      transfer = QuarkusTransaction.joiningExisting().call(() -> reserve(fspId, prepare));
    } catch (BusinessException e) {
      fspiop.putTransferError(fspId, requesterFspId, prepare.transferId(),
          new FspiopError(e.getCode(), e.getMessage()));
      return;
    }
    // a duplicate prepare of a finished transfer gets its final answer again
    if (transfer.getState() == TransferState.COMMITTED) {
      fspiop.putTransfer(transfer, TransferState.COMMITTED.name());
      return;
    }
    if (transfer.getState().isFinal()) {
      fspiop.putTransferError(fspId, requesterFspId, prepare.transferId(),
          new FspiopError(transfer.getErrorCode(), transfer.getErrorDescription()));
      return;
    }
    boolean commitNow = !"RESERVED".equalsIgnoreCase(config.payee().transferState());
    try {
      fspiop.putTransfer(transfer, commitNow ? "COMMITTED" : "RESERVED");
    } catch (RuntimeException e) {
      LOG.errorf(e, "Could not fulfil transfer %s", prepare.transferId());
      finish(fspId, prepare.transferId(), TransferState.ABORTED, new FspiopError("1001", e.getMessage()));
      return;
    }
    if (commitNow) {
      finish(fspId, prepare.transferId(), TransferState.COMMITTED, null);
    }
  }

  @Override
  @ActivateRequestContext
  public void transferNotified(String fspId, String transferId, String transferState) {
    if ("COMMITTED".equals(transferState)) {
      finish(fspId, transferId, TransferState.COMMITTED, null);
    } else if ("ABORTED".equals(transferState)) {
      finish(fspId, transferId, TransferState.ABORTED, new FspiopError("5000", "Transfer aborted by the hub"));
    }
  }

  @Override
  @ActivateRequestContext
  public void transferFailed(String fspId, String transferId, FspiopError error) {
    finish(fspId, transferId, TransferState.ABORTED, error);
  }

  @Override
  public List<Transfer> findAll(String fspId) {
    return QuarkusTransaction.joiningExisting().call(() -> transferRepository.findInboundByPayee(fspId));
  }

  /** expires an INBOUND quote or reservation past its expiration */
  public void expire(Transfer transfer) {
    if (transfer.getState() == TransferState.QUOTED) {
      QuarkusTransaction.joiningExisting().run(() -> {
        transfer.setState(TransferState.EXPIRED);
        transfer.setUpdatedAt(Instant.now());
        transferRepository.save(transfer);
      });
    } else {
      finish(transfer.getPayeeFspId(), transfer.getTransferId(), TransferState.EXPIRED,
          new FspiopError("3303", "Transfer expired"));
    }
  }

  private Transfer newQuote(String fspId, QuoteRequest request) {
    Party party = hostedParty(fspId, request.payee().idType(), request.payee().idValue())
        .orElseThrow(() -> new BusinessException(PARTY_NOT_FOUND.code(), PARTY_NOT_FOUND.description()));
    Account account = party.getAccount();
    if (request.currency() != account.getDfsp().getCurrency()) {
      throw new BusinessException("3100", "Payee account currency is " + account.getDfsp().getCurrency());
    }
    int scale = request.currency().scale();
    BigDecimal fee = request.amount().multiply(config.payee().feePercent())
        .divide(BigDecimal.valueOf(100), scale, RoundingMode.HALF_UP);
    boolean receive = "RECEIVE".equals(request.amountType());
    BigDecimal transferAmount = receive ? request.amount().add(fee) : request.amount();
    BigDecimal receiveAmount = receive ? request.amount() : request.amount().subtract(fee);
    if (receiveAmount.signum() <= 0) {
      throw new BusinessException("3100", "Amount does not cover the payee fee");
    }

    Instant now = Instant.now();
    Transfer t = new Transfer();
    t.setDirection(TransferDirection.INBOUND);
    t.setQuoteId(request.quoteId());
    t.setTransactionId(request.transactionId());
    t.setPayerFspId(request.payer().fspId());
    t.setPayerIdType(request.payer().idType());
    t.setPayerIdValue(request.payer().idValue());
    t.setPayeeFspId(fspId);
    t.setPayeeIdType(request.payee().idType());
    t.setPayeeIdValue(request.payee().idValue());
    t.setPayeeName(displayName(party));
    t.setPayeeAccountId(account.getId());
    t.setAmount(request.amount());
    t.setCurrency(request.currency());
    t.setTransferAmount(transferAmount);
    t.setPayeeReceiveAmount(receiveAmount);
    t.setPayeeFee(fee);
    t.setNote(request.note());
    t.setState(TransferState.QUOTED);
    t.setCreatedAt(now);
    t.setUpdatedAt(now);
    t.setExpiration(now.plus(config.transferExpiry()));
    t.setIlpPacket(ilpService.buildPacket(t));
    t.setIlpCondition(IlpService.condition(ilpService.fulfilment(t.getIlpPacket())));
    return t;
  }

  private Transfer reserve(String fspId, TransferPrepare prepare) {
    var duplicate = transferRepository.findByTransferId(TransferDirection.INBOUND, prepare.transferId());
    if (duplicate.isPresent()) {
      return duplicate.get();
    }
    Transfer t = transferRepository.findInboundByCondition(fspId, prepare.condition())
        .orElseThrow(() -> new BusinessException("3208", "No quote matches transfer " + prepare.transferId()));
    if (t.getState() != TransferState.QUOTED) {
      throw new BusinessException("3100", "Quote " + t.getQuoteId() + " is " + t.getState());
    }
    if (!t.getIlpPacket().equals(prepare.ilpPacket())) {
      throw new BusinessException("3106", "ILP packet does not match the quote");
    }
    if (t.getTransferAmount().compareTo(prepare.amount()) != 0 || t.getCurrency() != prepare.currency()) {
      throw new BusinessException("3100", "Transfer amount does not match the quote");
    }
    if (t.getExpiration().isBefore(Instant.now())) {
      throw new BusinessException("3302", "Quote " + t.getQuoteId() + " expired");
    }
    String fulfilment = ilpService.fulfilment(prepare.ilpPacket());
    if (!IlpService.matches(fulfilment, prepare.condition())) {
      throw new BusinessException("3106", "Condition does not match the ILP packet");
    }

    Account position = accountRepository.findDfspAccount(fspId, AccountTypeEnum.POSITION)
        .orElseThrow(() -> new NotFoundException("Position account of " + fspId));
    Account settlement = accountRepository.findDfspAccount(fspId, AccountTypeEnum.SETTLEMENT)
        .orElseThrow(() -> new NotFoundException("Settlement account of " + fspId));
    t.setTransferId(prepare.transferId());
    List<Posting> postings = new ArrayList<>(List.of(
        posting(position.getId(), t.getTransferAmount().negate()),
        posting(t.getPayeeAccountId(), t.getPayeeReceiveAmount())));
    if (t.getPayeeFee().signum() > 0) {
      postings.add(posting(settlement.getId(), t.getPayeeFee()));
    }
    ledgerService.reserve(ledgerReference(t), EntryType.TRANSFER_IN, postings);
    t.setFulfilment(fulfilment);
    t.setState(TransferState.RESERVED);
    t.setExpiration(prepare.expiration() != null ? prepare.expiration() : t.getExpiration());
    t.setUpdatedAt(Instant.now());
    return transferRepository.save(t);
  }

  private void finish(String fspId, String transferId, TransferState state, FspiopError error) {
    QuarkusTransaction.joiningExisting().run(() -> {
      var found = transferRepository.findByTransferId(TransferDirection.INBOUND, transferId)
          .filter(t -> fspId.equals(t.getPayeeFspId()));
      if (found.isEmpty()) {
        return;
      }
      Transfer t = found.get();
      if (state == TransferState.COMMITTED) {
        if (t.getState() != TransferState.RESERVED) {
          return;
        }
        ledgerService.commit(ledgerReference(t));
        t.setCompletedAt(Instant.now());
      } else if (t.getState() == TransferState.COMMITTED) {
        // the hub failed the transfer after the payee committed: undo the credit
        ledgerService.reverse(ledgerReference(t));
      } else if (t.getState().isFinal()) {
        return;
      } else {
        ledgerService.abort(ledgerReference(t));
      }
      t.setState(state);
      if (error != null) {
        t.setErrorCode(error.code());
        t.setErrorDescription(error.description());
      }
      t.setUpdatedAt(Instant.now());
      transferRepository.save(t);
    });
  }

  private Optional<Party> hostedParty(String fspId, IdTypeEnum idType, String idValue) {
    return partyRepository.findByIdentifier(idType, idValue)
        .filter(p -> fspId.equals(p.getAccount().getDfsp().getFspId()));
  }

  private static String displayName(Party party) {
    Customer customer = party.getAccount().getCustomer();
    return customer != null ? customer.getName() : null;
  }

}
