package cv.sitech.adapter.in.fspiop;

import java.math.BigDecimal;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import cv.sitech.adapter.fspiop.Fspiop;
import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.application.port.in.PayeeUseCase;
import cv.sitech.application.port.in.PayerCallbackUseCase;
import cv.sitech.application.port.in.QuoteRequest;
import cv.sitech.application.port.in.QuoteResponse;
import cv.sitech.application.port.in.TransferPrepare;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.FspiopError;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * FSPIOP v1.1 endpoints the hub calls. Every hosted DFSP is registered in the hub with the
 * callback base {@code /fspiop/{fspId}}, so the path says which DFSP a request is for, and whether
 * that DFSP acts as payer (PUT callbacks) or payee (GET/POST requests).
 */
@Path("/fspiop/{fspId}")
@Consumes(MediaType.WILDCARD)
// the hub asks for application/vnd.interoperability.*+json; acknowledgements carry no body
@Produces(MediaType.WILDCARD)
public class FspiopResource {

  private static final Logger LOG = Logger.getLogger(FspiopResource.class);

  private final PayerCallbackUseCase payer;
  private final PayeeUseCase payee;
  private final DfspUseCase dfspUseCase;
  private final FspiopDispatcher dispatcher;
  private final ObjectMapper objectMapper;

  public FspiopResource(PayerCallbackUseCase payer, PayeeUseCase payee, DfspUseCase dfspUseCase,
      FspiopDispatcher dispatcher, ObjectMapper objectMapper) {
    this.payer = payer;
    this.payee = payee;
    this.dfspUseCase = dfspUseCase;
    this.dispatcher = dispatcher;
    this.objectMapper = objectMapper;
  }

  // --- discovery ---

  @GET
  @Path("/parties/{type}/{id}")
  public Response getParty(@PathParam("fspId") String fspId, @PathParam("type") String type,
      @PathParam("id") String id, @HeaderParam(Fspiop.SOURCE) String source,
      @HeaderParam(Fspiop.DESTINATION) String destination) {
    check(fspId, source, destination);
    IdTypeEnum idType = idType(type);
    dispatcher.dispatch("GET /parties/" + type + "/" + id,
        () -> payee.partyLookup(fspId, source, idType, id));
    return Response.accepted().build();
  }

  @PUT
  @Path("/parties/{type}/{id}")
  public Response putParty(@PathParam("fspId") String fspId, @PathParam("type") String type,
      @PathParam("id") String id, String body) {
    Fspiop.PartiesPut put = read(body, Fspiop.PartiesPut.class);
    if (put.party() == null || put.party().partyIdInfo() == null) {
      throw malformed("party.partyIdInfo is required");
    }
    dispatcher.dispatch("PUT /parties/" + type + "/" + id,
        () -> payer.partyResolved(fspId, Fspiop.partyInfo(put.party())));
    return Response.ok().build();
  }

  @PUT
  @Path("/parties/{type}/{id}/error")
  public Response putPartyError(@PathParam("fspId") String fspId, @PathParam("type") String type,
      @PathParam("id") String id, String body) {
    FspiopError error = Fspiop.error(read(body, Fspiop.ErrorBody.class));
    IdTypeEnum idType = idType(type);
    dispatcher.dispatch("PUT /parties/" + type + "/" + id + "/error",
        () -> payer.partyLookupFailed(fspId, idType, id, error));
    return Response.ok().build();
  }

  // --- ALS registration results ---

  @PUT
  @Path("/participants/{segments: .+}")
  public Response putParticipants(@PathParam("fspId") String fspId, @PathParam("segments") String segments,
      String body) {
    if (segments.endsWith("/error")) {
      LOG.warnf("ALS rejected participant %s of %s: %s", segments, fspId, body);
    } else {
      LOG.infof("ALS registered participant %s of %s", segments, fspId);
    }
    return Response.ok().build();
  }

  // --- quotes ---

  @POST
  @Path("/quotes")
  public Response postQuote(@PathParam("fspId") String fspId, @HeaderParam(Fspiop.SOURCE) String source,
      @HeaderParam(Fspiop.DESTINATION) String destination, String body) {
    check(fspId, source, destination);
    Fspiop.QuotesPost post = read(body, Fspiop.QuotesPost.class);
    if (post.quoteId() == null || post.payee() == null || post.payer() == null || post.amount() == null) {
      throw malformed("quoteId, payee, payer and amount are required");
    }
    QuoteRequest request = new QuoteRequest(post.quoteId(), post.transactionId(), Fspiop.partyInfo(post.payer()),
        Fspiop.partyInfo(post.payee()), post.amountType(), Fspiop.amount(post.amount()),
        currency(post.amount().currency()), post.note());
    dispatcher.dispatch("POST /quotes " + post.quoteId(), () -> payee.quoteRequested(fspId, source, request));
    return Response.accepted().build();
  }

