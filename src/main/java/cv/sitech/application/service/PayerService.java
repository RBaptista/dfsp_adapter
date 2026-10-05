package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;

import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.PayerCallbackUseCase;
import cv.sitech.application.port.in.QuoteResponse;
import cv.sitech.application.port.in.StartTransferCommand;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.FspiopGateway;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.config.SimulatorConfig;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryType;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import cv.sitech.domain.transfer.TransferState;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;

/**
 * Payer side of a P2P transfer. Each step stores the new state before calling the hub, because the
 * hub's callback may arrive before the outgoing call returns.
 */
@ApplicationScoped
public class PayerService implements OutboundTransferUseCase, PayerCallbackUseCase {

  private static final Logger LOG = Logger.getLogger(PayerService.class);

  private final TransferRepository transferRepository;
  private final PartyRepository partyRepository;
  private final AccountRepository accountRepository;
  private final LedgerService ledgerService;
  private final FspiopGateway fspiop;
  private final CallbackRegistry callbacks;
  private final SimulatorConfig config;

  public PayerService(TransferRepository transferRepository, PartyRepository partyRepository,
      AccountRepository accountRepository, LedgerService ledgerService, FspiopGateway fspiop,
      CallbackRegistry callbacks, SimulatorConfig config) {
    this.transferRepository = transferRepository;
    this.partyRepository = partyRepository;
    this.accountRepository = accountRepository;
    this.ledgerService = ledgerService;
    this.fspiop = fspiop;
    this.callbacks = callbacks;
    this.config = config;
  }

  static String partyKey(String fspId, IdTypeEnum idType, String idValue) {
    return "party:" + fspId + ":" + idType + ":" + idValue;
  }

  static String ledgerReference(Transfer transfer) {
    return "OUT:" + transfer.getTransferId();
  }

  // --- user-facing API ---

  @Override
  public PartyInfo lookupParty(String fspId, IdTypeEnum idType, String idValue) {
    String key = partyKey(fspId, idType, idValue);
    callbacks.expect(key);
    fspiop.getParty(fspId, idType, idValue);
    return callbacks.await(key, config.callbackTimeout());
  }

  @Override
  public Transfer start(String fspId, StartTransferCommand command) {
    Transfer transfer = QuarkusTransaction.joiningExisting().call(() -> {
      Party payer = partyRepository.findByIdentifier(command.payerIdType(), command.payerIdValue())
          .filter(p -> fspId.equals(p.getAccount().getDfsp().getFspId()))
          .orElseThrow(() -> new NotFoundException(
              "Payer " + command.payerIdType() + "/" + command.payerIdValue() + " not Found"));
      CurrencyEnum currency = payer.getAccount().getDfsp().getCurrency();
      if (command.currency() != null && command.currency() != currency) {
        throw new IllegalArgumentException("Payer account currency is " + currency);
      }
      validateAmount(command.amount(), currency);

      Instant now = Instant.now();
      Transfer t = new Transfer();
      t.setDirection(TransferDirection.OUTBOUND);
      t.setTransferId(UUID.randomUUID().toString());
      t.setPayerFspId(fspId);
      t.setPayerIdType(command.payerIdType());
      t.setPayerIdValue(command.payerIdValue());
      t.setPayerAccountId(payer.getAccount().getId());
      t.setPayeeIdType(command.payeeIdType());
      t.setPayeeIdValue(command.payeeIdValue());
      t.setAmount(command.amount());
      t.setCurrency(currency);
      t.setNote(command.note());
      t.setAutoAccept(command.autoAccept());
      t.setState(TransferState.WAITING_FOR_PARTY_INFO);
      t.setCreatedAt(now);
      t.setUpdatedAt(now);
      t.setExpiration(now.plus(config.transferExpiry()));
      return transferRepository.save(t);
    });
    try {
      fspiop.getParty(fspId, command.payeeIdType(), command.payeeIdValue());
    } catch (RuntimeException e) {
      return abort(transfer, error(e));
    }
    return findById(fspId, transfer.getTransferId());
  }

  @Override
  public Transfer acceptParty(String fspId, String transferId, boolean accept) {
    Transfer transfer = findById(fspId, transferId);
    requireState(transfer, TransferState.WAITING_FOR_PARTY_ACCEPTANCE);
    return accept ? requestQuote(transfer) : abort(transfer, new FspiopError("5101", "Payee rejected by payer"));
  }

