package cv.sitech.application.service;

import java.util.List;

import cv.sitech.application.port.in.PartyUseCase;
import cv.sitech.application.port.out.PartyRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PartyService implements PartyUseCase {

  private final PartyRepository partyRepository;

  public PartyService(PartyRepository partyRepository) {
    this.partyRepository = partyRepository;
  }

  @Override
  @Transactional
  public Party create(IdTypeEnum idType, Account account, String msisdn) {
    return partyRepository.save(new Party(idType, account, msisdn));
  }

  @Override
  public Party findById(Long id) {
    return partyRepository.findById(id).orElseThrow(() -> new NotFoundException("Party " + id + " not Found"));
  }

  @Override
  public List<Party> findAll() {
    return partyRepository.findAll();
  }

  @Override
  @Transactional
  public Party update(Long id, IdTypeEnum idType, String msisdn) {
    Party party = findById(id);
    party.setIdType(idType);
    party.setMsisdn(msisdn);
    return partyRepository.save(party);
  }

  @Override
  @Transactional
  public void delete(Long id) {
    if (!partyRepository.deleteById(id)) {
      throw new NotFoundException("Party " + id + " not Found");
    }
  }

}
