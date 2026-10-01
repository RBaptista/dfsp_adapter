package cv.sitech.application.service;

import java.util.List;

import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.application.port.out.DfspRepository;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class DfspService implements DfspUseCase {

  private final DfspRepository dfspRepository;

  public DfspService(DfspRepository dfspRepository) {
    this.dfspRepository = dfspRepository;
  }

  @Override
  @Transactional
  public Dfsp create(String fspId, String displayName, CurrencyEnum currency, String callbackBaseUrl,
      Long netDebitCap, Long initialPosition, Long initialFunds) {
    return dfspRepository
        .save(new Dfsp(fspId, displayName, currency, callbackBaseUrl, netDebitCap, initialPosition, initialFunds));
  }

  @Override
  public Dfsp findById(String fspId) {
    return dfspRepository.findById(fspId).orElseThrow(() -> new NotFoundException("Dfsp " + fspId + " not Found"));
  }

  @Override
  public List<Dfsp> findAll() {
    return dfspRepository.findAll();
  }

  @Override
  @Transactional
  public Dfsp update(String fspId, String displayName, CurrencyEnum currency, Long initialPosition) {
    Dfsp dfsp = findById(fspId);
    dfsp.setDisplayName(displayName);
    dfsp.setCurrency(currency);
    dfsp.setInitialPosition(initialPosition);
    return dfspRepository.save(dfsp);
  }

}