  @PUT
  @Path("/quotes/{id}")
  public Response putQuote(@PathParam("fspId") String fspId, @PathParam("id") String quoteId, String body) {
    Fspiop.QuotesPut put = read(body, Fspiop.QuotesPut.class);
    BigDecimal fee = Fspiop.amount(put.payeeFspFee());
    QuoteResponse response = new QuoteResponse(quoteId, Fspiop.amount(put.transferAmount()),
        Fspiop.amount(put.payeeReceiveAmount()), fee != null ? fee : BigDecimal.ZERO,
        Fspiop.instant(put.expiration()), put.ilpPacket(), put.condition());
    dispatcher.dispatch("PUT /quotes/" + quoteId, () -> payer.quoteReceived(fspId, response));
    return Response.ok().build();
  }

  @PUT
  @Path("/quotes/{id}/error")
  public Response putQuoteError(@PathParam("fspId") String fspId, @PathParam("id") String quoteId, String body) {
    FspiopError error = Fspiop.error(read(body, Fspiop.ErrorBody.class));
    dispatcher.dispatch("PUT /quotes/" + quoteId + "/error", () -> payer.quoteFailed(fspId, quoteId, error));
    return Response.ok().build();
  }

  // --- transfers ---

  @POST
  @Path("/transfers")
  public Response postTransfer(@PathParam("fspId") String fspId, @HeaderParam(Fspiop.SOURCE) String source,
      @HeaderParam(Fspiop.DESTINATION) String destination, String body) {
    check(fspId, source, destination);
    Fspiop.TransfersPost post = read(body, Fspiop.TransfersPost.class);
    if (post.transferId() == null || post.amount() == null || post.condition() == null) {
      throw malformed("transferId, amount and condition are required");
    }
    TransferPrepare prepare = new TransferPrepare(post.transferId(), post.payerFsp(), post.payeeFsp(),
        Fspiop.amount(post.amount()), currency(post.amount().currency()), post.ilpPacket(), post.condition(),
        Fspiop.instant(post.expiration()));
    dispatcher.dispatch("POST /transfers " + post.transferId(),
        () -> payee.transferPrepared(fspId, source, prepare));
    return Response.accepted().build();
  }

  @PUT
  @Path("/transfers/{id}")
  public Response putTransfer(@PathParam("fspId") String fspId, @PathParam("id") String transferId, String body) {
    Fspiop.TransfersPut put = read(body, Fspiop.TransfersPut.class);
    dispatcher.dispatch("PUT /transfers/" + transferId, () -> payer.transferFulfilled(fspId, transferId,
        put.fulfilment(), put.transferState(), Fspiop.instant(put.completedTimestamp())));
    return Response.ok().build();
  }

  @PATCH
  @Path("/transfers/{id}")
  public Response patchTransfer(@PathParam("fspId") String fspId, @PathParam("id") String transferId,
      String body) {
    Fspiop.TransfersPut patch = read(body, Fspiop.TransfersPut.class);
    dispatcher.dispatch("PATCH /transfers/" + transferId,
        () -> payee.transferNotified(fspId, transferId, patch.transferState()));
    return Response.ok().build();
  }

  @PUT
  @Path("/transfers/{id}/error")
  public Response putTransferError(@PathParam("fspId") String fspId, @PathParam("id") String transferId,
      String body) {
    FspiopError error = Fspiop.error(read(body, Fspiop.ErrorBody.class));
    // the same DFSP may be payer, payee or both; each side ignores transfers it does not hold
    dispatcher.dispatch("PUT /transfers/" + transferId + "/error", () -> {
      payer.transferFailed(fspId, transferId, error);
      payee.transferFailed(fspId, transferId, error);
    });
    return Response.ok().build();
  }

  // --- helpers ---

  /** requests (not callbacks) must come from a known source and be addressed to the path's DFSP */
  private void check(String fspId, String source, String destination) {
    dfspUseCase.findById(fspId);
    if (source == null || source.isBlank()) {
      throw malformed(Fspiop.SOURCE + " header is required");
    }
    if (destination != null && !destination.isBlank() && !destination.equals(fspId)) {
      throw error(Response.Status.BAD_REQUEST, "3100",
          Fspiop.DESTINATION + " " + destination + " does not match " + fspId);
    }
  }

  private <T> T read(String body, Class<T> type) {
    if (body == null || body.isBlank()) {
      throw malformed("Request body is required");
    }
    try {
      return objectMapper.readValue(body, type);
    } catch (JsonProcessingException e) {
      throw malformed(e.getOriginalMessage());
    }
  }

  private static IdTypeEnum idType(String type) {
    try {
      return IdTypeEnum.valueOf(type);
    } catch (IllegalArgumentException e) {
      throw malformed("Unsupported partyIdType " + type);
    }
  }

  private static CurrencyEnum currency(String currency) {
    try {
      return CurrencyEnum.valueOf(currency);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw error(Response.Status.BAD_REQUEST, "3100", "Unsupported currency " + currency);
    }
  }

  private static WebApplicationException malformed(String description) {
    return error(Response.Status.BAD_REQUEST, "3101", "Malformed syntax: " + description);
  }

  private static WebApplicationException error(Response.Status status, String code, String description) {
    return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
        .entity(Fspiop.errorBody(new FspiopError(code, description))).build());
  }

}