  @Override
  public Transfer acceptQuote(String fspId, String transferId, boolean accept) {
    Transfer transfer = findById(fspId, transferId);
    requireState(transfer, TransferState.WAITING_FOR_QUOTE_ACCEPTANCE);
    return accept ? executeTransfer(transfer) : abort(transfer, new FspiopError("5101", "Quote rejected by payer"));
  }

  @Override
  public Transfer findById(String fspId, String transferId) {
    return QuarkusTransaction.joiningExisting().call(() -> transferRepository
        .findByTransferId(TransferDirection.OUTBOUND, transferId)
        .filter(t -> fspId.equals(t.getPayerFspId()))
        .orElseThrow(() -> new NotFoundException("Transfer " + transferId + " not Found")));
  }

  @Override
  public List<Transfer> findAll(String fspId) {
    return QuarkusTransaction.joiningExisting().call(() -> transferRepository.findOutboundByPayer(fspId));
  }

  // --- hub callbacks ---

  @Override
  @ActivateRequestContext
  public void partyResolved(String fspId, PartyInfo party) {
    callbacks.complete(partyKey(fspId, party.idType(), party.idValue()), party);
    List<Transfer> waiting = QuarkusTransaction.joiningExisting().call(() -> {
      List<Transfer> transfers = transferRepository.findAwaitingParty(fspId, party.idType(), party.idValue());
      for (Transfer t : transfers) {
        t.setPayeeFspId(party.fspId());
        t.setPayeeName(party.name());
        t.setState(TransferState.WAITING_FOR_PARTY_ACCEPTANCE);
        t.setUpdatedAt(Instant.now());
        transferRepository.save(t);
      }
      return transfers;
    });
    waiting.stream().filter(Transfer::isAutoAccept).forEach(this::requestQuote);
  }

  @Override
  @ActivateRequestContext
  public void partyLookupFailed(String fspId, IdTypeEnum idType, String idValue, FspiopError error) {
    callbacks.fail(partyKey(fspId, idType, idValue), new BusinessException(error.code(), error.description()));
    QuarkusTransaction.joiningExisting().call(() -> transferRepository.findAwaitingParty(fspId, idType, idValue))
        .forEach(t -> abort(t, error));
  }

  @Override
  @ActivateRequestContext
  public void quoteReceived(String fspId, QuoteResponse response) {
    Transfer transfer = QuarkusTransaction.joiningExisting().call(() -> {
      var found = transferRepository.findByQuoteId(TransferDirection.OUTBOUND, response.quoteId())
          .filter(t -> fspId.equals(t.getPayerFspId()) && t.getState() == TransferState.WAITING_FOR_QUOTE);
      if (found.isEmpty()) {
        LOG.warnf("Ignoring quote %s for %s: no transfer waiting for it", response.quoteId(), fspId);
        return null;
      }
      Transfer t = found.get();
      t.setTransferAmount(response.transferAmount());
      t.setPayeeReceiveAmount(response.payeeReceiveAmount());
      t.setPayeeFee(response.payeeFee());
      t.setIlpPacket(response.ilpPacket());
      t.setIlpCondition(response.condition());
      if (response.expiration() != null) {
        t.setExpiration(response.expiration());
      }
      t.setState(TransferState.WAITING_FOR_QUOTE_ACCEPTANCE);
      t.setUpdatedAt(Instant.now());
      return transferRepository.save(t);
    });
    if (transfer != null && transfer.isAutoAccept()) {
      executeTransfer(transfer);
    }
  }

  @Override
  @ActivateRequestContext
  public void quoteFailed(String fspId, String quoteId, FspiopError error) {
    QuarkusTransaction.joiningExisting().call(() -> transferRepository
        .findByQuoteId(TransferDirection.OUTBOUND, quoteId)
        .filter(t -> fspId.equals(t.getPayerFspId())))
        .ifPresent(t -> abort(t, error));
  }

  @Override
  @ActivateRequestContext
  public void transferFulfilled(String fspId, String transferId, String fulfilment, String transferState,
      Instant completedAt) {
    if ("ABORTED".equals(transferState)) {
      transferFailed(fspId, transferId, new FspiopError("5000", "Transfer aborted by the hub"));
      return;
    }
    QuarkusTransaction.joiningExisting().run(() -> {
      var found = transferRepository.findByTransferId(TransferDirection.OUTBOUND, transferId)
          .filter(t -> fspId.equals(t.getPayerFspId()) && t.getState() == TransferState.RESERVED);
      if (found.isEmpty()) {
        return;
      }
      Transfer t = found.get();
      if (!IlpService.matches(fulfilment, t.getIlpCondition())) {
        // the hub already validated and committed; keep the books in line with it
        LOG.warnf("Fulfilment of transfer %s does not match its condition", transferId);
      }
      ledgerService.commit(ledgerReference(t));
      t.setFulfilment(fulfilment);
      t.setState(TransferState.COMMITTED);
      t.setCompletedAt(completedAt != null ? completedAt : Instant.now());
      t.setUpdatedAt(Instant.now());
      transferRepository.save(t);
    });
  }

  @Override
  @ActivateRequestContext
  public void transferFailed(String fspId, String transferId, FspiopError error) {
    QuarkusTransaction.joiningExisting().call(() -> transferRepository
        .findByTransferId(TransferDirection.OUTBOUND, transferId)
        .filter(t -> fspId.equals(t.getPayerFspId()) && !t.getState().isFinal()))
        .ifPresent(t -> abort(t, error));
  }

  /** marks non-final transfers past their expiration as EXPIRED and releases their reservations */
  public void expire(Transfer transfer) {
    finish(transfer, TransferState.EXPIRED, new FspiopError("3303", "Transfer expired"));
  }

  // --- steps ---

  private Transfer requestQuote(Transfer transfer) {
    Transfer t = QuarkusTransaction.joiningExisting().call(() -> {
      transfer.setQuoteId(UUID.randomUUID().toString());
      transfer.setTransactionId(UUID.randomUUID().toString());
      transfer.setState(TransferState.WAITING_FOR_QUOTE);
      transfer.setUpdatedAt(Instant.now());
      return transferRepository.save(transfer);
    });
    try {
      fspiop.postQuote(t);
    } catch (RuntimeException e) {
      return abort(t, error(e));
    }
    return t;
  }

  private Transfer executeTransfer(Transfer transfer) {
    Transfer t;
    try {
      t = QuarkusTransaction.joiningExisting().call(() -> {
        Account position = accountRepository.findDfspAccount(transfer.getPayerFspId(), AccountTypeEnum.POSITION)
            .orElseThrow(() -> new NotFoundException("Position account of " + transfer.getPayerFspId()));
        ledgerService.reserve(ledgerReference(transfer), EntryType.TRANSFER_OUT, List.of(
            posting(transfer.getPayerAccountId(), transfer.getTransferAmount().negate()),
            posting(position.getId(), transfer.getTransferAmount())));
        transfer.setState(TransferState.RESERVED);
        // sent to the hub as the transfer's expiration; the hub times the transfer out at that point
        transfer.setExpiration(Instant.now().plus(config.transferExpiry()));
        transfer.setUpdatedAt(Instant.now());
        return transferRepository.save(transfer);
      });
    } catch (BusinessException e) {
      return abort(transfer, new FspiopError(e.getCode(), e.getMessage()));
    }
    try {
      fspiop.postTransfer(t);
    } catch (RuntimeException e) {
      return abort(t, error(e));
    }
    return t;
  }

  private Transfer abort(Transfer transfer, FspiopError error) {
    return finish(transfer, TransferState.ABORTED, error);
  }

  private Transfer finish(Transfer transfer, TransferState state, FspiopError error) {
    return QuarkusTransaction.joiningExisting().call(() -> {
      Transfer current = transferRepository.findByTransferId(TransferDirection.OUTBOUND, transfer.getTransferId())
          .orElse(transfer);
      if (current.getState().isFinal()) {
        return current;
      }
      ledgerService.abort(ledgerReference(current));
      current.setState(state);
      current.setErrorCode(error.code());
      current.setErrorDescription(error.description());
      current.setUpdatedAt(Instant.now());
      return transferRepository.save(current);
    });
  }

  private static void requireState(Transfer transfer, TransferState expected) {
    if (transfer.getState() != expected) {
      throw new BusinessException("3100",
          "Transfer " + transfer.getTransferId() + " is " + transfer.getState() + ", expected " + expected);
    }
  }

  private static void validateAmount(BigDecimal amount, CurrencyEnum currency) {
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("Amount must be positive");
    }
    if (amount.stripTrailingZeros().scale() > currency.scale()) {
      throw new IllegalArgumentException(currency + " amounts have at most " + currency.scale() + " decimals");
    }
  }

  private static FspiopError error(RuntimeException e) {
    return e instanceof BusinessException be ? new FspiopError(be.getCode(), be.getMessage())
        : new FspiopError("1001", "Hub unreachable: " + e.getMessage());
  }

}
